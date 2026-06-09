package com.qn.calendar.workorder.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

import com.qn.calendar.workorder.entity.WorkOrder;

public record CompletedWorkOrderStatsResponse(
        Long id,
        String orderNo,
        String buyerNickname,
        String remark,
        BigDecimal price,
        int estimatedMinutes,
        int actualTotalMinutes,
        int deltaMinutes,
        BigDecimal hourlyRate,
        LocalDateTime latestShipTime,
        LocalDateTime completedAt
) {

    public static CompletedWorkOrderStatsResponse from(WorkOrder workOrder, int actualTotalMinutes) {
        return new CompletedWorkOrderStatsResponse(
                workOrder.getId(),
                workOrder.getOrderNo(),
                workOrder.getBuyerNickname(),
                workOrder.getRemark(),
                workOrder.getPrice(),
                workOrder.getEstimatedMinutes(),
                actualTotalMinutes,
                actualTotalMinutes - workOrder.getEstimatedMinutes(),
                calculateHourlyRate(workOrder.getPrice(), actualTotalMinutes),
                workOrder.getLatestShipTime(),
                workOrder.getCompletedAt()
        );
    }

    private static BigDecimal calculateHourlyRate(BigDecimal price, int actualTotalMinutes) {
        if (actualTotalMinutes <= 0) {
            return null;
        }

        return price
                .multiply(BigDecimal.valueOf(60))
                .divide(BigDecimal.valueOf(actualTotalMinutes), 2, RoundingMode.HALF_UP);
    }
}
