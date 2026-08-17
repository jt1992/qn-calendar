package com.qn.calendar.workorder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import com.qn.calendar.settings.constant.SmtpSecurity;
import com.qn.calendar.settings.model.EmailSenderSettings;
import com.qn.calendar.settings.service.AppSettingsService;
import com.qn.calendar.settings.service.EmailRecipientService;
import com.qn.calendar.workorder.constant.ScheduleEmailViewType;
import com.qn.calendar.workorder.constant.WorkOrderSource;
import com.qn.calendar.workorder.constant.WorkOrderStatus;
import com.qn.calendar.workorder.dto.CompletedWorkOrderStatsResponse;
import com.qn.calendar.workorder.dto.ScheduleEmailRequest;
import com.qn.calendar.workorder.dto.WorkOrderSegmentResponse;
import com.qn.calendar.workorder.repository.WorkOrderRepository;
import com.qn.calendar.workorder.repository.WorkOrderSegmentPauseRepository;
import com.qn.calendar.workorder.repository.WorkOrderSegmentRepository;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessagePreparator;
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
        assertThat(document.sections().getFirst().days()).hasSize(7);
        assertThat(document.sections().getFirst().weeks()).hasSize(1);
        assertThat(document.sections().getFirst().days().getFirst().label()).startsWith("2026-06-08");

        WorkOrderEmailService.EmailOrderRow row = firstRow(document);
        assertThat(row.orderNo()).isEqualTo("ORD-001");
        assertThat(row.startTime()).isEqualTo("12:00");
        assertThat(row.endTime()).isEqualTo("15:15");
        assertThat(row.durationText()).isEqualTo("3小时15分钟");
        assertThat(row.shipDate()).isEqualTo("2026-06-09 15:15:00");
        assertThat(row.shipDateText()).isEqualTo("2026-06-09");
        assertThat(row.shipTimeText()).isEqualTo("15:15:00");
        assertThat(row.remark()).isEqualTo("买家留言：测试备注");
    }

    @Test
    void weeklyDocumentKeepsTimeAxisAndTrimsEmptyHours() {
        WorkOrderSegmentResponse earlyOrder = order(
                "ORD-EARLY",
                LocalDateTime.of(2026, 4, 1, 7, 30),
                LocalDateTime.of(2026, 4, 1, 13, 30),
                WorkOrderStatus.SCHEDULED
        );
        WorkOrderSegmentResponse lateOrder = order(
                "ORD-LATE",
                LocalDateTime.of(2026, 4, 6, 8, 30),
                LocalDateTime.of(2026, 4, 6, 18, 30),
                WorkOrderStatus.SCHEDULED
        );

        WorkOrderEmailService.EmailScheduleDocument document = WorkOrderEmailService.buildScheduleDocument(
                ScheduleEmailViewType.WEEK,
                LocalDate.of(2026, 4, 1),
                LocalDate.of(2026, 4, 7),
                List.of(earlyOrder, lateOrder)
        );
        WorkOrderEmailService.EmailScheduleSection section = document.sections().getFirst();
        List<String> tickLabels = section.timeTicks()
                .stream()
                .map(WorkOrderEmailService.EmailTimeTick::label)
                .toList();

        assertThat(tickLabels).startsWith("07:00");
        assertThat(tickLabels).endsWith("19:00");
        assertThat(tickLabels).doesNotContain("06:00", "20:00");
        assertThat(section.timeGridHeightStyle()).isEqualTo("height:707px;");
        assertThat(firstRow(document).timeGridStyle())
                .contains("position:absolute")
                .contains("top:")
                .contains("height:");
    }

    @Test
    void weeklyTimeGridFillsPrintablePageAtAnyTrimmedRange() {
        WorkOrderEmailService.EmailScheduleDocument shortRangeDocument = WorkOrderEmailService.buildScheduleDocument(
                ScheduleEmailViewType.WEEK,
                LocalDate.of(2026, 7, 14),
                LocalDate.of(2026, 7, 20),
                List.of(order(
                        "ORD-SHORT",
                        LocalDateTime.of(2026, 7, 14, 14, 0),
                        LocalDateTime.of(2026, 7, 14, 22, 0),
                        WorkOrderStatus.SCHEDULED
                ))
        );
        WorkOrderEmailService.EmailScheduleDocument longRangeDocument = WorkOrderEmailService.buildScheduleDocument(
                ScheduleEmailViewType.WEEK,
                LocalDate.of(2026, 4, 1),
                LocalDate.of(2026, 4, 7),
                List.of(order(
                        "ORD-LONG",
                        LocalDateTime.of(2026, 4, 1, 7, 0),
                        LocalDateTime.of(2026, 4, 1, 20, 0),
                        WorkOrderStatus.SCHEDULED
                ))
        );

        assertThat(shortRangeDocument.sections().getFirst().timeGridHeightStyle())
                .isEqualTo("height:707px;");
        assertThat(longRangeDocument.sections().getFirst().timeGridHeightStyle())
                .isEqualTo("height:707px;");
    }

    @Test
    void weeklyDocumentSplitsRangesLongerThanOneWeekForPrintBreaks() throws Exception {
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
        assertPdfPageCount(
                WorkOrderEmailService.renderLandscapePdf(render(templateEngine(), document)),
                2
        );
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

        WorkOrderEmailService.EmailOrderRow row = firstRow(document);
        assertThat(row.orderNo()).isEqualTo("ORD-MONTH");
        assertThat(row.startTime()).isEqualTo("10:30");
        assertThat(row.endTime()).isEqualTo("12:00");
        assertThat(row.durationText()).isEqualTo("1小时30分钟");
        assertThat(row.done()).isTrue();
    }

    @Test
    void monthlyDocumentStopsAfterLastScheduledWeekAndAddsNoMoreMessage() {
        WorkOrderSegmentResponse order = order(
                "ORD-MONTH",
                LocalDateTime.of(2026, 4, 6, 8, 30),
                LocalDateTime.of(2026, 4, 6, 14, 30),
                WorkOrderStatus.SCHEDULED
        );

        WorkOrderEmailService.EmailScheduleDocument document = WorkOrderEmailService.buildScheduleDocument(
                ScheduleEmailViewType.MONTH,
                LocalDate.of(2026, 4, 1),
                LocalDate.of(2026, 4, 30),
                List.of(order)
        );

        WorkOrderEmailService.EmailScheduleSection section = document.sections().getFirst();
        List<String> dayLabels = section.days()
                .stream()
                .map(WorkOrderEmailService.EmailDaySchedule::label)
                .toList();

        assertThat(section.weeks()).hasSize(2);
        assertThat(section.noMoreRowsMessage()).isEqualTo("2026-04-06 之后暂时没有排工单");
        assertThat(dayLabels).anyMatch((label) -> label.startsWith("2026-04-06"));
        assertThat(dayLabels).anyMatch((label) -> label.startsWith("2026-04-11"));
        assertThat(dayLabels).noneMatch((label) -> label.startsWith("2026-04-12"));
    }

    @Test
    void emailTemplateRendersWeeklyAndMonthlyDocuments() throws Exception {
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
                .contains("A4 landscape")
                .contains("week-timegrid")
                .contains("time-axis")
                .contains("font-size:10px;font-weight:900")
                .contains("10:00")
                .contains("12:00")
                .contains("weekly-remark")
                .contains("最晚发货")
                .contains("display:block;\">最晚发货：")
                .contains("font-size:11px;line-height:1.12;white-space:pre-line")
                .contains("white-space:nowrap;")
                .contains("2026-06-21")
                .contains("12:00:00")
                .contains("买家留言：测试备注")
                .doesNotContain("<span style=\"display:block;font-weight:800;\">备注：</span>")
                .doesNotContain("<h1")
                .doesNotContain("<h2")
                .doesNotContain("周表 ｜")
                .doesNotContain("2026-06-21 12:00:00")
                .doesNotContain("max-height")
                .doesNotContain("break-inside: avoid");
        assertThat(monthlyHtml)
                .contains("ORD-RENDER")
                .contains("月表 ｜ 2026-06")
                .contains("height:679px;")
                .contains("最晚发货")
                .doesNotContain("week-timegrid")
                .doesNotContain("time-axis")
                .contains("display:block;\">最晚发货：")
                .contains("white-space:nowrap;")
                .contains("2026-06-21")
                .contains("12:00:00")
                .contains("2026-06-20 之后暂时没有排工单")
                .contains("height: auto !important")
                .doesNotContain("备注")
                .doesNotContain("买家留言：测试备注")
                .doesNotContain("<h1")
                .doesNotContain("<h2")
                .doesNotContain("2026-06-21 12:00:00")
                .doesNotContain("break-inside: avoid");
        assertPdfPageCount(WorkOrderEmailService.renderLandscapePdf(weeklyHtml), 1);
        assertPdfPageCount(WorkOrderEmailService.renderLandscapePdf(monthlyHtml), 1);
    }

    @Test
    void monthlyCalendarUsesAllPrintableHeightWithoutFooterMessage() throws Exception {
        WorkOrderSegmentResponse order = order(
                "ORD-MONTH-END",
                LocalDateTime.of(2026, 6, 30, 10, 30),
                LocalDateTime.of(2026, 6, 30, 12, 0),
                WorkOrderStatus.SCHEDULED
        );
        WorkOrderEmailService.EmailScheduleDocument document = WorkOrderEmailService.buildScheduleDocument(
                ScheduleEmailViewType.MONTH,
                LocalDate.of(2026, 6, 1),
                LocalDate.of(2026, 6, 30),
                List.of(order)
        );

        String html = render(templateEngine(), document);

        assertThat(document.sections().getFirst().noMoreRowsMessage()).isNull();
        assertThat(html).contains("height:704px;");
        assertPdfPageCount(WorkOrderEmailService.renderLandscapePdf(html), 1);
    }

    private WorkOrderEmailService.EmailOrderRow firstRow(WorkOrderEmailService.EmailScheduleDocument document) {
        return document.sections()
                .stream()
                .flatMap((section) -> section.days().stream())
                .flatMap((day) -> day.rows().stream())
                .findFirst()
                .orElseThrow();
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
        assertThat(row.remark()).isEqualTo("测试备注");
        assertThat(row.price()).isEqualTo("¥300");
        assertThat(row.estimatedDuration()).isEqualTo("3 h");
        assertThat(row.actualDuration()).isEqualTo("2 h 30 m");
        assertThat(row.pausedDuration()).isEqualTo("0 m");
        assertThat(row.deltaText()).isEqualTo("提前 30m");
        assertThat(row.deltaTone()).isEqualTo("early");
        assertThat(row.hourlyRate()).isEqualTo("¥120.00");
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
                .contains("A4 landscape")
                .contains("完工统计表｜2026-06｜1 笔")
                .contains("width=\"100%\"")
                .contains("table-layout:fixed")
                .contains("Noto Sans SC")
                .contains("订单编号")
                .contains("订单备注")
                .contains("订单价格")
                .contains("预估工时")
                .contains("实际工时")
                .contains("工时差")
                .contains("时薪")
                .contains("text-align:right")
                .contains("2026-06")
                .contains("ORD-DONE")
                .contains("超出 15m")
                .contains("¥92.31")
                .doesNotContain(" / 小时")
                .doesNotContain("<h1")
                .doesNotContain("买家昵称");
    }

    @Test
    void emailTemplatesCanRenderLandscapePdf() throws Exception {
        WorkOrderSegmentResponse order = order(
                "ORD-PDF",
                LocalDateTime.of(2026, 6, 20, 10, 30),
                LocalDateTime.of(2026, 6, 20, 12, 0),
                WorkOrderStatus.SCHEDULED
        );
        TemplateEngine templateEngine = templateEngine();
        WorkOrderEmailService.EmailScheduleDocument scheduleDocument = WorkOrderEmailService.buildScheduleDocument(
                ScheduleEmailViewType.WEEK,
                LocalDate.of(2026, 6, 20),
                LocalDate.of(2026, 6, 26),
                List.of(order)
        );
        WorkOrderEmailService.CompletedStatsEmailDocument statsDocument = WorkOrderEmailService.buildCompletedStatsDocument(
                List.of(completedStats("ORD-DONE", 180, 195, BigDecimal.valueOf(92.31))),
                LocalDate.of(2026, 6, 1)
        );

        String scheduleHtml = render(templateEngine, scheduleDocument);
        String statsHtml = renderCompletedStats(templateEngine, statsDocument);

        assertThat(scheduleHtml).doesNotContain("&nbsp;");

        byte[] schedulePdf = WorkOrderEmailService.renderLandscapePdf(scheduleHtml);
        byte[] statsPdf = WorkOrderEmailService.renderLandscapePdf(statsHtml);

        assertThat(schedulePdf).startsWith("%PDF".getBytes(StandardCharsets.US_ASCII));
        assertThat(statsPdf).startsWith("%PDF".getBytes(StandardCharsets.US_ASCII));
        assertPdfPagesAreLandscape(schedulePdf);
        assertPdfPagesAreLandscape(statsPdf);
    }

    @Test
    void sendScheduleEmailUsesUtf8PdfAttachmentFilenamesWithoutHtmlBody() throws Exception {
        WorkOrderRepository workOrderRepository = mock(WorkOrderRepository.class);
        WorkOrderSegmentRepository segmentRepository = mock(WorkOrderSegmentRepository.class);
        WorkOrderSegmentPauseRepository pauseRepository = mock(WorkOrderSegmentPauseRepository.class);
        when(segmentRepository.findCalendarSegments(anyList(), any(), any())).thenReturn(List.of());
        when(workOrderRepository.findCompletedStats(WorkOrderStatus.DONE)).thenReturn(List.of());
        AppSettingsService appSettingsService = mock(AppSettingsService.class);
        EmailSenderSettings emailSenderSettings = new EmailSenderSettings(
                "sender@example.com",
                "smtp.example.com",
                587,
                SmtpSecurity.STARTTLS,
                "smtp-auth-code"
        );
        when(appSettingsService.getRequiredEmailSenderSettings()).thenReturn(emailSenderSettings);
        EmailRecipientService emailRecipientService = mock(EmailRecipientService.class);
        CapturingMailSender mailSender = new CapturingMailSender();
        WorkOrderEmailService service = new WorkOrderEmailService(
                workOrderRepository,
                segmentRepository,
                pauseRepository,
                appSettingsService,
                emailRecipientService,
                templateEngine(),
                Clock.system(ZoneId.of("Asia/Shanghai"))
        ) {
            @Override
            JavaMailSender createMailSender(EmailSenderSettings settings) {
                assertThat(settings).isEqualTo(emailSenderSettings);
                return mailSender;
            }
        };
        List<AttachmentFilenameCase> cases = List.of(
                new AttachmentFilenameCase(
                        new ScheduleEmailRequest(
                                List.of("receiver@example.com"),
                                "custom subject",
                                LocalDate.of(2026, 6, 20),
                                LocalDate.of(2026, 6, 26),
                                ScheduleEmailViewType.WEEK
                        ),
                        "filename*=UTF-8''%E5%91%A8%E8%A1%A8%20-%202026-06-20%20-%202026-06-26.pdf"
                ),
                new AttachmentFilenameCase(
                        new ScheduleEmailRequest(
                                List.of("receiver@example.com"),
                                "custom subject",
                                LocalDate.of(2026, 6, 1),
                                LocalDate.of(2026, 6, 30),
                                ScheduleEmailViewType.MONTH
                        ),
                        "filename*=UTF-8''%E6%9C%88%E8%A1%A8%20-%202026-06.pdf"
                ),
                new AttachmentFilenameCase(
                        new ScheduleEmailRequest(
                                List.of("receiver@example.com"),
                                "custom subject",
                                null,
                                null,
                                ScheduleEmailViewType.COMPLETED_STATS
                        ),
                        "filename*=UTF-8''%E5%AE%8C%E5%B7%A5%E7%BB%9F%E8%AE%A1%E8%A1%A8%20-%20%E5%85%A8%E9%83%A8.pdf"
                )
        );

        for (AttachmentFilenameCase currentCase : cases) {
            service.sendScheduleEmail(currentCase.request());

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            mailSender.sentMessage.writeTo(outputStream);
            String rawMessage = outputStream.toString(StandardCharsets.UTF_8);

            assertThat(rawMessage)
                    .contains("From: sender@example.com")
                    .contains("application/pdf")
                    .contains("Content-Disposition: attachment")
                    .contains("filename=\"=?UTF-8?B?")
                    .contains(currentCase.encodedFilename())
                    .doesNotContain("custom subject.pdf")
                    .doesNotContain("????")
                    .contains("text/plain")
                    .doesNotContain("text/html")
                    .doesNotContain("<table")
                    .doesNotContain("calendar-card");
            assertThat(rawMessage.indexOf(currentCase.encodedFilename()))
                    .isLessThan(rawMessage.indexOf("filename=\"=?UTF-8?B?"));
        }

        verify(emailRecipientService, org.mockito.Mockito.times(cases.size()))
                .recordUsed(List.of("receiver@example.com"));
    }

    @Test
    void completedStatsDocumentCanUseAllMonthLabel() {
        WorkOrderEmailService.CompletedStatsEmailDocument document = WorkOrderEmailService.buildCompletedStatsDocument(
                List.of(completedStats("ORD-DONE", 180, 150, BigDecimal.valueOf(120))),
                "全部"
        );

        assertThat(document.monthLabel()).isEqualTo("全部");
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
                WorkOrderSource.QIANNIU,
                null,
                "买家留言：测试备注",
                BigDecimal.valueOf(300),
                180,
                minutes,
                minutes,
                false,
                scheduledEnd.plusDays(1),
                status,
                scheduledStart,
                scheduledEnd,
                status == WorkOrderStatus.DONE ? scheduledEnd : null,
                false,
                0,
                false,
                false,
                null
        );
    }

    private void assertPdfPagesAreLandscape(byte[] pdf) throws Exception {
        try (PDDocument document = PDDocument.load(new ByteArrayInputStream(pdf))) {
            assertThat(document.getNumberOfPages()).isPositive();
            document.getPages().forEach((page) -> {
                assertThat(page.getMediaBox().getWidth()).isGreaterThan(page.getMediaBox().getHeight());
            });
        }
    }

    private void assertPdfPageCount(byte[] pdf, int expectedPageCount) throws Exception {
        try (PDDocument document = PDDocument.load(new ByteArrayInputStream(pdf))) {
            assertThat(document.getNumberOfPages()).isEqualTo(expectedPageCount);
        }
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
                "测试备注",
                BigDecimal.valueOf(300),
                estimatedMinutes,
                actualTotalMinutes,
                0,
                actualTotalMinutes - estimatedMinutes,
                hourlyRate,
                LocalDateTime.of(2026, 6, 1, 12, 0),
                LocalDateTime.of(2026, 6, 20, 18, 0),
                LocalDateTime.of(2026, 6, 20, 12, 0)
        );
    }

    private record AttachmentFilenameCase(ScheduleEmailRequest request, String encodedFilename) {
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
        Context context = new Context(Locale.CHINA);
        context.setVariable("subject", "工单排程表");
        context.setVariable("document", document);
        return templateEngine.process("email/schedule-week", context);
    }

    private String renderCompletedStats(
            TemplateEngine templateEngine,
            WorkOrderEmailService.CompletedStatsEmailDocument document
    ) {
        Context context = new Context(Locale.CHINA);
        context.setVariable("subject", "完工统计表");
        context.setVariable("document", document);
        return templateEngine.process("email/completed-stats", context);
    }

    private static final class CapturingMailSender implements JavaMailSender {

        private MimeMessage sentMessage;

        @Override
        public MimeMessage createMimeMessage() {
            return new MimeMessage(Session.getInstance(new Properties()));
        }

        @Override
        public MimeMessage createMimeMessage(InputStream contentStream) throws MailException {
            try {
                return new MimeMessage(Session.getInstance(new Properties()), contentStream);
            } catch (MessagingException exception) {
                throw new IllegalStateException(exception);
            }
        }

        @Override
        public void send(MimeMessage mimeMessage) throws MailException {
            this.sentMessage = mimeMessage;
        }

        @Override
        public void send(MimeMessage... mimeMessages) throws MailException {
            if (mimeMessages.length > 0) {
                this.sentMessage = mimeMessages[0];
            }
        }

        @Override
        public void send(MimeMessagePreparator mimeMessagePreparator) throws MailException {
            MimeMessage message = createMimeMessage();
            try {
                mimeMessagePreparator.prepare(message);
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
            send(message);
        }

        @Override
        public void send(MimeMessagePreparator... mimeMessagePreparators) throws MailException {
            for (MimeMessagePreparator mimeMessagePreparator : mimeMessagePreparators) {
                send(mimeMessagePreparator);
            }
        }

        @Override
        public void send(SimpleMailMessage simpleMessage) throws MailException {
            throw new UnsupportedOperationException("SimpleMailMessage is not used by this test");
        }

        @Override
        public void send(SimpleMailMessage... simpleMessages) throws MailException {
            throw new UnsupportedOperationException("SimpleMailMessage is not used by this test");
        }
    }
}
