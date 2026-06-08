package com.qn.calendar.workorder;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
        name = "work_order",
        uniqueConstraints = @UniqueConstraint(name = "uk_work_order_order_no", columnNames = "order_no")
)
public class WorkOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_no", nullable = false, unique = true, length = 80)
    private String orderNo;

    @Column(name = "price", nullable = false, precision = 14, scale = 2)
    private BigDecimal price;

    @Column(name = "estimated_minutes", nullable = false)
    private int estimatedMinutes;

    @Column(name = "actual_minutes", nullable = false)
    private int actualMinutes;

    @Column(name = "urgent", nullable = false)
    private boolean urgent;

    @Column(name = "latest_ship_time", nullable = false)
    private LocalDateTime latestShipTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private WorkOrderStatus status = WorkOrderStatus.PENDING;

    @Column(name = "scheduled_start")
    private LocalDateTime scheduledStart;

    @Column(name = "scheduled_end")
    private LocalDateTime scheduledEnd;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected WorkOrder() {
    }

    public WorkOrder(
            String orderNo,
            BigDecimal price,
            int estimatedMinutes,
            boolean urgent,
            LocalDateTime latestShipTime
    ) {
        this.orderNo = orderNo;
        this.price = price;
        this.estimatedMinutes = estimatedMinutes;
        this.actualMinutes = estimatedMinutes;
        this.urgent = urgent;
        this.latestShipTime = latestShipTime;
        this.status = WorkOrderStatus.PENDING;
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

    public void schedule(LocalDateTime scheduledStart, LocalDateTime scheduledEnd, int actualMinutes) {
        this.scheduledStart = scheduledStart;
        this.scheduledEnd = scheduledEnd;
        this.actualMinutes = actualMinutes;
        this.status = WorkOrderStatus.SCHEDULED;
        this.completedAt = null;
    }

    public void updateActualMinutes(int actualMinutes) {
        this.actualMinutes = actualMinutes;
    }

    public void unschedule() {
        this.scheduledStart = null;
        this.scheduledEnd = null;
        this.status = WorkOrderStatus.PENDING;
        this.completedAt = null;
    }

    public void markDone(LocalDateTime completedAt) {
        this.status = WorkOrderStatus.DONE;
        this.completedAt = completedAt;
    }

    public void reopen() {
        this.status = WorkOrderStatus.SCHEDULED;
        this.completedAt = null;
    }

    public Long getId() {
        return id;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public int getEstimatedMinutes() {
        return estimatedMinutes;
    }

    public int getActualMinutes() {
        return actualMinutes;
    }

    public boolean isUrgent() {
        return urgent;
    }

    public LocalDateTime getLatestShipTime() {
        return latestShipTime;
    }

    public WorkOrderStatus getStatus() {
        return status;
    }

    public LocalDateTime getScheduledStart() {
        return scheduledStart;
    }

    public LocalDateTime getScheduledEnd() {
        return scheduledEnd;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
