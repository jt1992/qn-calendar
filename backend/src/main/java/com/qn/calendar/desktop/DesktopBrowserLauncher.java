package com.qn.calendar.desktop;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.desktop", name = "enabled", havingValue = "true")
public class DesktopBrowserLauncher {

    private final DesktopBrowser desktopBrowser;
    private final LocalApplicationUrl localApplicationUrl;
    private final boolean openBrowserOnStartup;

    public DesktopBrowserLauncher(
            DesktopBrowser desktopBrowser,
            LocalApplicationUrl localApplicationUrl,
            @Value("${app.desktop.open-browser-on-startup:true}") boolean openBrowserOnStartup
    ) {
        this.desktopBrowser = desktopBrowser;
        this.localApplicationUrl = localApplicationUrl;
        this.openBrowserOnStartup = openBrowserOnStartup;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void openBrowser(ApplicationReadyEvent event) {
        if (!openBrowserOnStartup) {
            return;
        }

        desktopBrowser.open(localApplicationUrl.resolve(event.getApplicationContext()));
    }
}
