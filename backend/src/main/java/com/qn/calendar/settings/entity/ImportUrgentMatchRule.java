package com.qn.calendar.settings.entity;

import com.qn.calendar.settings.constant.ImportUrgentMatchType;

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
        name = "import_urgent_match_rule",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_import_urgent_match_rule_normalized_text",
                columnNames = "normalized_text"
        )
)
public class ImportUrgentMatchRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "text", nullable = false, length = 120)
    private String text;

    @Column(name = "normalized_text", nullable = false, unique = true, length = 120)
    private String normalizedText;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_type", nullable = false, length = 16)
    private ImportUrgentMatchType matchType;

    protected ImportUrgentMatchRule() {
    }

    public ImportUrgentMatchRule(
            String text,
            String normalizedText,
            ImportUrgentMatchType matchType
    ) {
        this.text = text;
        this.normalizedText = normalizedText;
        this.matchType = matchType;
    }

    public Long getId() {
        return id;
    }

    public String getText() {
        return text;
    }

    public String getNormalizedText() {
        return normalizedText;
    }

    public ImportUrgentMatchType getMatchType() {
        return matchType;
    }
}
