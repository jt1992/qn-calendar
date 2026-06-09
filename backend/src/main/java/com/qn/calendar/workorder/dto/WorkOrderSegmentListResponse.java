package com.qn.calendar.workorder.dto;

import java.util.List;

import com.qn.calendar.workorder.entity.WorkOrder;
import com.qn.calendar.workorder.entity.WorkOrderSegment;

public record WorkOrderSegmentListResponse(
        WorkOrderResponse workOrder,
        List<WorkOrderSegmentResponse> segments,
        int totalMinutes
) {

    public static WorkOrderSegmentListResponse from(WorkOrder workOrder, List<WorkOrderSegment> segments, int totalMinutes) {
        return new WorkOrderSegmentListResponse(
                WorkOrderResponse.from(workOrder),
                segments.stream()
                        .map((segment) -> WorkOrderSegmentResponse.from(segment, totalMinutes))
                        .toList(),
                totalMinutes
        );
    }
}
