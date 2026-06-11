package com.qn.calendar.workorder.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(
        name = "work_order_segment_pause",
        indexes = {
                @Index(name = "idx_work_order_segment_pause_segment", columnList = "work_order_segment_id"),
                @Index(name = "idx_work_order_segment_pause_open", columnList = "work_order_segment_id,resumed_at")
        }
)
public class WorkOrderSegmentPause {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "work_order_segment_id", nullable = false)
    private WorkOrderSegment segment;

    @Column(name = "paused_at", nullable = false)
    private LocalDateTime pausedAt;

    @Column(name = "resumed_at")
    private LocalDateTime resumedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected WorkOrderSegmentPause() {
    }

    public WorkOrderSegmentPause(WorkOrderSegment segment, LocalDateTime pausedAt) {
        this.segment = segment;
        this.pausedAt = pausedAt;
    }

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public void resume(LocalDateTime resumedAt) {
        this.resumedAt = resumedAt;
    }

    public Long getId() {
        return id;
    }

    public WorkOrderSegment getSegment() {
        return segment;
    }

    public LocalDateTime getPausedAt() {
        return pausedAt;
    }

    public LocalDateTime getResumedAt() {
        return resumedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
