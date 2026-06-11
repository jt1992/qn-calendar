package com.qn.calendar.workorder.repository;

import java.time.LocalDateTime;
import java.util.List;

import com.qn.calendar.workorder.constant.WorkOrderStatus;
import com.qn.calendar.workorder.entity.WorkOrder;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface WorkOrderRepository extends JpaRepository<WorkOrder, Long> {

    boolean existsByOrderNo(String orderNo);

    List<WorkOrder> findByStatusOrderByLatestShipTimeAscUrgentDescCreatedAtAsc(WorkOrderStatus status);

    List<WorkOrder> findByStatusOrderByCompletedAtDescLatestShipTimeAscCreatedAtAsc(WorkOrderStatus status);

    List<WorkOrder> findByStatusAndCompletedAtGreaterThanEqualAndCompletedAtLessThanOrderByCompletedAtDescLatestShipTimeAscCreatedAtAsc(
            WorkOrderStatus status,
            LocalDateTime completedAtFrom,
            LocalDateTime completedAtTo
    );
}
