package com.qn.calendar.workorder.dto;

public record ImportRowError(
        int row,
        int recordNumber,
        String message
) {
}
