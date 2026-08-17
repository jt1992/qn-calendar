package com.qn.calendar.settings.dto;

public record DeleteOrderSourceResponse(
        AppSettingsResponse settings,
        long deletedWorkOrderCount
) {
}
