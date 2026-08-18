package com.qn.calendar.settings.repository;

import java.util.List;

import com.qn.calendar.settings.entity.RemarkTagMatchRule;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface RemarkTagMatchRuleRepository extends JpaRepository<RemarkTagMatchRule, Long> {

    @Query("""
            select rule
            from RemarkTagMatchRule rule
            join fetch rule.remarkTag remarkTag
            order by remarkTag.displayOrder asc, rule.displayOrder asc, rule.id asc
            """)
    List<RemarkTagMatchRule> findAllInDisplayOrder();
}
