package com.qn.calendar;

import java.net.URI;
import java.nio.file.Path;

import com.qn.calendar.config.ApplicationDataDirectory;
import com.qn.calendar.desktop.DesktopSingleInstanceCoordinator;
import com.qn.calendar.desktop.LocalApplicationUrl;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;

@SpringBootApplication
public class QnCalendarApplication {

    public static void main(String[] args) {
        ApplicationDataDirectory.prepareDefaultDirectory();
        if (!isDesktopEnabled()) {
            SpringApplication.run(QnCalendarApplication.class, args);
            return;
        }

        System.setProperty("java.awt.headless", "false");
        runDesktopApplication(args);
    }

    private static boolean isDesktopEnabled() {
        return Boolean.parseBoolean(System.getProperty(
                "app.desktop.enabled",
                System.getenv().getOrDefault("APP_DESKTOP_ENABLED", "false")
        ));
    }

    private static void runDesktopApplication(String[] args) {
        LocalApplicationUrl localApplicationUrl = new LocalApplicationUrl();
        URI applicationUri = localApplicationUrl.resolveBeforeStartup(args);
        Path dataDirectory = Path.of(System.getProperty(ApplicationDataDirectory.DATA_DIR_PROPERTY));
        DesktopSingleInstanceCoordinator coordinator = DesktopSingleInstanceCoordinator.forApplication(
                dataDirectory,
                applicationUri
        );

        if (coordinator.coordinateLaunch()
                == DesktopSingleInstanceCoordinator.LaunchResult.EXISTING_APPLICATION_OPENED) {
            return;
        }

        SpringApplication application = new SpringApplication(QnCalendarApplication.class);
        application.addListeners((ApplicationListener<ContextClosedEvent>) event -> coordinator.close());

        try {
            application.run(args);
        } catch (RuntimeException | Error exception) {
            coordinator.close();
            throw exception;
        }
    }
}
