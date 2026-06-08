package com.qn.calendar.workorder.dto;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotNull;

public record ScheduleWorkOrderRequest(
        @NotNull LocalDateTime scheduledStart,
        @NotNull LocalDateTime scheduledEnd
) {
}
