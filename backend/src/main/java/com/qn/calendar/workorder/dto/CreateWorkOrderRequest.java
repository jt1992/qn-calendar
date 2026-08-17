package com.qn.calendar.workorder.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateWorkOrderRequest(
        @NotBlank(message = "订单编号不可为空")
        @Size(max = 80, message = "订单编号最长为 80 个字符")
        String orderNo,

        @NotNull(message = "买家实付金额不可为空")
        @DecimalMin(value = "0", message = "买家实付金额不可为负数")
        @Digits(integer = 12, fraction = 2, message = "买家实付金额最多 12 位整数与 2 位小数")
        BigDecimal price,

        @NotNull(message = "应发货时间不可为空")
        LocalDateTime latestShipTime,

        @Size(max = 120, message = "备注标签最长为 120 个字符")
        String urgentText,

        @Size(max = 1000, message = "买家留言最长为 1000 个字符")
        String buyerMessage,

        @Size(max = 1000, message = "商家备注最长为 1000 个字符")
        String merchantRemark,

        LocalDateTime paidAt
) {
}
