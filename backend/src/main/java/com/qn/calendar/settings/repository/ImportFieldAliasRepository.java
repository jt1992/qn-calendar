package com.qn.calendar.settings.repository;

import java.util.List;

import com.qn.calendar.settings.entity.ImportFieldAlias;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ImportFieldAliasRepository extends JpaRepository<ImportFieldAlias, Long> {

    List<ImportFieldAlias> findAllByOrderByIdAsc();
}
