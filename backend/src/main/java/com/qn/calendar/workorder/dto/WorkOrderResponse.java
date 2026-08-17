package com.qn.calendar.workorder.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.qn.calendar.workorder.constant.WorkOrderSource;
import com.qn.calendar.workorder.entity.WorkOrder;
import com.qn.calendar.workorder.constant.WorkOrderStatus;

public record WorkOrderResponse(
        Long id,
        String orderNo,
        WorkOrderSource source,
        String sourceCode,
        String sourceName,
        String sourceBadgeColor,
        String sourceBadgeText,
        String buyerNickname,
        String remark,
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
                workOrder.getSource(),
                workOrder.getSourceCode(),
                workOrder.getSourceName(),
                workOrder.getSourceBadgeColor(),
                workOrder.getSourceBadgeText(),
                workOrder.getBuyerNickname(),
                workOrder.getRemark(),
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
