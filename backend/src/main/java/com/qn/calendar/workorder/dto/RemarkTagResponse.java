package com.qn.calendar.workorder.dto;

import com.qn.calendar.settings.entity.RemarkTagDefinition;

public record RemarkTagResponse(
        Long id,
        String systemKey,
        String name,
        String color
) {

    public static RemarkTagResponse from(RemarkTagDefinition tag) {
        return new RemarkTagResponse(
                tag.getId(),
                tag.getSystemKey(),
                tag.getName(),
                tag.getColor()
        );
    }
}
