package com.qn.calendar.common;

import java.time.LocalDateTime;
import java.util.List;

public record ApiError(
        String message,
        List<String> details,
        LocalDateTime timestamp
) {

    public static ApiError of(String message, List<String> details) {
        return new ApiError(message, details, LocalDateTime.now());
    }
}
