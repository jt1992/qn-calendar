package com.qn.calendar.workorder.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.qn.calendar.workorder.constant.WorkOrderStatus;
import com.qn.calendar.workorder.constant.WorkOrderSource;
import com.qn.calendar.workorder.entity.WorkOrder;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface WorkOrderRepository extends JpaRepository<WorkOrder, Long> {

    Optional<WorkOrder> findByOrderNo(String orderNo);

    @Query("""
            select count(workOrder)
            from WorkOrder workOrder
            where upper(trim(workOrder.sourceCode)) = :sourceCode
               or ((workOrder.sourceCode is null or trim(workOrder.sourceCode) = '')
                   and (workOrder.source = :legacySource
                        or (workOrder.source is null and :sourceCode = 'QIANNIU')))
            """)
    long countBySourceIdentifier(
            @Param("sourceCode") String sourceCode,
            @Param("legacySource") WorkOrderSource legacySource
    );

    default int deleteBySourceIdentifier(String sourceCode, WorkOrderSource legacySource) {
        deleteRemarkTagAssignmentsBySourceIdentifier(sourceCode, legacySource.name());
        return deleteWorkOrdersBySourceIdentifier(sourceCode, legacySource);
    }

    @Modifying
    @Query(
            value = """
                    delete from work_order_remark_tag
                    where work_order_id in (
                        select id
                        from work_order
                        where upper(trim(source_code)) = :sourceCode
                           or ((source_code is null or trim(source_code) = '')
                               and (source = :legacySource
                                    or (source is null and :sourceCode = 'QIANNIU')))
                    )
                    """,
            nativeQuery = true
    )
    int deleteRemarkTagAssignmentsBySourceIdentifier(
            @Param("sourceCode") String sourceCode,
            @Param("legacySource") String legacySource
    );

    @Modifying
    @Query("""
            delete from WorkOrder workOrder
            where upper(trim(workOrder.sourceCode)) = :sourceCode
               or ((workOrder.sourceCode is null or trim(workOrder.sourceCode) = '')
                   and (workOrder.source = :legacySource
                        or (workOrder.source is null and :sourceCode = 'QIANNIU')))
            """)
    int deleteWorkOrdersBySourceIdentifier(
            @Param("sourceCode") String sourceCode,
            @Param("legacySource") WorkOrderSource legacySource
    );

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
