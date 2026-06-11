package com.qn.calendar.workorder.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import com.qn.calendar.workorder.entity.WorkOrder;
import com.qn.calendar.workorder.entity.WorkOrderSegment;

public record WorkOrderSegmentListResponse(
        WorkOrderResponse workOrder,
        List<WorkOrderSegmentResponse> segments,
        int totalMinutes
) {

    public static WorkOrderSegmentListResponse from(WorkOrder workOrder, List<WorkOrderSegment> segments, int totalMinutes) {
        return from(workOrder, segments, totalMinutes, Map.of(), 0, Map.of(), Map.of());
    }

    public static WorkOrderSegmentListResponse from(
            WorkOrder workOrder,
            List<WorkOrderSegment> segments,
            int totalMinutes,
            Map<Long, Boolean> pausedBySegmentId,
            int pausedMinutes
    ) {
        return from(workOrder, segments, totalMinutes, pausedBySegmentId, pausedMinutes, Map.of(), Map.of());
    }

    public static WorkOrderSegmentListResponse from(
            WorkOrder workOrder,
            List<WorkOrderSegment> segments,
            int totalMinutes,
            Map<Long, Boolean> pausedBySegmentId,
            int pausedMinutes,
            Map<Long, Boolean> scheduleStartLockedBySegmentId,
            Map<Long, LocalDateTime> latestPausedAtBySegmentId
    ) {
        return new WorkOrderSegmentListResponse(
                WorkOrderResponse.from(workOrder),
                segments.stream()
                        .map((segment) -> WorkOrderSegmentResponse.from(
                                segment,
                                totalMinutes,
                                pausedBySegmentId.getOrDefault(segment.getId(), false),
                                pausedMinutes,
                                scheduleStartLockedBySegmentId.getOrDefault(segment.getId(), false),
                                latestPausedAtBySegmentId.get(segment.getId())
                        ))
                        .toList(),
                totalMinutes
        );
    }
}
