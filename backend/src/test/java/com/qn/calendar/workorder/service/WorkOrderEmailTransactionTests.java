package com.qn.calendar.workorder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Properties;

import com.qn.calendar.settings.constant.SmtpSecurity;
import com.qn.calendar.settings.model.EmailSenderSettings;
import com.qn.calendar.settings.repository.EmailRecipientRepository;
import com.qn.calendar.settings.service.AppSettingsService;
import com.qn.calendar.settings.service.EmailRecipientService;
import com.qn.calendar.workorder.constant.ScheduleEmailViewType;
import com.qn.calendar.workorder.dto.ScheduleEmailRequest;
import com.qn.calendar.workorder.repository.WorkOrderRepository;
import com.qn.calendar.workorder.repository.WorkOrderSegmentPauseRepository;
import com.qn.calendar.workorder.repository.WorkOrderSegmentRepository;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.thymeleaf.TemplateEngine;

@SpringBootTest
@Import(WorkOrderEmailTransactionTests.Config.class)
class WorkOrderEmailTransactionTests {

    private static final String RECIPIENT = "transaction-test@example.com";

    @Autowired
    private WorkOrderEmailService service;

    @Autowired
    private EmailRecipientRepository recipientRepository;

    @Autowired
    @Qualifier("transactionTestMailSender")
    private JavaMailSender mailSender;

    @BeforeEach
    void setUp() {
        recipientRepository.deleteAll();
        reset(mailSender);
        when(mailSender.createMimeMessage())
                .thenAnswer((invocation) -> new MimeMessage(Session.getInstance(new Properties())));
    }

    @Test
    void recordsNewRecipientOnlyAfterMailSendCompletes() {
        doAnswer((invocation) -> {
            assertThat(recipientRepository.findByEmailIgnoreCase(RECIPIENT)).isEmpty();
            return null;
        }).when(mailSender).send(any(MimeMessage.class));

        service.sendScheduleEmail(request());

        verify(mailSender).send(any(MimeMessage.class));
        assertThat(recipientRepository.findByEmailIgnoreCase(RECIPIENT))
                .get()
                .satisfies((recipient) -> {
                    assertThat(recipient.getUsageCount()).isEqualTo(1);
                    assertThat(recipient.getLastUsedAt()).isNotNull();
                });
    }

    @Test
    void doesNotRecordRecipientWhenMailSendFails() {
        doThrow(new MailSendException("SMTP failed"))
                .when(mailSender)
                .send(any(MimeMessage.class));

        assertThatThrownBy(() -> service.sendScheduleEmail(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("排程 Email 发送失败");
        assertThat(recipientRepository.findByEmailIgnoreCase(RECIPIENT)).isEmpty();
    }

    private ScheduleEmailRequest request() {
        return new ScheduleEmailRequest(
                List.of(RECIPIENT),
                "交易测试",
                LocalDate.of(2026, 6, 1),
                LocalDate.of(2026, 6, 30),
                ScheduleEmailViewType.MONTH
        );
    }

    @TestConfiguration
    static class Config {

        @Bean("transactionTestMailSender")
        JavaMailSender transactionTestMailSender() {
            return mock(JavaMailSender.class);
        }

        @Bean
        @Primary
        WorkOrderEmailService transactionTestWorkOrderEmailService(
                WorkOrderRepository workOrderRepository,
                WorkOrderSegmentRepository segmentRepository,
                WorkOrderSegmentPauseRepository pauseRepository,
                EmailRecipientService emailRecipientService,
                TemplateEngine templateEngine,
                Clock clock,
                @Qualifier("transactionTestMailSender") JavaMailSender mailSender
        ) {
            AppSettingsService appSettingsService = mock(AppSettingsService.class);
            when(appSettingsService.getRequiredEmailSenderSettings()).thenReturn(new EmailSenderSettings(
                    "sender@example.com",
                    "smtp.example.com",
                    587,
                    SmtpSecurity.STARTTLS,
                    "smtp-auth-code"
            ));

            return new WorkOrderEmailService(
                    workOrderRepository,
                    segmentRepository,
                    pauseRepository,
                    appSettingsService,
                    emailRecipientService,
                    templateEngine,
                    clock
            ) {
                @Override
                JavaMailSender createMailSender(EmailSenderSettings settings) {
                    return mailSender;
                }
            };
        }
    }
}
