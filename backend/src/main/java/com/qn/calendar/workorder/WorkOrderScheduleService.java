package com.qn.calendar.workorder;

import java.time.Duration;
import java.time.LocalDateTime;

import com.qn.calendar.workorder.dto.ScheduleWorkOrderRequest;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkOrderScheduleService {

    private final WorkOrderRepository repository;

    public WorkOrderScheduleService(WorkOrderRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public WorkOrder schedule(Long id, ScheduleWorkOrderRequest request) {
        WorkOrder workOrder = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("找不到工單"));

        validateSchedule(workOrder, request.scheduledStart(), request.scheduledEnd());

        int actualMinutes = Math.toIntExact(Duration.between(
                request.scheduledStart(),
                request.scheduledEnd()
        ).toMinutes());

        workOrder.schedule(request.scheduledStart(), request.scheduledEnd(), actualMinutes);
        return workOrder;
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

        if (repository.existsOverlappingWorkOrder(
                workOrder.getId(),
                java.util.List.of(WorkOrderStatus.SCHEDULED),
                scheduledStart,
                scheduledEnd
        )) {
            throw new IllegalArgumentException("工單排程不可與未完成工單重疊");
        }
    }

    private boolean isFiveMinuteBoundary(LocalDateTime time) {
        return time.getSecond() == 0
                && time.getNano() == 0
                && time.getMinute() % 5 == 0;
    }
}
