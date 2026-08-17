package com.qn.calendar.settings.dto;

public record OrderSourceDeletionImpactResponse(
        String identifier,
        String name,
        long workOrderCount
) {
}
