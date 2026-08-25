package com.qn.calendar.workorder.repository;

import java.util.List;
import java.util.Optional;

import com.qn.calendar.workorder.entity.WorkOrderSegmentPause;
import com.qn.calendar.workorder.constant.WorkOrderSource;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface WorkOrderSegmentPauseRepository extends JpaRepository<WorkOrderSegmentPause, Long> {

    Optional<WorkOrderSegmentPause> findFirstBySegmentIdAndResumedAtIsNullOrderByPausedAtDescIdDesc(Long segmentId);

    Optional<WorkOrderSegmentPause> findFirstBySegmentIdOrderByPausedAtDescIdDesc(Long segmentId);

    boolean existsBySegmentIdAndResumedAtIsNull(Long segmentId);

    List<WorkOrderSegmentPause> findBySegmentIdOrderByPausedAtAscIdAsc(Long segmentId);

    @Query("""
            select pause
            from WorkOrderSegmentPause pause
            join fetch pause.segment segment
            where segment.workOrder.id = :workOrderId
            order by pause.pausedAt asc, pause.id asc
            """)
    List<WorkOrderSegmentPause> findByWorkOrderId(@Param("workOrderId") Long workOrderId);

    @Modifying
    @Query("""
            delete from WorkOrderSegmentPause pause
            where pause.segment.id = :segmentId
            """)
    void deleteBySegmentId(@Param("segmentId") Long segmentId);

    @Modifying
    @Query("""
            delete from WorkOrderSegmentPause pause
            where pause.segment.id in (
                select segment.id
                from WorkOrderSegment segment
                where segment.workOrder.id = :workOrderId
            )
            """)
    void deleteByWorkOrderId(@Param("workOrderId") Long workOrderId);

    @Modifying
    @Query("""
            delete from WorkOrderSegmentPause pause
            where pause.segment.id in (
                select segment.id
                from WorkOrderSegment segment
                where segment.workOrder.id in (
                    select workOrder.id
                    from WorkOrder workOrder
                    where upper(trim(workOrder.sourceCode)) = :sourceCode
                       or ((workOrder.sourceCode is null or trim(workOrder.sourceCode) = '')
                           and (workOrder.source = :legacySource
                                or (workOrder.source is null and :sourceCode = 'QIANNIU')))
                )
            )
            """)
    void deleteBySourceIdentifier(
            @Param("sourceCode") String sourceCode,
            @Param("legacySource") WorkOrderSource legacySource
    );
}
