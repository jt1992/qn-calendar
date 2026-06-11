package com.qn.calendar.workorder.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.qn.calendar.workorder.constant.WorkOrderStatus;
import com.qn.calendar.workorder.dto.CompletedWorkOrderStatsResponse;
import com.qn.calendar.workorder.dto.WorkOrderSegmentResponse;
import com.qn.calendar.workorder.dto.WorkOrderResponse;
import com.qn.calendar.workorder.entity.WorkOrder;
import com.qn.calendar.workorder.repository.WorkOrderRepository;
import com.qn.calendar.workorder.repository.WorkOrderSegmentRepository;
import com.qn.calendar.workorder.util.WorkOrderTimeUtils;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkOrderService {

    private final WorkOrderRepository repository;
    private final WorkOrderSegmentRepository segmentRepository;

    public WorkOrderService(
            WorkOrderRepository repository,
            WorkOrderSegmentRepository segmentRepository
    ) {
        this.repository = repository;
        this.segmentRepository = segmentRepository;
    }

    @Transactional(readOnly = true)
    public List<WorkOrderResponse> getPendingWorkOrders() {
        return repository.findByStatusOrderByLatestShipTimeAscUrgentDescCreatedAtAsc(WorkOrderStatus.PENDING)
                .stream()
                .map(WorkOrderResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<WorkOrderSegmentResponse> getCalendarWorkOrders(LocalDate dateFrom, LocalDate dateTo) {
        if (dateTo.isBefore(dateFrom)) {
            throw new IllegalArgumentException("日历日期区间不可无效");
        }

        LocalDateTime from = dateFrom.atStartOfDay();
        LocalDateTime toExclusive = dateTo.plusDays(1).atStartOfDay();

        return segmentRepository.findCalendarSegments(
                        List.of(WorkOrderStatus.SCHEDULED, WorkOrderStatus.DONE),
                        from,
                        toExclusive
                )
                .stream()
                .map((segment) -> WorkOrderSegmentResponse.from(
                        segment,
                        WorkOrderTimeUtils.totalMinutes(segmentRepository.findByWorkOrderIdOrderByScheduledStartAscScheduledEndAscIdAsc(
                                segment.getWorkOrder().getId()
                        ))
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CompletedWorkOrderStatsResponse> getCompletedWorkOrderStats() {
        return repository.findCompletedStats(WorkOrderStatus.DONE)
                .stream()
                .map((workOrder) -> CompletedWorkOrderStatsResponse.from(
                        workOrder,
                        WorkOrderTimeUtils.totalMinutes(
                                segmentRepository.findByWorkOrderIdOrderByScheduledStartAscScheduledEndAscIdAsc(
                                        workOrder.getId()
                                )
                        )
                ))
                .toList();
    }

    @Transactional
    public WorkOrder markAsDone(Long id) {
        WorkOrder workOrder = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("找不到工单"));

        workOrder.markDone(LocalDateTime.now());
        return workOrder;
    }

    @Transactional
    public WorkOrder updateDuration(Long id, int actualMinutes) {
        WorkOrder workOrder = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("找不到工单"));

        if (workOrder.getStatus() != WorkOrderStatus.PENDING) {
            throw new IllegalStateException("只有待排工单可先调整工时");
        }

        validateDuration(actualMinutes);
        workOrder.updateActualMinutes(actualMinutes);
        return workOrder;
    }

    @Transactional
    public WorkOrder unschedule(Long id) {
        WorkOrder workOrder = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("找不到工单"));

        if (workOrder.getStatus() == WorkOrderStatus.PENDING) {
            throw new IllegalStateException("工单尚未排入日历");
        }

        segmentRepository.deleteByWorkOrderId(id);
        workOrder.unschedule();
        return workOrder;
    }

    @Transactional
    public WorkOrder reopen(Long id) {
        WorkOrder workOrder = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("找不到工单"));

        workOrder.reopen();
        return workOrder;
    }

    private void validateDuration(int actualMinutes) {
        if (actualMinutes <= 0) {
            throw new IllegalArgumentException("工时必须大于 0");
        }

        if (actualMinutes % WorkOrderTimeUtils.SCHEDULE_GRANULARITY_MINUTES != 0) {
            throw new IllegalArgumentException("工时必须是 15 分钟的倍数");
        }
    }

}
