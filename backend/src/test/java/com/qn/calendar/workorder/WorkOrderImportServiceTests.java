package com.qn.calendar.workorder;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.qn.calendar.workorder.dto.ImportWorkOrderResponse;

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

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    @Test
    void importsRequiredChineseColumnsAndTreatsDateOnlyDeadlineAsEndOfDay() throws Exception {
        MockMultipartFile file = xlsxWithOneOrder("ORD-001", BigDecimal.valueOf(250), LocalDateTime.of(2026, 6, 9, 0, 0));

        ImportWorkOrderResponse response = importService.importXlsx(file);

        WorkOrder workOrder = repository.findAll().getFirst();
        assertThat(response.createdCount()).isEqualTo(1);
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

    private MockMultipartFile xlsxWithOneOrder(String orderNo, BigDecimal price, LocalDateTime latestShipDate) throws Exception {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("orders");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("訂單編號");
            header.createCell(1).setCellValue("訂單價格");
            header.createCell(2).setCellValue("最晚發貨日期");

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
}
