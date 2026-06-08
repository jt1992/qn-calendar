package com.qn.calendar.workorder.dto;

import java.util.List;

public record ImportWorkOrderResponse(
        int createdCount,
        int skippedCount,
        List<ImportRowError> errors
) {
}
