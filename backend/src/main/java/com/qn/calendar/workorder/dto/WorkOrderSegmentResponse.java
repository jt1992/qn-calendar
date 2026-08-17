package com.qn.calendar.workorder.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.qn.calendar.workorder.constant.WorkOrderSource;
import com.qn.calendar.workorder.constant.WorkOrderStatus;
import com.qn.calendar.workorder.entity.WorkOrder;
import com.qn.calendar.workorder.entity.WorkOrderSegment;
import com.qn.calendar.workorder.util.WorkOrderTimeUtils;

public record WorkOrderSegmentResponse(
        Long id,
        Long segmentId,
        Long workOrderId,
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
        int totalMinutes,
        boolean urgent,
        LocalDateTime latestShipTime,
        WorkOrderStatus status,
        LocalDateTime scheduledStart,
        LocalDateTime scheduledEnd,
        LocalDateTime completedAt,
        boolean paused,
        int pausedMinutes,
        boolean overdue,
        boolean scheduleStartLocked,
        LocalDateTime latestPausedAt
) {

    public static WorkOrderSegmentResponse from(WorkOrderSegment segment, int totalMinutes) {
        return from(segment, totalMinutes, false, 0, false, null);
    }

    public static WorkOrderSegmentResponse from(
            WorkOrderSegment segment,
            int totalMinutes,
            boolean paused,
            int pausedMinutes
    ) {
        return from(segment, totalMinutes, paused, pausedMinutes, false, null);
    }

    public static WorkOrderSegmentResponse from(
            WorkOrderSegment segment,
            int totalMinutes,
            boolean paused,
            int pausedMinutes,
            boolean scheduleStartLocked,
            LocalDateTime latestPausedAt
    ) {
        WorkOrder workOrder = segment.getWorkOrder();
        return new WorkOrderSegmentResponse(
                segment.getId(),
                segment.getId(),
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
                WorkOrderTimeUtils.segmentMinutes(segment),
                totalMinutes,
                workOrder.isUrgent(),
                workOrder.getLatestShipTime(),
                workOrder.getStatus(),
                segment.getScheduledStart(),
                segment.getScheduledEnd(),
                workOrder.getCompletedAt(),
                paused,
                pausedMinutes,
                segment.getScheduledEnd().isAfter(workOrder.getLatestShipTime()),
                scheduleStartLocked,
                latestPausedAt
        );
    }

}
