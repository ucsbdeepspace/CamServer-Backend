package edu.camserver.app.config;

import org.apache.catalina.connector.Connector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Adds a plain-HTTP connector on the loopback interface next to the public HTTPS port, so the
 * Nuxt frontend running on the same machine can proxy to the backend without needing to trust
 * the self-signed certificate. Disabled when {@code app.http.loopback-port} is 0.
 */
@Configuration
public class LoopbackHttpConnectorConfig {

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> loopbackHttpConnector(
            @Value("${app.http.loopback-port:0}") int port,
            @Value("${app.http.loopback-address:127.0.0.1}") String address) {
        return factory -> {
            if (port <= 0) {
                return;
            }
            Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
            connector.setPort(port);
            connector.setScheme("http");
            connector.setSecure(false);
            connector.setProperty("address", address);
            factory.addAdditionalTomcatConnectors(connector);
        };
    }
}
