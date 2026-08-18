package com.qn.calendar.workorder.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.qn.calendar.settings.entity.RemarkTagDefinition;
import com.qn.calendar.workorder.constant.WorkOrderSource;
import com.qn.calendar.workorder.constant.WorkOrderStatus;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
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

    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 20)
    private WorkOrderSource source;

    @Column(name = "source_name", length = 80)
    private String sourceName;

    @Column(name = "source_code", length = 40)
    private String sourceCode;

    @Column(name = "source_badge_color", length = 7)
    private String sourceBadgeColor;

    @Column(name = "source_badge_text", length = 8)
    private String sourceBadgeText;

    @Column(name = "buyer_nickname", length = 120)
    private String buyerNickname;

    @Column(name = "remark", length = 1000)
    private String remark;

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

    @Column(name = "order_time")
    private LocalDateTime orderTime;

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

    @OneToMany(mappedBy = "workOrder", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<WorkOrderSegment> segments = new ArrayList<>();

    @ManyToMany(fetch = jakarta.persistence.FetchType.EAGER)
    @JoinTable(
            name = "work_order_remark_tag",
            joinColumns = @JoinColumn(name = "work_order_id", nullable = false),
            inverseJoinColumns = @JoinColumn(name = "remark_tag_id", nullable = false),
            uniqueConstraints = @UniqueConstraint(
                    name = "uk_work_order_remark_tag",
                    columnNames = {"work_order_id", "remark_tag_id"}
            )
    )
    @OrderBy("displayOrder ASC, id ASC")
    private List<RemarkTagDefinition> remarkTags = new ArrayList<>();

    protected WorkOrder() {
    }

    public WorkOrder(
            String orderNo,
            BigDecimal price,
            int estimatedMinutes,
            boolean urgent,
            LocalDateTime latestShipTime
    ) {
        this(orderNo, null, null, price, estimatedMinutes, urgent, latestShipTime);
    }

    public WorkOrder(
            String orderNo,
            String buyerNickname,
            String remark,
            BigDecimal price,
            int estimatedMinutes,
            boolean urgent,
            LocalDateTime latestShipTime
    ) {
        this(orderNo, buyerNickname, remark, price, estimatedMinutes, urgent, latestShipTime, null);
    }

    public WorkOrder(
            String orderNo,
            String buyerNickname,
            String remark,
            BigDecimal price,
            int estimatedMinutes,
            boolean urgent,
            LocalDateTime latestShipTime,
            LocalDateTime orderTime
    ) {
        this(
                orderNo,
                buyerNickname,
                remark,
                price,
                estimatedMinutes,
                urgent,
                latestShipTime,
                orderTime,
                WorkOrderSource.QIANNIU
        );
    }

    public WorkOrder(
            String orderNo,
            String buyerNickname,
            String remark,
            BigDecimal price,
            int estimatedMinutes,
            boolean urgent,
            LocalDateTime latestShipTime,
            LocalDateTime orderTime,
            WorkOrderSource source
    ) {
        this(
                orderNo,
                buyerNickname,
                remark,
                price,
                estimatedMinutes,
                urgent,
                latestShipTime,
                orderTime,
                source,
                null
        );
    }

    public WorkOrder(
            String orderNo,
            String buyerNickname,
            String remark,
            BigDecimal price,
            int estimatedMinutes,
            boolean urgent,
            LocalDateTime latestShipTime,
            LocalDateTime orderTime,
            WorkOrderSource source,
            String sourceName
    ) {
        this(
                orderNo,
                buyerNickname,
                remark,
                price,
                estimatedMinutes,
                urgent,
                latestShipTime,
                orderTime,
                source,
                source == null ? WorkOrderSource.QIANNIU.name() : source.name(),
                source == WorkOrderSource.CUSTOM ? sourceName : source == null ? "千牛" : source.displayName(null),
                defaultBadgeColor(source),
                defaultBadgeText(source, sourceName)
        );
    }

    public WorkOrder(
            String orderNo,
            String buyerNickname,
            String remark,
            BigDecimal price,
            int estimatedMinutes,
            boolean urgent,
            LocalDateTime latestShipTime,
            LocalDateTime orderTime,
            WorkOrderSource source,
            String sourceCode,
            String sourceName,
            String sourceBadgeColor,
            String sourceBadgeText
    ) {
        this.orderNo = orderNo;
        this.source = source == null || source == WorkOrderSource.CUSTOM ? null : source;
        this.sourceCode = hasText(sourceCode)
                ? sourceCode.trim().toUpperCase(Locale.ROOT)
                : source == null ? WorkOrderSource.QIANNIU.name() : source.name();
        this.sourceName = hasText(sourceName)
                ? sourceName.trim()
                : source == null ? "千牛" : source.displayName(null);
        this.sourceBadgeColor = sourceBadgeColor;
        this.sourceBadgeText = sourceBadgeText;
        this.buyerNickname = buyerNickname;
        this.remark = remark;
        this.price = price;
        this.estimatedMinutes = estimatedMinutes;
        this.actualMinutes = estimatedMinutes;
        this.urgent = urgent;
        this.latestShipTime = latestShipTime;
        this.orderTime = orderTime;
        this.status = WorkOrderStatus.PENDING;
    }

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        this.source = legacySourceFor(getSourceCode());
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

    public void syncScheduleSummary(LocalDateTime scheduledStart, LocalDateTime scheduledEnd, int totalMinutes) {
        this.scheduledStart = scheduledStart;
        this.scheduledEnd = scheduledEnd;
        this.actualMinutes = totalMinutes;

        if (this.status != WorkOrderStatus.DONE) {
            this.status = WorkOrderStatus.SCHEDULED;
            this.completedAt = null;
        }
    }

    public void updateActualMinutes(int actualMinutes) {
        this.actualMinutes = actualMinutes;
    }

    public void updateImportedDetails(
            String remark,
            BigDecimal price,
            int estimatedMinutes,
            boolean urgent,
            LocalDateTime latestShipTime,
            LocalDateTime orderTime,
            WorkOrderSource source,
            String sourceCode,
            String sourceName,
            String sourceBadgeColor,
            String sourceBadgeText
    ) {
        boolean actualMinutesFollowEstimate = this.status == WorkOrderStatus.PENDING
                && this.actualMinutes == this.estimatedMinutes;

        this.remark = remark;
        this.price = price;
        this.estimatedMinutes = estimatedMinutes;
        this.urgent = urgent;
        this.latestShipTime = latestShipTime;
        this.orderTime = orderTime;
        this.source = source == null || source == WorkOrderSource.CUSTOM ? null : source;
        this.sourceCode = hasText(sourceCode)
                ? sourceCode.trim().toUpperCase(Locale.ROOT)
                : source == null ? WorkOrderSource.QIANNIU.name() : source.name();
        this.sourceName = hasText(sourceName)
                ? sourceName.trim()
                : source == null ? "千牛" : source.displayName(null);
        this.sourceBadgeColor = sourceBadgeColor;
        this.sourceBadgeText = sourceBadgeText;

        if (actualMinutesFollowEstimate) {
            this.actualMinutes = estimatedMinutes;
        }
    }

    public void unschedule() {
        this.scheduledStart = null;
        this.scheduledEnd = null;
        this.actualMinutes = this.estimatedMinutes;
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

    public void updateSourceMetadata(String sourceName, String sourceBadgeColor, String sourceBadgeText) {
        this.sourceName = sourceName;
        this.sourceBadgeColor = sourceBadgeColor;
        this.sourceBadgeText = sourceBadgeText;
    }

    public void replaceRemarkTags(List<RemarkTagDefinition> tags) {
        Map<Long, RemarkTagDefinition> uniqueTags = new LinkedHashMap<>();
        if (tags != null) {
            for (RemarkTagDefinition tag : tags) {
                if (tag != null && tag.getId() != null) {
                    uniqueTags.putIfAbsent(tag.getId(), tag);
                }
            }
        }
        this.remarkTags.clear();
        this.remarkTags.addAll(uniqueTags.values());
        this.urgent = this.remarkTags.stream()
                .anyMatch((tag) -> "URGENT".equals(tag.getSystemKey()));
    }

    public Long getId() {
        return id;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public WorkOrderSource getSource() {
        return hasText(sourceCode)
                ? WorkOrderSource.fromCode(sourceCode)
                : source == null ? WorkOrderSource.QIANNIU : source;
    }

    public String getSourceName() {
        return hasText(sourceName) ? sourceName : getSource().displayName(null);
    }

    public String getSourceCode() {
        return hasText(sourceCode)
                ? sourceCode.trim().toUpperCase(Locale.ROOT)
                : source == null ? WorkOrderSource.QIANNIU.name() : source.name();
    }

    public String getSourceBadgeColor() {
        return hasText(sourceBadgeColor) ? sourceBadgeColor : defaultBadgeColor(getSource());
    }

    public String getSourceBadgeText() {
        return hasText(sourceBadgeText) ? sourceBadgeText : defaultBadgeText(getSource(), getSourceName());
    }

    public String getBuyerNickname() {
        return buyerNickname;
    }

    public String getRemark() {
        return remark;
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

    public List<RemarkTagDefinition> getRemarkTags() {
        return List.copyOf(remarkTags);
    }

    public LocalDateTime getLatestShipTime() {
        return latestShipTime;
    }

    public LocalDateTime getOrderTime() {
        return orderTime;
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

    private static WorkOrderSource legacySourceFor(String sourceCode) {
        WorkOrderSource resolved = WorkOrderSource.fromCode(sourceCode);
        return resolved == WorkOrderSource.CUSTOM ? null : resolved;
    }

    private static String defaultBadgeColor(WorkOrderSource source) {
        if (source == WorkOrderSource.XIAOHONGSHU) {
            return "#FF5C5C";
        }
        if (source == WorkOrderSource.QIANNIU || source == null) {
            return "#218BFF";
        }
        return "#3B82F6";
    }

    private static String defaultBadgeText(WorkOrderSource source, String sourceName) {
        if (source == WorkOrderSource.XIAOHONGSHU) {
            return "书";
        }
        if (source == WorkOrderSource.QIANNIU || source == null) {
            return "千";
        }
        if (!hasText(sourceName)) {
            return "其";
        }
        int endIndex = sourceName.offsetByCodePoints(0, 1);
        return sourceName.substring(0, endIndex);
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
