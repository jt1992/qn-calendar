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
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.qn.calendar.settings.service.AppSettingsService;
import com.qn.calendar.workorder.dto.ImportRowError;
import com.qn.calendar.workorder.dto.ImportWorkOrderResponse;
import com.qn.calendar.workorder.entity.WorkOrder;
import com.qn.calendar.workorder.repository.WorkOrderRepository;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
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
            DateTimeFormatter.ofPattern("yyyy-M-d H:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-M-d H:mm"),
            DateTimeFormatter.ofPattern("yyyy/M/d H:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy/M/d H:mm")
    );

    private static final List<DateTimeFormatter> DATE_FORMATTERS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("yyyy/M/d"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd")
    );

    private static final Pattern EMBEDDED_SHIP_TIME_PATTERN = Pattern.compile(
            "(\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}\\s+\\d{1,2}:\\d{2}(?::\\d{2})?)\\s*前"
    );
    private static final Pattern MERCHANT_MONTH_DAY_PATTERN = Pattern.compile(
            "(?<!\\d)(\\d{1,2})[./月](\\d{1,2})(?:\\s*/\\s*(\\d{1,2}))?(?:号|日)?[^\\n，,。；;]{0,8}(?:发|發|收到)"
    );
    private static final Pattern MERCHANT_DAY_ONLY_BEFORE_KEYWORD_PATTERN = Pattern.compile(
            "(?<!\\d)(\\d{1,2})号[^\\n，,。；;]{0,8}(?:发|發|收到)"
    );
    private static final Pattern MERCHANT_DAY_ONLY_AFTER_KEYWORD_PATTERN = Pattern.compile(
            "(?:发|發)[^\\n，,。；;]{0,8}(\\d{1,2})号"
    );

    private final WorkOrderRepository repository;
    private final AppSettingsService appSettingsService;
    private final DataFormatter formatter = new DataFormatter(Locale.CHINA);

    public WorkOrderImportService(
            WorkOrderRepository repository,
            AppSettingsService appSettingsService
    ) {
        this.repository = repository;
        this.appSettingsService = appSettingsService;
    }

    @Transactional
    public ImportWorkOrderResponse importXlsx(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("XLSX 文件不可为空");
        }

        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getNumberOfSheets() > 0 ? workbook.getSheetAt(0) : null;

            if (sheet == null || sheet.getPhysicalNumberOfRows() == 0) {
                throw new IllegalArgumentException("XLSX 至少需要一列表头");
            }

            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            Map<String, Integer> headers = readHeaders(sheet, evaluator);
            validateRequiredHeaders(headers);

            int createdCount = 0;
            int skippedCount = 0;
            BigDecimal estimatedHourlyBaseAmount = appSettingsService.getEstimatedHourlyBaseAmount();
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
                        throw new IllegalArgumentException("订单编号不可为空");
                    }

                    if (!seenOrderNumbers.add(orderNo)) {
                        skippedCount++;
                        continue;
                    }

                    Optional<WorkOrder> existingWorkOrder = repository.findByOrderNo(orderNo);

                    if (existingWorkOrder.isPresent()) {
                        existingWorkOrder.get().updateOrderTimeIfMissing(readOrderTime(row, headers, evaluator));
                        skippedCount++;
                        continue;
                    }

                    BigDecimal price = readPrice(row, headers.get("price"), evaluator);
                    boolean urgent = headers.containsKey("urgent")
                            && readUrgent(row, headers.get("urgent"), evaluator);
                    String buyerMessage = readStringIfPresent(row, headers.get("buyerMessage"), evaluator);
                    String merchantRemark = readStringIfPresent(row, headers.get("merchantRemark"), evaluator);
                    String remark = buildRemark(buyerMessage, merchantRemark);
                    LocalDateTime orderTime = readOrderTime(row, headers, evaluator);
                    LocalDateTime latestShipTime = readLatestShipTime(
                            row,
                            headers,
                            evaluator,
                            orderTime
                    );
                    int estimatedMinutes = calculateEstimatedMinutes(price, estimatedHourlyBaseAmount);

                    repository.save(new WorkOrder(
                            orderNo,
                            null,
                            remark,
                            price,
                            estimatedMinutes,
                            urgent,
                            latestShipTime,
                            orderTime
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
            throw new IllegalArgumentException("无法读取 XLSX 文件");
        }
    }

    private Map<String, Integer> readHeaders(Sheet sheet, FormulaEvaluator evaluator) {
        Row headerRow = sheet.getRow(0);

        if (headerRow == null) {
            throw new IllegalArgumentException("XLSX 第一列必须是表头");
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
            throw new IllegalArgumentException("XLSX 缺少订单编号字段");
        }

        if (!headers.containsKey("price")) {
            throw new IllegalArgumentException("XLSX 缺少订单价格字段");
        }

        if (!headers.containsKey("latestShipTime")) {
            throw new IllegalArgumentException("XLSX 缺少最晚发货日期字段");
        }
    }

    private String canonicalHeader(String header) {
        String normalized = header == null
                ? ""
                : header.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s_-]", "");

        return switch (normalized) {
            case "orderno", "訂單編號", "订单编号" -> "orderNo";
            case "price", "orderprice", "amount", "buyerpaidamount", "訂單價格", "订单价格",
                    "買家實付金額", "买家实付金额", "價格", "价格", "金額", "金额" -> "price";
            case "urgent", "isurgent", "加急", "急件", "備註標籤", "备注标签" -> "urgent";
            case "buyermessage", "buyerremark", "買家留言", "买家留言" -> "buyerMessage";
            case "merchantremark", "sellerremark", "商家備註", "商家备注" -> "merchantRemark";
            case "paidat", "paymenttime", "orderpaidtime", "ordertime", "ordercreatedtime",
                    "訂單付款時間", "订单付款时间", "訂單時間", "订单时间", "下單時間", "下单时间",
                    "下單日期", "下单日期", "付款時間", "付款时间", "支付時間", "支付时间" -> "paidAt";
            case "latestshipdate", "latestshiptime", "latestshippingtime", "deadline",
                    "應發貨時間", "应发货时间", "最晚發貨日期", "最晚发货日期",
                    "最晚發貨時間", "最晚发货时间" -> "latestShipTime";
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

    private String readStringIfPresent(Row row, Integer index, FormulaEvaluator evaluator) {
        if (index == null) {
            return "";
        }

        return readString(row, index, evaluator);
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
            throw new IllegalArgumentException("价格不可为空");
        }

        try {
            BigDecimal price = new BigDecimal(value);

            if (price.signum() < 0) {
                throw new IllegalArgumentException("价格不可为负数");
            }

            return price;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("价格格式不正确");
        }
    }

    private boolean readUrgent(Row row, int index, FormulaEvaluator evaluator) {
        String value = readString(row, index, evaluator).toLowerCase(Locale.ROOT);
        return Set.of("true", "yes", "y", "1", "是", "加急", "急件").contains(value)
                || value.contains("加急")
                || value.contains("急件");
    }

    private String buildRemark(String buyerMessage, String merchantRemark) {
        List<String> parts = new java.util.ArrayList<>();

        if (!buyerMessage.isBlank()) {
            parts.add("买家留言：" + buyerMessage);
        }

        if (!merchantRemark.isBlank()) {
            parts.add("商家备注：" + merchantRemark);
        }

        return parts.isEmpty() ? "无任何备注" : String.join("\n", parts);
    }

    private LocalDateTime readOrderTime(Row row, Map<String, Integer> headers, FormulaEvaluator evaluator) {
        return headers.containsKey("paidAt")
                ? readOptionalDateTime(row, headers.get("paidAt"), evaluator)
                : null;
    }

    private LocalDateTime readLatestShipTime(
            Row row,
            Map<String, Integer> headers,
            FormulaEvaluator evaluator,
            LocalDateTime orderTime
    ) {
        String merchantRemark = readStringIfPresent(row, headers.get("merchantRemark"), evaluator);
        Optional<LocalDate> merchantShipDate = parseMerchantShipDate(merchantRemark, orderTime);

        if (merchantShipDate.isPresent()) {
            return merchantShipDate.get().atTime(END_OF_DAY);
        }

        return readLatestShipTimeFallback(row, headers.get("latestShipTime"), evaluator);
    }

    private LocalDateTime readLatestShipTimeFallback(Row row, int index, FormulaEvaluator evaluator) {
        Cell cell = row.getCell(index, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);

        if (cell == null) {
            throw new IllegalArgumentException("最晚发货日期不可为空");
        }

        if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            LocalDateTime dateTime = cell.getLocalDateTimeCellValue();
            return isDateOnly(dateTime) ? dateTime.toLocalDate().atTime(END_OF_DAY) : dateTime;
        }

        String value = formatter.formatCellValue(cell, evaluator).trim();

        if (value.isBlank()) {
            throw new IllegalArgumentException("最晚发货日期不可为空");
        }

        Optional<LocalDateTime> embeddedShipTime = parseEmbeddedShipTime(value);

        if (embeddedShipTime.isPresent()) {
            return embeddedShipTime.get();
        }

        Optional<LocalDateTime> parsedDateTime = parseDateTimeText(value);

        if (parsedDateTime.isPresent()) {
            return parsedDateTime.get();
        }

        throw new IllegalArgumentException("最晚发货日期格式不正确，请使用 yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss");
    }

    private Optional<LocalDateTime> parseEmbeddedShipTime(String value) {
        Matcher matcher = EMBEDDED_SHIP_TIME_PATTERN.matcher(value);
        LocalDateTime earliest = null;

        while (matcher.find()) {
            Optional<LocalDateTime> parsed = parseDateTimeText(matcher.group(1));

            if (parsed.isPresent() && (earliest == null || parsed.get().isBefore(earliest))) {
                earliest = parsed.get();
            }
        }

        return Optional.ofNullable(earliest);
    }

    private Optional<LocalDateTime> parseDateTimeText(String value) {
        for (DateTimeFormatter dateTimeFormatter : DATE_TIME_FORMATTERS) {
            try {
                return Optional.of(LocalDateTime.parse(value, dateTimeFormatter));
            } catch (DateTimeParseException ignored) {
                // Try the next supported format.
            }
        }

        for (DateTimeFormatter dateFormatter : DATE_FORMATTERS) {
            try {
                return Optional.of(LocalDate.parse(value, dateFormatter).atTime(END_OF_DAY));
            } catch (DateTimeParseException ignored) {
                // Try the next supported format.
            }
        }

        return Optional.empty();
    }

    private LocalDateTime readOptionalDateTime(Row row, int index, FormulaEvaluator evaluator) {
        Cell cell = row.getCell(index, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);

        if (cell == null) {
            return null;
        }

        if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            return cell.getLocalDateTimeCellValue();
        }

        String value = formatter.formatCellValue(cell, evaluator).trim();

        if (value.isBlank()) {
            return null;
        }

        return parseDateTimeText(value).orElse(null);
    }

    private Optional<LocalDate> parseMerchantShipDate(String merchantRemark, LocalDateTime paidAt) {
        if (merchantRemark == null || merchantRemark.isBlank()) {
            return Optional.empty();
        }

        int currentYear = LocalDate.now().getYear();
        Matcher monthDayMatcher = MERCHANT_MONTH_DAY_PATTERN.matcher(merchantRemark);

        if (monthDayMatcher.find()) {
            int month = Integer.parseInt(monthDayMatcher.group(1));
            int day = Integer.parseInt(monthDayMatcher.group(3) == null
                    ? monthDayMatcher.group(2)
                    : monthDayMatcher.group(3));
            return safeDate(currentYear, month, day);
        }

        Integer paidMonth = paidAt == null ? null : paidAt.getMonthValue();

        if (paidMonth == null) {
            return Optional.empty();
        }

        Matcher dayBeforeKeywordMatcher = MERCHANT_DAY_ONLY_BEFORE_KEYWORD_PATTERN.matcher(merchantRemark);

        if (dayBeforeKeywordMatcher.find()) {
            return safeDate(currentYear, paidMonth, Integer.parseInt(dayBeforeKeywordMatcher.group(1)));
        }

        Matcher dayAfterKeywordMatcher = MERCHANT_DAY_ONLY_AFTER_KEYWORD_PATTERN.matcher(merchantRemark);

        if (dayAfterKeywordMatcher.find()) {
            return safeDate(currentYear, paidMonth, Integer.parseInt(dayAfterKeywordMatcher.group(1)));
        }

        return Optional.empty();
    }

    private Optional<LocalDate> safeDate(int year, int month, int day) {
        try {
            return Optional.of(LocalDate.of(year, month, day));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private int calculateEstimatedMinutes(BigDecimal price, BigDecimal estimatedHourlyBaseAmount) {
        return price.divide(estimatedHourlyBaseAmount, 0, RoundingMode.CEILING)
                .multiply(BigDecimal.valueOf(60))
                .intValue();
    }

    private boolean isDateOnly(LocalDateTime dateTime) {
        return dateTime.toLocalTime().equals(LocalTime.MIDNIGHT);
    }
}
