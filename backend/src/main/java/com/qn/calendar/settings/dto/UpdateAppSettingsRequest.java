package com.qn.calendar.settings.dto;

import java.math.BigDecimal;
import java.time.LocalTime;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record UpdateAppSettingsRequest(
        @NotNull(message = "预估工时基础金额不可为空")
        @Positive(message = "预估工时基础金额必须大于 0")
        @Digits(integer = 12, fraction = 2, message = "预估工时基础金额最多保留 2 位小数")
        BigDecimal estimatedHourlyBaseAmount,
        @NotNull(message = "周表默认开始时间不可为空")
        @JsonFormat(pattern = "HH:mm")
        LocalTime weekViewDefaultStartTime
) {
}
