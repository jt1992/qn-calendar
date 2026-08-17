package com.qn.calendar.settings.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public class OrderSourceOption {

    @Column(name = "option_name", nullable = false, length = 80)
    private String name;

    @Column(name = "identifier", length = 40)
    private String identifier;

    @Column(name = "badge_color", length = 7)
    private String badgeColor;

    @Column(name = "badge_text", length = 8)
    private String badgeText;

    protected OrderSourceOption() {
    }

    public OrderSourceOption(String name, String identifier, String badgeColor, String badgeText) {
        this.name = name;
        this.identifier = identifier;
        this.badgeColor = badgeColor;
        this.badgeText = badgeText;
    }

    public String getName() {
        return name;
    }

    public String getIdentifier() {
        return identifier;
    }

    public String getBadgeColor() {
        return badgeColor;
    }

    public String getBadgeText() {
        return badgeText;
    }
}
