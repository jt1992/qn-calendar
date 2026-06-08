package com.qn.calendar.workorder.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.qn.calendar.workorder.WorkOrder;
import com.qn.calendar.workorder.WorkOrderStatus;

public record WorkOrderResponse(
        Long id,
        String orderNo,
        BigDecimal price,
        int estimatedMinutes,
        int actualMinutes,
        boolean urgent,
        LocalDateTime latestShipTime,
        WorkOrderStatus status,
        LocalDateTime scheduledStart,
        LocalDateTime scheduledEnd,
        LocalDateTime completedAt
) {

    public static WorkOrderResponse from(WorkOrder workOrder) {
        return new WorkOrderResponse(
                workOrder.getId(),
                workOrder.getOrderNo(),
                workOrder.getPrice(),
                workOrder.getEstimatedMinutes(),
                workOrder.getActualMinutes(),
                workOrder.isUrgent(),
                workOrder.getLatestShipTime(),
                workOrder.getStatus(),
                workOrder.getScheduledStart(),
                workOrder.getScheduledEnd(),
                workOrder.getCompletedAt()
        );
    }
}
