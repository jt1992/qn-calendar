package com.qn.calendar.workorder.dto;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;

import com.qn.calendar.workorder.WorkOrder;
import com.qn.calendar.workorder.WorkOrderSegment;
import com.qn.calendar.workorder.WorkOrderStatus;

public record WorkOrderSegmentResponse(
        Long id,
        Long segmentId,
        Long workOrderId,
        String orderNo,
        BigDecimal price,
        int estimatedMinutes,
        int actualMinutes,
        int totalMinutes,
        boolean urgent,
        LocalDateTime latestShipTime,
        WorkOrderStatus status,
        LocalDateTime scheduledStart,
        LocalDateTime scheduledEnd,
        LocalDateTime completedAt
) {

    public static WorkOrderSegmentResponse from(WorkOrderSegment segment, int totalMinutes) {
        WorkOrder workOrder = segment.getWorkOrder();
        return new WorkOrderSegmentResponse(
                segment.getId(),
                segment.getId(),
                workOrder.getId(),
                workOrder.getOrderNo(),
                workOrder.getPrice(),
                workOrder.getEstimatedMinutes(),
                segmentMinutes(segment),
                totalMinutes,
                workOrder.isUrgent(),
                workOrder.getLatestShipTime(),
                workOrder.getStatus(),
                segment.getScheduledStart(),
                segment.getScheduledEnd(),
                workOrder.getCompletedAt()
        );
    }

    private static int segmentMinutes(WorkOrderSegment segment) {
        return Math.toIntExact(Duration.between(
                segment.getScheduledStart(),
                segment.getScheduledEnd()
        ).toMinutes());
    }
}
