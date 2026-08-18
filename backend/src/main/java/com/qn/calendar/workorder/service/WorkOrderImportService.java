package com.qn.calendar.workorder.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.qn.calendar.settings.entity.OrderSourceOption;
import com.qn.calendar.settings.entity.RemarkTagDefinition;
import com.qn.calendar.settings.model.ImportFieldKey;
import com.qn.calendar.settings.model.ImportFieldSettingsSnapshot;
import com.qn.calendar.settings.service.AppSettingsService;
import com.qn.calendar.settings.service.ImportFieldSettingsService;
import com.qn.calendar.workorder.constant.WorkOrderSource;
import com.qn.calendar.workorder.dto.CreateWorkOrderRequest;
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
    private static final Pattern REMARK_MONTH_DAY_PATTERN = Pattern.compile(
            "(?<!\\d)(\\d{1,2})[./月](\\d{1,2})(?:\\s*/\\s*(\\d{1,2}))?(?:号|日)?[^\\n，,。；;]{0,8}(?:发|發|收到)"
    );
    private static final Pattern REMARK_DAY_ONLY_BEFORE_KEYWORD_PATTERN = Pattern.compile(
            "(?<!\\d)(\\d{1,2})号[^\\n，,。；;]{0,8}(?:发|發|收到)"
    );
    private static final Pattern REMARK_DAY_ONLY_AFTER_KEYWORD_PATTERN = Pattern.compile(
            "(?:发|發)[^\\n，,。；;]{0,8}(\\d{1,2})号"
    );
    private static final Pattern XIAOHONGSHU_ORDER_NO_PATTERN = Pattern.compile("^P\\d+$");
    private static final String ORDER_STATUS_HEADER = ImportFieldSettingsService.normalizeHeader("订单状态");
    private static final String XIAOHONGSHU_PENDING_STATUS = "待配货";

    private final WorkOrderRepository repository;
    private final AppSettingsService appSettingsService;
    private final ImportFieldSettingsService importFieldSettingsService;
    private final Clock clock;
    private final DataFormatter formatter = new DataFormatter(Locale.CHINA);

    public WorkOrderImportService(
            WorkOrderRepository repository,
            AppSettingsService appSettingsService,
            ImportFieldSettingsService importFieldSettingsService,
            Clock clock
    ) {
        this.repository = repository;
        this.appSettingsService = appSettingsService;
        this.importFieldSettingsService = importFieldSettingsService;
        this.clock = clock;
    }

    @Transactional
    public ImportWorkOrderResponse importXlsx(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("XLSX 文件不可为空");
        }

        Optional<SourceSelection> filenameSource = detectFilenameSource(file.getOriginalFilename());

        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getNumberOfSheets() > 0 ? workbook.getSheetAt(0) : null;

            if (sheet == null || sheet.getPhysicalNumberOfRows() == 0) {
                throw new IllegalArgumentException("XLSX 至少需要一列表头");
            }

            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            ImportFieldSettingsSnapshot importSettings = importFieldSettingsService.getImportSnapshot();
            Map<Long, RemarkTagDefinition> remarkTagsById = importFieldSettingsService.findRemarkTagsByIds(
                            importSettings.remarkTags().stream()
                                    .map(ImportFieldSettingsSnapshot.RemarkTagMatcher::id)
                                    .toList()
                    ).stream()
                    .collect(java.util.stream.Collectors.toMap(RemarkTagDefinition::getId, (tag) -> tag));
            HeaderMapping headerMapping = readHeaders(sheet, evaluator, importSettings);
            validateRequiredHeaders(headerMapping.canonicalHeaders());
            validateXiaohongshuHeaders(headerMapping, filenameSource);

            BigDecimal estimatedHourlyBaseAmount = appSettingsService.getEstimatedHourlyBaseAmount();
            List<ImportRowError> errors = new ArrayList<>();
            Map<String, ParsedWorkOrder> parsedWorkOrders = new LinkedHashMap<>();
            int skippedCount = 0;

            for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);

                if (isRowEmpty(row, evaluator)) {
                    continue;
                }

                int rowNumber = rowIndex + 1;

                try {
                    String orderNo = readString(
                            row,
                            headerMapping.canonicalHeaders().get(ImportFieldKey.ORDER_NO),
                            evaluator
                    );

                    if (orderNo.isBlank()) {
                        throw new IllegalArgumentException("订单编号不可为空");
                    }

                    SourceSelection sourceSelection = filenameSource.orElseGet(() -> detectSource(orderNo));
                    WorkOrderSource source = sourceSelection.source();

                    if (source == WorkOrderSource.XIAOHONGSHU) {
                        String orderStatus = readStringIfPresent(
                                row,
                                headerMapping.rawHeaders().get(ORDER_STATUS_HEADER),
                                evaluator
                        );

                        if (orderStatus.isBlank()) {
                            throw new IllegalArgumentException("小红书订单状态不可为空");
                        }

                        if (!XIAOHONGSHU_PENDING_STATUS.equals(orderStatus)) {
                            skippedCount++;
                            continue;
                        }
                    }

                    ParsedWorkOrder parsedWorkOrder = parseWorkOrder(
                            row,
                            headerMapping.canonicalHeaders(),
                            evaluator,
                            estimatedHourlyBaseAmount,
                            importSettings,
                            orderNo,
                            sourceSelection
                    );
                    parsedWorkOrders.put(parsedWorkOrder.orderNo(), parsedWorkOrder);
                } catch (RuntimeException exception) {
                    errors.add(new ImportRowError(rowNumber, exception.getMessage()));
                }
            }

            int createdCount = 0;
            int updatedCount = 0;

            for (ParsedWorkOrder parsedWorkOrder : parsedWorkOrders.values()) {
                Optional<WorkOrder> existingWorkOrder = repository.findByOrderNo(parsedWorkOrder.orderNo());

                if (existingWorkOrder.isPresent()) {
                    if (!existingWorkOrder.get().getSourceCode().equals(parsedWorkOrder.sourceCode())
                            || !existingWorkOrder.get().getSourceName().equals(parsedWorkOrder.sourceName())) {
                        throw new IllegalArgumentException(
                                "订单编号 " + parsedWorkOrder.orderNo() + " 已属于其他订单来源"
                        );
                    }

                    existingWorkOrder.get().updateImportedDetails(
                            parsedWorkOrder.remark(),
                            parsedWorkOrder.price(),
                            parsedWorkOrder.estimatedMinutes(),
                            parsedWorkOrder.urgent(),
                            parsedWorkOrder.latestShipTime(),
                            parsedWorkOrder.orderTime(),
                            parsedWorkOrder.source(),
                            parsedWorkOrder.sourceCode(),
                            parsedWorkOrder.sourceName(),
                            parsedWorkOrder.sourceBadgeColor(),
                            parsedWorkOrder.sourceBadgeText()
                    );
                    existingWorkOrder.get().replaceRemarkTags(resolveRemarkTags(
                            parsedWorkOrder.remarkTagIds(),
                            remarkTagsById
                    ));
                    updatedCount++;
                    continue;
                }

                WorkOrder workOrder = new WorkOrder(
                        parsedWorkOrder.orderNo(),
                        null,
                        parsedWorkOrder.remark(),
                        parsedWorkOrder.price(),
                        parsedWorkOrder.estimatedMinutes(),
                        parsedWorkOrder.urgent(),
                        parsedWorkOrder.latestShipTime(),
                        parsedWorkOrder.orderTime(),
                        parsedWorkOrder.source(),
                        parsedWorkOrder.sourceCode(),
                        parsedWorkOrder.sourceName(),
                        parsedWorkOrder.sourceBadgeColor(),
                        parsedWorkOrder.sourceBadgeText()
                );
                workOrder.replaceRemarkTags(resolveRemarkTags(
                        parsedWorkOrder.remarkTagIds(),
                        remarkTagsById
                ));
                repository.save(workOrder);
                createdCount++;
            }

            return new ImportWorkOrderResponse(createdCount, updatedCount, skippedCount, errors);
        } catch (IOException exception) {
            throw new IllegalArgumentException("无法读取 XLSX 文件");
        }
    }

    @Transactional
    public WorkOrder createPendingWorkOrder(CreateWorkOrderRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("工单内容不可为空");
        }

        String orderNo = trimToEmpty(request.orderNo());
        if (orderNo.isBlank()) {
            throw new IllegalArgumentException("订单编号不可为空");
        }
        if (repository.findByOrderNo(orderNo).isPresent()) {
            throw new IllegalStateException("订单编号 " + orderNo + " 已存在");
        }
        String requestedSourceName = trimToEmpty(request.sourceName());
        if (requestedSourceName.isBlank()) {
            throw new IllegalArgumentException("订单来源不可为空");
        }
        OrderSourceOption sourceOption = appSettingsService.getOrderSourceOptions().stream()
                .filter((option) -> option.getName().equalsIgnoreCase(requestedSourceName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("订单来源不在基础设置的可选范围内"));
        if (request.price() == null) {
            throw new IllegalArgumentException("买家实付金额不可为空");
        }
        if (request.price().signum() < 0) {
            throw new IllegalArgumentException("买家实付金额不可为负数");
        }
        if (request.latestShipTime() == null) {
            throw new IllegalArgumentException("应发货时间不可为空");
        }

        String buyerMessage = trimToEmpty(request.buyerMessage());
        String merchantRemark = trimToEmpty(request.merchantRemark());
        String remark = buildRemark(buyerMessage, merchantRemark);
        if (remark.length() > 1000) {
            throw new IllegalArgumentException("买家留言与商家备注合并后最长为 1000 个字符");
        }
        LocalDateTime latestShipTime = resolveLatestShipTime(
                merchantRemark,
                buyerMessage,
                request.paidAt(),
                request::latestShipTime
        );

        ImportFieldSettingsSnapshot importSettings = importFieldSettingsService.getImportSnapshot();
        String remarkTagValue = ImportFieldSettingsService.normalizeRemarkTagValue(request.urgentText());
        List<Long> remarkTagIds = importSettings.matchingRemarkTagIds(remarkTagValue);
        List<RemarkTagDefinition> remarkTags = importFieldSettingsService.findRemarkTagsByIds(remarkTagIds);
        boolean urgent = importSettings.containsSystemTag(
                remarkTagIds,
                ImportFieldSettingsService.URGENT_SYSTEM_KEY
        );
        WorkOrderSource source = WorkOrderSource.fromCode(sourceOption.getIdentifier());
        int estimatedMinutes = calculateEstimatedMinutes(
                request.price(),
                appSettingsService.getEstimatedHourlyBaseAmount()
        );

        WorkOrder workOrder = new WorkOrder(
                orderNo,
                null,
                remark,
                request.price(),
                estimatedMinutes,
                urgent,
                latestShipTime,
                request.paidAt(),
                source,
                sourceOption.getIdentifier(),
                sourceOption.getName(),
                sourceOption.getBadgeColor(),
                sourceOption.getBadgeText()
        );
        workOrder.replaceRemarkTags(remarkTags);
        return repository.save(workOrder);
    }

    private ParsedWorkOrder parseWorkOrder(
            Row row,
            Map<ImportFieldKey, Integer> headers,
            FormulaEvaluator evaluator,
            BigDecimal estimatedHourlyBaseAmount,
            ImportFieldSettingsSnapshot importSettings,
            String orderNo,
            SourceSelection sourceSelection
    ) {
        BigDecimal price = readPrice(row, headers.get(ImportFieldKey.PRICE), evaluator);
        List<Long> remarkTagIds = headers.containsKey(ImportFieldKey.URGENT)
                ? readRemarkTagIds(row, headers.get(ImportFieldKey.URGENT), evaluator, importSettings)
                : List.of();
        boolean urgent = importSettings.containsSystemTag(
                remarkTagIds,
                ImportFieldSettingsService.URGENT_SYSTEM_KEY
        );
        String buyerMessage = readStringIfPresent(row, headers.get(ImportFieldKey.BUYER_MESSAGE), evaluator);
        String merchantRemark = readStringIfPresent(row, headers.get(ImportFieldKey.MERCHANT_REMARK), evaluator);
        String remark = buildRemark(buyerMessage, merchantRemark);
        LocalDateTime orderTime = readOrderTime(row, headers, evaluator);
        LocalDateTime latestShipTime = readLatestShipTime(row, headers, evaluator, orderTime);
        int estimatedMinutes = calculateEstimatedMinutes(price, estimatedHourlyBaseAmount);

        return new ParsedWorkOrder(
                orderNo,
                remark,
                price,
                estimatedMinutes,
                urgent,
                remarkTagIds,
                latestShipTime,
                orderTime,
                sourceSelection.source(),
                sourceSelection.sourceCode(),
                sourceSelection.sourceName(),
                sourceSelection.sourceBadgeColor(),
                sourceSelection.sourceBadgeText()
        );
    }

    private HeaderMapping readHeaders(
            Sheet sheet,
            FormulaEvaluator evaluator,
            ImportFieldSettingsSnapshot importSettings
    ) {
        Row headerRow = sheet.getRow(0);

        if (headerRow == null) {
            throw new IllegalArgumentException("XLSX 第一列必须是表头");
        }

        Map<ImportFieldKey, List<HeaderMatch>> builtInMatches = new EnumMap<>(ImportFieldKey.class);
        Map<ImportFieldKey, List<HeaderMatch>> customMatches = new EnumMap<>(ImportFieldKey.class);
        Map<String, Integer> rawHeaders = new HashMap<>();

        for (Cell cell : headerRow) {
            String originalHeader = formatter.formatCellValue(cell, evaluator).trim();
            String normalizedHeader = ImportFieldSettingsService.normalizeHeader(originalHeader);

            if (normalizedHeader.isBlank()) {
                continue;
            }

            rawHeaders.putIfAbsent(normalizedHeader, cell.getColumnIndex());
            ImportFieldKey fieldKey = importSettings.headerAliases().get(normalizedHeader);

            if (fieldKey == null) {
                continue;
            }

            HeaderMatch match = new HeaderMatch(cell.getColumnIndex(), originalHeader);
            Map<ImportFieldKey, List<HeaderMatch>> target = importSettings.customHeaderAliases()
                    .contains(normalizedHeader)
                    ? customMatches
                    : builtInMatches;
            target.computeIfAbsent(fieldKey, ignored -> new ArrayList<>()).add(match);
        }

        Map<ImportFieldKey, Integer> canonicalHeaders = new EnumMap<>(ImportFieldKey.class);
        for (ImportFieldKey fieldKey : ImportFieldKey.values()) {
            List<HeaderMatch> fieldBuiltInMatches = builtInMatches.getOrDefault(fieldKey, List.of());
            List<HeaderMatch> fieldCustomMatches = customMatches.getOrDefault(fieldKey, List.of());
            validateSingleHeaderMatch(fieldKey, fieldBuiltInMatches);
            validateSingleHeaderMatch(fieldKey, fieldCustomMatches);

            HeaderMatch selectedMatch = !fieldCustomMatches.isEmpty()
                    ? fieldCustomMatches.getFirst()
                    : fieldBuiltInMatches.isEmpty() ? null : fieldBuiltInMatches.getFirst();
            if (selectedMatch != null) {
                canonicalHeaders.put(fieldKey, selectedMatch.columnIndex());
            }
        }

        return new HeaderMapping(canonicalHeaders, rawHeaders);
    }

    private void validateSingleHeaderMatch(ImportFieldKey fieldKey, List<HeaderMatch> matches) {
        if (matches.size() < 2) {
            return;
        }

        throw new IllegalArgumentException(
                "XLSX 字段「" + matches.get(0).originalName() + "」与「" + matches.get(1).originalName()
                        + "」同时映射到" + fieldLabel(fieldKey)
        );
    }

    private void validateRequiredHeaders(Map<ImportFieldKey, Integer> headers) {
        if (!headers.containsKey(ImportFieldKey.ORDER_NO)) {
            throw new IllegalArgumentException("XLSX 缺少订单编号字段");
        }

        if (!headers.containsKey(ImportFieldKey.PRICE)) {
            throw new IllegalArgumentException("XLSX 缺少订单价格字段");
        }

        if (!headers.containsKey(ImportFieldKey.LATEST_SHIP_TIME)) {
            throw new IllegalArgumentException("XLSX 缺少最晚发货日期字段");
        }
    }

    private void validateXiaohongshuHeaders(
            HeaderMapping headerMapping,
            Optional<SourceSelection> filenameSource
    ) {
        if (filenameSource.filter((selection) -> selection.source() == WorkOrderSource.XIAOHONGSHU).isPresent()
                && !headerMapping.rawHeaders().containsKey(ORDER_STATUS_HEADER)) {
            throw new IllegalArgumentException("小红书 XLSX 缺少订单状态字段");
        }
    }

    private Optional<SourceSelection> detectFilenameSource(String originalFilename) {
        String filename = normalizeSourceMatchText(originalFilename);
        if (filename.isBlank()) {
            return Optional.empty();
        }

        Map<String, SourceSelection> matches = new LinkedHashMap<>();
        for (OrderSourceOption option : appSettingsService.getOrderSourceOptions()) {
            String sourceName = normalizeSourceMatchText(option.getName());
            String badgeText = normalizeSourceMatchText(option.getBadgeText());
            boolean nameMatched = !sourceName.isBlank() && filename.contains(sourceName);
            boolean badgeMatched = !badgeText.isBlank() && filename.contains(badgeText);

            if (!nameMatched && !badgeMatched) {
                continue;
            }

            SourceSelection selection = sourceSelection(option);
            matches.putIfAbsent(selection.sourceCode(), selection);
        }

        if (matches.size() > 1) {
            throw new IllegalArgumentException(
                    "文件名同时匹配多个订单来源：" + String.join(
                            "、",
                            matches.values().stream().map(SourceSelection::sourceName).toList()
                    )
            );
        }

        return matches.values().stream().findFirst();
    }

    private SourceSelection detectSource(String orderNo) {
        String fallbackIdentifier = XIAOHONGSHU_ORDER_NO_PATTERN.matcher(orderNo).matches()
                ? WorkOrderSource.XIAOHONGSHU.name()
                : WorkOrderSource.QIANNIU.name();
        return appSettingsService.getOrderSourceOptions().stream()
                .filter((option) -> fallbackIdentifier.equalsIgnoreCase(option.getIdentifier()))
                .findFirst()
                .map(this::sourceSelection)
                .orElseGet(() -> {
                    WorkOrderSource source = WorkOrderSource.fromCode(fallbackIdentifier);
                    return new SourceSelection(
                            source,
                            fallbackIdentifier,
                            source.displayName(null),
                            source == WorkOrderSource.XIAOHONGSHU ? "#FF5C5C" : "#218BFF",
                            source == WorkOrderSource.XIAOHONGSHU ? "书" : "千"
                    );
                });
    }

    private SourceSelection sourceSelection(OrderSourceOption option) {
        return new SourceSelection(
                WorkOrderSource.fromCode(option.getIdentifier()),
                option.getIdentifier(),
                option.getName(),
                option.getBadgeColor(),
                option.getBadgeText()
        );
    }

    private String normalizeSourceMatchText(String value) {
        return trimToEmpty(value)
                .toLowerCase(Locale.ROOT)
                .replace('紅', '红')
                .replace('書', '书');
    }

    private String fieldLabel(ImportFieldKey fieldKey) {
        return switch (fieldKey) {
            case ORDER_NO -> "订单编号";
            case PRICE -> "订单价格";
            case LATEST_SHIP_TIME -> "最晚发货日期";
            case URGENT -> "备注标签";
            case BUYER_MESSAGE -> "买家留言";
            case MERCHANT_REMARK -> "商家备注";
            case PAID_AT -> "订单付款时间";
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

    private List<Long> readRemarkTagIds(
            Row row,
            int index,
            FormulaEvaluator evaluator,
            ImportFieldSettingsSnapshot importSettings
    ) {
        String value = ImportFieldSettingsService.normalizeRemarkTagValue(readString(row, index, evaluator));
        return importSettings.matchingRemarkTagIds(value);
    }

    private List<RemarkTagDefinition> resolveRemarkTags(
            List<Long> remarkTagIds,
            Map<Long, RemarkTagDefinition> remarkTagsById
    ) {
        return remarkTagIds.stream()
                .map(remarkTagsById::get)
                .filter(java.util.Objects::nonNull)
                .toList();
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

    private LocalDateTime readOrderTime(
            Row row,
            Map<ImportFieldKey, Integer> headers,
            FormulaEvaluator evaluator
    ) {
        return headers.containsKey(ImportFieldKey.PAID_AT)
                ? readOptionalDateTime(row, headers.get(ImportFieldKey.PAID_AT), evaluator)
                : null;
    }

    private LocalDateTime readLatestShipTime(
            Row row,
            Map<ImportFieldKey, Integer> headers,
            FormulaEvaluator evaluator,
            LocalDateTime orderTime
    ) {
        String merchantRemark = readStringIfPresent(row, headers.get(ImportFieldKey.MERCHANT_REMARK), evaluator);
        String buyerMessage = readStringIfPresent(row, headers.get(ImportFieldKey.BUYER_MESSAGE), evaluator);
        return resolveLatestShipTime(
                merchantRemark,
                buyerMessage,
                orderTime,
                () -> readLatestShipTimeFallback(
                        row,
                        headers.get(ImportFieldKey.LATEST_SHIP_TIME),
                        evaluator
                )
        );
    }

    private LocalDateTime resolveLatestShipTime(
            String merchantRemark,
            String buyerMessage,
            LocalDateTime paidAt,
            Supplier<LocalDateTime> explicitLatestShipTimeSupplier
    ) {
        Optional<LocalDate> merchantShipDate = parseRemarkShipDate(merchantRemark, paidAt);
        if (merchantShipDate.isPresent()) {
            return merchantShipDate.get().atTime(END_OF_DAY);
        }

        Optional<LocalDate> buyerShipDate = parseRemarkShipDate(buyerMessage, paidAt);
        if (buyerShipDate.isPresent()) {
            return buyerShipDate.get().atTime(END_OF_DAY);
        }

        return explicitLatestShipTimeSupplier.get();
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

        return parseDateTimeText(value)
                .orElseThrow(() -> new IllegalArgumentException(
                        "订单付款时间格式不正确，请使用 yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss"
                ));
    }

    private Optional<LocalDate> parseRemarkShipDate(String remark, LocalDateTime paidAt) {
        if (remark == null || remark.isBlank()) {
            return Optional.empty();
        }

        int currentYear = LocalDate.now(clock).getYear();
        Matcher monthDayMatcher = REMARK_MONTH_DAY_PATTERN.matcher(remark);

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

        Matcher dayBeforeKeywordMatcher = REMARK_DAY_ONLY_BEFORE_KEYWORD_PATTERN.matcher(remark);

        if (dayBeforeKeywordMatcher.find()) {
            return safeDate(currentYear, paidMonth, Integer.parseInt(dayBeforeKeywordMatcher.group(1)));
        }

        Matcher dayAfterKeywordMatcher = REMARK_DAY_ONLY_AFTER_KEYWORD_PATTERN.matcher(remark);

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

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private record ParsedWorkOrder(
            String orderNo,
            String remark,
            BigDecimal price,
            int estimatedMinutes,
            boolean urgent,
            List<Long> remarkTagIds,
            LocalDateTime latestShipTime,
            LocalDateTime orderTime,
            WorkOrderSource source,
            String sourceCode,
            String sourceName,
            String sourceBadgeColor,
            String sourceBadgeText
    ) {
    }

    private record HeaderMapping(
            Map<ImportFieldKey, Integer> canonicalHeaders,
            Map<String, Integer> rawHeaders
    ) {
    }

    private record HeaderMatch(
            int columnIndex,
            String originalName
    ) {
    }

    private record SourceSelection(
            WorkOrderSource source,
            String sourceCode,
            String sourceName,
            String sourceBadgeColor,
            String sourceBadgeText
    ) {
    }
}
