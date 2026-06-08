package com.qn.calendar.workorder;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkOrderSegmentRepository extends JpaRepository<WorkOrderSegment, Long> {

    List<WorkOrderSegment> findByWorkOrderIdOrderByScheduledStartAscScheduledEndAscIdAsc(Long workOrderId);

    @Query("""
            select segment
            from WorkOrderSegment segment
            join fetch segment.workOrder workOrder
            where workOrder.status in :statuses
              and segment.scheduledStart < :dateToExclusive
              and segment.scheduledEnd >= :dateFrom
            order by segment.scheduledStart asc, workOrder.urgent desc, workOrder.createdAt asc
            """)
    List<WorkOrderSegment> findCalendarSegments(
            @Param("statuses") Collection<WorkOrderStatus> statuses,
            @Param("dateFrom") LocalDateTime dateFrom,
            @Param("dateToExclusive") LocalDateTime dateToExclusive
    );

    @Query("""
            select count(segment) > 0
            from WorkOrderSegment segment
            join segment.workOrder workOrder
            where workOrder.id <> :workOrderId
              and workOrder.status in :statuses
              and segment.scheduledStart < :scheduledEnd
              and segment.scheduledEnd > :scheduledStart
            """)
    boolean existsOverlappingDifferentWorkOrder(
            @Param("workOrderId") Long workOrderId,
            @Param("statuses") Collection<WorkOrderStatus> statuses,
            @Param("scheduledStart") LocalDateTime scheduledStart,
            @Param("scheduledEnd") LocalDateTime scheduledEnd
    );

    @Modifying
    void deleteByWorkOrderId(Long workOrderId);
}
