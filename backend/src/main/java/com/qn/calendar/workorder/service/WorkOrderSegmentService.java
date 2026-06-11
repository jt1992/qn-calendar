package com.qn.calendar.workorder.service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.qn.calendar.workorder.constant.WorkOrderStatus;
import com.qn.calendar.workorder.dto.ScheduleWorkOrderRequest;
import com.qn.calendar.workorder.dto.SplitWorkOrderSegmentRequest;
import com.qn.calendar.workorder.dto.WorkOrderSegmentListResponse;
import com.qn.calendar.workorder.entity.WorkOrder;
import com.qn.calendar.workorder.entity.WorkOrderSegment;
import com.qn.calendar.workorder.entity.WorkOrderSegmentPause;
import com.qn.calendar.workorder.repository.WorkOrderRepository;
import com.qn.calendar.workorder.repository.WorkOrderSegmentPauseRepository;
import com.qn.calendar.workorder.repository.WorkOrderSegmentRepository;
import com.qn.calendar.workorder.util.WorkOrderTimeUtils;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkOrderSegmentService {

    private final WorkOrderRepository workOrderRepository;
    private final WorkOrderSegmentRepository segmentRepository;
    private final WorkOrderSegmentPauseRepository pauseRepository;

    public WorkOrderSegmentService(
            WorkOrderRepository workOrderRepository,
            WorkOrderSegmentRepository segmentRepository,
            WorkOrderSegmentPauseRepository pauseRepository
    ) {
        this.workOrderRepository = workOrderRepository;
        this.segmentRepository = segmentRepository;
        this.pauseRepository = pauseRepository;
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

        validateScheduleStartLock(segment, request.scheduledStart(), request.scheduledEnd());
        validateSchedule(workOrder, request.scheduledStart(), request.scheduledEnd());
        segment.updateSchedule(request.scheduledStart(), request.scheduledEnd());
        return normalize(workOrder);
    }

    @Transactional
    public WorkOrderSegmentListResponse deleteSegment(Long segmentId) {
        WorkOrderSegment segment = segmentRepository.findById(segmentId)
                .orElseThrow(() -> new IllegalArgumentException("找不到工单片段"));
        WorkOrder workOrder = segment.getWorkOrder();

        pauseRepository.deleteBySegmentId(segmentId);
        segmentRepository.delete(segment);
        return normalize(workOrder);
    }

    @Transactional
    public WorkOrderSegmentListResponse splitSegment(Long segmentId, SplitWorkOrderSegmentRequest request) {
        WorkOrderSegment segment = segmentRepository.findById(segmentId)
                .orElseThrow(() -> new IllegalArgumentException("找不到工单片段"));
        WorkOrder workOrder = segment.getWorkOrder();
        LocalDateTime originalEnd = segment.getScheduledEnd();

        if (pauseRepository.existsBySegmentIdAndResumedAtIsNull(segmentId)) {
            throw new IllegalStateException("暂停中的工单不可拆分");
        }

        validateSplit(segment, request.splitAt());
        validateScheduleStartLock(segment, segment.getScheduledStart(), request.splitAt());
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
        return completeSegment(segmentId, currentTime());
    }

    @Transactional
    public WorkOrderSegmentListResponse completeSegment(Long segmentId, LocalDateTime completedAt) {
        WorkOrderSegment segment = segmentRepository.findById(segmentId)
                .orElseThrow(() -> new IllegalArgumentException("找不到工单片段"));
        WorkOrder workOrder = segment.getWorkOrder();
        LocalDateTime normalizedCompletedAt = normalizeClockTime(completedAt);

        pauseRepository.findFirstBySegmentIdAndResumedAtIsNullOrderByPausedAtDescIdDesc(segmentId)
                .ifPresent((pause) -> resumePauseForCompletion(pause, normalizedCompletedAt));

        if (isSameScheduleDay(segment, normalizedCompletedAt)
                && normalizedCompletedAt.isAfter(segment.getScheduledStart())
                && !normalizedCompletedAt.isEqual(segment.getScheduledEnd())) {
            updateSegmentEndAndShiftFollowing(segment, normalizedCompletedAt);
        } else {
            syncScheduleSummary(workOrder);
        }

        workOrder.markDone(normalizedCompletedAt);
        return responseForWorkOrder(workOrder);
    }

    @Transactional
    public WorkOrderSegmentListResponse pauseSegment(Long segmentId) {
        return pauseSegment(segmentId, currentTime());
    }

    @Transactional
    public WorkOrderSegmentListResponse pauseSegment(Long segmentId, LocalDateTime pausedAt) {
        WorkOrderSegment segment = segmentRepository.findById(segmentId)
                .orElseThrow(() -> new IllegalArgumentException("找不到工单片段"));
        LocalDateTime normalizedPausedAt = normalizeClockTime(pausedAt);

        validatePauseAction(segment, normalizedPausedAt);

        if (pauseRepository.existsBySegmentIdAndResumedAtIsNull(segmentId)) {
            throw new IllegalStateException("工单已经暂停");
        }

        if (normalizedPausedAt.isAfter(segment.getScheduledEnd())) {
            updateSegmentEndAndShiftFollowing(segment, WorkOrderTimeUtils.roundUpToScheduleBoundary(normalizedPausedAt));
        }

        pauseRepository.save(new WorkOrderSegmentPause(segment, normalizedPausedAt));
        return responseForWorkOrder(segment.getWorkOrder());
    }

    @Transactional
    public WorkOrderSegmentListResponse resumeSegment(Long segmentId) {
        return resumeSegment(segmentId, currentTime());
    }

    @Transactional
    public WorkOrderSegmentListResponse resumeSegment(Long segmentId, LocalDateTime resumedAt) {
        WorkOrderSegment segment = segmentRepository.findById(segmentId)
                .orElseThrow(() -> new IllegalArgumentException("找不到工单片段"));
        WorkOrderSegmentPause pause = pauseRepository
                .findFirstBySegmentIdAndResumedAtIsNullOrderByPausedAtDescIdDesc(segmentId)
                .orElseThrow(() -> new IllegalStateException("工单目前未暂停"));
        LocalDateTime normalizedResumedAt = normalizeClockTime(resumedAt);

        if (segment.getWorkOrder().getStatus() == WorkOrderStatus.DONE) {
            throw new IllegalStateException("完成工单不可继续");
        }

        if (normalizedResumedAt.isBefore(pause.getPausedAt())) {
            throw new IllegalArgumentException("继续时间不可早于暂停时间");
        }

        pause.resume(normalizedResumedAt);

        if (normalizedResumedAt.isAfter(segment.getScheduledEnd())) {
            updateSegmentEndAndShiftFollowing(
                    segment,
                    WorkOrderTimeUtils.roundUpToScheduleBoundary(normalizedResumedAt)
            );
        }

        return responseForWorkOrder(segment.getWorkOrder());
    }

    @Transactional
    public WorkOrderSegmentListResponse deleteAllSegments(Long workOrderId) {
        WorkOrder workOrder = workOrderRepository.findById(workOrderId)
                .orElseThrow(() -> new IllegalArgumentException("找不到工单"));

        if (workOrder.getStatus() == WorkOrderStatus.PENDING) {
            throw new IllegalStateException("工单尚未排入日历");
        }

        pauseRepository.deleteByWorkOrderId(workOrderId);
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
                pauseRepository.deleteBySegmentId(next.getId());
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

        return responseForWorkOrder(workOrder);
    }

    private WorkOrderSegmentListResponse responseForWorkOrder(WorkOrder workOrder) {
        List<WorkOrderSegment> segments = segmentRepository.findByWorkOrderIdOrderByScheduledStartAscScheduledEndAscIdAsc(
                workOrder.getId()
        );

        int totalMinutes = WorkOrderTimeUtils.totalMinutes(segments);
        int pausedMinutes = pausedMinutes(workOrder);
        Map<Long, Boolean> pausedBySegmentId = new LinkedHashMap<>();
        Map<Long, Boolean> scheduleStartLockedBySegmentId = new LinkedHashMap<>();
        Map<Long, LocalDateTime> latestPausedAtBySegmentId = new LinkedHashMap<>();

        for (WorkOrderSegment segment : segments) {
            Optional<WorkOrderSegmentPause> latestPause = latestPause(segment.getId());

            pausedBySegmentId.put(
                    segment.getId(),
                    pauseRepository.existsBySegmentIdAndResumedAtIsNull(segment.getId())
            );
            scheduleStartLockedBySegmentId.put(segment.getId(), isScheduleStartLocked(segment, latestPause));
            latestPause.ifPresent((pause) -> latestPausedAtBySegmentId.put(segment.getId(), pause.getPausedAt()));
        }

        return WorkOrderSegmentListResponse.from(
                workOrder,
                segments,
                totalMinutes,
                pausedBySegmentId,
                pausedMinutes,
                scheduleStartLockedBySegmentId,
                latestPausedAtBySegmentId
        );
    }

    private void updateSegmentEndAndShiftFollowing(WorkOrderSegment segment, LocalDateTime scheduledEnd) {
        LocalDateTime previousEnd = segment.getScheduledEnd();
        Map<Long, WorkOrder> affectedWorkOrders = new LinkedHashMap<>();
        affectedWorkOrders.put(segment.getWorkOrder().getId(), segment.getWorkOrder());

        segment.updateSchedule(segment.getScheduledStart(), scheduledEnd);

        if (scheduledEnd.isAfter(previousEnd)) {
            shiftFollowingSegments(segment, previousEnd, scheduledEnd, affectedWorkOrders);
        }

        affectedWorkOrders.values().forEach(this::syncScheduleSummary);
    }

    private void shiftFollowingSegments(
            WorkOrderSegment sourceSegment,
            LocalDateTime previousEnd,
            LocalDateTime shiftedEnd,
            Map<Long, WorkOrder> affectedWorkOrders
    ) {
        LocalDateTime cursor = shiftedEnd;
        List<WorkOrderSegment> candidates = segmentRepository.findAutoShiftCandidates(
                sourceSegment.getId(),
                List.of(WorkOrderStatus.SCHEDULED, WorkOrderStatus.DONE),
                previousEnd
        );

        for (WorkOrderSegment candidate : candidates) {
            if (!candidate.getScheduledStart().isBefore(cursor)) {
                break;
            }

            Duration duration = Duration.between(candidate.getScheduledStart(), candidate.getScheduledEnd());
            LocalDateTime nextStart = cursor;
            LocalDateTime nextEnd = WorkOrderTimeUtils.roundUpToScheduleBoundary(nextStart.plus(duration));
            candidate.updateSchedule(nextStart, nextEnd);
            cursor = nextEnd;
            affectedWorkOrders.put(candidate.getWorkOrder().getId(), candidate.getWorkOrder());
        }
    }

    private void syncScheduleSummary(WorkOrder workOrder) {
        List<WorkOrderSegment> segments = segmentRepository.findByWorkOrderIdOrderByScheduledStartAscScheduledEndAscIdAsc(
                workOrder.getId()
        );

        if (segments.isEmpty()) {
            workOrder.unschedule();
            return;
        }

        workOrder.syncScheduleSummary(
                segments.getFirst().getScheduledStart(),
                segments.getLast().getScheduledEnd(),
                WorkOrderTimeUtils.totalMinutes(segments)
        );
    }

    private int pausedMinutes(WorkOrder workOrder) {
        LocalDateTime fallbackEnd = workOrder.getCompletedAt() == null ? currentTime() : workOrder.getCompletedAt();
        return WorkOrderTimeUtils.pauseMinutes(pauseRepository.findByWorkOrderId(workOrder.getId()), fallbackEnd);
    }

    private void resumePauseForCompletion(WorkOrderSegmentPause pause, LocalDateTime completedAt) {
        if (completedAt.isBefore(pause.getPausedAt())) {
            throw new IllegalArgumentException("完成时间不可早于暂停时间");
        }

        pause.resume(completedAt);
    }

    private boolean isSameScheduleDay(WorkOrderSegment segment, LocalDateTime completedAt) {
        return segment.getScheduledStart().toLocalDate().isEqual(completedAt.toLocalDate());
    }

    private void validatePauseAction(WorkOrderSegment segment, LocalDateTime actionAt) {
        if (segment.getWorkOrder().getStatus() == WorkOrderStatus.DONE) {
            throw new IllegalStateException("完成工单不可暂停");
        }

        if (!isSameScheduleDay(segment, actionAt)) {
            throw new IllegalStateException("只有当天工单可暂停");
        }

        if (actionAt.isBefore(segment.getScheduledStart())) {
            throw new IllegalStateException("工单尚未开始");
        }
    }

    private void validateScheduleStartLock(
            WorkOrderSegment segment,
            LocalDateTime requestedStart,
            LocalDateTime requestedEnd
    ) {
        Optional<WorkOrderSegmentPause> latestPause = latestPause(segment.getId());

        if (!isScheduleStartLocked(segment, latestPause)) {
            return;
        }

        if (!requestedStart.isEqual(segment.getScheduledStart())) {
            throw new IllegalStateException("已开始计时的工单不可调整开始时间");
        }

        LocalDateTime minEnd = WorkOrderTimeUtils.roundUpToScheduleBoundary(latestPause.orElseThrow().getPausedAt());
        if (requestedEnd.isBefore(minEnd)) {
            throw new IllegalArgumentException("排程结束时间不可早于最后暂停时间");
        }
    }

    private boolean isScheduleStartLocked(
            WorkOrderSegment segment,
            Optional<WorkOrderSegmentPause> latestPause
    ) {
        return latestPause.isPresent()
                && segment.getWorkOrder().getStatus() != WorkOrderStatus.DONE
                && segment.getScheduledStart().toLocalDate().isEqual(LocalDate.now());
    }

    private Optional<WorkOrderSegmentPause> latestPause(Long segmentId) {
        return pauseRepository.findFirstBySegmentIdOrderByPausedAtDescIdDesc(segmentId);
    }

    private LocalDateTime currentTime() {
        return normalizeClockTime(LocalDateTime.now());
    }

    private LocalDateTime normalizeClockTime(LocalDateTime value) {
        return value.withNano(0);
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
