package com.qn.calendar.workorder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.qn.calendar.workorder.dto.WorkOrderResponse;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkOrderService {

    private final WorkOrderRepository repository;

    public WorkOrderService(WorkOrderRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<WorkOrderResponse> getPendingWorkOrders() {
        return repository.findByStatusOrderByLatestShipTimeAscUrgentDescCreatedAtAsc(WorkOrderStatus.PENDING)
                .stream()
                .map(WorkOrderResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<WorkOrderResponse> getCalendarWorkOrders(LocalDate dateFrom, LocalDate dateTo) {
        if (dateTo.isBefore(dateFrom)) {
            throw new IllegalArgumentException("日曆日期區間不可無效");
        }

        LocalDateTime from = dateFrom.atStartOfDay();
        LocalDateTime toExclusive = dateTo.plusDays(1).atStartOfDay();

        return repository.findCalendarOrders(
                        List.of(WorkOrderStatus.SCHEDULED, WorkOrderStatus.DONE),
                        from,
                        toExclusive
                )
                .stream()
                .map(WorkOrderResponse::from)
                .toList();
    }

    @Transactional
    public WorkOrder markAsDone(Long id) {
        WorkOrder workOrder = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("找不到工單"));

        workOrder.markDone(LocalDateTime.now());
        return workOrder;
    }

    @Transactional
    public WorkOrder updateDuration(Long id, int actualMinutes) {
        WorkOrder workOrder = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("找不到工單"));

        if (workOrder.getStatus() != WorkOrderStatus.PENDING) {
            throw new IllegalStateException("只有待排工單可先調整工時");
        }

        validateDuration(actualMinutes);
        workOrder.updateActualMinutes(actualMinutes);
        return workOrder;
    }

    @Transactional
    public WorkOrder unschedule(Long id) {
        WorkOrder workOrder = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("找不到工單"));

        if (workOrder.getStatus() == WorkOrderStatus.PENDING) {
            throw new IllegalStateException("工單尚未排入日曆");
        }

        workOrder.unschedule();
        return workOrder;
    }

    @Transactional
    public WorkOrder reopen(Long id) {
        WorkOrder workOrder = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("找不到工單"));

        if (workOrder.getScheduledStart() != null
                && workOrder.getScheduledEnd() != null
                && repository.existsOverlappingWorkOrder(
                        workOrder.getId(),
                        List.of(WorkOrderStatus.SCHEDULED),
                        workOrder.getScheduledStart(),
                        workOrder.getScheduledEnd()
                )) {
            throw new IllegalArgumentException("工單排程不可與未完成工單重疊");
        }

        workOrder.reopen();
        return workOrder;
    }

    private void validateDuration(int actualMinutes) {
        if (actualMinutes <= 0) {
            throw new IllegalArgumentException("工時必須大於 0");
        }

        if (actualMinutes % 5 != 0) {
            throw new IllegalArgumentException("工時必須是 5 分鐘的倍數");
        }
    }
}
