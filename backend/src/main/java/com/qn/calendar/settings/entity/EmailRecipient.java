package com.qn.calendar.settings.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
        name = "email_recipient",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_email_recipient_email",
                columnNames = "email"
        ),
        indexes = @Index(
                name = "idx_email_recipient_last_used",
                columnList = "last_used_at"
        )
)
public class EmailRecipient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", length = 120)
    private String name;

    @Column(name = "email", nullable = false, unique = true, length = 320)
    private String email;

    @Column(name = "usage_count", nullable = false)
    private int usageCount;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected EmailRecipient() {
    }

    public EmailRecipient(String name, String email) {
        this.name = name;
        this.email = email;
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

    public void update(String name, String email) {
        this.name = name;
        this.email = email;
    }

    public void markUsed(LocalDateTime usedAt) {
        this.usageCount += 1;
        this.lastUsedAt = usedAt;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }

    public int getUsageCount() {
        return usageCount;
    }

    public LocalDateTime getLastUsedAt() {
        return lastUsedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
