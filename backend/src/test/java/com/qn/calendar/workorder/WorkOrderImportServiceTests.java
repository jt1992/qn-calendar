package com.qn.calendar.workorder;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.qn.calendar.settings.dto.UpdateAppSettingsRequest;
import com.qn.calendar.settings.repository.AppSettingRepository;
import com.qn.calendar.settings.service.AppSettingsService;
import com.qn.calendar.workorder.dto.ImportWorkOrderResponse;
import com.qn.calendar.workorder.entity.WorkOrder;
import com.qn.calendar.workorder.repository.WorkOrderRepository;
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
    private AppSettingRepository appSettingRepository;

    @Autowired
    private AppSettingsService appSettingsService;

    @BeforeEach
    void setUp() {
        segmentRepository.deleteAll();
        repository.deleteAll();
        appSettingRepository.deleteAll();
    }

    @Test
    void importsRequiredChineseColumnsAndTreatsDateOnlyDeadlineAsEndOfDay() throws Exception {
        MockMultipartFile file = xlsxWithOneOrder("ORD-001", BigDecimal.valueOf(250), LocalDateTime.of(2026, 6, 9, 0, 0));

        ImportWorkOrderResponse response = importService.importXlsx(file);

        WorkOrder workOrder = repository.findAll().getFirst();
        assertThat(response.createdCount()).as(response.errors().toString()).isEqualTo(1);
        assertThat(response.skippedCount()).isZero();
        assertThat(response.errors()).isEmpty();
        assertThat(workOrder.getOrderNo()).isEqualTo("ORD-001");
        assertThat(workOrder.getEstimatedMinutes()).isEqualTo(180);
        assertThat(workOrder.getActualMinutes()).isEqualTo(180);
        assertThat(workOrder.getLatestShipTime()).isEqualTo(LocalDateTime.of(2026, 6, 9, 23, 59, 59));
    }

    @Test
    void skipsDuplicateOrderNumbersOnRepeatedImport() throws Exception {
        MockMultipartFile file = xlsxWithOneOrder("ORD-002", BigDecimal.valueOf(100), LocalDateTime.of(2026, 6, 10, 0, 0));

        importService.importXlsx(file);
        ImportWorkOrderResponse secondResponse = importService.importXlsx(file);

        assertThat(secondResponse.createdCount()).isZero();
        assertThat(secondResponse.skippedCount()).isEqualTo(1);
        assertThat(repository.findAll()).hasSize(1);
    }

    @Test
    void usesSavedHourlyBaseAmountWhenImportingNewOrders() throws Exception {
        appSettingsService.updateSettings(new UpdateAppSettingsRequest(BigDecimal.valueOf(200)));
        MockMultipartFile file = xlsxWithOneOrder("ORD-BASE-AMOUNT", BigDecimal.valueOf(250), LocalDateTime.of(2026, 6, 10, 0, 0));

        ImportWorkOrderResponse response = importService.importXlsx(file);

        assertThat(response.createdCount()).as(response.errors().toString()).isEqualTo(1);
        assertThat(response.errors()).isEmpty();
        WorkOrder workOrder = repository.findAll().getFirst();
        assertThat(workOrder.getEstimatedMinutes()).isEqualTo(120);
        assertThat(workOrder.getActualMinutes()).isEqualTo(120);
    }

    @Test
    void backfillsMissingOrderTimeWhenExistingOrderIsImportedAgain() throws Exception {
        repository.save(new WorkOrder(
                "ORD-BACKFILL",
                BigDecimal.valueOf(100),
                60,
                false,
                LocalDateTime.of(2026, 6, 10, 18, 0)
        ));
        MockMultipartFile file = xlsxWithRows(
                List.of("订单编号", "买家实付金额", "订单付款时间", "应发货时间"),
                List.of(List.of(
                        "ORD-BACKFILL",
                        "100.00",
                        "2026-04-21 12:30:00",
                        "2026-04-23 23:59:59"
                ))
        );

        ImportWorkOrderResponse response = importService.importXlsx(file);

        assertThat(response.createdCount()).isZero();
        assertThat(response.skippedCount()).isEqualTo(1);
        assertThat(repository.findAll()).hasSize(1);
        assertThat(repository.findAll().getFirst().getOrderTime())
                .isEqualTo(LocalDateTime.of(2026, 4, 21, 12, 30));
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
                LocalDate.now().getYear(),
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
                LocalDate.now().getYear(),
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
                LocalDate.now().getYear(),
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
                LocalDate.now().getYear(),
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
