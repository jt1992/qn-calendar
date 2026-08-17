package com.qn.calendar.settings.repository;

import java.util.Collection;
import java.util.List;

import com.qn.calendar.settings.entity.ImportFieldAlias;
import com.qn.calendar.settings.model.ImportFieldKey;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ImportFieldAliasRepository extends JpaRepository<ImportFieldAlias, Long> {

    List<ImportFieldAlias> findAllByOrderByIdAsc();

    long deleteAllByFieldKeyAndNormalizedAliasIn(
            ImportFieldKey fieldKey,
            Collection<String> normalizedAliases
    );
}
