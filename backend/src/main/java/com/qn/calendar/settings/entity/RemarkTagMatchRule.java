package com.qn.calendar.settings.entity;

import com.qn.calendar.settings.constant.ImportUrgentMatchType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
        name = "remark_tag_match_rule",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_remark_tag_match_rule_normalized_text",
                columnNames = "normalized_text"
        )
)
public class RemarkTagMatchRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "remark_tag_id", nullable = false)
    private RemarkTagDefinition remarkTag;

    @Column(name = "text", nullable = false, length = 120)
    private String text;

    @Column(name = "normalized_text", nullable = false, unique = true, length = 120)
    private String normalizedText;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_type", nullable = false, length = 16)
    private ImportUrgentMatchType matchType;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    protected RemarkTagMatchRule() {
    }

    public RemarkTagMatchRule(
            RemarkTagDefinition remarkTag,
            String text,
            String normalizedText,
            ImportUrgentMatchType matchType,
            int displayOrder
    ) {
        this.remarkTag = remarkTag;
        this.text = text;
        this.normalizedText = normalizedText;
        this.matchType = matchType;
        this.displayOrder = displayOrder;
    }

    public Long getId() {
        return id;
    }

    public void normalizeAsContains(int displayOrder) {
        this.matchType = ImportUrgentMatchType.CONTAINS;
        this.displayOrder = displayOrder;
    }

    public RemarkTagDefinition getRemarkTag() {
        return remarkTag;
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

    public int getDisplayOrder() {
        return displayOrder;
    }
}
