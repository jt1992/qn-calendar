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
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.util.Locale;

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
    private static final String OPEN_PAGE_LABEL = "打开页面";
    private static final String EXIT_APPLICATION_LABEL = "关闭系统";
    private static final String TRAY_MENU_TEXT = OPEN_PAGE_LABEL + EXIT_APPLICATION_LABEL;
    private static final String WINDOWS_MENU_FONT_PROPERTY = "win.menu.font";
    private static final String[] WINDOWS_CJK_FONT_FAMILIES = {
            "Microsoft YaHei UI",
            "Microsoft YaHei",
            "SimSun"
    };

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

        boolean windows = isWindows();
        Font trayMenuFont = windows ? resolveWindowsTrayMenuFont() : null;
        boolean useChineseLabels = !windows || trayMenuFont != null;

        MenuItem openItem = new MenuItem(useChineseLabels ? OPEN_PAGE_LABEL : "Open page");
        openItem.addActionListener(action -> desktopBrowser.open(applicationUri));

        MenuItem exitItem = new MenuItem(useChineseLabels ? EXIT_APPLICATION_LABEL : "Exit");
        exitItem.addActionListener(action -> {
            systemTray.remove(trayIcon);
            int exitCode = SpringApplication.exit(event.getApplicationContext(), () -> 0);
            System.exit(exitCode);
        });

        PopupMenu popupMenu = new PopupMenu();

        if (trayMenuFont != null) {
            popupMenu.setFont(trayMenuFont);
            openItem.setFont(trayMenuFont);
            exitItem.setFont(trayMenuFont);
        }

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

    private Font resolveWindowsTrayMenuFont() {
        Object systemMenuFont = Toolkit.getDefaultToolkit().getDesktopProperty(WINDOWS_MENU_FONT_PROPERTY);

        if (systemMenuFont instanceof Font font && canDisplayTrayMenuText(font)) {
            log.info("Use Windows system menu font '{}' for the system tray menu.", font.getFamily());
            return font;
        }

        int fontSize = systemMenuFont instanceof Font font ? Math.max(font.getSize(), 12) : 12;
        for (String candidate : WINDOWS_CJK_FONT_FAMILIES) {
            Font font = new Font(candidate, Font.PLAIN, fontSize);

            if (candidate.equalsIgnoreCase(font.getFamily(Locale.ENGLISH)) && canDisplayTrayMenuText(font)) {
                log.info("Use fallback Windows CJK font '{}' for the system tray menu.", font.getFamily());
                return font;
            }
        }

        log.warn("No Windows font can display the Chinese system tray labels; use English labels instead.");
        return null;
    }

    private boolean canDisplayTrayMenuText(Font font) {
        return font.canDisplayUpTo(TRAY_MENU_TEXT) == -1;
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").startsWith("Windows");
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
