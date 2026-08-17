package com.qn.calendar.settings.repository;

import java.util.List;

import com.qn.calendar.settings.entity.ImportUrgentMatchRule;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ImportUrgentMatchRuleRepository extends JpaRepository<ImportUrgentMatchRule, Long> {

    List<ImportUrgentMatchRule> findAllByOrderByIdAsc();
}
