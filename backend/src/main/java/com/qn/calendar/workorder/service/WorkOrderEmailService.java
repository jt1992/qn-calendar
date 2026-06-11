package com.qn.calendar.workorder.service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
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
import com.qn.calendar.workorder.repository.WorkOrderSegmentPauseRepository;
import com.qn.calendar.workorder.repository.WorkOrderSegmentRepository;
import com.qn.calendar.workorder.util.WorkOrderTimeUtils;

import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;

import jakarta.activation.DataHandler;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.util.ByteArrayDataSource;

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

    private static final Locale DISPLAY_LOCALE = Locale.CHINA;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DAY_LABEL_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd E", DISPLAY_LOCALE);
    private static final DateTimeFormatter MONTH_LABEL_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
    private static final int MINUTES_PER_DAY = 24 * 60;
    private static final List<String> PDF_FONT_PATHS = List.of(
            "/System/Library/Fonts/STHeiti Medium.ttc",
            "/System/Library/Fonts/PingFang.ttc",
            "/System/Library/Fonts/Supplemental/Arial Unicode.ttf",
            "/Library/Fonts/Arial Unicode.ttf",
            "C:\\Windows\\Fonts\\msyh.ttc",
            "C:\\Windows\\Fonts\\simhei.ttf",
            "C:\\Windows\\Fonts\\simsun.ttc",
            "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
            "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc",
            "/usr/share/fonts/truetype/wqy/wqy-microhei.ttc"
    );

    private final WorkOrderRepository workOrderRepository;
    private final WorkOrderSegmentRepository segmentRepository;
    private final WorkOrderSegmentPauseRepository pauseRepository;
    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;
    private final String mailFrom;

    public WorkOrderEmailService(
            WorkOrderRepository workOrderRepository,
            WorkOrderSegmentRepository segmentRepository,
            WorkOrderSegmentPauseRepository pauseRepository,
            JavaMailSender mailSender,
            TemplateEngine templateEngine,
            @Value("${SMTP_FROM:}") String mailFrom
    ) {
        this.workOrderRepository = workOrderRepository;
        this.segmentRepository = segmentRepository;
        this.pauseRepository = pauseRepository;
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
                        )),
                        pauseRepository.existsBySegmentIdAndResumedAtIsNull(segment.getId()),
                        pausedMinutes(segment.getWorkOrder().getId(), segment.getWorkOrder().getCompletedAt())
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
            throw new IllegalArgumentException("Email 类型不可为空");
        }

        if (parseRecipients(request.to()).isEmpty()) {
            throw new IllegalArgumentException("Email 收件人不可为空");
        }

        if (request.viewType() == ScheduleEmailViewType.COMPLETED_STATS
                && request.dateFrom() == null
                && request.dateTo() == null) {
            return;
        }

        if (request.dateFrom() == null || request.dateTo() == null) {
            throw new IllegalArgumentException("Email 日期区间不可为空");
        }

        if (request.dateTo().isBefore(request.dateFrom())) {
            throw new IllegalArgumentException("Email 日期区间不可无效");
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
        Context context = new Context(DISPLAY_LOCALE);
        context.setVariable("subject", request.subject());
        context.setVariable("document", document);
        return templateEngine.process("email/schedule-week", context);
    }

    private String renderCompletedStatsHtml(
            ScheduleEmailRequest request,
            String monthLabel,
            List<CompletedWorkOrderStatsResponse> stats
    ) {
        Context context = new Context(DISPLAY_LOCALE);
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
                        ),
                        pausedMinutes(workOrder.getId(), workOrder.getCompletedAt())
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
                        ),
                        pausedMinutes(workOrder.getId(), workOrder.getCompletedAt())
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
                viewType == ScheduleEmailViewType.MONTH ? "月排程表" : "周排程表",
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
                formatStatsDurationText(stats.pausedMinutes()),
                formatStatsDeltaText(stats.deltaMinutes()),
                deltaTone(stats.deltaMinutes()),
                formatHourlyRate(stats.hourlyRate())
        );
    }

    private int pausedMinutes(Long workOrderId, LocalDateTime completedAt) {
        LocalDateTime fallbackEnd = completedAt == null ? LocalDateTime.now() : completedAt;
        return WorkOrderTimeUtils.pauseMinutes(pauseRepository.findByWorkOrderId(workOrderId), fallbackEnd);
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
                formatDateTime(segment.latestShipTime()),
                formatDate(segment.latestShipTime()),
                formatTimeWithSeconds(segment.latestShipTime()),
                segment.remark(),
                segment.urgent(),
                segment.status() == WorkOrderStatus.DONE,
                visibleStart,
                visibleEnd,
                ""
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
            EmailTimeGrid timeGrid = buildTimeGrid(days);
            List<EmailDaySchedule> styledDays = applyTimeGridStyles(days, timeGrid);

            if (!days.isEmpty() || sections.isEmpty()) {
                sections.add(new EmailScheduleSection(
                        formatDateRange(sectionStart, sectionEnd),
                        !sections.isEmpty(),
                        styledDays,
                        chunkDays(styledDays),
                        null,
                        timeGrid.ticks(),
                        timeGrid.heightStyle()
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
        LocalDate monthEnd = dateFrom.withDayOfMonth(dateFrom.lengthOfMonth());
        LocalDate calendarStart = startOfCalendarWeek(dateFrom);
        LocalDate lastScheduledDate = lastScheduledDate(rowsByDate, dateFrom);
        LocalDate calendarEnd = lastScheduledDate.isBefore(monthEnd)
                ? endOfCalendarWeek(lastScheduledDate)
                : endOfCalendarWeek(monthEnd);
        List<EmailDaySchedule> days = buildDaySchedules(calendarStart, calendarEnd, rowsByDate);
        String noMoreRowsMessage = lastScheduledDate.isBefore(monthEnd)
                ? DATE_FORMATTER.format(lastScheduledDate) + " 之后暂时没有排工单"
                : null;

        return List.of(new EmailScheduleSection(
                MONTH_LABEL_FORMATTER.format(dateFrom),
                false,
                days,
                chunkDays(days),
                noMoreRowsMessage,
                List.of(),
                ""
        ));
    }

    private static EmailTimeGrid buildTimeGrid(List<EmailDaySchedule> days) {
        List<EmailOrderRow> rows = days.stream()
                .flatMap((day) -> day.rows().stream())
                .toList();

        int gridStartMinutes = rows.stream()
                .mapToInt(WorkOrderEmailService::startMinutesOfDay)
                .min()
                .stream()
                .map((minutes) -> floorToHour(minutes))
                .findFirst()
                .orElse(9 * 60);
        int gridEndMinutes = rows.stream()
                .mapToInt(WorkOrderEmailService::endMinutesOfDay)
                .max()
                .stream()
                .map((minutes) -> ceilToHour(minutes))
                .findFirst()
                .orElse(18 * 60);

        gridStartMinutes = Math.max(0, Math.min(gridStartMinutes, MINUTES_PER_DAY - 60));
        gridEndMinutes = Math.max(gridStartMinutes + 60, Math.min(gridEndMinutes, MINUTES_PER_DAY));

        int durationMinutes = gridEndMinutes - gridStartMinutes;
        int hourCount = Math.max(1, (int) Math.ceil(durationMinutes / 60.0));
        int pxPerHour = hourCount <= 8 ? 58 : hourCount <= 12 ? 52 : hourCount <= 16 ? 44 : 36;
        int gridHeight = Math.max(240, Math.min(620, hourCount * pxPerHour));

        return new EmailTimeGrid(
                gridStartMinutes,
                gridEndMinutes,
                buildTimeTicks(gridStartMinutes, gridEndMinutes),
                "height:" + gridHeight + "px;"
        );
    }

    private static List<EmailDaySchedule> applyTimeGridStyles(
            List<EmailDaySchedule> days,
            EmailTimeGrid timeGrid
    ) {
        return days.stream()
                .map((day) -> new EmailDaySchedule(
                        day.label(),
                        day.rows()
                                .stream()
                                .map((row) -> row.withTimeGridStyle(timeGridStyle(row, timeGrid)))
                                .toList()
                ))
                .toList();
    }

    private static List<EmailTimeTick> buildTimeTicks(int gridStartMinutes, int gridEndMinutes) {
        List<EmailTimeTick> ticks = new ArrayList<>();
        int durationMinutes = gridEndMinutes - gridStartMinutes;

        for (int minutes = gridStartMinutes; minutes <= gridEndMinutes; minutes += 60) {
            double offset = (minutes - gridStartMinutes) * 100.0 / durationMinutes;
            ticks.add(new EmailTimeTick(formatTimeTick(minutes), offsetStyle(offset)));
        }

        return ticks;
    }

    private static String timeGridStyle(EmailOrderRow row, EmailTimeGrid timeGrid) {
        int durationMinutes = timeGrid.endMinutes() - timeGrid.startMinutes();
        int startOffset = Math.max(0, startMinutesOfDay(row) - timeGrid.startMinutes());
        int rowDuration = Math.max(15, endMinutesOfDay(row) - startMinutesOfDay(row));
        double top = startOffset * 100.0 / durationMinutes;
        double height = rowDuration * 100.0 / durationMinutes;

        return "position:absolute;left:4px;right:4px;"
                + offsetStyle(top)
                + String.format(Locale.ROOT, "height:%.3f%%;", height)
                + "min-height:42px;";
    }

    private static String offsetStyle(double offset) {
        return String.format(Locale.ROOT, "top:%.3f%%;", offset);
    }

    private static int startMinutesOfDay(EmailOrderRow row) {
        return row.sortStart().toLocalTime().toSecondOfDay() / 60;
    }

    private static int endMinutesOfDay(EmailOrderRow row) {
        if (row.sortEnd().toLocalDate().isAfter(row.sortStart().toLocalDate())) {
            return MINUTES_PER_DAY;
        }

        return row.sortEnd().toLocalTime().toSecondOfDay() / 60;
    }

    private static int floorToHour(int minutes) {
        return (minutes / 60) * 60;
    }

    private static int ceilToHour(int minutes) {
        return ((minutes + 59) / 60) * 60;
    }

    private static String formatTimeTick(int minutes) {
        int boundedMinutes = Math.min(minutes, MINUTES_PER_DAY);
        return String.format(Locale.ROOT, "%02d:%02d", boundedMinutes / 60, boundedMinutes % 60);
    }

    private static LocalDate lastScheduledDate(
            Map<LocalDate, List<EmailOrderRow>> rowsByDate,
            LocalDate fallback
    ) {
        return rowsByDate.entrySet()
                .stream()
                .filter((entry) -> !entry.getValue().isEmpty())
                .map(Map.Entry::getKey)
                .max(LocalDate::compareTo)
                .orElse(fallback);
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

            days.add(new EmailDaySchedule(DAY_LABEL_FORMATTER.format(cursor), rows));

            cursor = cursor.plusDays(1);
        }

        return days;
    }

    private static List<EmailScheduleWeek> chunkDays(List<EmailDaySchedule> days) {
        List<EmailScheduleWeek> weeks = new ArrayList<>();

        for (int index = 0; index < days.size(); index += 7) {
            weeks.add(new EmailScheduleWeek(days.subList(index, Math.min(index + 7, days.size()))));
        }

        return weeks;
    }

    private static LocalDate startOfCalendarWeek(LocalDate date) {
        return date.minusDays(date.getDayOfWeek().getValue() % 7);
    }

    private static LocalDate endOfCalendarWeek(LocalDate date) {
        return startOfCalendarWeek(date).plusDays(6);
    }

    private static String formatDateRange(LocalDate dateFrom, LocalDate dateTo) {
        if (dateFrom.equals(dateTo)) {
            return DATE_FORMATTER.format(dateFrom);
        }

        return DATE_FORMATTER.format(dateFrom) + " - " + DATE_FORMATTER.format(dateTo);
    }

    private static String formatDateTime(LocalDateTime value) {
        return value == null ? "-" : value.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private static String formatDate(LocalDateTime value) {
        return value == null ? "-" : value.format(DATE_FORMATTER);
    }

    private static String formatTimeWithSeconds(LocalDateTime value) {
        return value == null ? "-" : value.format(DateTimeFormatter.ofPattern("HH:mm:ss"));
    }

    private static String formatDurationText(int minutes) {
        int hours = minutes / 60;
        int remainingMinutes = minutes % 60;

        if (hours <= 0) {
            return remainingMinutes + "分钟";
        }

        if (remainingMinutes == 0) {
            return hours + "小时";
        }

        return hours + "小时" + remainingMinutes + "分钟";
    }

    private static String formatStatsDurationText(int minutes) {
        int normalizedMinutes = Math.max(0, minutes);
        return (normalizedMinutes / 60) + "小时" + (normalizedMinutes % 60) + "分钟";
    }

    private static String formatStatsDeltaText(int minutes) {
        if (minutes == 0) {
            return "符合预期";
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

        return formatCurrency(value) + " / 小时";
    }

    private static String formatCurrency(BigDecimal value) {
        if (value == null) {
            return "-";
        }

        NumberFormat formatter = NumberFormat.getNumberInstance(DISPLAY_LOCALE);
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
            byte[] pdf = renderLandscapePdf(html);
            if (StringUtils.hasText(mailFrom)) {
                helper.setFrom(mailFrom);
            }
            helper.setTo(recipients.toArray(String[]::new));
            helper.setSubject(request.subject());
            helper.setText("", false);
            addPdfAttachment(helper, pdfFileName(request.subject()), pdf);
            mailSender.send(message);
        } catch (MessagingException exception) {
            throw new IllegalStateException("排程 Email 创建失败");
        } catch (MailException exception) {
            throw new IllegalStateException("排程 Email 发送失败");
        } catch (IOException exception) {
            throw new IllegalStateException("排程 Email PDF 生成失败");
        }
    }

    private static void addPdfAttachment(
            MimeMessageHelper helper,
            String filename,
            byte[] pdf
    ) throws MessagingException {
        String encodedFilename = encodeRfc5987Value(filename);
        MimeBodyPart attachment = new MimeBodyPart();
        attachment.setDataHandler(new DataHandler(new ByteArrayDataSource(pdf, "application/pdf")));
        attachment.setHeader("Content-Type", "application/pdf; name*=UTF-8''" + encodedFilename);
        attachment.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + encodedFilename);
        helper.getRootMimeMultipart().addBodyPart(attachment);
    }

    private static String encodeRfc5987Value(String value) {
        StringBuilder builder = new StringBuilder();

        for (byte currentByte : value.getBytes(StandardCharsets.UTF_8)) {
            int unsignedByte = currentByte & 0xff;

            if (isRfc5987AttrChar(unsignedByte)) {
                builder.append((char) unsignedByte);
            } else {
                builder.append('%');
                builder.append(String.format(Locale.ROOT, "%02X", unsignedByte));
            }
        }

        return builder.toString();
    }

    private static boolean isRfc5987AttrChar(int value) {
        return value >= 'a' && value <= 'z'
                || value >= 'A' && value <= 'Z'
                || value >= '0' && value <= '9'
                || value == '!'
                || value == '#'
                || value == '$'
                || value == '&'
                || value == '+'
                || value == '-'
                || value == '.'
                || value == '^'
                || value == '_'
                || value == '`'
                || value == '|'
                || value == '~';
    }

    static byte[] renderLandscapePdf(String html) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        PdfRendererBuilder builder = new PdfRendererBuilder();
        builder.useFastMode();
        builder.useDefaultPageSize(297, 210, BaseRendererBuilder.PageSizeUnits.MM);
        registerPdfFonts(builder);
        builder.withHtmlContent(html, null);
        builder.toStream(outputStream);
        builder.run();
        return outputStream.toByteArray();
    }

    private static void registerPdfFonts(PdfRendererBuilder builder) {
        PDF_FONT_PATHS.stream()
                .map(File::new)
                .filter(File::isFile)
                .findFirst()
                .ifPresent((font) -> {
                    builder.useFont(font, "Noto Sans SC");
                    builder.useFont(font, "Microsoft YaHei");
                    builder.useFont(font, "Arial");
                });
    }

    private static String pdfFileName(String subject) {
        String safeSubject = StringUtils.hasText(subject)
                ? subject.replaceAll("[\\\\/:*?\"<>|\\r\\n]+", "_").trim()
                : "工单排程表";

        if (!StringUtils.hasText(safeSubject)) {
            safeSubject = "工单排程表";
        }

        return safeSubject + ".pdf";
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
            List<EmailDaySchedule> days,
            List<EmailScheduleWeek> weeks,
            String noMoreRowsMessage,
            List<EmailTimeTick> timeTicks,
            String timeGridHeightStyle
    ) {
    }

    public record EmailScheduleWeek(List<EmailDaySchedule> days) {
    }

    public record EmailDaySchedule(String label, List<EmailOrderRow> rows) {
    }

    public record EmailTimeGrid(
            int startMinutes,
            int endMinutes,
            List<EmailTimeTick> ticks,
            String heightStyle
    ) {
    }

    public record EmailTimeTick(String label, String offsetStyle) {
    }

    public record EmailOrderRow(
            String orderNo,
            String startTime,
            String endTime,
            String durationText,
            String shipDate,
            String shipDateText,
            String shipTimeText,
            String remark,
            boolean urgent,
            boolean done,
            LocalDateTime sortStart,
            LocalDateTime sortEnd,
            String timeGridStyle
    ) {
        EmailOrderRow withTimeGridStyle(String nextTimeGridStyle) {
            return new EmailOrderRow(
                    orderNo,
                    startTime,
                    endTime,
                    durationText,
                    shipDate,
                    shipDateText,
                    shipTimeText,
                    remark,
                    urgent,
                    done,
                    sortStart,
                    sortEnd,
                    nextTimeGridStyle
            );
        }
    }

    public record CompletedStatsEmailDocument(String monthLabel, List<CompletedStatsEmailRow> rows) {
    }

    public record CompletedStatsEmailRow(
            String orderNo,
            String remark,
            String price,
            String estimatedDuration,
            String actualDuration,
            String pausedDuration,
            String deltaText,
            String deltaTone,
            String hourlyRate
    ) {
    }
}
