package com.qn.calendar.workorder.dto;

public record ImportRowError(
        int row,
        String message
) {
}
