package com.qn.calendar.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import com.qn.calendar.settings.constant.SmtpSecurity;
import com.qn.calendar.settings.dto.UpdateEmailSenderSettingsRequest;
import com.qn.calendar.settings.dto.UpdateAppSettingsRequest;
import com.qn.calendar.settings.repository.AppSettingRepository;
import com.qn.calendar.settings.service.AppSettingsService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class AppSettingsServiceTests {

    @Autowired
    private AppSettingsService service;

    @Autowired
    private AppSettingRepository repository;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    @Test
    void getSettingsReturnsDefaultBaseAmountAndPersistsIt() {
        var settings = service.getSettings();

        assertThat(settings.estimatedHourlyBaseAmount()).isEqualByComparingTo("100");
        assertThat(settings.emailSender().configured()).isFalse();
        assertThat(repository.findAll()).hasSize(1);
        assertThat(repository.findAll().getFirst().getEstimatedHourlyBaseAmount()).isEqualByComparingTo("100");
    }

    @Test
    void updateSettingsPersistsBaseAmountForLaterReads() {
        service.updateSettings(new UpdateAppSettingsRequest(BigDecimal.valueOf(150)));

        assertThat(service.getSettings().estimatedHourlyBaseAmount()).isEqualByComparingTo("150");
        assertThat(service.getEstimatedHourlyBaseAmount()).isEqualByComparingTo("150");
    }

    @Test
    void getRequiredEmailSenderSettingsRejectsMissingConfiguration() {
        assertThatThrownBy(() -> service.getRequiredEmailSenderSettings())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("请先在全局设置中配置寄件者 SMTP");
    }

    @Test
    void updateEmailSenderSettingsPersistsSmtpConfigWithoutReturningAuthCode() {
        var settings = service.updateEmailSenderSettings(new UpdateEmailSenderSettingsRequest(
                " sender@example.com ",
                " smtp.example.com ",
                587,
                SmtpSecurity.STARTTLS,
                " smtp-auth-code "
        ));

        assertThat(settings.emailSender().configured()).isTrue();
        assertThat(settings.emailSender().senderEmailMasked()).isEqualTo("s***@example.com");
        assertThat(settings.emailSender().senderEmail()).isEqualTo("sender@example.com");
        assertThat(settings.emailSender().smtpHost()).isEqualTo("smtp.example.com");
        assertThat(settings.emailSender().smtpPort()).isEqualTo(587);
        assertThat(settings.emailSender().smtpSecurity()).isEqualTo(SmtpSecurity.STARTTLS);
        assertThat(settings.toString()).doesNotContain("smtp-auth-code");

        var emailSenderSettings = service.getRequiredEmailSenderSettings();
        assertThat(emailSenderSettings.senderEmail()).isEqualTo("sender@example.com");
        assertThat(emailSenderSettings.smtpHost()).isEqualTo("smtp.example.com");
        assertThat(emailSenderSettings.smtpPort()).isEqualTo(587);
        assertThat(emailSenderSettings.smtpSecurity()).isEqualTo(SmtpSecurity.STARTTLS);
        assertThat(emailSenderSettings.smtpAuthCode()).isEqualTo("smtp-auth-code");
    }

    @Test
    void updateEmailSenderSettingsRetainsExistingAuthCodeWhenOmitted() {
        service.updateEmailSenderSettings(new UpdateEmailSenderSettingsRequest(
                "sender@example.com",
                "smtp.example.com",
                465,
                SmtpSecurity.SSL,
                "smtp-auth-code"
        ));

        var settings = service.updateEmailSenderSettings(new UpdateEmailSenderSettingsRequest(
                "updated@example.com",
                "smtp.updated.example.com",
                587,
                SmtpSecurity.STARTTLS,
                null
        ));

        assertThat(settings.emailSender().senderEmail()).isEqualTo("updated@example.com");
        assertThat(service.getRequiredEmailSenderSettings().smtpAuthCode()).isEqualTo("smtp-auth-code");
    }

    @Test
    void updateEmailSenderSettingsRequiresAuthCodeForInitialConfiguration() {
        assertThatThrownBy(() -> service.updateEmailSenderSettings(new UpdateEmailSenderSettingsRequest(
                "sender@example.com",
                "smtp.example.com",
                465,
                SmtpSecurity.SSL,
                null
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("授权码不可为空");
    }
}
