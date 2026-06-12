package com.qn.calendar.settings.model;

import com.qn.calendar.settings.constant.SmtpSecurity;

public record EmailSenderSettings(
        String senderEmail,
        String smtpHost,
        int smtpPort,
        SmtpSecurity smtpSecurity,
        String smtpAuthCode
) {
}
