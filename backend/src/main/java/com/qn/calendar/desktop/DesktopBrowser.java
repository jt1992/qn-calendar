package com.qn.calendar.desktop;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.net.URI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class DesktopBrowser {

    private static final Logger log = LoggerFactory.getLogger(DesktopBrowser.class);

    public void open(URI uri) {
        if (GraphicsEnvironment.isHeadless()) {
            log.info("Skip opening browser in headless environment: {}", uri);
            return;
        }

        if (!Desktop.isDesktopSupported()) {
            log.info("Skip opening browser because Desktop API is not supported: {}", uri);
            return;
        }

        try {
            Desktop desktop = Desktop.getDesktop();
            if (!desktop.isSupported(Desktop.Action.BROWSE)) {
                log.info("Skip opening browser because browse action is not supported: {}", uri);
                return;
            }

            desktop.browse(uri);
        } catch (Exception exception) {
            log.warn("Failed to open browser: {}", uri, exception);
        }
    }
}
