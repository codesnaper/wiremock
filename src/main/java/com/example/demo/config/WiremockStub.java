package com.example.demo.config;


import com.github.tomakehurst.wiremock.common.ServletContextFileSource;
import com.github.tomakehurst.wiremock.common.Slf4jNotifier;
import com.github.tomakehurst.wiremock.core.WireMockApp;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.extension.Extension;
import com.github.tomakehurst.wiremock.extension.responsetemplating.ResponseTemplateTransformer;
import com.github.tomakehurst.wiremock.http.StubRequestHandler;
import com.github.tomakehurst.wiremock.servlet.NotImplementedContainer;
import com.github.tomakehurst.wiremock.servlet.WireMockHandlerDispatchingServlet;
import com.google.common.base.MoreObjects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.context.annotation.Configuration;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRegistration;

@Configuration
public class WiremockStub implements ServletContextInitializer {


    @Autowired
    @Qualifier("CallbacksAction")
    private Extension callbacksAction;

    @Override
    public void onStartup(ServletContext servletContext) throws ServletException {
        servletContext.setInitParameter("WireMockFileSourceRoot", "/wiremock");
        servletContext.setInitParameter("verboseLoggingEnabled", "true");
        String fileSourceRoot = servletContext.getInitParameter("WireMockFileSourceRoot");
        boolean verboseLoggingEnabled = Boolean.parseBoolean((String) MoreObjects.firstNonNull(servletContext.getInitParameter("verboseLoggingEnabled"), "true"));
        WireMockConfiguration wireMockConfiguration = WireMockConfiguration
                .options()
                .withRootDirectory(new ServletContextFileSource(servletContext, fileSourceRoot).getPath())
                .extensions(new ResponseTemplateTransformer(false), callbacksAction);
        WireMockApp wireMockApp = new WireMockApp(wireMockConfiguration, new NotImplementedContainer());
        servletContext.setAttribute("WireMockApp", wireMockApp);
        servletContext.setAttribute(StubRequestHandler.class.getName(), wireMockApp.buildStubRequestHandler());
        servletContext.setAttribute("Notifier", new Slf4jNotifier(verboseLoggingEnabled));
        ServletRegistration.Dynamic servletRegistration = servletContext.addServlet("wiremock-mock-service-handler-servlet", new WireMockHandlerDispatchingServlet());
        servletRegistration.setInitParameter("RequestHandlerClass", "com.github.tomakehurst.wiremock.http.StubRequestHandler");
        servletRegistration.addMapping("/stub/*");
    }

}
