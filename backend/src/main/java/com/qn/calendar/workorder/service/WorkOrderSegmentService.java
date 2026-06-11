package com.qn.calendar.workorder.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.qn.calendar.workorder.constant.WorkOrderStatus;
import com.qn.calendar.workorder.dto.ScheduleWorkOrderRequest;
import com.qn.calendar.workorder.dto.SplitWorkOrderSegmentRequest;
import com.qn.calendar.workorder.dto.WorkOrderSegmentListResponse;
import com.qn.calendar.workorder.entity.WorkOrder;
import com.qn.calendar.workorder.entity.WorkOrderSegment;
import com.qn.calendar.workorder.repository.WorkOrderRepository;
import com.qn.calendar.workorder.repository.WorkOrderSegmentRepository;
import com.qn.calendar.workorder.util.WorkOrderTimeUtils;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkOrderSegmentService {

    private final WorkOrderRepository workOrderRepository;
    private final WorkOrderSegmentRepository segmentRepository;

    public WorkOrderSegmentService(
            WorkOrderRepository workOrderRepository,
            WorkOrderSegmentRepository segmentRepository
    ) {
        this.workOrderRepository = workOrderRepository;
        this.segmentRepository = segmentRepository;
    }

    @Transactional
    public WorkOrderSegmentListResponse createSegment(Long workOrderId, ScheduleWorkOrderRequest request) {
        WorkOrder workOrder = workOrderRepository.findById(workOrderId)
                .orElseThrow(() -> new IllegalArgumentException("找不到工单"));

        validateSchedule(workOrder, request.scheduledStart(), request.scheduledEnd());
        segmentRepository.save(new WorkOrderSegment(workOrder, request.scheduledStart(), request.scheduledEnd()));
        return normalize(workOrder);
    }

    @Transactional
    public WorkOrderSegmentListResponse updateSegment(Long segmentId, ScheduleWorkOrderRequest request) {
        WorkOrderSegment segment = segmentRepository.findById(segmentId)
                .orElseThrow(() -> new IllegalArgumentException("找不到工单片段"));
        WorkOrder workOrder = segment.getWorkOrder();

        validateSchedule(workOrder, request.scheduledStart(), request.scheduledEnd());
        segment.updateSchedule(request.scheduledStart(), request.scheduledEnd());
        return normalize(workOrder);
    }

    @Transactional
    public WorkOrderSegmentListResponse deleteSegment(Long segmentId) {
        WorkOrderSegment segment = segmentRepository.findById(segmentId)
                .orElseThrow(() -> new IllegalArgumentException("找不到工单片段"));
        WorkOrder workOrder = segment.getWorkOrder();

        segmentRepository.delete(segment);
        return normalize(workOrder);
    }

    @Transactional
    public WorkOrderSegmentListResponse splitSegment(Long segmentId, SplitWorkOrderSegmentRequest request) {
        WorkOrderSegment segment = segmentRepository.findById(segmentId)
                .orElseThrow(() -> new IllegalArgumentException("找不到工单片段"));
        WorkOrder workOrder = segment.getWorkOrder();
        LocalDateTime originalEnd = segment.getScheduledEnd();

        validateSplit(segment, request.splitAt());
        segment.updateSchedule(segment.getScheduledStart(), request.splitAt());
        segmentRepository.save(new WorkOrderSegment(workOrder, request.splitAt(), originalEnd));
        List<WorkOrderSegment> segments = segmentRepository.findByWorkOrderIdOrderByScheduledStartAscScheduledEndAscIdAsc(
                workOrder.getId()
        );
        int totalMinutes = segments.stream()
                .mapToInt(WorkOrderTimeUtils::segmentMinutes)
                .sum();
        workOrder.syncScheduleSummary(
                segments.getFirst().getScheduledStart(),
                segments.getLast().getScheduledEnd(),
                totalMinutes
        );
        return WorkOrderSegmentListResponse.from(workOrder, segments, totalMinutes);
    }

    @Transactional
    public WorkOrderSegmentListResponse completeSegment(Long segmentId) {
        return completeSegment(segmentId, LocalDateTime.now());
    }

    @Transactional
    public WorkOrderSegmentListResponse completeSegment(Long segmentId, LocalDateTime completedAt) {
        WorkOrderSegment segment = segmentRepository.findById(segmentId)
                .orElseThrow(() -> new IllegalArgumentException("找不到工单片段"));
        WorkOrder workOrder = segment.getWorkOrder();
        LocalDateTime roundedCompletedAt = WorkOrderTimeUtils.roundUpToScheduleBoundary(completedAt);

        if (isSameScheduleDay(segment, roundedCompletedAt) && roundedCompletedAt.isAfter(segment.getScheduledEnd())) {
            validateSchedule(workOrder, segment.getScheduledStart(), roundedCompletedAt);
            segment.updateSchedule(segment.getScheduledStart(), roundedCompletedAt);
        }

        workOrder.markDone(roundedCompletedAt);
        return normalize(workOrder);
    }

    @Transactional
    public WorkOrderSegmentListResponse deleteAllSegments(Long workOrderId) {
        WorkOrder workOrder = workOrderRepository.findById(workOrderId)
                .orElseThrow(() -> new IllegalArgumentException("找不到工单"));

        if (workOrder.getStatus() == WorkOrderStatus.PENDING) {
            throw new IllegalStateException("工单尚未排入日历");
        }

        segmentRepository.deleteByWorkOrderId(workOrderId);
        workOrder.unschedule();
        return WorkOrderSegmentListResponse.from(workOrder, List.of(), 0);
    }

    private WorkOrderSegmentListResponse normalize(WorkOrder workOrder) {
        List<WorkOrderSegment> segments = new ArrayList<>(
                segmentRepository.findByWorkOrderIdOrderByScheduledStartAscScheduledEndAscIdAsc(workOrder.getId())
        );

        if (segments.isEmpty()) {
            workOrder.unschedule();
            return WorkOrderSegmentListResponse.from(workOrder, List.of(), 0);
        }

        segments.sort(Comparator
                .comparing(WorkOrderSegment::getScheduledStart)
                .thenComparing(WorkOrderSegment::getScheduledEnd)
                .thenComparing(WorkOrderSegment::getId));

        List<WorkOrderSegment> normalized = new ArrayList<>();
        WorkOrderSegment current = segments.getFirst();

        for (int index = 1; index < segments.size(); index++) {
            WorkOrderSegment next = segments.get(index);

            if (!next.getScheduledStart().isAfter(current.getScheduledEnd())) {
                if (next.getScheduledEnd().isAfter(current.getScheduledEnd())) {
                    current.updateSchedule(current.getScheduledStart(), next.getScheduledEnd());
                }
                segmentRepository.delete(next);
            } else {
                normalized.add(current);
                current = next;
            }
        }

        normalized.add(current);

        int totalMinutes = normalized.stream()
                .mapToInt(WorkOrderTimeUtils::segmentMinutes)
                .sum();
        workOrder.syncScheduleSummary(
                normalized.getFirst().getScheduledStart(),
                normalized.getLast().getScheduledEnd(),
                totalMinutes
        );

        return WorkOrderSegmentListResponse.from(workOrder, normalized, totalMinutes);
    }

    private boolean isSameScheduleDay(WorkOrderSegment segment, LocalDateTime completedAt) {
        return segment.getScheduledStart().toLocalDate().isEqual(completedAt.toLocalDate());
    }

    private void validateSchedule(WorkOrder workOrder, LocalDateTime scheduledStart, LocalDateTime scheduledEnd) {
        long minutes = Duration.between(scheduledStart, scheduledEnd).toMinutes();

        if (minutes <= 0) {
            throw new IllegalArgumentException("排程结束时间必须晚于开始时间");
        }

        if (minutes % WorkOrderTimeUtils.SCHEDULE_GRANULARITY_MINUTES != 0) {
            throw new IllegalArgumentException("工时必须是 15 分钟的倍数");
        }

        if (!WorkOrderTimeUtils.isScheduleBoundary(scheduledStart)
                || !WorkOrderTimeUtils.isScheduleBoundary(scheduledEnd)) {
            throw new IllegalArgumentException("排程时间必须符合 15 分钟粒度");
        }

        if (scheduledEnd.isAfter(workOrder.getLatestShipTime())) {
            throw new IllegalArgumentException("排程结束时间不可超过最晚发货时间");
        }

        if (segmentRepository.existsOverlappingDifferentWorkOrder(
                workOrder.getId(),
                List.of(WorkOrderStatus.SCHEDULED, WorkOrderStatus.DONE),
                scheduledStart,
                scheduledEnd
        )) {
            throw new IllegalArgumentException("不同工单排程不可重叠");
        }
    }

    private void validateSplit(WorkOrderSegment segment, LocalDateTime splitAt) {
        if (!WorkOrderTimeUtils.isScheduleBoundary(splitAt)) {
            throw new IllegalArgumentException("拆分时间必须符合 15 分钟粒度");
        }

        if (!splitAt.isAfter(segment.getScheduledStart()) || !splitAt.isBefore(segment.getScheduledEnd())) {
            throw new IllegalArgumentException("拆分时间必须位于片段内");
        }
    }

}
