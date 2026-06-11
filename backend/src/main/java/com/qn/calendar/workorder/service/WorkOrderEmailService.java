package com.qn.calendar.workorder.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.qn.calendar.workorder.constant.ScheduleEmailViewType;
import com.qn.calendar.workorder.constant.WorkOrderStatus;
import com.qn.calendar.workorder.dto.CompletedWorkOrderStatsResponse;
import com.qn.calendar.workorder.dto.ScheduleEmailRequest;
import com.qn.calendar.workorder.dto.WorkOrderSegmentResponse;
import com.qn.calendar.workorder.repository.WorkOrderRepository;
import com.qn.calendar.workorder.repository.WorkOrderSegmentRepository;
import com.qn.calendar.workorder.util.WorkOrderTimeUtils;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

@Service
public class WorkOrderEmailService {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DAY_LABEL_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd E", Locale.TAIWAN);
    private static final DateTimeFormatter MONTH_LABEL_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    private final WorkOrderRepository workOrderRepository;
    private final WorkOrderSegmentRepository segmentRepository;
    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;
    private final String mailFrom;

    public WorkOrderEmailService(
            WorkOrderRepository workOrderRepository,
            WorkOrderSegmentRepository segmentRepository,
            JavaMailSender mailSender,
            TemplateEngine templateEngine,
            @Value("${SMTP_FROM:}") String mailFrom
    ) {
        this.workOrderRepository = workOrderRepository;
        this.segmentRepository = segmentRepository;
        this.mailSender = mailSender;
        this.templateEngine = templateEngine;
        this.mailFrom = mailFrom == null ? "" : mailFrom.trim();
    }

    @Transactional(readOnly = true)
    public void sendScheduleEmail(ScheduleEmailRequest request) {
        validateRequest(request);

        if (request.viewType() == ScheduleEmailViewType.COMPLETED_STATS) {
            String html = renderCompletedStatsHtml(request);
            send(request, html);
            return;
        }

        LocalDate dateFrom = resolveDateFrom(request);
        LocalDate dateTo = resolveDateTo(request, dateFrom);

        List<WorkOrderSegmentResponse> segments = segmentRepository.findCalendarSegments(
                        List.of(WorkOrderStatus.SCHEDULED, WorkOrderStatus.DONE),
                        dateFrom.atStartOfDay(),
                        dateTo.plusDays(1).atStartOfDay()
                )
                .stream()
                .map((segment) -> WorkOrderSegmentResponse.from(
                        segment,
                        WorkOrderTimeUtils.totalMinutes(segmentRepository.findByWorkOrderIdOrderByScheduledStartAscScheduledEndAscIdAsc(
                                segment.getWorkOrder().getId()
                        ))
                ))
                .toList();

        String html = renderHtml(request, dateFrom, dateTo, segments);
        send(request, html);
    }

    private String renderCompletedStatsHtml(ScheduleEmailRequest request) {
        if (request.dateFrom() == null) {
            return renderCompletedStatsHtml(request, "全部", findCompletedStats());
        }

        LocalDate dateFrom = resolveDateFrom(request);
        LocalDate dateTo = resolveDateTo(request, dateFrom);
        return renderCompletedStatsHtml(
                request,
                MONTH_LABEL_FORMATTER.format(dateFrom),
                findCompletedStats(dateFrom, dateTo)
        );
    }

    private void validateRequest(ScheduleEmailRequest request) {
        if (request.viewType() == null) {
            throw new IllegalArgumentException("Email 類型不可為空");
        }

        if (parseRecipients(request.to()).isEmpty()) {
            throw new IllegalArgumentException("Email 收件者不可為空");
        }

        if (request.viewType() == ScheduleEmailViewType.COMPLETED_STATS
                && request.dateFrom() == null
                && request.dateTo() == null) {
            return;
        }

        if (request.dateFrom() == null || request.dateTo() == null) {
            throw new IllegalArgumentException("Email 日期區間不可為空");
        }

        if (request.dateTo().isBefore(request.dateFrom())) {
            throw new IllegalArgumentException("Email 日期區間不可無效");
        }
    }

    private LocalDate resolveDateFrom(ScheduleEmailRequest request) {
        if (request.viewType() == ScheduleEmailViewType.MONTH
                || request.viewType() == ScheduleEmailViewType.COMPLETED_STATS) {
            return request.dateFrom().withDayOfMonth(1);
        }

        return request.dateFrom();
    }

    private LocalDate resolveDateTo(ScheduleEmailRequest request, LocalDate dateFrom) {
        if (request.viewType() == ScheduleEmailViewType.MONTH
                || request.viewType() == ScheduleEmailViewType.COMPLETED_STATS) {
            return dateFrom.withDayOfMonth(dateFrom.lengthOfMonth());
        }

        return request.dateTo();
    }

    private String renderHtml(
            ScheduleEmailRequest request,
            LocalDate dateFrom,
            LocalDate dateTo,
            List<WorkOrderSegmentResponse> segments
    ) {
        EmailScheduleDocument document = buildScheduleDocument(request.viewType(), dateFrom, dateTo, segments);
        Context context = new Context(Locale.TAIWAN);
        context.setVariable("subject", request.subject());
        context.setVariable("document", document);
        return templateEngine.process("email/schedule-week", context);
    }

    private String renderCompletedStatsHtml(
            ScheduleEmailRequest request,
            String monthLabel,
            List<CompletedWorkOrderStatsResponse> stats
    ) {
        Context context = new Context(Locale.TAIWAN);
        context.setVariable("subject", request.subject());
        context.setVariable("document", buildCompletedStatsDocument(stats, monthLabel));
        return templateEngine.process("email/completed-stats", context);
    }

    private List<CompletedWorkOrderStatsResponse> findCompletedStats() {
        return workOrderRepository.findCompletedStats(WorkOrderStatus.DONE)
                .stream()
                .map((workOrder) -> CompletedWorkOrderStatsResponse.from(
                        workOrder,
                        WorkOrderTimeUtils.totalMinutes(
                                segmentRepository.findByWorkOrderIdOrderByScheduledStartAscScheduledEndAscIdAsc(
                                        workOrder.getId()
                                )
                        )
                ))
                .toList();
    }

    private List<CompletedWorkOrderStatsResponse> findCompletedStats(LocalDate dateFrom, LocalDate dateTo) {
        return workOrderRepository.findCompletedStatsByOrderTimeRange(
                        WorkOrderStatus.DONE,
                        dateFrom.atStartOfDay(),
                        dateTo.plusDays(1).atStartOfDay()
                )
                .stream()
                .map((workOrder) -> CompletedWorkOrderStatsResponse.from(
                        workOrder,
                        WorkOrderTimeUtils.totalMinutes(
                                segmentRepository.findByWorkOrderIdOrderByScheduledStartAscScheduledEndAscIdAsc(
                                        workOrder.getId()
                                )
                        )
                ))
                .toList();
    }

    static EmailScheduleDocument buildScheduleDocument(
            ScheduleEmailViewType viewType,
            LocalDate dateFrom,
            LocalDate dateTo,
            List<WorkOrderSegmentResponse> segments
    ) {
        Map<LocalDate, List<EmailOrderRow>> rowsByDate = buildRowsByDate(dateFrom, dateTo, segments);
        List<EmailScheduleSection> sections = viewType == ScheduleEmailViewType.MONTH
                ? buildMonthSections(dateFrom, rowsByDate)
                : buildWeekSections(dateFrom, dateTo, rowsByDate);

        return new EmailScheduleDocument(
                viewType == ScheduleEmailViewType.MONTH ? "月排程表" : "週排程表",
                viewType == ScheduleEmailViewType.MONTH
                        ? MONTH_LABEL_FORMATTER.format(dateFrom)
                        : formatDateRange(dateFrom, dateTo),
                viewType == ScheduleEmailViewType.WEEK,
                sections
        );
    }

    static CompletedStatsEmailDocument buildCompletedStatsDocument(
            List<CompletedWorkOrderStatsResponse> stats,
            LocalDate month
    ) {
        return buildCompletedStatsDocument(stats, MONTH_LABEL_FORMATTER.format(month));
    }

    static CompletedStatsEmailDocument buildCompletedStatsDocument(
            List<CompletedWorkOrderStatsResponse> stats,
            String monthLabel
    ) {
        return new CompletedStatsEmailDocument(
                monthLabel,
                stats.stream()
                        .map(WorkOrderEmailService::toCompletedStatsEmailRow)
                        .toList()
        );
    }

    private static CompletedStatsEmailRow toCompletedStatsEmailRow(CompletedWorkOrderStatsResponse stats) {
        return new CompletedStatsEmailRow(
                stats.orderNo(),
                displayText(stats.remark()),
                formatCurrency(stats.price()),
                formatStatsDurationText(stats.estimatedMinutes()),
                formatStatsDurationText(stats.actualTotalMinutes()),
                formatStatsDeltaText(stats.deltaMinutes()),
                deltaTone(stats.deltaMinutes()),
                formatHourlyRate(stats.hourlyRate())
        );
    }

    private static Map<LocalDate, List<EmailOrderRow>> buildRowsByDate(
            LocalDate dateFrom,
            LocalDate dateTo,
            List<WorkOrderSegmentResponse> segments
    ) {
        Map<LocalDate, List<EmailOrderRow>> rowsByDate = new LinkedHashMap<>();
        LocalDate cursor = dateFrom;

        while (!cursor.isAfter(dateTo)) {
            rowsByDate.put(cursor, new ArrayList<>());
            cursor = cursor.plusDays(1);
        }

        for (WorkOrderSegmentResponse segment : segments) {
            addSegmentRows(segment, dateFrom, dateTo, rowsByDate);
        }

        rowsByDate.values().forEach((rows) -> rows.sort(Comparator
                .comparing(EmailOrderRow::sortStart)
                .thenComparing((row) -> !row.urgent())
                .thenComparing(EmailOrderRow::orderNo)));

        return rowsByDate;
    }

    private static void addSegmentRows(
            WorkOrderSegmentResponse segment,
            LocalDate dateFrom,
            LocalDate dateTo,
            Map<LocalDate, List<EmailOrderRow>> rowsByDate
    ) {
        if (segment.scheduledStart() == null || segment.scheduledEnd() == null) {
            return;
        }

        LocalDate cursor = dateFrom;

        while (!cursor.isAfter(dateTo)) {
            LocalDateTime dayStart = cursor.atStartOfDay();
            LocalDateTime dayEnd = cursor.plusDays(1).atStartOfDay();
            LocalDateTime visibleStart = max(segment.scheduledStart(), dayStart);
            LocalDateTime visibleEnd = min(segment.scheduledEnd(), dayEnd);

            if (visibleEnd.isAfter(visibleStart)) {
                rowsByDate.get(cursor).add(toEmailOrderRow(segment, visibleStart, visibleEnd));
            }

            cursor = cursor.plusDays(1);
        }
    }

    private static EmailOrderRow toEmailOrderRow(
            WorkOrderSegmentResponse segment,
            LocalDateTime visibleStart,
            LocalDateTime visibleEnd
    ) {
        int minutes = Math.toIntExact(Duration.between(visibleStart, visibleEnd).toMinutes());
        return new EmailOrderRow(
                segment.orderNo(),
                TIME_FORMATTER.format(visibleStart),
                TIME_FORMATTER.format(visibleEnd),
                formatDurationText(minutes),
                DATE_FORMATTER.format(segment.latestShipTime()),
                segment.remark(),
                segment.urgent(),
                segment.status() == WorkOrderStatus.DONE,
                visibleStart
        );
    }

    private static List<EmailScheduleSection> buildWeekSections(
            LocalDate dateFrom,
            LocalDate dateTo,
            Map<LocalDate, List<EmailOrderRow>> rowsByDate
    ) {
        List<EmailScheduleSection> sections = new ArrayList<>();
        LocalDate sectionStart = dateFrom;

        while (!sectionStart.isAfter(dateTo)) {
            LocalDate sectionEnd = min(sectionStart.plusDays(6), dateTo);
            List<EmailDaySchedule> days = buildDaySchedules(sectionStart, sectionEnd, rowsByDate);

            if (!days.isEmpty() || sections.isEmpty()) {
                sections.add(new EmailScheduleSection(
                        formatDateRange(sectionStart, sectionEnd),
                        !sections.isEmpty(),
                        days
                ));
            }

            sectionStart = sectionEnd.plusDays(1);
        }

        return sections;
    }

    private static List<EmailScheduleSection> buildMonthSections(
            LocalDate dateFrom,
            Map<LocalDate, List<EmailOrderRow>> rowsByDate
    ) {
        return List.of(new EmailScheduleSection(
                MONTH_LABEL_FORMATTER.format(dateFrom),
                false,
                buildDaySchedules(dateFrom, dateFrom.withDayOfMonth(dateFrom.lengthOfMonth()), rowsByDate)
        ));
    }

    private static List<EmailDaySchedule> buildDaySchedules(
            LocalDate dateFrom,
            LocalDate dateTo,
            Map<LocalDate, List<EmailOrderRow>> rowsByDate
    ) {
        List<EmailDaySchedule> days = new ArrayList<>();
        LocalDate cursor = dateFrom;

        while (!cursor.isAfter(dateTo)) {
            List<EmailOrderRow> rows = rowsByDate.getOrDefault(cursor, List.of());

            if (!rows.isEmpty()) {
                days.add(new EmailDaySchedule(DAY_LABEL_FORMATTER.format(cursor), rows));
            }

            cursor = cursor.plusDays(1);
        }

        return days;
    }

    private static String formatDateRange(LocalDate dateFrom, LocalDate dateTo) {
        if (dateFrom.equals(dateTo)) {
            return DATE_FORMATTER.format(dateFrom);
        }

        return DATE_FORMATTER.format(dateFrom) + " - " + DATE_FORMATTER.format(dateTo);
    }

    private static String formatDurationText(int minutes) {
        int hours = minutes / 60;
        int remainingMinutes = minutes % 60;

        if (hours <= 0) {
            return remainingMinutes + "分鐘";
        }

        if (remainingMinutes == 0) {
            return hours + "小時";
        }

        return hours + "小時" + remainingMinutes + "分鐘";
    }

    private static String formatStatsDurationText(int minutes) {
        int normalizedMinutes = Math.max(0, minutes);
        return (normalizedMinutes / 60) + "小時" + (normalizedMinutes % 60) + "分鐘";
    }

    private static String formatStatsDeltaText(int minutes) {
        if (minutes == 0) {
            return "符合預期";
        }

        return (minutes > 0 ? "超出 " : "提前 ") + formatStatsDurationText(Math.abs(minutes));
    }

    private static String deltaTone(int minutes) {
        if (minutes > 0) {
            return "late";
        }

        if (minutes < 0) {
            return "early";
        }

        return "normal";
    }

    private static String formatHourlyRate(BigDecimal value) {
        if (value == null) {
            return "-";
        }

        return formatCurrency(value) + " / 小時";
    }

    private static String formatCurrency(BigDecimal value) {
        if (value == null) {
            return "-";
        }

        NumberFormat formatter = NumberFormat.getNumberInstance(Locale.TAIWAN);
        formatter.setMinimumFractionDigits(0);
        formatter.setMaximumFractionDigits(2);
        return "$" + formatter.format(value);
    }

    private static String displayText(String value) {
        return StringUtils.hasText(value) ? value : "-";
    }

    private static LocalDateTime max(LocalDateTime first, LocalDateTime second) {
        return first.isAfter(second) ? first : second;
    }

    private static LocalDateTime min(LocalDateTime first, LocalDateTime second) {
        return first.isBefore(second) ? first : second;
    }

    private static LocalDate min(LocalDate first, LocalDate second) {
        return first.isBefore(second) ? first : second;
    }

    private void send(ScheduleEmailRequest request, String html) {
        try {
            List<String> recipients = parseRecipients(request.to());
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            if (StringUtils.hasText(mailFrom)) {
                helper.setFrom(mailFrom);
            }
            helper.setTo(recipients.toArray(String[]::new));
            helper.setSubject(request.subject());
            helper.setText(html, true);
            mailSender.send(message);
        } catch (MessagingException exception) {
            throw new IllegalStateException("排程 Email 建立失敗");
        } catch (MailException exception) {
            throw new IllegalStateException("排程 Email 發送失敗");
        }
    }

    private List<String> parseRecipients(List<String> recipients) {
        if (recipients == null) {
            return List.of();
        }

        return recipients.stream()
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }

    public record EmailScheduleDocument(
            String viewLabel,
            String rangeLabel,
            boolean weekView,
            List<EmailScheduleSection> sections
    ) {
    }

    public record EmailScheduleSection(
            String title,
            boolean pageBreakBefore,
            List<EmailDaySchedule> days
    ) {
    }

    public record EmailDaySchedule(String label, List<EmailOrderRow> rows) {
    }

    public record EmailOrderRow(
            String orderNo,
            String startTime,
            String endTime,
            String durationText,
            String shipDate,
            String remark,
            boolean urgent,
            boolean done,
            LocalDateTime sortStart
    ) {
    }

    public record CompletedStatsEmailDocument(String monthLabel, List<CompletedStatsEmailRow> rows) {
    }

    public record CompletedStatsEmailRow(
            String orderNo,
            String remark,
            String price,
            String estimatedDuration,
            String actualDuration,
            String deltaText,
            String deltaTone,
            String hourlyRate
    ) {
    }
}
