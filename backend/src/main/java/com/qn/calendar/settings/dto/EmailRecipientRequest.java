package com.qn.calendar.settings.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record EmailRecipientRequest(
        @NotBlank(message = "收件人姓名不可为空")
        @Size(max = 120, message = "收件人姓名最多 120 个字符") String name,
        @NotBlank(message = "收件 Email 不可为空")
        @Email(message = "收件 Email 格式无效")
        @Size(max = 320, message = "收件 Email 最多 320 个字符") String email
) {
}
