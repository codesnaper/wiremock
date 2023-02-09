package com.example.demo.actions;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.core.Admin;
import com.github.tomakehurst.wiremock.extension.Parameters;
import com.github.tomakehurst.wiremock.extension.PostServeAction;
import com.github.tomakehurst.wiremock.extension.responsetemplating.RequestTemplateModel;
import com.github.tomakehurst.wiremock.extension.responsetemplating.TemplateEngine;
import com.github.tomakehurst.wiremock.http.HttpHeader;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.wiremock.webhooks.WebhookDefinition;
import wiremock.webhooks.org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import wiremock.webhooks.org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import wiremock.webhooks.org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import wiremock.webhooks.org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import wiremock.webhooks.org.apache.hc.core5.http.ClassicHttpRequest;
import wiremock.webhooks.org.apache.hc.core5.http.ContentType;
import wiremock.webhooks.org.apache.hc.core5.http.ParseException;
import wiremock.webhooks.org.apache.hc.core5.http.io.SocketConfig;
import wiremock.webhooks.org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import wiremock.webhooks.org.apache.hc.core5.http.io.entity.EntityUtils;
import wiremock.webhooks.org.apache.hc.core5.http.io.support.ClassicRequestBuilder;
import wiremock.webhooks.org.apache.hc.core5.util.TimeValue;
import wiremock.webhooks.org.apache.hc.core5.util.Timeout;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Component("CallbacksAction")
public class CallbacksAction extends PostServeAction {

    private List<WebhookDefinition> webhookDefinitions = new ArrayList<>();

    private ObjectMapper mapper = new ObjectMapper();

    private String response = "";

    private final TemplateEngine templateEngine = new TemplateEngine(Collections.emptyMap(), (Long)null, Collections.emptySet());
    @Override
    public String getName() {
        return "eg-callbacks";
    }

    public void doAction(ServeEvent serveEvent, Admin admin, Parameters parameters) {
        CloseableHttpClient closeableHttpClient = this.createHttpClient();
        this.webhookDefinitions = webhookDefinitions(parameters);
        this.webhookDefinitions
                .forEach(webhookDefinition -> {
                    CloseableHttpResponse closeableHttpResponse = null;
                    try {
                        if(parameters.containsKey("response")){
                            webhookDefinition.getExtraParameters().put("response", parameters.get("response"));
                        }
                        closeableHttpResponse = closeableHttpClient.execute(this.buildRequest(this.applyTemplating(webhookDefinition, serveEvent)));
                        if(closeableHttpResponse.getCode() == HttpStatus.OK.value()){
                            parameters.put("response",EntityUtils.toString(closeableHttpResponse.getEntity()));
                        }
                    } catch (IOException | ParseException e) {
                        e.printStackTrace();
                    } finally {
                        try {
                            closeableHttpResponse.close();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                });
        super.doAction(serveEvent, admin, parameters);
    }

    private List<WebhookDefinition> webhookDefinitions(Parameters parameters) {
        return parameters.getList("callback")
                .stream()
                .map(parameter -> {
                    Map<String, Object> map = (Map<String, Object>) parameter;
                    return WebhookDefinition.from(Parameters.from(map));
                })
                .collect(Collectors.toList());
    }

    private WebhookDefinition applyTemplating(WebhookDefinition webhookDefinition, ServeEvent serveEvent) throws JsonProcessingException {
        Map<String, Object> model = new HashMap();
        model.put("originalRequest", RequestTemplateModel.from(serveEvent.getRequest()));
        if(webhookDefinition.getExtraParameters().containsKey("response")){
            Map<String, String> map = mapper.readValue(webhookDefinition.getExtraParameters().getString("response"), Map.class);
            model.putAll(map);
        }
        AtomicInteger counter = new AtomicInteger();
        model.putAll(this.webhookDefinitions.stream()
                        .collect(Collectors.toMap(webhookDefinition1 -> "parameter-"+counter.incrementAndGet(), webhookDefinition1 -> webhookDefinition1.getExtraParameters())));
        WebhookDefinition renderedWebhookDefinition = webhookDefinition.withUrl(this.renderTemplate(model, webhookDefinition.getUrl())).withMethod(this.renderTemplate(model, webhookDefinition.getMethod())).withHeaders((List)webhookDefinition.getHeaders().all().stream().map((header) -> {
            return new HttpHeader(header.key(), (Collection)header.values().stream().map((value) -> {
                return this.renderTemplate(model, value);
            }).collect(Collectors.toList()));
        }).collect(Collectors.toList()));
        if (webhookDefinition.getBody() != null) {
            renderedWebhookDefinition = webhookDefinition.withBody(this.renderTemplate(model, webhookDefinition.getBody()));
        }
        // TODO: need to sign xml
        if(webhookDefinition.getExtraParameters().containsKey("digital-sign-xml")){
            renderedWebhookDefinition = webhookDefinition.withBody(webhookDefinition.getBody());
        }

        return renderedWebhookDefinition;
    }

    private String renderTemplate(Object context, String value) {
        return this.templateEngine.getUncachedTemplate(value).apply(context);
    }

    private ClassicHttpRequest buildRequest(WebhookDefinition definition) {
        ClassicRequestBuilder requestBuilder = ClassicRequestBuilder.create(definition.getMethod()).setUri(definition.getUrl());
        Iterator headerIterator = definition.getHeaders().all().iterator();
        while(headerIterator.hasNext()) {
            HttpHeader header = (HttpHeader)headerIterator.next();
            Iterator headerValueIterator = header.values().iterator();

            while(headerValueIterator.hasNext()) {
                String value = (String)headerValueIterator.next();
                requestBuilder.addHeader(header.key(), value);
            }
        }

        if (definition.getRequestMethod().hasEntity() && definition.hasBody()) {
            requestBuilder.setEntity(new ByteArrayEntity(definition.getBinaryBody(), ContentType.DEFAULT_BINARY));
        }

        return requestBuilder.build();
    }

    private CloseableHttpClient createHttpClient() {
        return HttpClientBuilder.create().disableAuthCaching().disableAutomaticRetries().disableCookieManagement().disableRedirectHandling().disableContentCompression().setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create().setDefaultSocketConfig(SocketConfig.custom().setSoTimeout(Timeout.ofMilliseconds(30000L)).build()).setMaxConnPerRoute(1000).setMaxConnTotal(1000).setValidateAfterInactivity(TimeValue.ofSeconds(5L)).build()).setConnectionReuseStrategy((request, response, context) -> {
            return false;
        }).setKeepAliveStrategy((response, context) -> TimeValue.ZERO_MILLISECONDS).build();
    }
}
