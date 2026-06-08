package com.qn.calendar.workorder;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkOrderRepository extends JpaRepository<WorkOrder, Long> {

    boolean existsByOrderNo(String orderNo);

    List<WorkOrder> findByStatusOrderByLatestShipTimeAscUrgentDescCreatedAtAsc(WorkOrderStatus status);
}
