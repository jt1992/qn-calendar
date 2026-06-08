package com.qn.calendar.workorder;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.IntStream;

import com.qn.calendar.workorder.dto.ScheduleEmailRequest;
import com.qn.calendar.workorder.dto.WorkOrderResponse;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

@Service
public class WorkOrderEmailService {

    private static final DateTimeFormatter DAY_LABEL_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd E", Locale.TAIWAN);
    private static final DateTimeFormatter TIME_LABEL_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final WorkOrderRepository repository;
    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;

    public WorkOrderEmailService(
            WorkOrderRepository repository,
            JavaMailSender mailSender,
            TemplateEngine templateEngine
    ) {
        this.repository = repository;
        this.mailSender = mailSender;
        this.templateEngine = templateEngine;
    }

    @Transactional(readOnly = true)
    public void sendScheduleEmail(ScheduleEmailRequest request) {
        validateRequest(request);

        List<WorkOrderResponse> orders = repository.findCalendarOrders(
                        List.of(WorkOrderStatus.SCHEDULED, WorkOrderStatus.DONE),
                        request.dateFrom().atStartOfDay(),
                        request.dateTo().plusDays(1).atStartOfDay()
                )
                .stream()
                .map(WorkOrderResponse::from)
                .toList();

        String html = renderHtml(request, orders);
        send(request, html);
    }

    private void validateRequest(ScheduleEmailRequest request) {
        if (request.viewType() != ScheduleEmailViewType.WEEK) {
            throw new IllegalArgumentException("Email 目前只支援 WEEK 視圖");
        }

        if (request.dateTo().isBefore(request.dateFrom())) {
            throw new IllegalArgumentException("Email 日期區間不可無效");
        }
    }

    private String renderHtml(ScheduleEmailRequest request, List<WorkOrderResponse> orders) {
        List<EmailDay> days = buildDays(request.dateFrom(), request.dateTo());
        List<EmailSlotRow> rows = buildRows(days, orders);
        Context context = new Context(Locale.TAIWAN);
        context.setVariable("subject", request.subject());
        context.setVariable("days", days);
        context.setVariable("rows", rows);
        return templateEngine.process("email/schedule-week", context);
    }

    private List<EmailDay> buildDays(LocalDate dateFrom, LocalDate dateTo) {
        List<EmailDay> days = new ArrayList<>();
        LocalDate cursor = dateFrom;

        while (!cursor.isAfter(dateTo)) {
            days.add(new EmailDay(cursor, DAY_LABEL_FORMATTER.format(cursor)));
            cursor = cursor.plusDays(1);
        }

        return days;
    }

    private List<EmailSlotRow> buildRows(List<EmailDay> days, List<WorkOrderResponse> orders) {
        Map<Integer, Map<LocalDate, List<WorkOrderResponse>>> grouped = new LinkedHashMap<>();

        IntStream.range(0, 24).forEach((hour) -> {
            Map<LocalDate, List<WorkOrderResponse>> byDay = new LinkedHashMap<>();
            days.forEach((day) -> byDay.put(day.date(), new ArrayList<>()));
            grouped.put(hour, byDay);
        });

        for (WorkOrderResponse order : orders) {
            if (order.scheduledStart() == null) {
                continue;
            }

            int hour = order.scheduledStart().getHour();
            LocalDate day = order.scheduledStart().toLocalDate();
            Map<LocalDate, List<WorkOrderResponse>> byDay = grouped.get(hour);

            if (byDay != null && byDay.containsKey(day)) {
                byDay.get(day).add(order);
            }
        }

        return grouped.entrySet()
                .stream()
                .map((entry) -> new EmailSlotRow(TIME_LABEL_FORMATTER.format(LocalTime.of(entry.getKey(), 0)), entry.getValue()))
                .toList();
    }

    private void send(ScheduleEmailRequest request, String html) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setTo(request.to().toArray(String[]::new));
            helper.setSubject(request.subject());
            helper.setText(html, true);
            mailSender.send(message);
        } catch (MessagingException exception) {
            throw new IllegalStateException("排程 Email 建立失敗");
        } catch (MailException exception) {
            throw new IllegalStateException("排程 Email 發送失敗");
        }
    }

    public record EmailDay(LocalDate date, String label) {
    }

    public record EmailSlotRow(
            String timeLabel,
            Map<LocalDate, List<WorkOrderResponse>> ordersByDay
    ) {
    }
}
