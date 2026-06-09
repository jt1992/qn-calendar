package com.qn.calendar.workorder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.qn.calendar.workorder.constant.WorkOrderStatus;
import com.qn.calendar.workorder.dto.ScheduleWorkOrderRequest;
import com.qn.calendar.workorder.dto.SplitWorkOrderSegmentRequest;
import com.qn.calendar.workorder.dto.WorkOrderSegmentListResponse;
import com.qn.calendar.workorder.entity.WorkOrder;
import com.qn.calendar.workorder.repository.WorkOrderRepository;
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

    @BeforeEach
    void setUp() {
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
                .hasMessage("不同工單排程不可重疊");
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
                .hasMessage("排程結束時間不可超過最晚發貨時間");
    }

    @Test
    void completingSegmentExtendsEndToCurrentTimeRoundedUpToFifteenMinutes() {
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
        assertThat(response.segments().getFirst().scheduledEnd()).isEqualTo(LocalDateTime.of(2026, 6, 9, 1, 30));
        assertThat(response.totalMinutes()).isEqualTo(90);
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
                .hasMessage("拆分時間必須符合 15 分鐘粒度");
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
