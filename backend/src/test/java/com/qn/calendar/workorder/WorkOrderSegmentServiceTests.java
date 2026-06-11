package com.qn.calendar.workorder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

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

@SpringBootTest
class WorkOrderSegmentServiceTests {

    @Autowired
    private WorkOrderSegmentService service;

    @Autowired
    private WorkOrderRepository workOrderRepository;

    @Autowired
    private WorkOrderSegmentRepository segmentRepository;

    @Autowired
    private WorkOrderSegmentPauseRepository pauseRepository;

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
    void todaySegmentWithPauseHistoryLocksStartTime() {
        LocalDate today = LocalDate.now();
        WorkOrder workOrder = workOrderRepository.save(order(
                "ORD-TODAY-LOCK-START",
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
        assertThatThrownBy(() -> service.updateSegment(segmentId, request(
                today.atTime(1, 15),
                today.atTime(5, 30)
        )))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("已开始计时的工单不可调整开始时间");
    }

    @Test
    void todaySegmentWithPauseHistoryCanResizeEndNoEarlierThanLastPause() {
        LocalDate today = LocalDate.now();
        WorkOrder workOrder = workOrderRepository.save(order(
                "ORD-TODAY-LOCK-END",
                today.plusDays(1).atTime(18, 0)
        ));
        WorkOrderSegmentListResponse created = service.createSegment(workOrder.getId(), request(
                today.atTime(1, 0),
                today.atTime(5, 0)
        ));
        Long segmentId = created.segments().getFirst().segmentId();

        service.pauseSegment(segmentId, today.atTime(3, 7));
        service.resumeSegment(segmentId, today.atTime(3, 15));

        assertThatThrownBy(() -> service.updateSegment(segmentId, request(
                today.atTime(1, 0),
                today.atTime(3, 0)
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("排程结束时间不可早于最后暂停时间");

        WorkOrderSegmentListResponse response = service.updateSegment(segmentId, request(
                today.atTime(1, 0),
                today.atTime(3, 15)
        ));

        assertThat(response.segments().getFirst().scheduledStart()).isEqualTo(today.atTime(1, 0));
        assertThat(response.segments().getFirst().scheduledEnd()).isEqualTo(today.atTime(3, 15));
        assertThat(response.segments().getFirst().scheduleStartLocked()).isTrue();
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
}
