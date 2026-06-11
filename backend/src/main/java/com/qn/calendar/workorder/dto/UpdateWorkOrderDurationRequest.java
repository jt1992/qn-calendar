package com.qn.calendar.workorder.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record UpdateWorkOrderDurationRequest(
        @NotNull(message = "工时不可为空")
        @Min(value = 15, message = "工时不可小于 15 分钟")
        Integer actualMinutes
) {
}
