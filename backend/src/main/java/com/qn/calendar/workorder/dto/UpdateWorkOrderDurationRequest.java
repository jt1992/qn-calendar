package com.qn.calendar.workorder.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record UpdateWorkOrderDurationRequest(
        @NotNull(message = "工時不可為空")
        @Min(value = 5, message = "工時不可小於 5 分鐘")
        Integer actualMinutes
) {
}
