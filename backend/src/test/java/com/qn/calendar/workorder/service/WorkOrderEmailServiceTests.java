package com.qn.calendar.workorder.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

import com.qn.calendar.workorder.constant.ScheduleEmailViewType;
import com.qn.calendar.workorder.constant.WorkOrderStatus;
import com.qn.calendar.workorder.dto.CompletedWorkOrderStatsResponse;
import com.qn.calendar.workorder.dto.WorkOrderSegmentResponse;

import org.junit.jupiter.api.Test;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

class WorkOrderEmailServiceTests {

    @Test
    void weeklyDocumentGroupsRowsByScheduledDateAndIncludesShippingFields() {
        WorkOrderSegmentResponse order = order(
                "ORD-001",
                LocalDateTime.of(2026, 6, 8, 12, 0),
                LocalDateTime.of(2026, 6, 8, 15, 15),
                WorkOrderStatus.SCHEDULED
        );

        WorkOrderEmailService.EmailScheduleDocument document = WorkOrderEmailService.buildScheduleDocument(
                ScheduleEmailViewType.WEEK,
                LocalDate.of(2026, 6, 8),
                LocalDate.of(2026, 6, 14),
                List.of(order)
        );

        assertThat(document.weekView()).isTrue();
        assertThat(document.rangeLabel()).isEqualTo("2026-06-08 - 2026-06-14");
        assertThat(document.sections()).hasSize(1);
        assertThat(document.sections().getFirst().days()).hasSize(1);
        assertThat(document.sections().getFirst().days().getFirst().label()).startsWith("2026-06-08");

        WorkOrderEmailService.EmailOrderRow row = document.sections().getFirst().days().getFirst().rows().getFirst();
        assertThat(row.orderNo()).isEqualTo("ORD-001");
        assertThat(row.startTime()).isEqualTo("12:00");
        assertThat(row.endTime()).isEqualTo("15:15");
        assertThat(row.durationText()).isEqualTo("3小時15分鐘");
        assertThat(row.shipDate()).isEqualTo("2026-06-09");
        assertThat(row.remark()).isEqualTo("买家留言：測試備註");
    }

    @Test
    void weeklyDocumentSplitsRangesLongerThanOneWeekForPrintBreaks() {
        WorkOrderSegmentResponse firstWeekOrder = order(
                "ORD-FIRST",
                LocalDateTime.of(2026, 6, 8, 9, 0),
                LocalDateTime.of(2026, 6, 8, 10, 0),
                WorkOrderStatus.SCHEDULED
        );
        WorkOrderSegmentResponse secondWeekOrder = order(
                "ORD-SECOND",
                LocalDateTime.of(2026, 6, 15, 9, 0),
                LocalDateTime.of(2026, 6, 15, 10, 0),
                WorkOrderStatus.SCHEDULED
        );

        WorkOrderEmailService.EmailScheduleDocument document = WorkOrderEmailService.buildScheduleDocument(
                ScheduleEmailViewType.WEEK,
                LocalDate.of(2026, 6, 8),
                LocalDate.of(2026, 6, 16),
                List.of(firstWeekOrder, secondWeekOrder)
        );

        assertThat(document.sections()).hasSize(2);
        assertThat(document.sections().get(0).title()).isEqualTo("2026-06-08 - 2026-06-14");
        assertThat(document.sections().get(0).pageBreakBefore()).isFalse();
        assertThat(document.sections().get(1).title()).isEqualTo("2026-06-15 - 2026-06-16");
        assertThat(document.sections().get(1).pageBreakBefore()).isTrue();
    }

    @Test
    void monthlyDocumentUsesMonthRangeAndNonWeeklyRows() {
        WorkOrderSegmentResponse order = order(
                "ORD-MONTH",
                LocalDateTime.of(2026, 6, 20, 10, 30),
                LocalDateTime.of(2026, 6, 20, 12, 0),
                WorkOrderStatus.DONE
        );

        WorkOrderEmailService.EmailScheduleDocument document = WorkOrderEmailService.buildScheduleDocument(
                ScheduleEmailViewType.MONTH,
                LocalDate.of(2026, 6, 1),
                LocalDate.of(2026, 6, 30),
                List.of(order)
        );

        assertThat(document.weekView()).isFalse();
        assertThat(document.rangeLabel()).isEqualTo("2026-06");
        assertThat(document.sections()).hasSize(1);
        assertThat(document.sections().getFirst().title()).isEqualTo("2026-06");

        WorkOrderEmailService.EmailOrderRow row = document.sections().getFirst().days().getFirst().rows().getFirst();
        assertThat(row.orderNo()).isEqualTo("ORD-MONTH");
        assertThat(row.startTime()).isEqualTo("10:30");
        assertThat(row.endTime()).isEqualTo("12:00");
        assertThat(row.durationText()).isEqualTo("1小時30分鐘");
        assertThat(row.done()).isTrue();
    }

    @Test
    void emailTemplateRendersWeeklyAndMonthlyDocuments() {
        WorkOrderSegmentResponse order = order(
                "ORD-RENDER",
                LocalDateTime.of(2026, 6, 20, 10, 30),
                LocalDateTime.of(2026, 6, 20, 12, 0),
                WorkOrderStatus.SCHEDULED
        );
        TemplateEngine templateEngine = templateEngine();
        WorkOrderEmailService.EmailScheduleDocument weeklyDocument = WorkOrderEmailService.buildScheduleDocument(
                ScheduleEmailViewType.WEEK,
                LocalDate.of(2026, 6, 20),
                LocalDate.of(2026, 6, 20),
                List.of(order)
        );
        WorkOrderEmailService.EmailScheduleDocument monthlyDocument = WorkOrderEmailService.buildScheduleDocument(
                ScheduleEmailViewType.MONTH,
                LocalDate.of(2026, 6, 1),
                LocalDate.of(2026, 6, 30),
                List.of(order)
        );

        String weeklyHtml = render(templateEngine, weeklyDocument);
        String monthlyHtml = render(templateEngine, monthlyDocument);

        assertThat(weeklyHtml)
                .contains("ORD-RENDER")
                .contains("發貨日期")
                .contains("備注")
                .contains("买家留言：測試備註");
        assertThat(monthlyHtml)
                .contains("ORD-RENDER")
                .doesNotContain("發貨日期")
                .doesNotContain("備注")
                .doesNotContain("买家留言：測試備註");
    }

    @Test
    void completedStatsDocumentMatchesFrontendTableFormatting() {
        WorkOrderEmailService.CompletedStatsEmailDocument document = WorkOrderEmailService.buildCompletedStatsDocument(
                List.of(completedStats("ORD-DONE", 180, 150, BigDecimal.valueOf(120))),
                LocalDate.of(2026, 6, 1)
        );

        assertThat(document.monthLabel()).isEqualTo("2026-06");
        WorkOrderEmailService.CompletedStatsEmailRow row = document.rows().getFirst();

        assertThat(row.orderNo()).isEqualTo("ORD-DONE");
        assertThat(row.buyerNickname()).isEqualTo("測試買家");
        assertThat(row.remark()).isEqualTo("測試備註");
        assertThat(row.price()).isEqualTo("$300");
        assertThat(row.estimatedDuration()).isEqualTo("3小時0分鐘");
        assertThat(row.actualDuration()).isEqualTo("2小時30分鐘");
        assertThat(row.deltaText()).isEqualTo("提前 0小時30分鐘");
        assertThat(row.deltaTone()).isEqualTo("early");
        assertThat(row.hourlyRate()).isEqualTo("$120 / 小時");
    }

    @Test
    void completedStatsTemplateRendersFrontendTableColumns() {
        TemplateEngine templateEngine = templateEngine();
        WorkOrderEmailService.CompletedStatsEmailDocument document = WorkOrderEmailService.buildCompletedStatsDocument(
                List.of(completedStats("ORD-DONE", 180, 195, BigDecimal.valueOf(92.31))),
                LocalDate.of(2026, 6, 1)
        );

        String html = renderCompletedStats(templateEngine, document);

        assertThat(html)
                .contains("訂單編號")
                .contains("買家暱稱")
                .contains("訂單備注")
                .contains("訂單價格")
                .contains("原本預估時長")
                .contains("實際總時長")
                .contains("差異時間")
                .contains("時薪")
                .contains("2026-06")
                .contains("ORD-DONE")
                .contains("超出 0小時15分鐘")
                .contains("$92.31 / 小時");
    }

    private WorkOrderSegmentResponse order(
            String orderNo,
            LocalDateTime scheduledStart,
            LocalDateTime scheduledEnd,
            WorkOrderStatus status
    ) {
        int minutes = Math.toIntExact(java.time.Duration.between(scheduledStart, scheduledEnd).toMinutes());
        return new WorkOrderSegmentResponse(
                1L,
                1L,
                1L,
                orderNo,
                null,
                "买家留言：測試備註",
                BigDecimal.valueOf(300),
                180,
                minutes,
                minutes,
                false,
                scheduledEnd.plusDays(1),
                status,
                scheduledStart,
                scheduledEnd,
                status == WorkOrderStatus.DONE ? scheduledEnd : null
        );
    }

    private CompletedWorkOrderStatsResponse completedStats(
            String orderNo,
            int estimatedMinutes,
            int actualTotalMinutes,
            BigDecimal hourlyRate
    ) {
        return new CompletedWorkOrderStatsResponse(
                1L,
                orderNo,
                "測試買家",
                "測試備註",
                BigDecimal.valueOf(300),
                estimatedMinutes,
                actualTotalMinutes,
                actualTotalMinutes - estimatedMinutes,
                hourlyRate,
                LocalDateTime.of(2026, 6, 20, 18, 0),
                LocalDateTime.of(2026, 6, 20, 12, 0)
        );
    }

    private TemplateEngine templateEngine() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode("HTML");
        resolver.setCharacterEncoding("UTF-8");

        TemplateEngine templateEngine = new SpringTemplateEngine();
        templateEngine.setTemplateResolver(resolver);
        return templateEngine;
    }

    private String render(TemplateEngine templateEngine, WorkOrderEmailService.EmailScheduleDocument document) {
        Context context = new Context(Locale.TAIWAN);
        context.setVariable("subject", "工單排程表");
        context.setVariable("document", document);
        return templateEngine.process("email/schedule-week", context);
    }

    private String renderCompletedStats(
            TemplateEngine templateEngine,
            WorkOrderEmailService.CompletedStatsEmailDocument document
    ) {
        Context context = new Context(Locale.TAIWAN);
        context.setVariable("subject", "完工統計表");
        context.setVariable("document", document);
        return templateEngine.process("email/completed-stats", context);
    }
}
