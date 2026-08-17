package com.qn.calendar.settings.dto;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.qn.calendar.settings.constant.SmtpSecurity;
import com.qn.calendar.settings.entity.AppSetting;
import com.qn.calendar.settings.entity.OrderSourceOption;

public record AppSettingsResponse(
        BigDecimal estimatedHourlyBaseAmount,
        @JsonFormat(pattern = "HH:mm")
        LocalTime weekViewDefaultStartTime,
        List<OrderSourceOptionResponse> orderSourceOptions,
        EmailSenderResponse emailSender
) {

    public static AppSettingsResponse from(AppSetting appSetting) {
        return new AppSettingsResponse(
                appSetting.getEstimatedHourlyBaseAmount(),
                appSetting.getWeekViewDefaultStartTime(),
                appSetting.getOrderSourceOptions().stream().map(OrderSourceOptionResponse::from).toList(),
                EmailSenderResponse.from(appSetting)
        );
    }

    public record OrderSourceOptionResponse(
            String name,
            String identifier,
            String badgeColor,
            String badgeText
    ) {

        static OrderSourceOptionResponse from(OrderSourceOption option) {
            return new OrderSourceOptionResponse(
                    option.getName(),
                    option.getIdentifier(),
                    option.getBadgeColor(),
                    option.getBadgeText()
            );
        }
    }

    public record EmailSenderResponse(
            boolean configured,
            String senderEmailMasked,
            String senderEmail,
            String smtpHost,
            Integer smtpPort,
            SmtpSecurity smtpSecurity
    ) {

        static EmailSenderResponse from(AppSetting appSetting) {
            return new EmailSenderResponse(
                    appSetting.isEmailSenderConfigured(),
                    maskEmail(appSetting.getEmailSender()),
                    appSetting.getEmailSender(),
                    appSetting.getSmtpHost(),
                    appSetting.getSmtpPort(),
                    appSetting.getSmtpSecurity()
            );
        }

        private static String maskEmail(String email) {
            if (email == null || email.trim().isEmpty()) {
                return "";
            }

            String trimmedEmail = email.trim();
            int atIndex = trimmedEmail.indexOf('@');

            if (atIndex <= 0) {
                return "***";
            }

            String domain = trimmedEmail.substring(atIndex);

            if (atIndex == 1) {
                return "*" + domain;
            }

            return trimmedEmail.charAt(0) + "***" + domain;
        }
    }
}
