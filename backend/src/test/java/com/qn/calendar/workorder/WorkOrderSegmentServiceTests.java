package com.qn.calendar.workorder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import com.qn.calendar.workorder.constant.WorkOrderStatus;
import com.qn.calendar.workorder.dto.ScheduleWorkOrderRequest;
import com.qn.calendar.workorder.dto.SplitWorkOrderSegmentRequest;
import com.qn.calendar.workorder.dto.WorkOrderSegmentListResponse;
import com.qn.calendar.workorder.entity.WorkOrder;
import com.qn.calendar.workorder.entity.WorkOrderSegment;
import com.qn.calendar.workorder.repository.WorkOrderRepository;
import com.qn.calendar.workorder.repository.WorkOrderSegmentPauseRepository;
import com.qn.calendar.workorder.repository.WorkOrderSegmentRepository;
import com.qn.calendar.workorder.service.WorkOrderSegmentService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

@SpringBootTest
@Import(WorkOrderSegmentServiceTests.FixedClockConfiguration.class)
class WorkOrderSegmentServiceTests {

    @Autowired
    private WorkOrderSegmentService service;

    @Autowired
    private WorkOrderRepository workOrderRepository;

    @Autowired
    private WorkOrderSegmentRepository segmentRepository;

    @Autowired
    private WorkOrderSegmentPauseRepository pauseRepository;

    @Autowired
    private Clock clock;

    @BeforeEach
    void setUp() {
        pauseRepository.deleteAll();
        segmentRepository.deleteAll();
        workOrderRepository.deleteAll();
    }

    @Test
    void createsMultipleSegmentsForSameWorkOrder() {
        WorkOrder workOrder = workOrderRepository.save(order("ORD-MULTI"));

        service.createSegment(workOrder.getId(), request(
                LocalDateTime.of(2026, 6, 8, 9, 0),
                LocalDateTime.of(2026, 6, 8, 10, 0)
        ));
        WorkOrderSegmentListResponse response = service.createSegment(workOrder.getId(), request(
                LocalDateTime.of(2026, 6, 9, 13, 0),
                LocalDateTime.of(2026, 6, 9, 14, 30)
        ));

        assertThat(response.segments()).hasSize(2);
        assertThat(response.totalMinutes()).isEqualTo(150);
        assertThat(response.workOrder().status()).isEqualTo(WorkOrderStatus.SCHEDULED);
        assertThat(response.workOrder().actualMinutes()).isEqualTo(150);
    }

    @Test
    void mergesAdjacentSegmentsForSameWorkOrder() {
        WorkOrder workOrder = workOrderRepository.save(order("ORD-MERGE"));

        service.createSegment(workOrder.getId(), request(
                LocalDateTime.of(2026, 6, 8, 9, 0),
                LocalDateTime.of(2026, 6, 8, 10, 0)
        ));
        WorkOrderSegmentListResponse response = service.createSegment(workOrder.getId(), request(
                LocalDateTime.of(2026, 6, 8, 10, 0),
                LocalDateTime.of(2026, 6, 8, 11, 30)
        ));

        assertThat(response.segments()).hasSize(1);
        assertThat(response.segments().getFirst().scheduledStart()).isEqualTo(LocalDateTime.of(2026, 6, 8, 9, 0));
        assertThat(response.segments().getFirst().scheduledEnd()).isEqualTo(LocalDateTime.of(2026, 6, 8, 11, 30));
        assertThat(response.totalMinutes()).isEqualTo(150);
    }

    @Test
    void mergesOverlappingSegmentsAfterUpdate() {
        WorkOrder workOrder = workOrderRepository.save(order("ORD-UPDATE-MERGE"));
        service.createSegment(workOrder.getId(), request(
                LocalDateTime.of(2026, 6, 8, 9, 0),
                LocalDateTime.of(2026, 6, 8, 10, 0)
        ));
        WorkOrderSegmentListResponse second = service.createSegment(workOrder.getId(), request(
                LocalDateTime.of(2026, 6, 8, 11, 0),
                LocalDateTime.of(2026, 6, 8, 12, 0)
        ));

        WorkOrderSegmentListResponse response = service.updateSegment(
                second.segments().getLast().segmentId(),
                request(
                        LocalDateTime.of(2026, 6, 8, 9, 30),
                        LocalDateTime.of(2026, 6, 8, 12, 0)
                )
        );

        assertThat(response.segments()).hasSize(1);
        assertThat(response.segments().getFirst().scheduledStart()).isEqualTo(LocalDateTime.of(2026, 6, 8, 9, 0));
        assertThat(response.segments().getFirst().scheduledEnd()).isEqualTo(LocalDateTime.of(2026, 6, 8, 12, 0));
        assertThat(response.totalMinutes()).isEqualTo(180);
    }

    @Test
    void rejectsOverlappingSegmentForDifferentWorkOrder() {
        WorkOrder existing = workOrderRepository.save(order("ORD-EXISTING"));
        service.createSegment(existing.getId(), request(
                LocalDateTime.of(2026, 6, 8, 9, 0),
                LocalDateTime.of(2026, 6, 8, 11, 0)
        ));
        WorkOrder incoming = workOrderRepository.save(order("ORD-INCOMING"));

        assertThatThrownBy(() -> service.createSegment(incoming.getId(), request(
                LocalDateTime.of(2026, 6, 8, 10, 0),
                LocalDateTime.of(2026, 6, 8, 12, 0)
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("不同工单排程不可重叠");
    }

    @Test
    void rejectsSegmentBeyondLatestShipTime() {
        WorkOrder workOrder = workOrderRepository.save(order(
                "ORD-DEADLINE",
                LocalDateTime.of(2026, 6, 8, 10, 0)
        ));

        assertThatThrownBy(() -> service.createSegment(workOrder.getId(), request(
                LocalDateTime.of(2026, 6, 8, 9, 0),
                LocalDateTime.of(2026, 6, 8, 10, 15)
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("排程结束时间不可超过最晚发货时间");
    }

    @Test
    void completingSegmentExtendsEndToCurrentTime() {
        WorkOrder workOrder = workOrderRepository.save(order(
                "ORD-COMPLETE-EXTEND",
                LocalDateTime.of(2026, 6, 9, 2, 0)
        ));
        WorkOrderSegmentListResponse created = service.createSegment(workOrder.getId(), request(
                LocalDateTime.of(2026, 6, 9, 0, 0),
                LocalDateTime.of(2026, 6, 9, 1, 0)
        ));

        WorkOrderSegmentListResponse response = service.completeSegment(
                created.segments().getFirst().segmentId(),
                LocalDateTime.of(2026, 6, 9, 1, 26, 1)
        );

        assertThat(response.workOrder().status()).isEqualTo(WorkOrderStatus.DONE);
        assertThat(response.segments()).hasSize(1);
        assertThat(response.segments().getFirst().scheduledStart()).isEqualTo(LocalDateTime.of(2026, 6, 9, 0, 0));
        assertThat(response.segments().getFirst().scheduledEnd()).isEqualTo(LocalDateTime.of(2026, 6, 9, 1, 26, 1));
        assertThat(response.totalMinutes()).isEqualTo(86);
    }

    @Test
    void completingSegmentOnDifferentDayDoesNotExtendEndToCurrentTime() {
        WorkOrder workOrder = workOrderRepository.save(order(
                "ORD-COMPLETE-OTHER-DAY",
                LocalDateTime.of(2026, 6, 9, 2, 0)
        ));
        WorkOrderSegmentListResponse created = service.createSegment(workOrder.getId(), request(
                LocalDateTime.of(2026, 6, 8, 0, 0),
                LocalDateTime.of(2026, 6, 8, 1, 0)
        ));

        WorkOrderSegmentListResponse response = service.completeSegment(
                created.segments().getFirst().segmentId(),
                LocalDateTime.of(2026, 6, 9, 1, 26, 1)
        );

        assertThat(response.workOrder().status()).isEqualTo(WorkOrderStatus.DONE);
        assertThat(response.workOrder().completedAt()).isEqualTo(LocalDateTime.of(2026, 6, 9, 1, 26, 1));
        assertThat(response.segments()).hasSize(1);
        assertThat(response.segments().getFirst().scheduledStart()).isEqualTo(LocalDateTime.of(2026, 6, 8, 0, 0));
        assertThat(response.segments().getFirst().scheduledEnd()).isEqualTo(LocalDateTime.of(2026, 6, 8, 1, 0));
        assertThat(response.totalMinutes()).isEqualTo(60);
    }

    @Test
    void resumeAfterScheduledEndExtendsCurrentSegmentAndPushesFollowingOrdersEvenWhenTheyBecomeOverdue() {
        WorkOrder current = workOrderRepository.save(order(
                "ORD-PAUSE-CURRENT",
                LocalDateTime.of(2026, 6, 8, 5, 0)
        ));
        WorkOrder following = workOrderRepository.save(order(
                "ORD-PAUSE-FOLLOWING",
                LocalDateTime.of(2026, 6, 8, 6, 0)
        ));
        WorkOrderSegmentListResponse currentCreated = service.createSegment(current.getId(), request(
                LocalDateTime.of(2026, 6, 8, 3, 0),
                LocalDateTime.of(2026, 6, 8, 5, 0)
        ));
        service.createSegment(following.getId(), request(
                LocalDateTime.of(2026, 6, 8, 5, 0),
                LocalDateTime.of(2026, 6, 8, 6, 0)
        ));

        Long currentSegmentId = currentCreated.segments().getFirst().segmentId();
        service.pauseSegment(currentSegmentId, LocalDateTime.of(2026, 6, 8, 4, 0));
        WorkOrderSegmentListResponse response = service.resumeSegment(
                currentSegmentId,
                LocalDateTime.of(2026, 6, 8, 5, 30)
        );

        assertThat(response.segments().getFirst().scheduledEnd()).isEqualTo(LocalDateTime.of(2026, 6, 8, 5, 30));
        assertThat(response.segments().getFirst().paused()).isFalse();
        assertThat(response.segments().getFirst().pausedMinutes()).isEqualTo(90);
        assertThat(response.segments().getFirst().overdue()).isTrue();

        WorkOrderSegment followingSegment = segmentRepository
                .findByWorkOrderIdOrderByScheduledStartAscScheduledEndAscIdAsc(following.getId())
                .getFirst();
        assertThat(followingSegment.getScheduledStart()).isEqualTo(LocalDateTime.of(2026, 6, 8, 5, 30));
        assertThat(followingSegment.getScheduledEnd()).isEqualTo(LocalDateTime.of(2026, 6, 8, 6, 30));
        assertThat(workOrderRepository.findById(following.getId()).orElseThrow().getScheduledEnd())
                .isEqualTo(LocalDateTime.of(2026, 6, 8, 6, 30));
    }

    @Test
    void supportsMultiplePauseResumeIntervals() {
        WorkOrder workOrder = workOrderRepository.save(order("ORD-MULTI-PAUSE"));
        WorkOrderSegmentListResponse created = service.createSegment(workOrder.getId(), request(
                LocalDateTime.of(2026, 6, 8, 3, 0),
                LocalDateTime.of(2026, 6, 8, 5, 0)
        ));
        Long segmentId = created.segments().getFirst().segmentId();

        service.pauseSegment(segmentId, LocalDateTime.of(2026, 6, 8, 4, 0));
        service.resumeSegment(segmentId, LocalDateTime.of(2026, 6, 8, 4, 10));
        service.pauseSegment(segmentId, LocalDateTime.of(2026, 6, 8, 4, 30));
        WorkOrderSegmentListResponse response = service.resumeSegment(
                segmentId,
                LocalDateTime.of(2026, 6, 8, 4, 45)
        );

        assertThat(response.segments().getFirst().scheduledEnd()).isEqualTo(LocalDateTime.of(2026, 6, 8, 5, 0));
        assertThat(response.segments().getFirst().pausedMinutes()).isEqualTo(25);
        assertThat(pauseRepository.findByWorkOrderId(workOrder.getId())).hasSize(2);
    }

    @Test
    void resumeBeforeScheduledEndDoesNotExtendCalendarEnd() {
        WorkOrder workOrder = workOrderRepository.save(order("ORD-PAUSE-SECONDS"));
        WorkOrderSegmentListResponse created = service.createSegment(workOrder.getId(), request(
                LocalDateTime.of(2026, 6, 8, 3, 0),
                LocalDateTime.of(2026, 6, 8, 5, 0)
        ));
        Long segmentId = created.segments().getFirst().segmentId();

        service.pauseSegment(segmentId, LocalDateTime.of(2026, 6, 8, 4, 0, 7));
        WorkOrderSegmentListResponse response = service.resumeSegment(
                segmentId,
                LocalDateTime.of(2026, 6, 8, 4, 10, 19)
        );

        assertThat(response.segments().getFirst().scheduledEnd()).isEqualTo(LocalDateTime.of(2026, 6, 8, 5, 0));
        assertThat(response.segments().getFirst().pausedMinutes()).isEqualTo(10);
    }

    @Test
    void resumeAfterScheduledEndRoundsExtendedCalendarEndToFifteenMinuteBoundary() {
        WorkOrder workOrder = workOrderRepository.save(order("ORD-PAUSE-AFTER-END"));
        WorkOrderSegmentListResponse created = service.createSegment(workOrder.getId(), request(
                LocalDateTime.of(2026, 6, 8, 3, 0),
                LocalDateTime.of(2026, 6, 8, 5, 0)
        ));
        Long segmentId = created.segments().getFirst().segmentId();

        service.pauseSegment(segmentId, LocalDateTime.of(2026, 6, 8, 4, 0, 7));
        WorkOrderSegmentListResponse response = service.resumeSegment(
                segmentId,
                LocalDateTime.of(2026, 6, 8, 5, 10, 19)
        );

        assertThat(response.segments().getFirst().scheduledEnd()).isEqualTo(LocalDateTime.of(2026, 6, 8, 5, 15));
        assertThat(response.segments().getFirst().pausedMinutes()).isEqualTo(70);
    }

    @Test
    void todaySegmentWithPauseHistoryCanMoveAndRetainsPausesInsideNewRange() {
        LocalDate today = LocalDate.now(clock);
        WorkOrder workOrder = workOrderRepository.save(order(
                "ORD-TODAY-MOVE",
                today.plusDays(1).atTime(18, 0)
        ));
        WorkOrderSegmentListResponse created = service.createSegment(workOrder.getId(), request(
                today.atTime(1, 0),
                today.atTime(5, 0)
        ));
        Long segmentId = created.segments().getFirst().segmentId();

        service.pauseSegment(segmentId, today.atTime(3, 0));
        WorkOrderSegmentListResponse resumed = service.resumeSegment(segmentId, today.atTime(3, 15));

        assertThat(resumed.segments().getFirst().scheduleStartLocked()).isTrue();
        assertThat(resumed.segments().getFirst().latestPausedAt()).isEqualTo(today.atTime(3, 0));
        WorkOrderSegmentListResponse moved = service.updateSegment(segmentId, request(
                today.atTime(2, 0),
                today.atTime(6, 0)
        ));

        assertThat(moved.segments().getFirst().scheduledStart()).isEqualTo(today.atTime(2, 0));
        assertThat(moved.segments().getFirst().scheduledEnd()).isEqualTo(today.atTime(6, 0));
        assertThat(pauseRepository.findByWorkOrderId(workOrder.getId()))
                .extracting("pausedAt", "resumedAt")
                .containsExactly(tuple(today.atTime(3, 0), today.atTime(3, 15)));
    }

    @Test
    void movingTodaySegmentDeletesOnlyPausesOutsideNewRange() {
        LocalDate today = LocalDate.now(clock);
        WorkOrder workOrder = workOrderRepository.save(order(
                "ORD-TODAY-PRUNE-PAUSES",
                today.plusDays(1).atTime(18, 0)
        ));
        WorkOrderSegmentListResponse created = service.createSegment(workOrder.getId(), request(
                today.atTime(7, 0),
                today.atTime(11, 0)
        ));
        Long segmentId = created.segments().getFirst().segmentId();

        service.pauseSegment(segmentId, today.atTime(7, 15));
        service.resumeSegment(segmentId, today.atTime(7, 30));
        service.pauseSegment(segmentId, today.atTime(8, 0));
        service.resumeSegment(segmentId, today.atTime(8, 15));
        service.pauseSegment(segmentId, today.atTime(10, 30));
        service.resumeSegment(segmentId, today.atTime(10, 45));

        service.updateSegment(segmentId, request(
                today.atTime(8, 0),
                today.atTime(12, 0)
        ));

        assertThat(pauseRepository.findByWorkOrderId(workOrder.getId()))
                .extracting("pausedAt", "resumedAt")
                .containsExactly(
                        tuple(today.atTime(8, 0), today.atTime(8, 15)),
                        tuple(today.atTime(10, 30), today.atTime(10, 45))
                );
    }

    @Test
    void movingTodaySegmentDeletesClosedPauseWhenResumeFallsOutsideNewRange() {
        LocalDate today = LocalDate.now(clock);
        WorkOrder workOrder = workOrderRepository.save(order(
                "ORD-TODAY-PRUNE-RESUME",
                today.plusDays(1).atTime(18, 0)
        ));
        WorkOrderSegmentListResponse created = service.createSegment(workOrder.getId(), request(
                today.atTime(7, 0),
                today.atTime(11, 0)
        ));
        Long segmentId = created.segments().getFirst().segmentId();

        service.pauseSegment(segmentId, today.atTime(10, 30));
        service.resumeSegment(segmentId, today.atTime(10, 45));

        service.updateSegment(segmentId, request(
                today.atTime(6, 30),
                today.atTime(10, 30)
        ));

        assertThat(pauseRepository.findByWorkOrderId(workOrder.getId())).isEmpty();
    }

    @Test
    void movingTodaySegmentKeepsOpenPauseWhenPausedAtRemainsInsideNewRange() {
        LocalDate today = LocalDate.now(clock);
        WorkOrder workOrder = workOrderRepository.save(order(
                "ORD-TODAY-KEEP-OPEN-PAUSE",
                today.plusDays(1).atTime(18, 0)
        ));
        WorkOrderSegmentListResponse created = service.createSegment(workOrder.getId(), request(
                today.atTime(7, 0),
                today.atTime(11, 0)
        ));
        Long segmentId = created.segments().getFirst().segmentId();

        service.pauseSegment(segmentId, today.atTime(10, 0));

        WorkOrderSegmentListResponse response = service.updateSegment(segmentId, request(
                today.atTime(8, 0),
                today.atTime(12, 0)
        ));

        assertThat(response.segments().getFirst().paused()).isTrue();
        assertThat(pauseRepository.findByWorkOrderId(workOrder.getId()))
                .singleElement()
                .satisfies((pause) -> {
                    assertThat(pause.getPausedAt()).isEqualTo(today.atTime(10, 0));
                    assertThat(pause.getResumedAt()).isNull();
                });
    }

    @Test
    void movingTodaySegmentToAnotherDayDeletesItsPauseHistory() {
        LocalDate today = LocalDate.now(clock);
        WorkOrder workOrder = workOrderRepository.save(order(
                "ORD-TODAY-MOVE-CROSS-DAY",
                today.plusDays(2).atTime(18, 0)
        ));
        WorkOrderSegmentListResponse created = service.createSegment(workOrder.getId(), request(
                today.atTime(7, 0),
                today.atTime(11, 0)
        ));
        Long segmentId = created.segments().getFirst().segmentId();

        service.pauseSegment(segmentId, today.atTime(8, 0));
        service.resumeSegment(segmentId, today.atTime(8, 15));

        WorkOrderSegmentListResponse response = service.updateSegment(segmentId, request(
                today.plusDays(1).atTime(7, 0),
                today.plusDays(1).atTime(11, 0)
        ));

        assertThat(response.segments().getFirst().scheduledStart()).isEqualTo(today.plusDays(1).atTime(7, 0));
        assertThat(pauseRepository.findByWorkOrderId(workOrder.getId())).isEmpty();
    }

    @Test
    void mergingMovedSegmentTransfersValidPauseHistoryToSurvivingSegment() {
        LocalDate today = LocalDate.now(clock);
        WorkOrder workOrder = workOrderRepository.save(order(
                "ORD-TODAY-MOVE-MERGE",
                today.plusDays(1).atTime(18, 0)
        ));
        WorkOrderSegmentListResponse first = service.createSegment(workOrder.getId(), request(
                today.atTime(7, 0),
                today.atTime(8, 0)
        ));
        Long survivingSegmentId = first.segments().getFirst().segmentId();
        WorkOrderSegmentListResponse second = service.createSegment(workOrder.getId(), request(
                today.atTime(9, 0),
                today.atTime(11, 0)
        ));
        Long movedSegmentId = second.segments().getLast().segmentId();

        service.pauseSegment(movedSegmentId, today.atTime(9, 15));
        service.resumeSegment(movedSegmentId, today.atTime(9, 30));

        WorkOrderSegmentListResponse response = service.updateSegment(movedSegmentId, request(
                today.atTime(7, 30),
                today.atTime(9, 30)
        ));

        assertThat(response.segments()).singleElement().satisfies((segment) -> {
            assertThat(segment.segmentId()).isEqualTo(survivingSegmentId);
            assertThat(segment.scheduledStart()).isEqualTo(today.atTime(7, 0));
            assertThat(segment.scheduledEnd()).isEqualTo(today.atTime(9, 30));
        });
        assertThat(pauseRepository.findByWorkOrderId(workOrder.getId()))
                .singleElement()
                .satisfies((pause) -> {
                    assertThat(pause.getSegment().getId()).isEqualTo(survivingSegmentId);
                    assertThat(pause.getPausedAt()).isEqualTo(today.atTime(9, 15));
                    assertThat(pause.getResumedAt()).isEqualTo(today.atTime(9, 30));
                });
    }

    @Test
    void todaySegmentWithPauseHistoryCanOnlyResizeByExtendingEnd() {
        LocalDate today = LocalDate.now(clock);
        WorkOrder workOrder = workOrderRepository.save(order(
                "ORD-TODAY-RESIZE-END",
                today.plusDays(1).atTime(18, 0)
        ));
        WorkOrderSegmentListResponse created = service.createSegment(workOrder.getId(), request(
                today.atTime(1, 0),
                today.atTime(5, 0)
        ));
        Long segmentId = created.segments().getFirst().segmentId();

        service.pauseSegment(segmentId, today.atTime(3, 0));
        service.resumeSegment(segmentId, today.atTime(3, 15));

        assertThatThrownBy(() -> service.updateSegment(segmentId, request(
                today.atTime(1, 0),
                today.atTime(4, 45)
        )))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("已开始计时的工单只能向后延长结束时间");

        assertThatThrownBy(() -> service.updateSegment(segmentId, request(
                today.atTime(0, 45),
                today.atTime(5, 0)
        )))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("已开始计时的工单调整工时时不可修改开始时间");

        WorkOrderSegmentListResponse response = service.updateSegment(segmentId, request(
                today.atTime(1, 0),
                today.atTime(5, 30)
        ));

        assertThat(response.segments().getFirst().scheduledStart()).isEqualTo(today.atTime(1, 0));
        assertThat(response.segments().getFirst().scheduledEnd()).isEqualTo(today.atTime(5, 30));
        assertThat(response.segments().getFirst().scheduleStartLocked()).isTrue();
    }

    @Test
    void beijingDateBoundaryAppliesTodayResizeRuleWhenUtcIsPreviousDay() {
        LocalDate beijingToday = LocalDate.now(clock);

        assertThat(beijingToday).isEqualTo(LocalDate.of(2026, 7, 16));
        assertThat(LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC))
                .isEqualTo(LocalDate.of(2026, 7, 15));

        WorkOrder workOrder = workOrderRepository.save(order(
                "ORD-BEIJING-DATE-BOUNDARY",
                beijingToday.plusDays(1).atTime(18, 0)
        ));
        WorkOrderSegmentListResponse created = service.createSegment(workOrder.getId(), request(
                beijingToday.atTime(1, 0),
                beijingToday.atTime(5, 0)
        ));
        Long segmentId = created.segments().getFirst().segmentId();

        service.pauseSegment(segmentId, beijingToday.atTime(3, 0));
        service.resumeSegment(segmentId, beijingToday.atTime(3, 15));

        assertThatThrownBy(() -> service.updateSegment(segmentId, request(
                beijingToday.atTime(1, 0),
                beijingToday.atTime(4, 45)
        )))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("已开始计时的工单只能向后延长结束时间");
    }

    @Test
    void completingWhilePausedClosesPauseAndSubtractsThroughCompletion() {
        WorkOrder workOrder = workOrderRepository.save(order("ORD-COMPLETE-PAUSED"));
        WorkOrderSegmentListResponse created = service.createSegment(workOrder.getId(), request(
                LocalDateTime.of(2026, 6, 8, 3, 0),
                LocalDateTime.of(2026, 6, 8, 5, 0)
        ));
        Long segmentId = created.segments().getFirst().segmentId();

        service.pauseSegment(segmentId, LocalDateTime.of(2026, 6, 8, 4, 0));
        WorkOrderSegmentListResponse response = service.completeSegment(
                segmentId,
                LocalDateTime.of(2026, 6, 8, 4, 30)
        );

        assertThat(response.workOrder().status()).isEqualTo(WorkOrderStatus.DONE);
        assertThat(response.segments().getFirst().scheduledEnd()).isEqualTo(LocalDateTime.of(2026, 6, 8, 4, 30));
        assertThat(response.segments().getFirst().paused()).isFalse();
        assertThat(response.segments().getFirst().pausedMinutes()).isEqualTo(30);
        assertThat(response.totalMinutes()).isEqualTo(90);
        assertThat(pauseRepository.findFirstBySegmentIdAndResumedAtIsNullOrderByPausedAtDescIdDesc(segmentId)).isEmpty();
    }

    @Test
    void splitsSegmentAtFifteenMinuteBoundary() {
        WorkOrder workOrder = workOrderRepository.save(order("ORD-SPLIT"));
        WorkOrderSegmentListResponse created = service.createSegment(workOrder.getId(), request(
                LocalDateTime.of(2026, 6, 8, 9, 0),
                LocalDateTime.of(2026, 6, 8, 11, 0)
        ));

        WorkOrderSegmentListResponse response = service.splitSegment(
                created.segments().getFirst().segmentId(),
                new SplitWorkOrderSegmentRequest(LocalDateTime.of(2026, 6, 8, 10, 0))
        );

        assertThat(response.segments()).hasSize(2);
        assertThat(response.totalMinutes()).isEqualTo(120);
        assertThat(response.segments().getFirst().scheduledEnd()).isEqualTo(LocalDateTime.of(2026, 6, 8, 10, 0));
        assertThat(response.segments().getLast().scheduledStart()).isEqualTo(LocalDateTime.of(2026, 6, 8, 10, 0));
    }

    @Test
    void rejectsSplitOutsideFifteenMinuteBoundary() {
        WorkOrder workOrder = workOrderRepository.save(order("ORD-SPLIT-INVALID"));
        WorkOrderSegmentListResponse created = service.createSegment(workOrder.getId(), request(
                LocalDateTime.of(2026, 6, 8, 9, 0),
                LocalDateTime.of(2026, 6, 8, 11, 0)
        ));

        assertThatThrownBy(() -> service.splitSegment(
                created.segments().getFirst().segmentId(),
                new SplitWorkOrderSegmentRequest(LocalDateTime.of(2026, 6, 8, 10, 5))
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("拆分时间必须符合 15 分钟粒度");
    }

    @Test
    void deletingOneOfSeveralSegmentsKeepsWorkOrderScheduled() {
        WorkOrder workOrder = workOrderRepository.save(order("ORD-DELETE-ONE"));
        WorkOrderSegmentListResponse first = service.createSegment(workOrder.getId(), request(
                LocalDateTime.of(2026, 6, 8, 9, 0),
                LocalDateTime.of(2026, 6, 8, 10, 0)
        ));
        service.createSegment(workOrder.getId(), request(
                LocalDateTime.of(2026, 6, 9, 13, 0),
                LocalDateTime.of(2026, 6, 9, 14, 0)
        ));

        WorkOrderSegmentListResponse response = service.deleteSegment(first.segments().getFirst().segmentId());

        assertThat(response.segments()).hasSize(1);
        assertThat(response.totalMinutes()).isEqualTo(60);
        assertThat(response.workOrder().status()).isEqualTo(WorkOrderStatus.SCHEDULED);
    }

    @Test
    void deletingTodaySegmentClearsAllSegmentsAndPausesForWorkOrder() {
        LocalDate today = LocalDate.now(clock);
        WorkOrder workOrder = workOrderRepository.save(order(
                "ORD-DELETE-TODAY-ALL",
                today.plusDays(2).atTime(18, 0)
        ));
        WorkOrderSegmentListResponse todaySegment = service.createSegment(workOrder.getId(), request(
                today.atTime(7, 0),
                today.atTime(8, 0)
        ));
        Long todaySegmentId = todaySegment.segments().getFirst().segmentId();
        service.createSegment(workOrder.getId(), request(
                today.plusDays(1).atTime(9, 0),
                today.plusDays(1).atTime(10, 0)
        ));
        service.pauseSegment(todaySegmentId, today.atTime(7, 15));
        service.resumeSegment(todaySegmentId, today.atTime(7, 30));

        WorkOrderSegmentListResponse response = service.deleteSegment(todaySegmentId);

        assertThat(response.segments()).isEmpty();
        assertThat(response.totalMinutes()).isZero();
        assertThat(response.workOrder().status()).isEqualTo(WorkOrderStatus.PENDING);
        assertThat(response.workOrder().actualMinutes()).isEqualTo(response.workOrder().estimatedMinutes());
        assertThat(segmentRepository.findByWorkOrderIdOrderByScheduledStartAscScheduledEndAscIdAsc(workOrder.getId()))
                .isEmpty();
        assertThat(pauseRepository.findByWorkOrderId(workOrder.getId())).isEmpty();
    }

    @Test
    void deletingLastSegmentReturnsWorkOrderToPending() {
        WorkOrder workOrder = workOrderRepository.save(order("ORD-DELETE-LAST"));
        WorkOrderSegmentListResponse created = service.createSegment(workOrder.getId(), request(
                LocalDateTime.of(2026, 6, 8, 9, 0),
                LocalDateTime.of(2026, 6, 8, 10, 0)
        ));

        WorkOrderSegmentListResponse response = service.deleteSegment(created.segments().getFirst().segmentId());

        assertThat(response.segments()).isEmpty();
        assertThat(response.totalMinutes()).isZero();
        assertThat(response.workOrder().status()).isEqualTo(WorkOrderStatus.PENDING);
        assertThat(response.workOrder().actualMinutes()).isEqualTo(response.workOrder().estimatedMinutes());
    }

    private WorkOrder order(String orderNo) {
        return order(orderNo, LocalDateTime.of(2026, 6, 10, 18, 0));
    }

    private WorkOrder order(String orderNo, LocalDateTime latestShipTime) {
        return new WorkOrder(
                orderNo,
                BigDecimal.valueOf(300),
                180,
                false,
                latestShipTime
        );
    }

    private ScheduleWorkOrderRequest request(LocalDateTime scheduledStart, LocalDateTime scheduledEnd) {
        return new ScheduleWorkOrderRequest(scheduledStart, scheduledEnd);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {

        @Bean
        @Primary
        Clock fixedApplicationClock() {
            return Clock.fixed(
                    Instant.parse("2026-07-15T16:30:00Z"),
                    ZoneId.of("Asia/Shanghai")
            );
        }
    }
}
