package com.qn.calendar.workorder;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.qn.calendar.workorder.dto.ScheduleWorkOrderRequest;
import com.qn.calendar.workorder.dto.SplitWorkOrderSegmentRequest;
import com.qn.calendar.workorder.dto.WorkOrderSegmentListResponse;

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
                .orElseThrow(() -> new IllegalArgumentException("找不到工單"));

        validateSchedule(workOrder, request.scheduledStart(), request.scheduledEnd());
        segmentRepository.save(new WorkOrderSegment(workOrder, request.scheduledStart(), request.scheduledEnd()));
        return normalize(workOrder);
    }

    @Transactional
    public WorkOrderSegmentListResponse updateSegment(Long segmentId, ScheduleWorkOrderRequest request) {
        WorkOrderSegment segment = segmentRepository.findById(segmentId)
                .orElseThrow(() -> new IllegalArgumentException("找不到工單片段"));
        WorkOrder workOrder = segment.getWorkOrder();

        validateSchedule(workOrder, request.scheduledStart(), request.scheduledEnd());
        segment.updateSchedule(request.scheduledStart(), request.scheduledEnd());
        return normalize(workOrder);
    }

    @Transactional
    public WorkOrderSegmentListResponse deleteSegment(Long segmentId) {
        WorkOrderSegment segment = segmentRepository.findById(segmentId)
                .orElseThrow(() -> new IllegalArgumentException("找不到工單片段"));
        WorkOrder workOrder = segment.getWorkOrder();

        segmentRepository.delete(segment);
        return normalize(workOrder);
    }

    @Transactional
    public WorkOrderSegmentListResponse splitSegment(Long segmentId, SplitWorkOrderSegmentRequest request) {
        WorkOrderSegment segment = segmentRepository.findById(segmentId)
                .orElseThrow(() -> new IllegalArgumentException("找不到工單片段"));
        WorkOrder workOrder = segment.getWorkOrder();
        LocalDateTime originalEnd = segment.getScheduledEnd();

        validateSplit(segment, request.splitAt());
        segment.updateSchedule(segment.getScheduledStart(), request.splitAt());
        segmentRepository.save(new WorkOrderSegment(workOrder, request.splitAt(), originalEnd));
        List<WorkOrderSegment> segments = segmentRepository.findByWorkOrderIdOrderByScheduledStartAscScheduledEndAscIdAsc(
                workOrder.getId()
        );
        int totalMinutes = segments.stream()
                .mapToInt(WorkOrderSegmentService::segmentMinutes)
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
                .orElseThrow(() -> new IllegalArgumentException("找不到工單片段"));
        WorkOrder workOrder = segment.getWorkOrder();
        LocalDateTime roundedCompletedAt = roundUpToFiveMinuteBoundary(completedAt);

        if (roundedCompletedAt.isAfter(segment.getScheduledEnd())) {
            validateSchedule(workOrder, segment.getScheduledStart(), roundedCompletedAt);
            segment.updateSchedule(segment.getScheduledStart(), roundedCompletedAt);
        }

        workOrder.markDone(roundedCompletedAt);
        return normalize(workOrder);
    }

    @Transactional
    public WorkOrderSegmentListResponse deleteAllSegments(Long workOrderId) {
        WorkOrder workOrder = workOrderRepository.findById(workOrderId)
                .orElseThrow(() -> new IllegalArgumentException("找不到工單"));

        if (workOrder.getStatus() == WorkOrderStatus.PENDING) {
            throw new IllegalStateException("工單尚未排入日曆");
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
                .mapToInt(WorkOrderSegmentService::segmentMinutes)
                .sum();
        workOrder.syncScheduleSummary(
                normalized.getFirst().getScheduledStart(),
                normalized.getLast().getScheduledEnd(),
                totalMinutes
        );

        return WorkOrderSegmentListResponse.from(workOrder, normalized, totalMinutes);
    }

    private void validateSchedule(WorkOrder workOrder, LocalDateTime scheduledStart, LocalDateTime scheduledEnd) {
        long minutes = Duration.between(scheduledStart, scheduledEnd).toMinutes();

        if (minutes <= 0) {
            throw new IllegalArgumentException("排程結束時間必須晚於開始時間");
        }

        if (minutes % 5 != 0) {
            throw new IllegalArgumentException("工時必須是 5 分鐘的倍數");
        }

        if (!isFiveMinuteBoundary(scheduledStart) || !isFiveMinuteBoundary(scheduledEnd)) {
            throw new IllegalArgumentException("排程時間必須符合 5 分鐘粒度");
        }

        if (scheduledEnd.isAfter(workOrder.getLatestShipTime())) {
            throw new IllegalArgumentException("排程結束時間不可超過最晚發貨時間");
        }

        if (segmentRepository.existsOverlappingDifferentWorkOrder(
                workOrder.getId(),
                List.of(WorkOrderStatus.SCHEDULED, WorkOrderStatus.DONE),
                scheduledStart,
                scheduledEnd
        )) {
            throw new IllegalArgumentException("不同工單排程不可重疊");
        }
    }

    private void validateSplit(WorkOrderSegment segment, LocalDateTime splitAt) {
        if (!isFiveMinuteBoundary(splitAt)) {
            throw new IllegalArgumentException("拆分時間必須符合 5 分鐘粒度");
        }

        if (!splitAt.isAfter(segment.getScheduledStart()) || !splitAt.isBefore(segment.getScheduledEnd())) {
            throw new IllegalArgumentException("拆分時間必須位於片段內");
        }
    }

    private static int segmentMinutes(WorkOrderSegment segment) {
        return Math.toIntExact(Duration.between(
                segment.getScheduledStart(),
                segment.getScheduledEnd()
        ).toMinutes());
    }

    private boolean isFiveMinuteBoundary(LocalDateTime time) {
        return time.getSecond() == 0
                && time.getNano() == 0
                && time.getMinute() % 5 == 0;
    }

    private LocalDateTime roundUpToFiveMinuteBoundary(LocalDateTime time) {
        LocalDateTime truncated = time.withSecond(0).withNano(0);
        int remainder = truncated.getMinute() % 5;

        if (remainder == 0 && time.getSecond() == 0 && time.getNano() == 0) {
            return truncated;
        }

        return truncated.plusMinutes(remainder == 0 ? 5 : 5 - remainder);
    }
}
