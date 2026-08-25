package com.qn.calendar.workorder.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import com.qn.calendar.workorder.constant.WorkOrderStatus;
import com.qn.calendar.workorder.constant.WorkOrderSource;
import com.qn.calendar.workorder.entity.WorkOrderSegment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
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

    @Query("""
            select segment
            from WorkOrderSegment segment
            join fetch segment.workOrder workOrder
            where segment.id <> :segmentId
              and workOrder.status in :statuses
              and segment.scheduledEnd > :from
            order by segment.scheduledStart asc, segment.scheduledEnd asc, segment.id asc
            """)
    List<WorkOrderSegment> findAutoShiftCandidates(
            @Param("segmentId") Long segmentId,
            @Param("statuses") Collection<WorkOrderStatus> statuses,
            @Param("from") LocalDateTime from
    );

    @Modifying
    void deleteByWorkOrderId(Long workOrderId);

    @Modifying
    @Query("""
            delete from WorkOrderSegment segment
            where segment.workOrder.id in (
                select workOrder.id
                from WorkOrder workOrder
                where upper(trim(workOrder.sourceCode)) = :sourceCode
                   or ((workOrder.sourceCode is null or trim(workOrder.sourceCode) = '')
                       and (workOrder.source = :legacySource
                            or (workOrder.source is null and :sourceCode = 'QIANNIU')))
            )
            """)
    void deleteBySourceIdentifier(
            @Param("sourceCode") String sourceCode,
            @Param("legacySource") WorkOrderSource legacySource
    );
}
