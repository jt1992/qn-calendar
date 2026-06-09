package com.qn.calendar.workorder.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.qn.calendar.workorder.entity.WorkOrder;
import com.qn.calendar.workorder.entity.WorkOrderSegment;
import com.qn.calendar.workorder.constant.WorkOrderStatus;
import com.qn.calendar.workorder.util.WorkOrderTimeUtils;

public record WorkOrderSegmentResponse(
        Long id,
        Long segmentId,
        Long workOrderId,
        String orderNo,
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
        LocalDateTime completedAt
) {

    public static WorkOrderSegmentResponse from(WorkOrderSegment segment, int totalMinutes) {
        WorkOrder workOrder = segment.getWorkOrder();
        return new WorkOrderSegmentResponse(
                segment.getId(),
                segment.getId(),
                workOrder.getId(),
                workOrder.getOrderNo(),
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
                workOrder.getCompletedAt()
        );
    }

}
