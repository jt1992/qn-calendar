package com.qn.calendar.workorder.service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.IntStream;

import com.qn.calendar.workorder.constant.ScheduleEmailViewType;
import com.qn.calendar.workorder.constant.WorkOrderStatus;
import com.qn.calendar.workorder.dto.ScheduleEmailRequest;
import com.qn.calendar.workorder.dto.WorkOrderSegmentResponse;
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

    private static final DateTimeFormatter DAY_LABEL_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd E", Locale.TAIWAN);
    private static final DateTimeFormatter TIME_LABEL_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int SLOT_MINUTES = WorkOrderTimeUtils.SCHEDULE_GRANULARITY_MINUTES;
    private static final int SLOTS_PER_HOUR = 60 / SLOT_MINUTES;
    private static final int SLOTS_PER_DAY = 24 * SLOTS_PER_HOUR;

    private final WorkOrderSegmentRepository segmentRepository;
    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;
    private final String mailFrom;

    public WorkOrderEmailService(
            WorkOrderSegmentRepository segmentRepository,
            JavaMailSender mailSender,
            TemplateEngine templateEngine,
            @Value("${SMTP_FROM:}") String mailFrom
    ) {
        this.segmentRepository = segmentRepository;
        this.mailSender = mailSender;
        this.templateEngine = templateEngine;
        this.mailFrom = mailFrom == null ? "" : mailFrom.trim();
    }

    @Transactional(readOnly = true)
    public void sendScheduleEmail(ScheduleEmailRequest request) {
        validateRequest(request);

        List<WorkOrderSegmentResponse> segments = segmentRepository.findCalendarSegments(
                        List.of(WorkOrderStatus.SCHEDULED, WorkOrderStatus.DONE),
                        request.dateFrom().atStartOfDay(),
                        request.dateTo().plusDays(1).atStartOfDay()
                )
                .stream()
                .map((segment) -> WorkOrderSegmentResponse.from(
                        segment,
                        WorkOrderTimeUtils.totalMinutes(segmentRepository.findByWorkOrderIdOrderByScheduledStartAscScheduledEndAscIdAsc(
                                segment.getWorkOrder().getId()
                        ))
                ))
                .toList();

        String html = renderHtml(request, segments);
        send(request, html);
    }

    private void validateRequest(ScheduleEmailRequest request) {
        if (request.viewType() != ScheduleEmailViewType.WEEK) {
            throw new IllegalArgumentException("Email 目前只支援 WEEK 視圖");
        }

        if (request.dateTo().isBefore(request.dateFrom())) {
            throw new IllegalArgumentException("Email 日期區間不可無效");
        }

        if (parseRecipients(request.to()).isEmpty()) {
            throw new IllegalArgumentException("Email 收件者不可為空");
        }
    }

    private String renderHtml(ScheduleEmailRequest request, List<WorkOrderSegmentResponse> segments) {
        EmailScheduleTable table = buildScheduleTable(request.dateFrom(), request.dateTo(), segments);
        Context context = new Context(Locale.TAIWAN);
        context.setVariable("subject", request.subject());
        context.setVariable("days", table.days());
        context.setVariable("rows", table.rows());
        context.setVariable("totalColumnCount", table.totalColumnCount());
        return templateEngine.process("email/schedule-week", context);
    }

    static EmailScheduleTable buildScheduleTable(LocalDate dateFrom, LocalDate dateTo, List<WorkOrderSegmentResponse> segments) {
        List<EmailDay> days = new ArrayList<>();
        Map<LocalDate, List<EmailOrderSegment>> segmentsByDay = new LinkedHashMap<>();
        LocalDate cursor = dateFrom;

        while (!cursor.isAfter(dateTo)) {
            segmentsByDay.put(cursor, new ArrayList<>());
            cursor = cursor.plusDays(1);
        }

        for (WorkOrderSegmentResponse segment : segments) {
            addOrderSegments(segment, dateFrom, dateTo, segmentsByDay);
        }

        segmentsByDay.values().forEach(WorkOrderEmailService::assignLanes);

        int startSlot = findScheduleStartSlot(segmentsByDay);
        int endSlot = findScheduleEndSlot(segmentsByDay, startSlot);

        segmentsByDay.forEach((date, daySegments) -> {
            int laneCount = daySegments.stream()
                    .mapToInt(EmailOrderSegment::lane)
                    .max()
                    .orElse(0) + 1;
            days.add(new EmailDay(date, DAY_LABEL_FORMATTER.format(date), Math.max(1, laneCount)));
        });

        List<EmailSlotRow> rows = buildRows(days, segmentsByDay, startSlot, endSlot);
        int totalColumnCount = 1 + days.stream()
                .mapToInt(EmailDay::laneCount)
                .sum();
        return new EmailScheduleTable(days, rows, totalColumnCount);
    }

    private static void addOrderSegments(
            WorkOrderSegmentResponse segment,
            LocalDate dateFrom,
            LocalDate dateTo,
            Map<LocalDate, List<EmailOrderSegment>> segmentsByDay
    ) {
        if (segment.scheduledStart() == null || segment.scheduledEnd() == null) {
            return;
        }

        LocalDate cursor = dateFrom;

        while (!cursor.isAfter(dateTo)) {
            LocalDateTime dayStart = cursor.atStartOfDay();
            LocalDateTime dayEnd = cursor.plusDays(1).atStartOfDay();
            LocalDateTime segmentStart = max(segment.scheduledStart(), dayStart);
            LocalDateTime segmentEnd = min(segment.scheduledEnd(), dayEnd);

            if (segmentEnd.isAfter(segmentStart)) {
                int startSlot = slotIndex(segmentStart.toLocalTime());
                int endSlot = slotIndex(segmentEnd.toLocalTime());

                if (segmentEnd.equals(dayEnd)) {
                    endSlot = SLOTS_PER_DAY;
                }

                segmentsByDay.get(cursor).add(new EmailOrderSegment(
                        segment,
                        segmentStart,
                        segmentEnd,
                        startSlot,
                        Math.max(startSlot + 1, endSlot),
                        0
                ));
            }

            cursor = cursor.plusDays(1);
        }
    }

    private static void assignLanes(List<EmailOrderSegment> segments) {
        segments.sort(Comparator
                .comparing(EmailOrderSegment::visibleStart)
                .thenComparing((segment) -> !segment.order().urgent())
                .thenComparing((segment) -> segment.order().orderNo()));

        List<LocalDateTime> laneEnds = new ArrayList<>();

        for (int segmentIndex = 0; segmentIndex < segments.size(); segmentIndex++) {
            EmailOrderSegment segment = segments.get(segmentIndex);
            int lane = firstAvailableLane(laneEnds, segment.visibleStart());

            if (lane == laneEnds.size()) {
                laneEnds.add(segment.visibleEnd());
            } else {
                laneEnds.set(lane, segment.visibleEnd());
            }

            segments.set(segmentIndex, segment.withLane(lane));
        }
    }

    private static int firstAvailableLane(List<LocalDateTime> laneEnds, LocalDateTime start) {
        for (int lane = 0; lane < laneEnds.size(); lane++) {
            if (!laneEnds.get(lane).isAfter(start)) {
                return lane;
            }
        }

        return laneEnds.size();
    }

    private static List<EmailSlotRow> buildRows(
            List<EmailDay> days,
            Map<LocalDate, List<EmailOrderSegment>> segmentsByDay,
            int startSlot,
            int endSlot
    ) {
        Map<EmailCellKey, EmailOrderSegment> startsByCell = new HashMap<>();
        Map<EmailCellKey, Boolean> coveredByCell = new HashMap<>();

        segmentsByDay.forEach((date, segments) -> segments.forEach((segment) -> {
            startsByCell.put(new EmailCellKey(date, segment.startSlot(), segment.lane()), segment);

            for (int slot = segment.startSlot() + 1; slot < segment.endSlot(); slot++) {
                coveredByCell.put(new EmailCellKey(date, slot, segment.lane()), true);
            }
        }));

        return IntStream.range(startSlot, endSlot)
                .mapToObj((slot) -> new EmailSlotRow(
                        timeLabel(slot),
                        slot % SLOTS_PER_HOUR == 0,
                        buildCells(days, startsByCell, coveredByCell, slot)
                ))
                .toList();
    }

    private static int findScheduleStartSlot(Map<LocalDate, List<EmailOrderSegment>> segmentsByDay) {
        return segmentsByDay.values()
                .stream()
                .flatMap(List::stream)
                .mapToInt(EmailOrderSegment::startSlot)
                .min()
                .orElse(0);
    }

    private static int findScheduleEndSlot(Map<LocalDate, List<EmailOrderSegment>> segmentsByDay, int startSlot) {
        return segmentsByDay.values()
                .stream()
                .flatMap(List::stream)
                .mapToInt(EmailOrderSegment::endSlot)
                .max()
                .orElse(startSlot);
    }

    private static List<EmailTableCell> buildCells(
            List<EmailDay> days,
            Map<EmailCellKey, EmailOrderSegment> startsByCell,
            Map<EmailCellKey, Boolean> coveredByCell,
            int slot
    ) {
        List<EmailTableCell> cells = new ArrayList<>();

        for (EmailDay day : days) {
            for (int lane = 0; lane < day.laneCount(); lane++) {
                EmailCellKey key = new EmailCellKey(day.date(), slot, lane);
                EmailOrderSegment segment = startsByCell.get(key);

                if (segment != null) {
                    cells.add(EmailTableCell.order(segment.endSlot() - segment.startSlot(), segment.order()));
                } else if (coveredByCell.containsKey(key)) {
                    cells.add(EmailTableCell.covered());
                } else {
                    cells.add(EmailTableCell.empty());
                }
            }
        }

        return cells;
    }

    private static int slotIndex(LocalTime time) {
        return Math.toIntExact(Duration.between(LocalTime.MIDNIGHT, time).toMinutes() / SLOT_MINUTES);
    }

    private static String timeLabel(int slot) {
        return TIME_LABEL_FORMATTER.format(LocalTime.MIDNIGHT.plusMinutes((long) slot * SLOT_MINUTES));
    }

    private static LocalDateTime max(LocalDateTime first, LocalDateTime second) {
        return first.isAfter(second) ? first : second;
    }

    private static LocalDateTime min(LocalDateTime first, LocalDateTime second) {
        return first.isBefore(second) ? first : second;
    }

    private record EmailCellKey(LocalDate date, int slot, int lane) {
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

    public record EmailScheduleTable(List<EmailDay> days, List<EmailSlotRow> rows, int totalColumnCount) {
    }

    public record EmailDay(LocalDate date, String label, int laneCount) {
    }

    public record EmailSlotRow(
            String timeLabel,
            boolean hourStart,
            List<EmailTableCell> cells
    ) {
    }

    public record EmailTableCell(
            boolean rendered,
            int rowSpan,
            WorkOrderSegmentResponse order
    ) {

        static EmailTableCell order(int rowSpan, WorkOrderSegmentResponse order) {
            return new EmailTableCell(true, rowSpan, order);
        }

        static EmailTableCell empty() {
            return new EmailTableCell(true, 1, null);
        }

        static EmailTableCell covered() {
            return new EmailTableCell(false, 1, null);
        }
    }

    private record EmailOrderSegment(
            WorkOrderSegmentResponse order,
            LocalDateTime visibleStart,
            LocalDateTime visibleEnd,
            int startSlot,
            int endSlot,
            int lane
    ) {

        EmailOrderSegment {
            Objects.requireNonNull(order);
            Objects.requireNonNull(visibleStart);
            Objects.requireNonNull(visibleEnd);
        }

        EmailOrderSegment withLane(int lane) {
            return new EmailOrderSegment(order, visibleStart, visibleEnd, startSlot, endSlot, lane);
        }
    }
}
