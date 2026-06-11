package com.qn.calendar.workorder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.qn.calendar.workorder.constant.WorkOrderStatus;
import com.qn.calendar.workorder.dto.ScheduleWorkOrderRequest;
import com.qn.calendar.workorder.entity.WorkOrder;
import com.qn.calendar.workorder.repository.WorkOrderRepository;
import com.qn.calendar.workorder.repository.WorkOrderSegmentRepository;
import com.qn.calendar.workorder.service.WorkOrderScheduleService;
import com.qn.calendar.workorder.service.WorkOrderService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class WorkOrderServiceTests {

    @Autowired
    private WorkOrderService service;

    @Autowired
    private WorkOrderScheduleService scheduleService;

    @Autowired
    private WorkOrderRepository repository;

    @Autowired
    private WorkOrderSegmentRepository segmentRepository;

    @BeforeEach
    void setUp() {
        segmentRepository.deleteAll();
        repository.deleteAll();
    }

    @Test
    void listsPendingWorkOrdersByNearestLatestShipTimeFirst() {
        repository.save(order("ORD-LATER-URGENT", true, LocalDateTime.of(2026, 6, 11, 17, 0)));
        repository.save(order("ORD-EARLIER-NORMAL", false, LocalDateTime.of(2026, 6, 9, 12, 0)));
        repository.save(order("ORD-SAME-TIME-URGENT", true, LocalDateTime.of(2026, 6, 10, 18, 0)));
        repository.save(order("ORD-SAME-TIME-NORMAL", false, LocalDateTime.of(2026, 6, 10, 18, 0)));

        assertThat(service.getPendingWorkOrders())
                .extracting("orderNo")
                .containsExactly(
                        "ORD-EARLIER-NORMAL",
                        "ORD-SAME-TIME-URGENT",
                        "ORD-SAME-TIME-NORMAL",
                        "ORD-LATER-URGENT"
                );
    }

    @Test
    void updatesPendingDurationInFifteenMinuteUnits() {
        WorkOrder workOrder = repository.save(order("ORD-DURATION"));

        WorkOrder updated = service.updateDuration(workOrder.getId(), 90);

        assertThat(updated.getActualMinutes()).isEqualTo(90);
        assertThat(repository.findById(workOrder.getId()).orElseThrow().getActualMinutes()).isEqualTo(90);
    }

    @Test
    void rejectsDurationThatIsNotFifteenMinuteMultiple() {
        WorkOrder workOrder = repository.save(order("ORD-INVALID-DURATION"));

        assertThatThrownBy(() -> service.updateDuration(workOrder.getId(), 92))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("工时必须是 15 分钟的倍数");
    }

    @Test
    void rejectsScheduledWorkOrderDurationUpdate() {
        WorkOrder workOrder = repository.save(order("ORD-SCHEDULED"));
        workOrder.schedule(
                LocalDateTime.of(2026, 6, 8, 9, 0),
                LocalDateTime.of(2026, 6, 8, 11, 0),
                120
        );
        repository.saveAndFlush(workOrder);

        assertThatThrownBy(() -> service.updateDuration(workOrder.getId(), 90))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("只有待排工单可先调整工时");
    }

    @Test
    void unschedulesWorkOrderBackToPending() {
        WorkOrder workOrder = repository.save(order("ORD-UNSCHEDULE"));
        workOrder.schedule(
                LocalDateTime.of(2026, 6, 8, 9, 0),
                LocalDateTime.of(2026, 6, 8, 10, 30),
                90
        );
        repository.saveAndFlush(workOrder);

        WorkOrder updated = service.unschedule(workOrder.getId());

        assertThat(updated.getStatus()).isEqualTo(WorkOrderStatus.PENDING);
        assertThat(updated.getScheduledStart()).isNull();
        assertThat(updated.getScheduledEnd()).isNull();
        assertThat(updated.getActualMinutes()).isEqualTo(updated.getEstimatedMinutes());
    }

    @Test
    void rejectsUnschedulingPendingWorkOrder() {
        WorkOrder workOrder = repository.save(order("ORD-PENDING"));

        assertThatThrownBy(() -> service.unschedule(workOrder.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("工单尚未排入日历");
    }

    @Test
    void rejectsOverlappingDifferentScheduledWorkOrder() {
        WorkOrder existing = repository.save(order("ORD-EXISTING"));
        scheduleService.schedule(
                existing.getId(),
                new ScheduleWorkOrderRequest(
                        LocalDateTime.of(2026, 6, 8, 12, 0),
                        LocalDateTime.of(2026, 6, 8, 14, 0)
                )
        );
        WorkOrder incoming = repository.save(order("ORD-INCOMING"));

        assertThatThrownBy(() -> scheduleService.schedule(
                incoming.getId(),
                new ScheduleWorkOrderRequest(
                        LocalDateTime.of(2026, 6, 8, 13, 0),
                        LocalDateTime.of(2026, 6, 8, 15, 0)
                )
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("不同工单排程不可重叠");
    }

    @Test
    void rejectsOverlapWithDifferentDoneWorkOrder() {
        WorkOrder completed = repository.save(order("ORD-DONE"));
        scheduleService.schedule(
                completed.getId(),
                new ScheduleWorkOrderRequest(
                        LocalDateTime.of(2026, 6, 8, 12, 0),
                        LocalDateTime.of(2026, 6, 8, 14, 0)
                )
        );
        completed.markDone(LocalDateTime.of(2026, 6, 8, 14, 0));
        repository.saveAndFlush(completed);
        WorkOrder incoming = repository.save(order("ORD-BLOCKED"));

        assertThatThrownBy(() -> scheduleService.schedule(
                incoming.getId(),
                new ScheduleWorkOrderRequest(
                        LocalDateTime.of(2026, 6, 8, 13, 0),
                        LocalDateTime.of(2026, 6, 8, 15, 0)
                )
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("不同工单排程不可重叠");
    }

    @Test
    void allowsReopeningDoneWorkOrderWhenItOverlapsActiveWorkOrder() {
        WorkOrder completed = repository.save(order("ORD-DONE-OVERLAP"));
        completed.schedule(
                LocalDateTime.of(2026, 6, 8, 12, 0),
                LocalDateTime.of(2026, 6, 8, 14, 0),
                120
        );
        completed.markDone(LocalDateTime.of(2026, 6, 8, 14, 0));
        repository.saveAndFlush(completed);
        WorkOrder active = repository.save(order("ORD-ACTIVE"));
        active.schedule(
                LocalDateTime.of(2026, 6, 8, 13, 0),
                LocalDateTime.of(2026, 6, 8, 15, 0),
                120
        );
        repository.saveAndFlush(active);

        WorkOrder reopened = service.reopen(completed.getId());

        assertThat(reopened.getStatus()).isEqualTo(WorkOrderStatus.SCHEDULED);
        assertThat(reopened.getCompletedAt()).isNull();
    }

    @Test
    void completedStatsIncludeEmptyReservedFieldsAndSumSegments() {
        WorkOrder completed = repository.save(order("ORD-COMPLETED"));
        scheduleService.schedule(
                completed.getId(),
                new ScheduleWorkOrderRequest(
                        LocalDateTime.of(2026, 6, 8, 9, 0),
                        LocalDateTime.of(2026, 6, 8, 10, 30)
                )
        );
        scheduleService.schedule(
                completed.getId(),
                new ScheduleWorkOrderRequest(
                        LocalDateTime.of(2026, 6, 9, 13, 0),
                        LocalDateTime.of(2026, 6, 9, 14, 0)
                )
        );
        completed.markDone(LocalDateTime.of(2026, 6, 9, 14, 0));
        repository.saveAndFlush(completed);
        repository.save(order("ORD-PENDING-STATS"));

        var stats = service.getCompletedWorkOrderStats();

        assertThat(stats).hasSize(1);
        assertThat(stats.getFirst().orderNo()).isEqualTo("ORD-COMPLETED");
        assertThat(stats.getFirst().buyerNickname()).isNull();
        assertThat(stats.getFirst().remark()).isNull();
        assertThat(stats.getFirst().estimatedMinutes()).isEqualTo(180);
        assertThat(stats.getFirst().actualTotalMinutes()).isEqualTo(150);
        assertThat(stats.getFirst().deltaMinutes()).isEqualTo(-30);
        assertThat(stats.getFirst().hourlyRate()).isEqualByComparingTo("120.00");
        assertThat(stats.getFirst().orderTime()).isNull();
    }

    private WorkOrder order(String orderNo) {
        return order(orderNo, false, LocalDateTime.of(2026, 6, 10, 18, 0));
    }

    private WorkOrder order(String orderNo, boolean urgent, LocalDateTime latestShipTime) {
        return new WorkOrder(
                orderNo,
                BigDecimal.valueOf(300),
                180,
                urgent,
                latestShipTime
        );
    }
}
