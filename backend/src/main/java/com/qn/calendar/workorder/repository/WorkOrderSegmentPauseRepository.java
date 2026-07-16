package com.qn.calendar.workorder.repository;

import java.util.List;
import java.util.Optional;

import com.qn.calendar.workorder.entity.WorkOrderSegmentPause;

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
}
