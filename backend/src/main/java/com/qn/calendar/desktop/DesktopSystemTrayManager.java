package com.qn.calendar.desktop;

import java.awt.AWTException;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.net.URI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.desktop", name = "enabled", havingValue = "true")
public class DesktopSystemTrayManager {

    private static final Logger log = LoggerFactory.getLogger(DesktopSystemTrayManager.class);
    private static final String OPEN_PAGE_LABEL = "Open page";
    private static final String EXIT_APPLICATION_LABEL = "Exit";

    private final DesktopBrowser desktopBrowser;
    private final LocalApplicationUrl localApplicationUrl;

    public DesktopSystemTrayManager(DesktopBrowser desktopBrowser, LocalApplicationUrl localApplicationUrl) {
        this.desktopBrowser = desktopBrowser;
        this.localApplicationUrl = localApplicationUrl;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initialize(ApplicationReadyEvent event) {
        if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
            log.info("Skip system tray initialization because the environment does not support it.");
            return;
        }

        URI applicationUri = localApplicationUrl.resolve(event.getApplicationContext());
        SystemTray systemTray = SystemTray.getSystemTray();
        TrayIcon trayIcon = new TrayIcon(createTrayImage(), "Qn Calendar");
        trayIcon.setImageAutoSize(true);

        MenuItem openItem = new MenuItem(OPEN_PAGE_LABEL);
        openItem.addActionListener(action -> desktopBrowser.open(applicationUri));

        MenuItem exitItem = new MenuItem(EXIT_APPLICATION_LABEL);
        exitItem.addActionListener(action -> {
            systemTray.remove(trayIcon);
            int exitCode = SpringApplication.exit(event.getApplicationContext(), () -> 0);
            System.exit(exitCode);
        });

        PopupMenu popupMenu = new PopupMenu();

        popupMenu.add(openItem);
        popupMenu.addSeparator();
        popupMenu.add(exitItem);

        trayIcon.setPopupMenu(popupMenu);
        trayIcon.addActionListener(action -> desktopBrowser.open(applicationUri));

        try {
            systemTray.add(trayIcon);
        } catch (AWTException exception) {
            log.warn("Failed to add system tray icon.", exception);
        }
    }

    private Image createTrayImage() {
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();

        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(new Color(37, 99, 235));
            graphics.fillRoundRect(2, 2, 28, 28, 8, 8);
            graphics.setColor(Color.WHITE);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
            graphics.drawString("Q", 9, 23);
        } finally {
            graphics.dispose();
        }

        return image;
    }
}
