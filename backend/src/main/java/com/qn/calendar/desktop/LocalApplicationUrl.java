package com.qn.calendar.desktop;

import java.net.URI;

import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class LocalApplicationUrl {

    public URI resolve(ApplicationContext applicationContext) {
        Environment environment = applicationContext.getEnvironment();
        int port = environment.getProperty(
                "local.server.port",
                Integer.class,
                environment.getProperty("server.port", Integer.class, 8080)
        );

        return URI.create("http://localhost:" + port);
    }
}
