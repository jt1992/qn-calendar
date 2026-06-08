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

    @Modifying
    void deleteByWorkOrderId(Long workOrderId);
}
