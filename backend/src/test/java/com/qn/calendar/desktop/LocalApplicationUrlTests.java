package com.qn.calendar.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;

class LocalApplicationUrlTests {

    @Test
    void addsTheSameLaunchNonceToApplicationReadyAndPreStartupUrls() {
        LocalApplicationUrl applicationUrl = new LocalApplicationUrl("startup-nonce");
        GenericApplicationContext applicationContext = new GenericApplicationContext();
        applicationContext.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("test", Map.of("local.server.port", "9090"))
        );

        URI readyUrl = applicationUrl.resolve(applicationContext);
        URI preStartupUrl = applicationUrl.resolveBeforeStartup(
                new String[] {"--server.port=9090"},
                new Properties(),
                Map.of()
        );

        assertThat(readyUrl).isEqualTo(URI.create("http://localhost:9090/?launch=startup-nonce"));
        assertThat(preStartupUrl).isEqualTo(readyUrl);
    }

    @Test
    void resolvesPreStartupPortUsingSpringBootPropertyPrecedence() {
        LocalApplicationUrl applicationUrl = new LocalApplicationUrl("nonce");
        Properties systemProperties = new Properties();
        systemProperties.setProperty("server.port", "8081");
        Map<String, String> environment = Map.of("SERVER_PORT", "8082");
        Properties importedEnvironment = new Properties();
        importedEnvironment.setProperty("SERVER_PORT", "8084");

        assertThat(applicationUrl.resolveBeforeStartup(
                new String[] {"--server.port=8083"},
                systemProperties,
                environment,
                importedEnvironment
        )).hasPort(8083);
        assertThat(applicationUrl.resolveBeforeStartup(
                new String[0],
                systemProperties,
                environment,
                importedEnvironment
        )).hasPort(8081);
        assertThat(applicationUrl.resolveBeforeStartup(
                new String[0],
                new Properties(),
                environment,
                importedEnvironment
        )).hasPort(8082);
        assertThat(applicationUrl.resolveBeforeStartup(
                new String[0],
                new Properties(),
                Map.of(),
                importedEnvironment
        )).hasPort(8084);
        assertThat(applicationUrl.resolveBeforeStartup(
                new String[0],
                new Properties(),
                Map.of(),
                new Properties()
        )).hasPort(8080);
    }

    @Test
    void defaultInstancesShareOneNonceForTheProcess() {
        LocalApplicationUrl first = new LocalApplicationUrl();
        LocalApplicationUrl second = new LocalApplicationUrl();

        URI firstUrl = first.resolveBeforeStartup(new String[0], new Properties(), Map.of());
        URI secondUrl = second.resolveBeforeStartup(new String[0], new Properties(), Map.of());

        assertThat(firstUrl.getQuery()).startsWith("launch=");
        assertThat(secondUrl.getQuery()).isEqualTo(firstUrl.getQuery());
    }
}
