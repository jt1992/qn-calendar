package com.qn.calendar.settings.dto;

import java.time.LocalDateTime;

import com.qn.calendar.settings.entity.EmailRecipient;

public record EmailRecipientResponse(
        Long id,
        String name,
        String email,
        int usageCount,
        LocalDateTime lastUsedAt
) {

    public static EmailRecipientResponse from(EmailRecipient recipient) {
        return new EmailRecipientResponse(
                recipient.getId(),
                recipient.getName(),
                recipient.getEmail(),
                recipient.getUsageCount(),
                recipient.getLastUsedAt()
        );
    }
}
