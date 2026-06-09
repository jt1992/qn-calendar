package com.qn.calendar.workorder.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.qn.calendar.workorder.dto.ImportRowError;
import com.qn.calendar.workorder.dto.ImportWorkOrderResponse;
import com.qn.calendar.workorder.entity.WorkOrder;
import com.qn.calendar.workorder.repository.WorkOrderRepository;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class WorkOrderImportService {

    private static final LocalTime END_OF_DAY = LocalTime.of(23, 59, 59);

    private static final List<DateTimeFormatter> DATE_TIME_FORMATTERS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy/M/d H:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy/M/d H:mm")
    );

    private static final List<DateTimeFormatter> DATE_FORMATTERS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("yyyy/M/d"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd")
    );

    private final WorkOrderRepository repository;
    private final DataFormatter formatter = new DataFormatter(Locale.TAIWAN);

    public WorkOrderImportService(WorkOrderRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public ImportWorkOrderResponse importXlsx(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("XLSX 檔案不可為空");
        }

        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getNumberOfSheets() > 0 ? workbook.getSheetAt(0) : null;

            if (sheet == null || sheet.getPhysicalNumberOfRows() == 0) {
                throw new IllegalArgumentException("XLSX 至少需要一列表頭");
            }

            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            Map<String, Integer> headers = readHeaders(sheet, evaluator);
            validateRequiredHeaders(headers);

            int createdCount = 0;
            int skippedCount = 0;
            List<ImportRowError> errors = new java.util.ArrayList<>();
            Set<String> seenOrderNumbers = new HashSet<>();

            for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);

                if (isRowEmpty(row, evaluator)) {
                    continue;
                }

                int rowNumber = rowIndex + 1;

                try {
                    String orderNo = readString(row, headers.get("orderNo"), evaluator);

                    if (orderNo.isBlank()) {
                        throw new IllegalArgumentException("訂單編號不可為空");
                    }

                    if (!seenOrderNumbers.add(orderNo) || repository.existsByOrderNo(orderNo)) {
                        skippedCount++;
                        continue;
                    }

                    BigDecimal price = readPrice(row, headers.get("price"), evaluator);
                    boolean urgent = headers.containsKey("urgent")
                            && readUrgent(row, headers.get("urgent"), evaluator);
                    LocalDateTime latestShipTime = readLatestShipTime(
                            row,
                            headers.get("latestShipTime"),
                            evaluator
                    );
                    int estimatedMinutes = calculateEstimatedMinutes(price);

                    repository.save(new WorkOrder(
                            orderNo,
                            price,
                            estimatedMinutes,
                            urgent,
                            latestShipTime
                    ));
                    createdCount++;
                } catch (DataIntegrityViolationException exception) {
                    skippedCount++;
                } catch (RuntimeException exception) {
                    errors.add(new ImportRowError(rowNumber, exception.getMessage()));
                }
            }

            return new ImportWorkOrderResponse(createdCount, skippedCount, errors);
        } catch (IOException exception) {
            throw new IllegalArgumentException("無法讀取 XLSX 檔案");
        }
    }

    private Map<String, Integer> readHeaders(Sheet sheet, FormulaEvaluator evaluator) {
        Row headerRow = sheet.getRow(0);

        if (headerRow == null) {
            throw new IllegalArgumentException("XLSX 第一列必須是表頭");
        }

        Map<String, Integer> headers = new HashMap<>();

        for (Cell cell : headerRow) {
            String header = canonicalHeader(formatter.formatCellValue(cell, evaluator));

            if (!header.isBlank()) {
                headers.put(header, cell.getColumnIndex());
            }
        }

        return headers;
    }

    private void validateRequiredHeaders(Map<String, Integer> headers) {
        if (!headers.containsKey("orderNo")) {
            throw new IllegalArgumentException("XLSX 缺少訂單編號欄位");
        }

        if (!headers.containsKey("price")) {
            throw new IllegalArgumentException("XLSX 缺少訂單價格欄位");
        }

        if (!headers.containsKey("latestShipTime")) {
            throw new IllegalArgumentException("XLSX 缺少最晚發貨日期欄位");
        }
    }

    private String canonicalHeader(String header) {
        String normalized = header == null
                ? ""
                : header.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s_-]", "");

        return switch (normalized) {
            case "orderno", "訂單編號", "订单编号" -> "orderNo";
            case "price", "orderprice", "amount", "訂單價格", "订单价格", "價格", "价格", "金額", "金额" -> "price";
            case "urgent", "isurgent", "加急", "急件" -> "urgent";
            case "latestshipdate", "latestshiptime", "latestshippingtime", "deadline", "最晚發貨日期", "最晚发货日期", "最晚發貨時間", "最晚发货时间" -> "latestShipTime";
            default -> "";
        };
    }

    private boolean isRowEmpty(Row row, FormulaEvaluator evaluator) {
        if (row == null) {
            return true;
        }

        for (Cell cell : row) {
            if (!formatter.formatCellValue(cell, evaluator).isBlank()) {
                return false;
            }
        }

        return true;
    }

    private String readString(Row row, int index, FormulaEvaluator evaluator) {
        Cell cell = row.getCell(index, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);

        if (cell == null) {
            return "";
        }

        return formatter.formatCellValue(cell, evaluator).trim();
    }

    private BigDecimal readPrice(Row row, int index, FormulaEvaluator evaluator) {
        String value = readString(row, index, evaluator)
                .replace(",", "")
                .replace("NT$", "")
                .replace("¥", "")
                .replace("￥", "")
                .replace("$", "")
                .trim();

        if (value.isBlank()) {
            throw new IllegalArgumentException("價格不可為空");
        }

        try {
            BigDecimal price = new BigDecimal(value);

            if (price.signum() < 0) {
                throw new IllegalArgumentException("價格不可為負數");
            }

            return price;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("價格格式不正確");
        }
    }

    private boolean readUrgent(Row row, int index, FormulaEvaluator evaluator) {
        String value = readString(row, index, evaluator).toLowerCase(Locale.ROOT);
        return Set.of("true", "yes", "y", "1", "是", "加急", "急件").contains(value);
    }

    private LocalDateTime readLatestShipTime(Row row, int index, FormulaEvaluator evaluator) {
        Cell cell = row.getCell(index, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);

        if (cell == null) {
            throw new IllegalArgumentException("最晚發貨日期不可為空");
        }

        if (DateUtil.isCellDateFormatted(cell)) {
            LocalDateTime dateTime = cell.getLocalDateTimeCellValue();
            return isDateOnly(dateTime) ? dateTime.toLocalDate().atTime(END_OF_DAY) : dateTime;
        }

        String value = formatter.formatCellValue(cell, evaluator).trim();

        if (value.isBlank()) {
            throw new IllegalArgumentException("最晚發貨日期不可為空");
        }

        for (DateTimeFormatter dateTimeFormatter : DATE_TIME_FORMATTERS) {
            try {
                return LocalDateTime.parse(value, dateTimeFormatter);
            } catch (DateTimeParseException ignored) {
                // Try the next supported format.
            }
        }

        for (DateTimeFormatter dateFormatter : DATE_FORMATTERS) {
            try {
                return LocalDate.parse(value, dateFormatter).atTime(END_OF_DAY);
            } catch (DateTimeParseException ignored) {
                // Try the next supported format.
            }
        }

        throw new IllegalArgumentException("最晚發貨日期格式不正確，請使用 yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss");
    }

    private int calculateEstimatedMinutes(BigDecimal price) {
        return price.divide(BigDecimal.valueOf(100), 0, RoundingMode.CEILING)
                .multiply(BigDecimal.valueOf(60))
                .intValue();
    }

    private boolean isDateOnly(LocalDateTime dateTime) {
        return dateTime.toLocalTime().equals(LocalTime.MIDNIGHT);
    }
}
