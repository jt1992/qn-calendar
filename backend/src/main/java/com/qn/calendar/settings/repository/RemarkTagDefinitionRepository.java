package com.qn.calendar.settings.repository;

import java.util.List;
import java.util.Optional;

import com.qn.calendar.settings.entity.RemarkTagDefinition;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface RemarkTagDefinitionRepository extends JpaRepository<RemarkTagDefinition, Long> {

    List<RemarkTagDefinition> findAllByOrderByDisplayOrderAscIdAsc();

    Optional<RemarkTagDefinition> findBySystemKey(String systemKey);

    @Modifying
    @Transactional
    @Query(
            value = "delete from work_order_remark_tag where remark_tag_id = :remarkTagId",
            nativeQuery = true
    )
    int deleteWorkOrderAssignments(@Param("remarkTagId") Long remarkTagId);

    @Modifying
    @Transactional
    @Query(value = "delete from work_order_remark_tag", nativeQuery = true)
    int deleteAllWorkOrderAssignments();

    @Modifying
    @Transactional
    @Query(
            value = """
                    insert or ignore into work_order_remark_tag (work_order_id, remark_tag_id)
                    select work_order.id, :remarkTagId
                    from work_order
                    where work_order.urgent = 1
                      and not exists (
                          select 1
                          from work_order_remark_tag existing_assignment
                          where existing_assignment.work_order_id = work_order.id
                            and existing_assignment.remark_tag_id = :remarkTagId
                      )
                    """,
            nativeQuery = true
    )
    int backfillUrgentAssignments(@Param("remarkTagId") Long remarkTagId);
}
