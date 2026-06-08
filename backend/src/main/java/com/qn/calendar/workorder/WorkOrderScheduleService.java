package com.qn.calendar.workorder;

import com.qn.calendar.workorder.dto.ScheduleWorkOrderRequest;
import com.qn.calendar.workorder.dto.WorkOrderSegmentListResponse;

import org.springframework.stereotype.Service;

@Service
public class WorkOrderScheduleService {

    private final WorkOrderSegmentService segmentService;

    public WorkOrderScheduleService(WorkOrderSegmentService segmentService) {
        this.segmentService = segmentService;
    }

    public WorkOrderSegmentListResponse schedule(Long id, ScheduleWorkOrderRequest request) {
        return segmentService.createSegment(id, request);
    }
}
