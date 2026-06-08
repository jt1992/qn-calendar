package com.qn.calendar.workorder;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkOrderRepository extends JpaRepository<WorkOrder, Long> {

    boolean existsByOrderNo(String orderNo);

    List<WorkOrder> findByStatusOrderByLatestShipTimeAscUrgentDescCreatedAtAsc(WorkOrderStatus status);

    @Query("""
            select workOrder
            from WorkOrder workOrder
            where workOrder.status in :statuses
              and workOrder.scheduledStart is not null
              and workOrder.scheduledEnd is not null
              and workOrder.scheduledStart < :dateToExclusive
              and workOrder.scheduledEnd >= :dateFrom
            order by workOrder.scheduledStart asc, workOrder.urgent desc, workOrder.createdAt asc
            """)
    List<WorkOrder> findCalendarOrders(
            @Param("statuses") Collection<WorkOrderStatus> statuses,
            @Param("dateFrom") LocalDateTime dateFrom,
            @Param("dateToExclusive") LocalDateTime dateToExclusive
    );

    @Query("""
            select count(workOrder) > 0
            from WorkOrder workOrder
            where workOrder.id <> :id
              and workOrder.status in :statuses
              and workOrder.scheduledStart is not null
              and workOrder.scheduledEnd is not null
              and workOrder.scheduledStart < :scheduledEnd
              and workOrder.scheduledEnd > :scheduledStart
            """)
    boolean existsOverlappingWorkOrder(
            @Param("id") Long id,
            @Param("statuses") Collection<WorkOrderStatus> statuses,
            @Param("scheduledStart") LocalDateTime scheduledStart,
            @Param("scheduledEnd") LocalDateTime scheduledEnd
    );
}
