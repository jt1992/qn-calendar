package com.qn.calendar.workorder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.qn.calendar.settings.repository.RemarkTagDefinitionRepository;
import com.qn.calendar.workorder.constant.WorkOrderSource;
import com.qn.calendar.workorder.constant.WorkOrderStatus;
import com.qn.calendar.workorder.dto.ScheduleWorkOrderRequest;
import com.qn.calendar.workorder.entity.WorkOrder;
import com.qn.calendar.workorder.entity.WorkOrderSegmentPause;
import com.qn.calendar.workorder.repository.WorkOrderRepository;
import com.qn.calendar.workorder.repository.WorkOrderSegmentPauseRepository;
import com.qn.calendar.workorder.repository.WorkOrderSegmentRepository;
import com.qn.calendar.workorder.service.WorkOrderScheduleService;
import com.qn.calendar.workorder.service.WorkOrderSegmentService;
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
    private WorkOrderSegmentService segmentService;

    @Autowired
    private WorkOrderRepository repository;

    @Autowired
    private WorkOrderSegmentRepository segmentRepository;

    @Autowired
    private WorkOrderSegmentPauseRepository pauseRepository;

    @Autowired
    private RemarkTagDefinitionRepository remarkTagRepository;

    @Autowired
    private Clock clock;

    @BeforeEach
    void setUp() {
        pauseRepository.deleteAll();
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
    void calendarSegmentsIncludeXiaohongshuSource() {
        WorkOrder workOrder = repository.save(new WorkOrder(
                "P802335189951019482",
                null,
                null,
                BigDecimal.valueOf(300),
                180,
                false,
                LocalDateTime.of(2026, 8, 30, 18, 0),
                null,
                WorkOrderSource.XIAOHONGSHU
        ));
        scheduleService.schedule(
                workOrder.getId(),
                new ScheduleWorkOrderRequest(
                        LocalDateTime.of(2026, 8, 20, 9, 0),
                        LocalDateTime.of(2026, 8, 20, 12, 0)
                )
        );

        assertThat(service.getCalendarWorkOrders(
                LocalDate.of(2026, 8, 20),
                LocalDate.of(2026, 8, 20)
        )).singleElement().satisfies((segment) -> {
            assertThat(segment.orderNo()).isEqualTo("P802335189951019482");
            assertThat(segment.source()).isEqualTo(WorkOrderSource.XIAOHONGSHU);
        });
    }

    @Test
    void pendingAndCalendarResponsesIncludeRemarkTagMetadata() {
        var urgentTag = remarkTagRepository.findBySystemKey("URGENT").orElseThrow();
        WorkOrder workOrder = order(
                "ORD-REMARK-TAG-RESPONSE",
                false,
                LocalDateTime.of(2026, 8, 30, 18, 0)
        );
        workOrder.replaceRemarkTags(List.of(urgentTag));
        workOrder = repository.save(workOrder);

        assertThat(service.getPendingWorkOrders()).singleElement().satisfies((pending) -> {
            assertThat(pending.urgent()).isTrue();
            assertThat(pending.remarkTags()).singleElement().satisfies((tag) -> {
                assertThat(tag.systemKey()).isEqualTo("URGENT");
                assertThat(tag.name()).isEqualTo("加急");
                assertThat(tag.color()).isEqualTo("#FF6F61");
            });
        });

        scheduleService.schedule(
                workOrder.getId(),
                new ScheduleWorkOrderRequest(
                        LocalDateTime.of(2026, 8, 20, 13, 0),
                        LocalDateTime.of(2026, 8, 20, 14, 0)
                )
        );

        assertThat(service.getCalendarWorkOrders(
                LocalDate.of(2026, 8, 20),
                LocalDate.of(2026, 8, 20)
        )).singleElement().satisfies((segment) -> {
            assertThat(segment.urgent()).isTrue();
            assertThat(segment.remarkTags()).singleElement().satisfies((tag) -> {
                assertThat(tag.systemKey()).isEqualTo("URGENT");
                assertThat(tag.name()).isEqualTo("加急");
                assertThat(tag.color()).isEqualTo("#FF6F61");
            });
        });
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
        LocalDate today = LocalDate.now(clock);
        WorkOrder workOrder = repository.save(order(
                "ORD-UNSCHEDULE",
                false,
                today.plusDays(1).atTime(18, 0)
        ));
        var scheduled = scheduleService.schedule(
                workOrder.getId(),
                new ScheduleWorkOrderRequest(today.atTime(7, 0), today.atTime(10, 0))
        );
        Long segmentId = scheduled.segments().getFirst().segmentId();
        segmentService.pauseSegment(segmentId, today.atTime(8, 0));
        segmentService.resumeSegment(segmentId, today.atTime(8, 15));

        WorkOrder updated = service.unschedule(workOrder.getId());

        assertThat(updated.getStatus()).isEqualTo(WorkOrderStatus.PENDING);
        assertThat(updated.getScheduledStart()).isNull();
        assertThat(updated.getScheduledEnd()).isNull();
        assertThat(updated.getActualMinutes()).isEqualTo(updated.getEstimatedMinutes());
        assertThat(segmentRepository.findByWorkOrderIdOrderByScheduledStartAscScheduledEndAscIdAsc(workOrder.getId()))
                .isEmpty();
        assertThat(pauseRepository.findByWorkOrderId(workOrder.getId())).isEmpty();
    }

    @Test
    void rejectsUnschedulingPendingWorkOrder() {
        WorkOrder workOrder = repository.save(order("ORD-PENDING"));

        assertThatThrownBy(() -> service.unschedule(workOrder.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("工单尚未排入日历");
    }

    @Test
    void deletesPendingWorkOrder() {
        WorkOrder workOrder = repository.save(order("ORD-DELETE-PENDING"));

        service.deletePendingWorkOrder(workOrder.getId());

        assertThat(repository.findById(workOrder.getId())).isEmpty();
    }

    @Test
    void rejectsDeletingScheduledWorkOrder() {
        WorkOrder workOrder = repository.save(order("ORD-DELETE-SCHEDULED"));
        scheduleService.schedule(
                workOrder.getId(),
                new ScheduleWorkOrderRequest(
                        LocalDateTime.of(2026, 6, 8, 9, 0),
                        LocalDateTime.of(2026, 6, 8, 10, 0)
                )
        );

        assertThatThrownBy(() -> service.deletePendingWorkOrder(workOrder.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("只有待排工单可删除");
        assertThat(repository.findById(workOrder.getId())).isPresent();
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
    void rejectsReopeningDoneWorkOrder() {
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

        assertThatThrownBy(() -> service.reopen(completed.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("完成工单不可复原");
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
        assertThat(stats.getFirst().remark()).isNull();
        assertThat(stats.getFirst().estimatedMinutes()).isEqualTo(180);
        assertThat(stats.getFirst().actualTotalMinutes()).isEqualTo(150);
        assertThat(stats.getFirst().pausedMinutes()).isZero();
        assertThat(stats.getFirst().deltaMinutes()).isEqualTo(-30);
        assertThat(stats.getFirst().hourlyRate()).isEqualByComparingTo("120.00");
        assertThat(stats.getFirst().orderTime()).isNull();
    }

    @Test
    void completedStatsSubtractPausedMinutesFromScheduledSpan() {
        WorkOrder completed = repository.save(order("ORD-PAUSED-STATS"));
        var created = scheduleService.schedule(
                completed.getId(),
                new ScheduleWorkOrderRequest(
                        LocalDateTime.of(2026, 6, 8, 3, 0),
                        LocalDateTime.of(2026, 6, 8, 5, 0)
                )
        );
        Long segmentId = created.segments().getFirst().segmentId();

        segmentService.pauseSegment(segmentId, LocalDateTime.of(2026, 6, 8, 4, 0));
        segmentService.resumeSegment(segmentId, LocalDateTime.of(2026, 6, 8, 4, 30));
        segmentService.completeSegment(segmentId, LocalDateTime.of(2026, 6, 8, 5, 30));

        var stats = service.getCompletedWorkOrderStats();

        assertThat(stats).hasSize(1);
        assertThat(stats.getFirst().actualTotalMinutes()).isEqualTo(120);
        assertThat(stats.getFirst().pausedMinutes()).isEqualTo(30);
        assertThat(stats.getFirst().deltaMinutes()).isEqualTo(-60);
        assertThat(stats.getFirst().hourlyRate()).isEqualByComparingTo("150.00");
    }

    @Test
    void completedStatsOnlyCountPauseOverlappingSplitSegments() {
        WorkOrder completed = repository.save(order("ORD-SPLIT-PAUSE-STATS"));
        scheduleService.schedule(
                completed.getId(),
                new ScheduleWorkOrderRequest(
                        LocalDateTime.of(2026, 6, 8, 9, 0),
                        LocalDateTime.of(2026, 6, 8, 10, 0)
                )
        );
        scheduleService.schedule(
                completed.getId(),
                new ScheduleWorkOrderRequest(
                        LocalDateTime.of(2026, 6, 8, 13, 0),
                        LocalDateTime.of(2026, 6, 8, 15, 0)
                )
        );
        var segments = segmentRepository.findByWorkOrderIdOrderByScheduledStartAscScheduledEndAscIdAsc(
                completed.getId()
        );
        WorkOrderSegmentPause pause = new WorkOrderSegmentPause(
                segments.getFirst(),
                LocalDateTime.of(2026, 6, 8, 9, 30)
        );
        pause.resume(LocalDateTime.of(2026, 6, 8, 14, 0));
        pauseRepository.save(pause);
        completed.markDone(LocalDateTime.of(2026, 6, 8, 14, 0));
        repository.saveAndFlush(completed);

        var stats = service.getCompletedWorkOrderStats();

        assertThat(stats).hasSize(1);
        assertThat(stats.getFirst().pausedMinutes()).isEqualTo(90);
        assertThat(stats.getFirst().actualTotalMinutes()).isEqualTo(90);
    }

    @Test
    void completedStatsLimitOpenPauseToScheduledTime() {
        WorkOrder completed = repository.save(order("ORD-OPEN-PAUSE-STATS"));
        var scheduled = scheduleService.schedule(
                completed.getId(),
                new ScheduleWorkOrderRequest(
                        LocalDateTime.of(2026, 6, 8, 9, 0),
                        LocalDateTime.of(2026, 6, 8, 14, 0)
                )
        );
        Long segmentId = scheduled.segments().getFirst().segmentId();
        segmentService.pauseSegment(segmentId, LocalDateTime.of(2026, 6, 8, 10, 0));
        completed.markDone(LocalDateTime.of(2026, 6, 9, 9, 0));
        repository.saveAndFlush(completed);

        var stats = service.getCompletedWorkOrderStats();

        assertThat(stats).hasSize(1);
        assertThat(stats.getFirst().pausedMinutes()).isEqualTo(240);
        assertThat(stats.getFirst().actualTotalMinutes()).isEqualTo(60);
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
