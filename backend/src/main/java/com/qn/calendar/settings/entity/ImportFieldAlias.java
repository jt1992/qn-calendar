package com.qn.calendar.settings.entity;

import com.qn.calendar.settings.model.ImportFieldKey;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
        name = "import_field_alias",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_import_field_alias_normalized_alias",
                columnNames = "normalized_alias"
        )
)
public class ImportFieldAlias {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "field_key", nullable = false, length = 32)
    private ImportFieldKey fieldKey;

    @Column(name = "alias", nullable = false, length = 120)
    private String alias;

    @Column(name = "normalized_alias", nullable = false, unique = true, length = 120)
    private String normalizedAlias;

    protected ImportFieldAlias() {
    }

    public ImportFieldAlias(ImportFieldKey fieldKey, String alias, String normalizedAlias) {
        this.fieldKey = fieldKey;
        this.alias = alias;
        this.normalizedAlias = normalizedAlias;
    }

    public Long getId() {
        return id;
    }

    public ImportFieldKey getFieldKey() {
        return fieldKey;
    }

    public String getAlias() {
        return alias;
    }

    public String getNormalizedAlias() {
        return normalizedAlias;
    }
}
