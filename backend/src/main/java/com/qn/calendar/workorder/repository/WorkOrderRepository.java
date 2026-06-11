package com.qn.calendar.workorder.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.qn.calendar.workorder.constant.WorkOrderStatus;
import com.qn.calendar.workorder.entity.WorkOrder;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface WorkOrderRepository extends JpaRepository<WorkOrder, Long> {

    Optional<WorkOrder> findByOrderNo(String orderNo);

    List<WorkOrder> findByStatusOrderByLatestShipTimeAscUrgentDescCreatedAtAsc(WorkOrderStatus status);

    @Query("""
            select workOrder
            from WorkOrder workOrder
            where workOrder.status = :status
            order by case when workOrder.orderTime is null then 1 else 0 end,
                     workOrder.orderTime desc,
                     workOrder.latestShipTime asc,
                     workOrder.createdAt asc
            """)
    List<WorkOrder> findCompletedStats(@Param("status") WorkOrderStatus status);

    @Query("""
            select workOrder
            from WorkOrder workOrder
            where workOrder.status = :status
              and workOrder.orderTime >= :orderTimeFrom
              and workOrder.orderTime < :orderTimeTo
            order by workOrder.orderTime desc,
                     workOrder.latestShipTime asc,
                     workOrder.createdAt asc
            """)
    List<WorkOrder> findCompletedStatsByOrderTimeRange(
            @Param("status") WorkOrderStatus status,
            @Param("orderTimeFrom") LocalDateTime orderTimeFrom,
            @Param("orderTimeTo") LocalDateTime orderTimeTo
    );
}
