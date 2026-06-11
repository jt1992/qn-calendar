package com.qn.calendar;

import com.qn.calendar.config.ApplicationDataDirectory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class QnCalendarApplication {

    public static void main(String[] args) {
        ApplicationDataDirectory.prepareDefaultDirectory();
        if (isDesktopEnabled()) {
            System.setProperty("java.awt.headless", "false");
        }
        SpringApplication.run(QnCalendarApplication.class, args);
    }

    private static boolean isDesktopEnabled() {
        return Boolean.parseBoolean(System.getProperty(
                "app.desktop.enabled",
                System.getenv().getOrDefault("APP_DESKTOP_ENABLED", "false")
        ));
    }
}
