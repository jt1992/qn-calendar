package com.qn.calendar.settings.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "app_setting")
public class AppSetting {

    @Id
    private Long id;

    @Column(name = "estimated_hourly_base_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal estimatedHourlyBaseAmount;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected AppSetting() {
    }

    public AppSetting(Long id, BigDecimal estimatedHourlyBaseAmount) {
        this.id = id;
        this.estimatedHourlyBaseAmount = estimatedHourlyBaseAmount;
    }

    @PrePersist
    @PreUpdate
    void updateTimestamp() {
        this.updatedAt = LocalDateTime.now();
    }

    public void updateEstimatedHourlyBaseAmount(BigDecimal estimatedHourlyBaseAmount) {
        this.estimatedHourlyBaseAmount = estimatedHourlyBaseAmount;
    }

    public BigDecimal getEstimatedHourlyBaseAmount() {
        return estimatedHourlyBaseAmount;
    }
}
