package com.qn.calendar.desktop;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class LocalApplicationUrl {

    private static final Logger log = LoggerFactory.getLogger(LocalApplicationUrl.class);
    private static final int DEFAULT_PORT = 8080;
    private static final String PROCESS_LAUNCH_NONCE = UUID.randomUUID().toString();
    private static final String SERVER_PORT_ARGUMENT_PREFIX = "--server.port=";

    private final String launchNonce;

    public LocalApplicationUrl() {
        this(PROCESS_LAUNCH_NONCE);
    }

    LocalApplicationUrl(String launchNonce) {
        this.launchNonce = Objects.requireNonNull(launchNonce);
    }

    public URI resolve(ApplicationContext applicationContext) {
        Environment environment = applicationContext.getEnvironment();
        int port = environment.getProperty(
                "local.server.port",
                Integer.class,
                environment.getProperty("server.port", Integer.class, DEFAULT_PORT)
        );

        return resolve(port);
    }

    public URI resolveBeforeStartup(String[] args) {
        return resolveBeforeStartup(
                args,
                System.getProperties(),
                System.getenv(),
                loadImportedEnvironmentFiles()
        );
    }

    URI resolveBeforeStartup(String[] args, Properties systemProperties, Map<String, String> environment) {
        return resolveBeforeStartup(args, systemProperties, environment, new Properties());
    }

    URI resolveBeforeStartup(
            String[] args,
            Properties systemProperties,
            Map<String, String> environment,
            Properties importedEnvironment
    ) {
        String commandLinePort = Arrays.stream(args)
                .filter(argument -> argument.startsWith(SERVER_PORT_ARGUMENT_PREFIX))
                .map(argument -> argument.substring(SERVER_PORT_ARGUMENT_PREFIX.length()))
                .reduce((first, second) -> second)
                .orElse(null);
        String configuredPort = firstNonBlank(
                commandLinePort,
                systemProperties.getProperty("server.port"),
                systemProperties.getProperty("SERVER_PORT"),
                environment.get("SERVER_PORT"),
                importedEnvironment.getProperty("SERVER_PORT")
        );

        return resolve(configuredPort == null ? DEFAULT_PORT : Integer.parseInt(configuredPort));
    }

    private URI resolve(int port) {
        String encodedNonce = URLEncoder.encode(launchNonce, StandardCharsets.UTF_8);
        return URI.create("http://localhost:" + port + "/?launch=" + encodedNonce);
    }

    private String firstNonBlank(String... values) {
        return Arrays.stream(values)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(null);
    }

    private Properties loadImportedEnvironmentFiles() {
        Properties importedEnvironment = new Properties();
        loadProperties(Path.of(".env"), importedEnvironment);
        loadProperties(Path.of("..", ".env"), importedEnvironment);
        return importedEnvironment;
    }

    private void loadProperties(Path path, Properties target) {
        if (!Files.isRegularFile(path)) {
            return;
        }

        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            target.load(reader);
        } catch (IOException exception) {
            log.warn("Failed to read optional desktop startup configuration: {}", path, exception);
        }
    }
}
