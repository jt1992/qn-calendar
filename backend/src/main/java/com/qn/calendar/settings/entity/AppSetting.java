package com.qn.calendar.settings.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;

import com.qn.calendar.settings.constant.SmtpSecurity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

    @Column(name = "week_view_default_start_time")
    private LocalTime weekViewDefaultStartTime;

    @Column(name = "email_sender", length = 320)
    private String emailSender;

    @Column(name = "smtp_host")
    private String smtpHost;

    @Column(name = "smtp_port")
    private Integer smtpPort;

    @Enumerated(EnumType.STRING)
    @Column(name = "smtp_security", length = 20)
    private SmtpSecurity smtpSecurity;

    @Column(name = "smtp_auth_code", length = 1024)
    private String smtpAuthCode;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected AppSetting() {
    }

    public AppSetting(
            Long id,
            BigDecimal estimatedHourlyBaseAmount,
            LocalTime weekViewDefaultStartTime
    ) {
        this.id = id;
        this.estimatedHourlyBaseAmount = estimatedHourlyBaseAmount;
        this.weekViewDefaultStartTime = weekViewDefaultStartTime;
    }

    @PrePersist
    @PreUpdate
    void updateTimestamp() {
        this.updatedAt = LocalDateTime.now();
    }

    public void updateBasicSettings(
            BigDecimal estimatedHourlyBaseAmount,
            LocalTime weekViewDefaultStartTime
    ) {
        this.estimatedHourlyBaseAmount = estimatedHourlyBaseAmount;
        this.weekViewDefaultStartTime = weekViewDefaultStartTime;
    }

    public void updateEmailSenderSettings(
            String emailSender,
            String smtpHost,
            Integer smtpPort,
            SmtpSecurity smtpSecurity,
            String smtpAuthCode
    ) {
        this.emailSender = emailSender;
        this.smtpHost = smtpHost;
        this.smtpPort = smtpPort;
        this.smtpSecurity = smtpSecurity;
        this.smtpAuthCode = smtpAuthCode;
    }

    public boolean isEmailSenderConfigured() {
        return hasText(emailSender)
                && hasText(smtpHost)
                && smtpPort != null
                && smtpSecurity != null
                && hasText(smtpAuthCode);
    }

    public BigDecimal getEstimatedHourlyBaseAmount() {
        return estimatedHourlyBaseAmount;
    }

    public LocalTime getWeekViewDefaultStartTime() {
        return weekViewDefaultStartTime;
    }

    public String getEmailSender() {
        return emailSender;
    }

    public String getSmtpHost() {
        return smtpHost;
    }

    public Integer getSmtpPort() {
        return smtpPort;
    }

    public SmtpSecurity getSmtpSecurity() {
        return smtpSecurity;
    }

    public String getSmtpAuthCode() {
        return smtpAuthCode;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
