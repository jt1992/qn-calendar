package com.qn.calendar.workorder.dto;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotNull;

public record SplitWorkOrderSegmentRequest(
        @NotNull LocalDateTime splitAt
) {
}
