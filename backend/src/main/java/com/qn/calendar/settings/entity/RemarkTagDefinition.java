package com.qn.calendar.settings.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
        name = "remark_tag_definition",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_remark_tag_definition_system_key",
                        columnNames = "system_key"
                ),
                @UniqueConstraint(
                        name = "uk_remark_tag_definition_normalized_name",
                        columnNames = "normalized_name"
                )
        }
)
public class RemarkTagDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "system_key", unique = true, length = 32)
    private String systemKey;

    @Column(name = "name", nullable = false, length = 80)
    private String name;

    @Column(name = "normalized_name", nullable = false, unique = true, length = 80)
    private String normalizedName;

    @Column(name = "color", nullable = false, length = 7)
    private String color;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    protected RemarkTagDefinition() {
    }

    public RemarkTagDefinition(
            String systemKey,
            String name,
            String normalizedName,
            String color,
            int displayOrder
    ) {
        this.systemKey = systemKey;
        this.name = name;
        this.normalizedName = normalizedName;
        this.color = color;
        this.displayOrder = displayOrder;
    }

    public void stageNormalizedName(String normalizedName) {
        this.normalizedName = normalizedName;
    }

    public void update(String name, String normalizedName, String color, int displayOrder) {
        this.name = name;
        this.normalizedName = normalizedName;
        this.color = color;
        this.displayOrder = displayOrder;
    }

    public Long getId() {
        return id;
    }

    public String getSystemKey() {
        return systemKey;
    }

    public String getName() {
        return name;
    }

    public String getNormalizedName() {
        return normalizedName;
    }

    public String getColor() {
        return color;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }
}
