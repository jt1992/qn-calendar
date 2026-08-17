package com.qn.calendar.workorder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.qn.calendar.settings.constant.ImportUrgentMatchType;
import com.qn.calendar.settings.dto.UpdateAppSettingsRequest;
import com.qn.calendar.settings.dto.UpdateImportFieldSettingsRequest;
import com.qn.calendar.settings.model.ImportFieldKey;
import com.qn.calendar.settings.repository.AppSettingRepository;
import com.qn.calendar.settings.repository.ImportFieldAliasRepository;
import com.qn.calendar.settings.repository.ImportUrgentMatchRuleRepository;
import com.qn.calendar.settings.service.AppSettingsService;
import com.qn.calendar.settings.service.ImportFieldSettingsService;
import com.qn.calendar.workorder.constant.WorkOrderSource;
import com.qn.calendar.workorder.constant.WorkOrderStatus;
import com.qn.calendar.workorder.dto.ImportWorkOrderResponse;
import com.qn.calendar.workorder.entity.WorkOrder;
import com.qn.calendar.workorder.entity.WorkOrderSegment;
import com.qn.calendar.workorder.entity.WorkOrderSegmentPause;
import com.qn.calendar.workorder.repository.WorkOrderRepository;
import com.qn.calendar.workorder.repository.WorkOrderSegmentPauseRepository;
import com.qn.calendar.workorder.repository.WorkOrderSegmentRepository;
import com.qn.calendar.workorder.service.WorkOrderImportService;

import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;

@SpringBootTest
class WorkOrderImportServiceTests {

    @Autowired
    private WorkOrderImportService importService;

    @Autowired
    private WorkOrderRepository repository;

    @Autowired
    private WorkOrderSegmentRepository segmentRepository;

    @Autowired
    private WorkOrderSegmentPauseRepository pauseRepository;

    @Autowired
    private AppSettingRepository appSettingRepository;

    @Autowired
    private AppSettingsService appSettingsService;

    @Autowired
    private ImportFieldSettingsService importFieldSettingsService;

    @Autowired
    private ImportFieldAliasRepository importFieldAliasRepository;

    @Autowired
    private ImportUrgentMatchRuleRepository importUrgentMatchRuleRepository;

    @Autowired
    private Clock clock;

    @BeforeEach
    void setUp() {
        pauseRepository.deleteAll();
        segmentRepository.deleteAll();
        repository.deleteAll();
        appSettingRepository.deleteAll();
        importFieldAliasRepository.deleteAll();
        importUrgentMatchRuleRepository.deleteAll();
    }

    @Test
    void importsRequiredChineseColumnsAndTreatsDateOnlyDeadlineAsEndOfDay() throws Exception {
        MockMultipartFile file = xlsxWithOneOrder("ORD-001", BigDecimal.valueOf(250), LocalDateTime.of(2026, 6, 9, 0, 0));

        ImportWorkOrderResponse response = importService.importXlsx(file);

        WorkOrder workOrder = repository.findAll().getFirst();
        assertThat(response.createdCount()).as(response.errors().toString()).isEqualTo(1);
        assertThat(response.updatedCount()).isZero();
        assertThat(response.skippedCount()).isZero();
        assertThat(response.errors()).isEmpty();
        assertThat(workOrder.getOrderNo()).isEqualTo("ORD-001");
        assertThat(workOrder.getSource()).isEqualTo(WorkOrderSource.QIANNIU);
        assertThat(workOrder.getEstimatedMinutes()).isEqualTo(180);
        assertThat(workOrder.getActualMinutes()).isEqualTo(180);
        assertThat(workOrder.getLatestShipTime()).isEqualTo(LocalDateTime.of(2026, 6, 9, 23, 59, 59));
    }

    @Test
    void updatesExistingOrderFieldsAndDefaultPendingDuration() throws Exception {
        importService.importXlsx(xlsxWithOneOrder(
                "ORD-002",
                BigDecimal.valueOf(100),
                LocalDateTime.of(2026, 6, 10, 0, 0)
        ));
        Long originalId = repository.findByOrderNo("ORD-002").orElseThrow().getId();
        MockMultipartFile file = xlsxWithRows(
                List.of(
                        "订单编号",
                        "买家实付金额",
                        "订单付款时间",
                        "应发货时间",
                        "买家留言",
                        "商家备注",
                        "备注标签"
                ),
                List.of(List.of(
                        "ORD-002",
                        "250.00",
                        "2026-06-01 12:30:00",
                        "2026-06-12 15:30:00",
                        "新的买家留言",
                        "新的商家备注",
                        "加急"
                ))
        );

        ImportWorkOrderResponse response = importService.importXlsx(file);

        WorkOrder workOrder = repository.findByOrderNo("ORD-002").orElseThrow();
        assertThat(response.createdCount()).isZero();
        assertThat(response.updatedCount()).isEqualTo(1);
        assertThat(response.errors()).isEmpty();
        assertThat(repository.findAll()).hasSize(1);
        assertThat(workOrder.getId()).isEqualTo(originalId);
        assertThat(workOrder.getStatus()).isEqualTo(WorkOrderStatus.PENDING);
        assertThat(workOrder.getPrice()).isEqualByComparingTo("250.00");
        assertThat(workOrder.getEstimatedMinutes()).isEqualTo(180);
        assertThat(workOrder.getActualMinutes()).isEqualTo(180);
        assertThat(workOrder.isUrgent()).isTrue();
        assertThat(workOrder.getLatestShipTime()).isEqualTo(LocalDateTime.of(2026, 6, 12, 15, 30));
        assertThat(workOrder.getOrderTime()).isEqualTo(LocalDateTime.of(2026, 6, 1, 12, 30));
        assertThat(workOrder.getRemark()).isEqualTo("""
                买家留言：新的买家留言
                商家备注：新的商家备注""");
    }

    @Test
    void usesSavedHourlyBaseAmountWhenImportingNewOrders() throws Exception {
        appSettingsService.updateSettings(new UpdateAppSettingsRequest(
                BigDecimal.valueOf(200),
                LocalTime.of(6, 0)
        ));
        MockMultipartFile file = xlsxWithOneOrder("ORD-BASE-AMOUNT", BigDecimal.valueOf(250), LocalDateTime.of(2026, 6, 10, 0, 0));

        ImportWorkOrderResponse response = importService.importXlsx(file);

        assertThat(response.createdCount()).as(response.errors().toString()).isEqualTo(1);
        assertThat(response.updatedCount()).isZero();
        assertThat(response.errors()).isEmpty();
        WorkOrder workOrder = repository.findAll().getFirst();
        assertThat(workOrder.getEstimatedMinutes()).isEqualTo(120);
        assertThat(workOrder.getActualMinutes()).isEqualTo(120);
    }

    @Test
    void preservesManuallyAdjustedPendingDurationWhenUpdatingImportedFields() throws Exception {
        WorkOrder existing = new WorkOrder(
                "ORD-CUSTOM-DURATION",
                null,
                "旧备注",
                BigDecimal.valueOf(100),
                60,
                true,
                LocalDateTime.of(2026, 6, 10, 18, 0),
                LocalDateTime.of(2026, 5, 1, 12, 0)
        );
        existing.updateActualMinutes(95);
        repository.save(existing);
        MockMultipartFile file = xlsxWithOneOrder(
                "ORD-CUSTOM-DURATION",
                BigDecimal.valueOf(250),
                LocalDateTime.of(2026, 6, 20, 0, 0)
        );

        ImportWorkOrderResponse response = importService.importXlsx(file);

        WorkOrder workOrder = repository.findByOrderNo("ORD-CUSTOM-DURATION").orElseThrow();
        assertThat(response.createdCount()).isZero();
        assertThat(response.updatedCount()).isEqualTo(1);
        assertThat(response.errors()).isEmpty();
        assertThat(repository.findAll()).hasSize(1);
        assertThat(workOrder.getEstimatedMinutes()).isEqualTo(180);
        assertThat(workOrder.getActualMinutes()).isEqualTo(95);
        assertThat(workOrder.getOrderTime()).isNull();
        assertThat(workOrder.getRemark()).isEqualTo("无任何备注");
        assertThat(workOrder.isUrgent()).isFalse();
    }

    @Test
    void usesLastValidRowForDuplicateOrderNumberAndCountsUniqueOrderOnce() throws Exception {
        MockMultipartFile file = xlsxWithRows(
                List.of(
                        "订单编号",
                        "买家实付金额",
                        "订单付款时间",
                        "应发货时间",
                        "商家备注",
                        "备注标签"
                ),
                List.of(
                        List.of(
                                "ORD-DUPLICATE",
                                "100.00",
                                "2026-05-01 09:00:00",
                                "2026-06-10 18:00:00",
                                "第一笔备注",
                                "否"
                        ),
                        List.of(
                                "ORD-DUPLICATE",
                                "价格错误",
                                "2026-05-02 09:00:00",
                                "2026-06-11 18:00:00",
                                "错误列备注",
                                "否"
                        ),
                        List.of(
                                "ORD-DUPLICATE",
                                "250.00",
                                "2026-05-03 09:00:00",
                                "2026-06-12 18:00:00",
                                "最后一笔有效备注",
                                "加急"
                        )
                )
        );

        ImportWorkOrderResponse response = importService.importXlsx(file);

        WorkOrder workOrder = repository.findByOrderNo("ORD-DUPLICATE").orElseThrow();
        assertThat(response.createdCount()).isEqualTo(1);
        assertThat(response.updatedCount()).isZero();
        assertThat(response.errors()).hasSize(1);
        assertThat(response.errors().getFirst().row()).isEqualTo(3);
        assertThat(repository.findAll()).hasSize(1);
        assertThat(workOrder.getPrice()).isEqualByComparingTo("250.00");
        assertThat(workOrder.getEstimatedMinutes()).isEqualTo(180);
        assertThat(workOrder.isUrgent()).isTrue();
        assertThat(workOrder.getOrderTime()).isEqualTo(LocalDateTime.of(2026, 5, 3, 9, 0));
        assertThat(workOrder.getLatestShipTime()).isEqualTo(LocalDateTime.of(2026, 6, 12, 18, 0));
        assertThat(workOrder.getRemark()).isEqualTo("商家备注：最后一笔有效备注");
    }

    @Test
    void leavesExistingOrderUntouchedWhenReplacementRowHasAnError() throws Exception {
        WorkOrder existing = repository.save(new WorkOrder(
                "ORD-INVALID-REPLACEMENT",
                null,
                "旧备注",
                BigDecimal.valueOf(100),
                60,
                true,
                LocalDateTime.of(2026, 6, 20, 18, 0),
                LocalDateTime.of(2026, 5, 1, 9, 0)
        ));
        Long originalId = existing.getId();
        MockMultipartFile file = xlsxWithRows(
                List.of(
                        "订单编号",
                        "买家实付金额",
                        "订单付款时间",
                        "应发货时间",
                        "商家备注",
                        "备注标签"
                ),
                List.of(List.of(
                        "ORD-INVALID-REPLACEMENT",
                        "不是价格",
                        "2026-05-02 09:00:00",
                        "2026-06-21 18:00:00",
                        "不应写入的备注",
                        "否"
                ))
        );

        ImportWorkOrderResponse response = importService.importXlsx(file);

        WorkOrder workOrder = repository.findByOrderNo("ORD-INVALID-REPLACEMENT").orElseThrow();
        assertThat(response.createdCount()).isZero();
        assertThat(response.updatedCount()).isZero();
        assertThat(response.errors()).hasSize(1);
        assertThat(workOrder.getId()).isEqualTo(originalId);
        assertThat(workOrder.getPrice()).isEqualByComparingTo("100.00");
        assertThat(workOrder.getRemark()).isEqualTo("旧备注");
        assertThat(workOrder.getEstimatedMinutes()).isEqualTo(60);
        assertThat(workOrder.getActualMinutes()).isEqualTo(60);
        assertThat(workOrder.isUrgent()).isTrue();
        assertThat(workOrder.getLatestShipTime()).isEqualTo(LocalDateTime.of(2026, 6, 20, 18, 0));
        assertThat(workOrder.getOrderTime()).isEqualTo(LocalDateTime.of(2026, 5, 1, 9, 0));
    }

    @Test
    void leavesExistingOrderUntouchedWhenReplacementOrderTimeIsInvalid() throws Exception {
        WorkOrder existing = repository.save(new WorkOrder(
                "ORD-INVALID-ORDER-TIME",
                null,
                "旧备注",
                BigDecimal.valueOf(100),
                60,
                true,
                LocalDateTime.of(2026, 6, 20, 18, 0),
                LocalDateTime.of(2026, 5, 1, 9, 0)
        ));
        MockMultipartFile file = xlsxWithRows(
                List.of(
                        "订单编号",
                        "买家实付金额",
                        "订单付款时间",
                        "应发货时间",
                        "商家备注",
                        "备注标签"
                ),
                List.of(List.of(
                        "ORD-INVALID-ORDER-TIME",
                        "250.00",
                        "不是日期",
                        "2026-06-21 18:00:00",
                        "不应写入的备注",
                        "否"
                ))
        );

        ImportWorkOrderResponse response = importService.importXlsx(file);

        WorkOrder workOrder = repository.findByOrderNo("ORD-INVALID-ORDER-TIME").orElseThrow();
        assertThat(response.createdCount()).isZero();
        assertThat(response.updatedCount()).isZero();
        assertThat(response.errors()).singleElement().satisfies((error) -> {
            assertThat(error.row()).isEqualTo(2);
            assertThat(error.message()).isEqualTo(
                    "订单时间格式不正确，请使用 yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss"
            );
        });
        assertThat(workOrder.getId()).isEqualTo(existing.getId());
        assertThat(workOrder.getPrice()).isEqualByComparingTo("100.00");
        assertThat(workOrder.getRemark()).isEqualTo("旧备注");
        assertThat(workOrder.isUrgent()).isTrue();
        assertThat(workOrder.getLatestShipTime()).isEqualTo(LocalDateTime.of(2026, 6, 20, 18, 0));
        assertThat(workOrder.getOrderTime()).isEqualTo(LocalDateTime.of(2026, 5, 1, 9, 0));
    }

    @Test
    void updatesScheduledAndDoneOrdersWithoutChangingLifecycleSegmentsOrPauses() throws Exception {
        LocalDateTime scheduledStart = LocalDateTime.of(2026, 6, 10, 7, 0);
        LocalDateTime scheduledEnd = LocalDateTime.of(2026, 6, 10, 8, 0);
        WorkOrder scheduled = new WorkOrder(
                "ORD-SCHEDULED",
                null,
                "旧排程备注",
                BigDecimal.valueOf(100),
                60,
                false,
                LocalDateTime.of(2026, 6, 20, 18, 0),
                LocalDateTime.of(2026, 5, 1, 9, 0)
        );
        scheduled.schedule(scheduledStart, scheduledEnd, 60);
        repository.saveAndFlush(scheduled);
        WorkOrderSegment segment = segmentRepository.saveAndFlush(
                new WorkOrderSegment(scheduled, scheduledStart, scheduledEnd)
        );
        WorkOrderSegmentPause pause = new WorkOrderSegmentPause(
                segment,
                LocalDateTime.of(2026, 6, 10, 7, 20)
        );
        pause.resume(LocalDateTime.of(2026, 6, 10, 7, 30));
        pauseRepository.saveAndFlush(pause);

        LocalDateTime doneStart = LocalDateTime.of(2026, 6, 11, 7, 0);
        LocalDateTime doneEnd = LocalDateTime.of(2026, 6, 11, 9, 0);
        LocalDateTime completedAt = LocalDateTime.of(2026, 6, 11, 8, 45);
        WorkOrder done = new WorkOrder(
                "ORD-DONE",
                null,
                "旧完成备注",
                BigDecimal.valueOf(200),
                120,
                true,
                LocalDateTime.of(2026, 6, 21, 18, 0),
                LocalDateTime.of(2026, 5, 2, 9, 0)
        );
        done.schedule(doneStart, doneEnd, 120);
        done.markDone(completedAt);
        repository.saveAndFlush(done);
        WorkOrderSegment doneSegment = segmentRepository.saveAndFlush(
                new WorkOrderSegment(done, doneStart, doneEnd)
        );

        MockMultipartFile file = xlsxWithRows(
                List.of(
                        "订单编号",
                        "买家实付金额",
                        "订单付款时间",
                        "应发货时间",
                        "商家备注",
                        "备注标签"
                ),
                List.of(
                        List.of(
                                "ORD-SCHEDULED",
                                "500.00",
                                "2026-05-10 12:00:00",
                                "2026-06-09 23:59:59",
                                "新排程备注",
                                "加急"
                        ),
                        List.of(
                                "ORD-DONE",
                                "600.00",
                                "2026-05-11 12:00:00",
                                "2026-06-22 23:59:59",
                                "新完成备注",
                                "否"
                        )
                )
        );

        ImportWorkOrderResponse response = importService.importXlsx(file);

        WorkOrder updatedScheduled = repository.findByOrderNo("ORD-SCHEDULED").orElseThrow();
        WorkOrder updatedDone = repository.findByOrderNo("ORD-DONE").orElseThrow();
        assertThat(response.createdCount()).isZero();
        assertThat(response.updatedCount()).isEqualTo(2);
        assertThat(response.errors()).isEmpty();

        assertThat(updatedScheduled.getStatus()).isEqualTo(WorkOrderStatus.SCHEDULED);
        assertThat(updatedScheduled.getScheduledStart()).isEqualTo(scheduledStart);
        assertThat(updatedScheduled.getScheduledEnd()).isEqualTo(scheduledEnd);
        assertThat(updatedScheduled.getActualMinutes()).isEqualTo(60);
        assertThat(updatedScheduled.getCompletedAt()).isNull();
        assertThat(updatedScheduled.getEstimatedMinutes()).isEqualTo(300);
        assertThat(updatedScheduled.getLatestShipTime()).isEqualTo(LocalDateTime.of(2026, 6, 9, 23, 59, 59));
        assertThat(updatedScheduled.getRemark()).isEqualTo("商家备注：新排程备注");
        assertThat(updatedScheduled.isUrgent()).isTrue();

        assertThat(updatedDone.getStatus()).isEqualTo(WorkOrderStatus.DONE);
        assertThat(updatedDone.getScheduledStart()).isEqualTo(doneStart);
        assertThat(updatedDone.getScheduledEnd()).isEqualTo(doneEnd);
        assertThat(updatedDone.getActualMinutes()).isEqualTo(120);
        assertThat(updatedDone.getCompletedAt()).isEqualTo(completedAt);
        assertThat(updatedDone.getEstimatedMinutes()).isEqualTo(360);
        assertThat(updatedDone.getRemark()).isEqualTo("商家备注：新完成备注");
        assertThat(updatedDone.isUrgent()).isFalse();

        assertThat(segmentRepository.findByWorkOrderIdOrderByScheduledStartAscScheduledEndAscIdAsc(scheduled.getId()))
                .extracting(WorkOrderSegment::getId)
                .containsExactly(segment.getId());
        assertThat(segmentRepository.findByWorkOrderIdOrderByScheduledStartAscScheduledEndAscIdAsc(done.getId()))
                .extracting(WorkOrderSegment::getId)
                .containsExactly(doneSegment.getId());
        assertThat(pauseRepository.findByWorkOrderId(scheduled.getId()))
                .extracting(WorkOrderSegmentPause::getId)
                .containsExactly(pause.getId());
    }

    @Test
    void importsRealOrderColumnsByHeaderNameAndCombinesRemarks() throws Exception {
        MockMultipartFile file = xlsxWithRows(
                List.of("商家备注", "应发货时间", "订单付款时间", "买家留言", "订单编号", "备注标签", "买家实付金额"),
                List.of(List.of(
                        "29号发！！！蛋糕裙恢复尺寸200+加急100",
                        "子订单3304375611452199951： 2026-06-11 22:17前 ; ",
                        "2026-05-27 22:17:38",
                        "买家要保留裙襬",
                        "3304375611452180770",
                        "加急单",
                        "280.00"
                ))
        );

        ImportWorkOrderResponse response = importService.importXlsx(file);

        assertThat(response.createdCount()).as(response.errors().toString()).isEqualTo(1);
        assertThat(response.errors()).isEmpty();
        WorkOrder workOrder = repository.findAll().getFirst();
        assertThat(workOrder.getOrderNo()).isEqualTo("3304375611452180770");
        assertThat(workOrder.getPrice()).isEqualByComparingTo("280.00");
        assertThat(workOrder.getEstimatedMinutes()).isEqualTo(180);
        assertThat(workOrder.isUrgent()).isTrue();
        assertThat(workOrder.getOrderTime()).isEqualTo(LocalDateTime.of(2026, 5, 27, 22, 17, 38));
        assertThat(workOrder.getRemark()).isEqualTo("""
                买家留言：买家要保留裙襬
                商家备注：29号发！！！蛋糕裙恢复尺寸200+加急100""");
        assertThat(workOrder.getLatestShipTime()).isEqualTo(LocalDateTime.of(
                LocalDate.now(clock).getYear(),
                5,
                29,
                23,
                59,
                59
        ));
    }

    @Test
    void parsesMerchantRemarkMonthDayWithCurrentYear() throws Exception {
        MockMultipartFile file = xlsxWithRows(
                List.of("订单编号", "买家实付金额", "商家备注", "订单付款时间", "应发货时间"),
                List.of(List.of(
                        "ORD-MERCHANT-MONTH-DAY",
                        "460.00",
                        "5.22发！蝴蝶结泡泡袖恢复尺寸400元 加急100元",
                        "2026-05-21 14:38:39",
                        "子订单ORD-MERCHANT-MONTH-DAY： 2026-06-05 14:38前 ; "
                ))
        );

        ImportWorkOrderResponse response = importService.importXlsx(file);

        assertThat(response.createdCount()).as(response.errors().toString()).isEqualTo(1);
        assertThat(response.errors()).isEmpty();
        assertThat(repository.findAll().getFirst().getLatestShipTime()).isEqualTo(LocalDateTime.of(
                LocalDate.now(clock).getYear(),
                5,
                22,
                23,
                59,
                59
        ));
    }

    @Test
    void prefersMerchantRemarkShipDateOverBuyerMessage() throws Exception {
        MockMultipartFile file = xlsxWithRows(
                List.of("订单编号", "买家实付金额", "商家备注", "买家留言", "订单付款时间", "应发货时间"),
                List.of(List.of(
                        "ORD-MERCHANT-PRIORITY",
                        "100.00",
                        "5.22发",
                        "5.23发",
                        "2026-05-21 14:38:39",
                        "2026-06-05 14:38:00"
                ))
        );

        ImportWorkOrderResponse response = importService.importXlsx(file);

        assertThat(response.createdCount()).as(response.errors().toString()).isEqualTo(1);
        assertThat(response.errors()).isEmpty();
        assertThat(repository.findAll().getFirst().getLatestShipTime()).isEqualTo(LocalDateTime.of(
                LocalDate.now(clock).getYear(),
                5,
                22,
                23,
                59,
                59
        ));
    }

    @Test
    void usesBuyerMessageShipDateBeforeFallback() throws Exception {
        MockMultipartFile file = xlsxWithRows(
                List.of("订单编号", "买家实付金额", "商家备注", "买家留言", "订单付款时间", "应发货时间"),
                List.of(List.of(
                        "ORD-BUYER-MESSAGE-SHIP-DATE",
                        "100.00",
                        "一般备注",
                        "24号发",
                        "2026-05-21 14:38:39",
                        "2026-06-05 14:38:00"
                ))
        );

        ImportWorkOrderResponse response = importService.importXlsx(file);

        assertThat(response.createdCount()).as(response.errors().toString()).isEqualTo(1);
        assertThat(response.errors()).isEmpty();
        assertThat(repository.findAll().getFirst().getLatestShipTime()).isEqualTo(LocalDateTime.of(
                LocalDate.now(clock).getYear(),
                5,
                24,
                23,
                59,
                59
        ));
    }

    @Test
    void fallsBackToEarliestEmbeddedShipTimeWhenRemarksHaveNoShipDate() throws Exception {
        MockMultipartFile file = xlsxWithRows(
                List.of("订单编号", "买家实付金额", "商家备注", "买家留言", "应发货时间"),
                List.of(List.of(
                        "ORD-SHIP-TEXT",
                        "100.00",
                        "一般备注",
                        "一般留言",
                        "子订单A： 2026-06-11 22:17前 ; 子订单B： 2026-06-10 09:00前 ; "
                ))
        );

        ImportWorkOrderResponse response = importService.importXlsx(file);

        assertThat(response.createdCount()).isEqualTo(1);
        assertThat(response.errors()).isEmpty();
        assertThat(repository.findAll().getFirst().getLatestShipTime())
                .isEqualTo(LocalDateTime.of(2026, 6, 10, 9, 0));
    }

    @Test
    void importsOnlyPendingXiaohongshuOrdersAndReportsSkippedRows() throws Exception {
        MockMultipartFile file = xlsxWithRows(
                List.of(
                        "订单号",
                        "订单状态",
                        "小红书编码",
                        "用户应付金额(元)",
                        "支付时间",
                        "承诺发货时间",
                        "用户备注",
                        "包裹备注标记",
                        "包裹备注信息"
                ),
                List.of(
                        List.of(
                                "P100000000000000001", "已完成", "XHS-SKU", "700.00",
                                "2026-03-14 14:43:14", "2026-03-29 14:43:14", "", "红旗", "已完成订单"
                        ),
                        List.of(
                                "P100000000000000002", "配货中", "XHS-SKU", "500.00",
                                "2026-04-02 14:36:05", "2026-04-17 14:36:05", "", "", "配货中订单"
                        ),
                        List.of(
                                "P802335189951019482", "待配货", "XHS-SKU", "324.99",
                                "2026-08-15 16:39:54", "2026-08-30 16:39:54", "", "红旗", "待排备注"
                        ),
                        List.of(
                                "P100000000000000004", "已取消", "XHS-SKU", "", "", "", "", "", ""
                        )
                )
        );

        ImportWorkOrderResponse response = importService.importXlsx(file);

        assertThat(response.createdCount()).isEqualTo(1);
        assertThat(response.updatedCount()).isZero();
        assertThat(response.skippedCount()).isEqualTo(3);
        assertThat(response.errors()).isEmpty();
        assertThat(repository.findAll()).singleElement().satisfies((workOrder) -> {
            assertThat(workOrder.getOrderNo()).isEqualTo("P802335189951019482");
            assertThat(workOrder.getSource()).isEqualTo(WorkOrderSource.XIAOHONGSHU);
            assertThat(workOrder.getPrice()).isEqualByComparingTo("324.99");
            assertThat(workOrder.getOrderTime()).isEqualTo(LocalDateTime.of(2026, 8, 15, 16, 39, 54));
            assertThat(workOrder.getLatestShipTime()).isEqualTo(LocalDateTime.of(2026, 8, 30, 16, 39, 54));
            assertThat(workOrder.getRemark()).isEqualTo("商家备注：待排备注");
            assertThat(workOrder.isUrgent()).isFalse();
        });
    }

    @Test
    void appliesConfiguredUrgentTextWhenImportingXiaohongshuOrders() throws Exception {
        importFieldSettingsService.updateSettings(new UpdateImportFieldSettingsRequest(
                java.util.Arrays.stream(ImportFieldKey.values())
                        .map((fieldKey) -> new UpdateImportFieldSettingsRequest.FieldAliases(
                                fieldKey.getApiKey(),
                                List.of()
                        ))
                        .toList(),
                new UpdateImportFieldSettingsRequest.UrgentRules(List.of(
                        new UpdateImportFieldSettingsRequest.UrgentRule(
                                "红旗",
                                ImportUrgentMatchType.EXACT
                        )
                ))
        ));
        MockMultipartFile file = xlsxWithRows(
                List.of(
                        "订单号", "订单状态", "小红书编码", "用户应付金额(元)",
                        "承诺发货时间", "包裹备注标记"
                ),
                List.of(List.of(
                        "P802335189951019482", "待配货", "XHS-SKU", "324.99",
                        "2026-08-30 16:39:54", "红旗"
                ))
        );

        ImportWorkOrderResponse response = importService.importXlsx(file);

        assertThat(response.createdCount()).isEqualTo(1);
        assertThat(response.skippedCount()).isZero();
        assertThat(response.errors()).isEmpty();
        assertThat(repository.findAll().getFirst().isUrgent()).isTrue();
    }

    @Test
    void prefersConfiguredAliasWhenWorkbookAlsoContainsBuiltInAliasForSameField() throws Exception {
        importFieldSettingsService.updateSettings(new UpdateImportFieldSettingsRequest(
                java.util.Arrays.stream(ImportFieldKey.values())
                        .map((fieldKey) -> new UpdateImportFieldSettingsRequest.FieldAliases(
                                fieldKey.getApiKey(),
                                fieldKey == ImportFieldKey.PRICE
                                        ? List.of("商家应收金额(元)（支付金额）")
                                        : List.of()
                        ))
                        .toList(),
                new UpdateImportFieldSettingsRequest.UrgentRules(List.of())
        ));
        MockMultipartFile file = xlsxWithRows(
                List.of(
                        "订单号", "订单状态", "小红书编码", "用户应付金额(元)",
                        "商家应收金额(元)（支付金额）", "承诺发货时间"
                ),
                List.of(List.of(
                        "P802335189951019482", "待配货", "XHS-SKU", "324.99",
                        "400.00", "2026-08-30 16:39:54"
                ))
        );

        ImportWorkOrderResponse response = importService.importXlsx(file);

        assertThat(response.createdCount()).isEqualTo(1);
        assertThat(response.errors()).isEmpty();
        assertThat(repository.findAll().getFirst().getPrice()).isEqualByComparingTo("400.00");
    }

    @Test
    void rejectsMultipleBuiltInAliasesEvenWhenConfiguredAliasAppearsFirst() throws Exception {
        importFieldSettingsService.updateSettings(new UpdateImportFieldSettingsRequest(
                java.util.Arrays.stream(ImportFieldKey.values())
                        .map((fieldKey) -> new UpdateImportFieldSettingsRequest.FieldAliases(
                                fieldKey.getApiKey(),
                                fieldKey == ImportFieldKey.PRICE ? List.of("结算金额") : List.of()
                        ))
                        .toList(),
                new UpdateImportFieldSettingsRequest.UrgentRules(List.of())
        ));
        MockMultipartFile file = xlsxWithRows(
                List.of("订单编号", "结算金额", "用户应付金额(元)", "买家实付金额", "应发货时间"),
                List.of(List.of("ORD-1", "300.00", "200.00", "100.00", "2026-08-30 16:39:54"))
        );

        assertThatThrownBy(() -> importService.importXlsx(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("XLSX 字段「用户应付金额(元)」与「买家实付金额」同时映射到订单价格");
        assertThat(repository.findAll()).isEmpty();
    }

    @Test
    void usesSelectedOrderNumberHeaderWhenDetectingSource() throws Exception {
        importFieldSettingsService.updateSettings(new UpdateImportFieldSettingsRequest(
                java.util.Arrays.stream(ImportFieldKey.values())
                        .map((fieldKey) -> new UpdateImportFieldSettingsRequest.FieldAliases(
                                fieldKey.getApiKey(),
                                fieldKey == ImportFieldKey.ORDER_NO ? List.of("主订单号") : List.of()
                        ))
                        .toList(),
                new UpdateImportFieldSettingsRequest.UrgentRules(List.of())
        ));
        MockMultipartFile file = xlsxWithRows(
                List.of("主订单号", "订单号", "买家实付金额", "应发货时间"),
                List.of(List.of("P802335189951019482", "TB-IGNORED", "100.00", "2026-08-30 16:39:54"))
        );

        ImportWorkOrderResponse response = importService.importXlsx(file);

        assertThat(response.createdCount()).isEqualTo(1);
        assertThat(response.errors()).isEmpty();
        assertThat(repository.findAll()).singleElement().satisfies((workOrder) -> {
            assertThat(workOrder.getOrderNo()).isEqualTo("P802335189951019482");
            assertThat(workOrder.getSource()).isEqualTo(WorkOrderSource.QIANNIU);
        });
    }

    @Test
    void detectsXiaohongshuFromSelectedOrderNumberHeaderAndPNumber() throws Exception {
        MockMultipartFile file = xlsxWithRows(
                List.of("订单号", "订单状态", "用户应付金额(元)", "承诺发货时间"),
                List.of(List.of(
                        "P802335189951019482", "待配货", "324.99", "2026-08-30 16:39:54"
                ))
        );

        ImportWorkOrderResponse response = importService.importXlsx(file);

        assertThat(response.createdCount()).isEqualTo(1);
        assertThat(response.errors()).isEmpty();
        assertThat(repository.findAll().getFirst().getSource()).isEqualTo(WorkOrderSource.XIAOHONGSHU);
    }

    @Test
    void rejectsXiaohongshuWorkbookWithoutOrderStatusHeader() throws Exception {
        MockMultipartFile file = xlsxWithRows(
                List.of("订单号", "小红书编码", "用户应付金额(元)", "承诺发货时间"),
                List.of(List.of(
                        "P802335189951019482", "XHS-SKU", "324.99", "2026-08-30 16:39:54"
                ))
        );

        assertThatThrownBy(() -> importService.importXlsx(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("小红书 XLSX 缺少订单状态字段");
    }

    @Test
    void reportsBlankXiaohongshuOrderStatusAsRowError() throws Exception {
        MockMultipartFile file = xlsxWithRows(
                List.of(
                        "订单号", "订单状态", "小红书编码", "用户应付金额(元)", "承诺发货时间"
                ),
                List.of(List.of(
                        "P802335189951019482", "", "XHS-SKU", "324.99", "2026-08-30 16:39:54"
                ))
        );

        ImportWorkOrderResponse response = importService.importXlsx(file);

        assertThat(response.createdCount()).isZero();
        assertThat(response.skippedCount()).isZero();
        assertThat(response.errors()).singleElement().satisfies((error) -> {
            assertThat(error.row()).isEqualTo(2);
            assertThat(error.message()).isEqualTo("小红书订单状态不可为空");
        });
    }

    @Test
    void rollsBackEarlierRowsWhenExistingOrderBelongsToAnotherSource() throws Exception {
        repository.save(new WorkOrder(
                "P999999999999999999",
                null,
                "existing",
                new BigDecimal("100.00"),
                60,
                false,
                LocalDateTime.of(2026, 9, 1, 18, 0),
                null
        ));
        MockMultipartFile file = xlsxWithRows(
                List.of(
                        "订单号", "订单状态", "小红书编码", "用户应付金额(元)", "承诺发货时间"
                ),
                List.of(
                        List.of(
                                "P111111111111111111", "待配货", "XHS-SKU", "200.00",
                                "2026-08-30 16:39:54"
                        ),
                        List.of(
                                "P999999999999999999", "待配货", "XHS-SKU", "300.00",
                                "2026-08-30 16:39:54"
                        )
                )
        );

        assertThatThrownBy(() -> importService.importXlsx(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("订单编号 P999999999999999999 已属于其他订单来源");
        assertThat(repository.findAll())
                .extracting(WorkOrder::getOrderNo)
                .containsExactly("P999999999999999999");
    }

    @Test
    void rejectsWorkbookWhenMultipleHeadersMapToTheSameField() throws Exception {
        MockMultipartFile file = xlsxWithRows(
                List.of("订单编号", "订单号", "买家实付金额", "应发货时间"),
                List.of(List.of("ORD-1", "ORD-2", "100.00", "2026-08-30 16:39:54"))
        );

        assertThatThrownBy(() -> importService.importXlsx(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("XLSX 字段「订单编号」与「订单号」同时映射到订单编号");
        assertThat(repository.findAll()).isEmpty();
    }

    private MockMultipartFile xlsxWithOneOrder(String orderNo, BigDecimal price, LocalDateTime latestShipDate) throws Exception {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("orders");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("订单编号");
            header.createCell(1).setCellValue("订单价格");
            header.createCell(2).setCellValue("最晚发货日期");

            CreationHelper creationHelper = workbook.getCreationHelper();
            CellStyle dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(creationHelper.createDataFormat().getFormat("yyyy-mm-dd"));

            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue(orderNo);
            row.createCell(1).setCellValue(price.doubleValue());
            row.createCell(2).setCellValue(latestShipDate);
            row.getCell(2).setCellStyle(dateStyle);

            workbook.write(outputStream);

            return new MockMultipartFile(
                    "file",
                    "orders.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    outputStream.toByteArray()
            );
        }
    }

    private MockMultipartFile xlsxWithRows(List<String> headers, List<List<String>> rows) throws Exception {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("orders");
            Row header = sheet.createRow(0);

            for (int index = 0; index < headers.size(); index++) {
                header.createCell(index).setCellValue(headers.get(index));
            }

            for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                Row row = sheet.createRow(rowIndex + 1);
                List<String> values = rows.get(rowIndex);

                for (int cellIndex = 0; cellIndex < values.size(); cellIndex++) {
                    row.createCell(cellIndex).setCellValue(values.get(cellIndex));
                }
            }

            workbook.write(outputStream);

            return new MockMultipartFile(
                    "file",
                    "orders.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    outputStream.toByteArray()
            );
        }
    }
}
