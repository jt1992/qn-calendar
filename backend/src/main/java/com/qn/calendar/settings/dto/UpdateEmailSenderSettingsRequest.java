package com.qn.calendar.settings.dto;

import com.qn.calendar.settings.constant.SmtpSecurity;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateEmailSenderSettingsRequest(
        @NotBlank(message = "寄件 Email 不可为空")
        @Email(message = "寄件 Email 格式无效")
        @Size(max = 320, message = "寄件 Email 最多 320 个字符")
        String senderEmail,

        @NotBlank(message = "SMTP 服务器不可为空")
        @Size(max = 255, message = "SMTP 服务器最多 255 个字符")
        String smtpHost,

        @NotNull(message = "SMTP 端口不可为空")
        @Min(value = 1, message = "SMTP 端口必须大于 0")
        @Max(value = 65535, message = "SMTP 端口不可超过 65535")
        Integer smtpPort,

        @NotNull(message = "加密方式不可为空")
        SmtpSecurity smtpSecurity,

        @Size(max = 1024, message = "授权码最多 1024 个字符")
        String smtpAuthCode
) {
}
