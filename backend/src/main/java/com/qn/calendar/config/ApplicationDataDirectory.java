package com.qn.calendar.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ApplicationDataDirectory {

    public static final String DATA_DIR_PROPERTY = "qn.calendar.data-dir";
    private static final String DATA_DIR_ENV = "QN_CALENDAR_DATA_DIR";
    private static final String DEFAULT_DATA_DIR = ".qn-calendar";

    private ApplicationDataDirectory() {
    }

    public static void prepareDefaultDirectory() {
        Path dataDirectory = resolveDefaultDirectory();

        try {
            Files.createDirectories(dataDirectory);
        } catch (IOException exception) {
            throw new IllegalStateException("無法建立資料目錄：" + dataDirectory, exception);
        }

        System.setProperty(DATA_DIR_PROPERTY, dataDirectory.toString());
    }

    private static Path resolveDefaultDirectory() {
        String configuredDirectory = firstNonBlank(
                System.getProperty(DATA_DIR_PROPERTY),
                System.getenv(DATA_DIR_ENV)
        );

        if (configuredDirectory != null) {
            return Path.of(configuredDirectory).toAbsolutePath().normalize();
        }

        return Path.of(System.getProperty("user.home"), DEFAULT_DATA_DIR).toAbsolutePath().normalize();
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }

        if (second != null && !second.isBlank()) {
            return second;
        }

        return null;
    }
}
