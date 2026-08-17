package com.qn.calendar.settings.dto;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record UpdateAppSettingsRequest(
        @NotNull(message = "预估工时基础金额不可为空")
        @Positive(message = "预估工时基础金额必须大于 0")
        @Digits(integer = 12, fraction = 2, message = "预估工时基础金额最多保留 2 位小数")
        BigDecimal estimatedHourlyBaseAmount,
        @NotNull(message = "周表默认开始时间不可为空")
        @JsonFormat(pattern = "HH:mm")
        LocalTime weekViewDefaultStartTime,
        @NotNull(message = "订单来源选项不可为空")
        @Size(min = 1, max = 20, message = "订单来源选项必须介于 1 到 20 个")
        List<@Valid OrderSourceOptionRequest> orderSourceOptions
) {

    public record OrderSourceOptionRequest(
            @NotBlank(message = "订单来源名称不可为空")
            @Size(max = 80, message = "订单来源名称最长为 80 个字符")
            String name,
            @NotBlank(message = "订单来源识别文字不可为空")
            @Size(max = 40, message = "订单来源识别文字最长为 40 个字符")
            String identifier,
            @NotBlank(message = "订单来源标签颜色不可为空")
            String badgeColor,
            @NotBlank(message = "订单来源标签文字不可为空")
            @Size(max = 8, message = "订单来源标签文字过长")
            String badgeText
    ) {
    }
}
