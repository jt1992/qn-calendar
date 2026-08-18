package com.qn.calendar.settings.service;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.qn.calendar.settings.constant.SmtpSecurity;
import com.qn.calendar.settings.dto.AppSettingsResponse;
import com.qn.calendar.settings.dto.DeleteOrderSourceResponse;
import com.qn.calendar.settings.dto.OrderSourceDeletionImpactResponse;
import com.qn.calendar.settings.dto.UpdateEmailSenderSettingsRequest;
import com.qn.calendar.settings.dto.UpdateAppSettingsRequest;
import com.qn.calendar.settings.entity.AppSetting;
import com.qn.calendar.settings.entity.OrderSourceOption;
import com.qn.calendar.settings.model.EmailSenderSettings;
import com.qn.calendar.settings.repository.AppSettingRepository;
import com.qn.calendar.workorder.constant.WorkOrderSource;
import com.qn.calendar.workorder.entity.WorkOrder;
import com.qn.calendar.workorder.repository.WorkOrderRepository;
import com.qn.calendar.workorder.repository.WorkOrderSegmentPauseRepository;
import com.qn.calendar.workorder.repository.WorkOrderSegmentRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AppSettingsService {

    private static final Long APP_SETTING_ID = 1L;

    public static final BigDecimal DEFAULT_ESTIMATED_HOURLY_BASE_AMOUNT = BigDecimal.valueOf(100);
    public static final LocalTime DEFAULT_WEEK_VIEW_START_TIME = LocalTime.of(6, 0);
    public static final String DEFAULT_BADGE_COLOR = "#3B82F6";
    public static final List<OrderSourceOption> DEFAULT_ORDER_SOURCE_OPTIONS = List.of(
            new OrderSourceOption("千牛", "QIANNIU", "#218BFF", "千"),
            new OrderSourceOption("小红书", "XIAOHONGSHU", "#FF5C5C", "书")
    );
    private static final Pattern SOURCE_IDENTIFIER_PATTERN = Pattern.compile("[A-Z][A-Z0-9_]{0,39}");
    private static final Pattern BADGE_COLOR_PATTERN = Pattern.compile("#[0-9A-F]{6}");
    private static final Pattern BADGE_TEXT_PATTERN = Pattern.compile("(?:\\p{IsHan}|[A-Za-z])");

    private final AppSettingRepository repository;
    private final WorkOrderRepository workOrderRepository;
    private final WorkOrderSegmentRepository segmentRepository;
    private final WorkOrderSegmentPauseRepository pauseRepository;

    public AppSettingsService(
            AppSettingRepository repository,
            WorkOrderRepository workOrderRepository,
            WorkOrderSegmentRepository segmentRepository,
            WorkOrderSegmentPauseRepository pauseRepository
    ) {
        this.repository = repository;
        this.workOrderRepository = workOrderRepository;
        this.segmentRepository = segmentRepository;
        this.pauseRepository = pauseRepository;
    }

    @Transactional
    public AppSettingsResponse getSettings() {
        return AppSettingsResponse.from(getOrCreateSettings());
    }

    @Transactional(readOnly = true)
    public BigDecimal getEstimatedHourlyBaseAmount() {
        return repository.findById(APP_SETTING_ID)
                .map(AppSetting::getEstimatedHourlyBaseAmount)
                .orElse(DEFAULT_ESTIMATED_HOURLY_BASE_AMOUNT);
    }

    @Transactional(readOnly = true)
    public List<OrderSourceOption> getOrderSourceOptions() {
        return repository.findById(APP_SETTING_ID)
                .map(AppSetting::getOrderSourceOptions)
                .filter((options) -> !options.isEmpty())
                .map(this::normalizeStoredOrderSourceOptions)
                .orElse(DEFAULT_ORDER_SOURCE_OPTIONS);
    }

    @Transactional(readOnly = true)
    public EmailSenderSettings getRequiredEmailSenderSettings() {
        AppSetting appSetting = repository.findById(APP_SETTING_ID)
                .orElseThrow(() -> new IllegalStateException("请先在全局设置中配置寄件者 SMTP"));

        if (!appSetting.isEmailSenderConfigured()) {
            throw new IllegalStateException("请先在全局设置中配置寄件者 SMTP");
        }

        return new EmailSenderSettings(
                appSetting.getEmailSender(),
                appSetting.getSmtpHost(),
                appSetting.getSmtpPort(),
                appSetting.getSmtpSecurity(),
                appSetting.getSmtpAuthCode()
        );
    }

    @Transactional
    public AppSettingsResponse updateSettings(UpdateAppSettingsRequest request) {
        validateEstimatedHourlyBaseAmount(request.estimatedHourlyBaseAmount());
        validateWeekViewDefaultStartTime(request.weekViewDefaultStartTime());
        AppSetting appSetting = getOrCreateSettings();
        List<OrderSourceOption> orderSourceOptions = normalizeOrderSourceOptions(
                request.orderSourceOptions(),
                appSetting.getOrderSourceOptions()
        );

        appSetting.updateBasicSettings(
                request.estimatedHourlyBaseAmount(),
                request.weekViewDefaultStartTime(),
                orderSourceOptions
        );
        syncWorkOrderSourceMetadata(orderSourceOptions);
        return AppSettingsResponse.from(repository.save(appSetting));
    }

    @Transactional
    public OrderSourceDeletionImpactResponse getOrderSourceDeletionImpact(String identifier) {
        OrderSourceOption sourceOption = getRequiredOrderSourceOption(identifier);
        String normalizedIdentifier = sourceOption.getIdentifier();
        long workOrderCount = workOrderRepository.countBySourceIdentifier(
                normalizedIdentifier,
                WorkOrderSource.fromCode(normalizedIdentifier)
        );

        return new OrderSourceDeletionImpactResponse(
                normalizedIdentifier,
                sourceOption.getName(),
                workOrderCount
        );
    }

    @Transactional
    public DeleteOrderSourceResponse deleteOrderSource(String identifier) {
        AppSetting appSetting = getOrCreateSettings();
        List<OrderSourceOption> currentOptions = appSetting.getOrderSourceOptions();
        OrderSourceOption sourceOption = findRequiredOrderSourceOption(currentOptions, identifier);

        if (currentOptions.size() <= 1) {
            throw new IllegalStateException("请至少保留一个订单来源选项");
        }

        String normalizedIdentifier = sourceOption.getIdentifier();
        WorkOrderSource legacySource = WorkOrderSource.fromCode(normalizedIdentifier);
        pauseRepository.deleteBySourceIdentifier(normalizedIdentifier, legacySource);
        segmentRepository.deleteBySourceIdentifier(normalizedIdentifier, legacySource);
        int deletedWorkOrderCount = workOrderRepository.deleteBySourceIdentifier(
                normalizedIdentifier,
                legacySource
        );
        List<OrderSourceOption> remainingOptions = currentOptions.stream()
                .filter((option) -> !option.getIdentifier().equals(normalizedIdentifier))
                .toList();

        appSetting.updateBasicSettings(
                appSetting.getEstimatedHourlyBaseAmount(),
                appSetting.getWeekViewDefaultStartTime(),
                remainingOptions
        );

        return new DeleteOrderSourceResponse(
                AppSettingsResponse.from(repository.save(appSetting)),
                deletedWorkOrderCount
        );
    }

    @Transactional
    public AppSettingsResponse updateEmailSenderSettings(UpdateEmailSenderSettingsRequest request) {
        String emailSender = trim(request.senderEmail());
        String smtpHost = trim(request.smtpHost());
        String requestedSmtpAuthCode = trim(request.smtpAuthCode());
        AppSetting appSetting = getOrCreateSettings();
        String smtpAuthCode = hasText(requestedSmtpAuthCode)
                ? requestedSmtpAuthCode
                : appSetting.getSmtpAuthCode();
        validateEmailSenderSettings(
                emailSender,
                smtpHost,
                request.smtpPort(),
                request.smtpSecurity(),
                smtpAuthCode
        );

        appSetting.updateEmailSenderSettings(
                emailSender,
                smtpHost,
                request.smtpPort(),
                request.smtpSecurity(),
                smtpAuthCode
        );
        return AppSettingsResponse.from(repository.save(appSetting));
    }

    private AppSetting getOrCreateSettings() {
        AppSetting appSetting = repository.findById(APP_SETTING_ID)
                .orElseGet(() -> repository.save(new AppSetting(
                        APP_SETTING_ID,
                        DEFAULT_ESTIMATED_HOURLY_BASE_AMOUNT,
                        DEFAULT_WEEK_VIEW_START_TIME
                )));

        List<OrderSourceOption> storedOrderSources = appSetting.getOrderSourceOptions();
        List<OrderSourceOption> normalizedOrderSources = storedOrderSources.isEmpty()
                ? DEFAULT_ORDER_SOURCE_OPTIONS
                : normalizeStoredOrderSourceOptions(storedOrderSources);
        boolean orderSourcesChanged = !sameOrderSourceOptions(storedOrderSources, normalizedOrderSources);

        if (appSetting.getWeekViewDefaultStartTime() == null || orderSourcesChanged) {
            appSetting.updateBasicSettings(
                    appSetting.getEstimatedHourlyBaseAmount(),
                    appSetting.getWeekViewDefaultStartTime() == null
                            ? DEFAULT_WEEK_VIEW_START_TIME
                            : appSetting.getWeekViewDefaultStartTime(),
                    normalizedOrderSources
            );
        }

        return appSetting;
    }

    private OrderSourceOption getRequiredOrderSourceOption(String identifier) {
        return findRequiredOrderSourceOption(getOrCreateSettings().getOrderSourceOptions(), identifier);
    }

    private OrderSourceOption findRequiredOrderSourceOption(
            List<OrderSourceOption> options,
            String identifier
    ) {
        String normalizedIdentifier = trim(identifier).toUpperCase(Locale.ROOT);

        return options.stream()
                .filter((option) -> option.getIdentifier().equals(normalizedIdentifier))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("找不到订单来源：" + normalizedIdentifier));
    }

    private void validateEstimatedHourlyBaseAmount(BigDecimal estimatedHourlyBaseAmount) {
        if (estimatedHourlyBaseAmount == null) {
            throw new IllegalArgumentException("预估工时基础金额不可为空");
        }

        if (estimatedHourlyBaseAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("预估工时基础金额必须大于 0");
        }

        if (estimatedHourlyBaseAmount.scale() > 2) {
            throw new IllegalArgumentException("预估工时基础金额最多保留 2 位小数");
        }
    }

    private void validateWeekViewDefaultStartTime(LocalTime weekViewDefaultStartTime) {
        if (weekViewDefaultStartTime == null) {
            throw new IllegalArgumentException("周表默认开始时间不可为空");
        }

        if (weekViewDefaultStartTime.getMinute() % 30 != 0
                || weekViewDefaultStartTime.getSecond() != 0
                || weekViewDefaultStartTime.getNano() != 0) {
            throw new IllegalArgumentException("周表默认开始时间必须以 30 分钟为单位");
        }
    }

    private List<OrderSourceOption> normalizeOrderSourceOptions(
            List<UpdateAppSettingsRequest.OrderSourceOptionRequest> orderSourceOptions,
            List<OrderSourceOption> currentOrderSourceOptions
    ) {
        if (orderSourceOptions == null || orderSourceOptions.isEmpty()) {
            throw new IllegalArgumentException("请至少保留一个订单来源选项");
        }
        if (orderSourceOptions.size() > 20) {
            throw new IllegalArgumentException("订单来源选项最多为 20 个");
        }

        List<OrderSourceOption> normalizedOptions = new ArrayList<>();
        Set<String> normalizedNames = new HashSet<>();
        Set<String> currentIdentifiers = currentOrderSourceOptions.stream()
                .map(OrderSourceOption::getIdentifier)
                .map(this::normalizeIdentifier)
                .collect(Collectors.toSet());
        Set<String> identifiers = new HashSet<>();

        for (UpdateAppSettingsRequest.OrderSourceOptionRequest option : orderSourceOptions) {
            if (option == null) {
                throw new IllegalArgumentException("订单来源选项不可为空");
            }
            String name = trim(option.name());
            String identifier = normalizeIdentifier(option.identifier());
            String badgeColor = trim(option.badgeColor()).toUpperCase(Locale.ROOT);
            String badgeText = trim(option.badgeText());
            if (!hasText(name)) {
                throw new IllegalArgumentException("订单来源名称不可为空");
            }
            if (name.length() > 80) {
                throw new IllegalArgumentException("订单来源名称最长为 80 个字符");
            }
            if (!normalizedNames.add(name.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("订单来源名称不可重复");
            }
            if (!hasText(identifier)) {
                identifier = generateSourceIdentifier(currentIdentifiers, identifiers);
            } else if (!currentIdentifiers.contains(identifier)) {
                throw new IllegalArgumentException("订单来源内部编号无效，请重新载入设置后再试");
            }
            if (!identifiers.add(identifier)) {
                throw new IllegalArgumentException("订单来源内部编号不可重复");
            }
            if (!BADGE_COLOR_PATTERN.matcher(badgeColor).matches()) {
                throw new IllegalArgumentException("订单来源标签颜色必须是六位十六进制色码");
            }
            if (!BADGE_TEXT_PATTERN.matcher(badgeText).matches()) {
                throw new IllegalArgumentException("订单来源标签单一文字只能填写一个中文字符或英文字母");
            }
            normalizedOptions.add(new OrderSourceOption(name, identifier, badgeColor, badgeText));
        }

        return List.copyOf(normalizedOptions);
    }

    private String normalizeIdentifier(String identifier) {
        return trim(identifier).toUpperCase(Locale.ROOT);
    }

    private String generateSourceIdentifier(
            Set<String> currentIdentifiers,
            Set<String> requestedIdentifiers
    ) {
        String identifier;
        do {
            identifier = "SRC_" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        } while (currentIdentifiers.contains(identifier) || requestedIdentifiers.contains(identifier));
        return identifier;
    }

    private List<OrderSourceOption> normalizeStoredOrderSourceOptions(List<OrderSourceOption> options) {
        List<OrderSourceOption> normalized = new ArrayList<>();
        Set<String> identifiers = new HashSet<>();
        int customIndex = 1;

        for (OrderSourceOption option : options) {
            String name = trim(option.getName());
            String identifier = trim(option.getIdentifier()).toUpperCase(Locale.ROOT);
            String badgeColor = trim(option.getBadgeColor()).toUpperCase(Locale.ROOT);
            String badgeText = trim(option.getBadgeText());

            if (!SOURCE_IDENTIFIER_PATTERN.matcher(identifier).matches() || identifiers.contains(identifier)) {
                identifier = defaultIdentifier(name, customIndex++);
                while (identifiers.contains(identifier)) {
                    identifier = "CUSTOM_" + customIndex++;
                }
            }
            identifiers.add(identifier);

            if (!BADGE_COLOR_PATTERN.matcher(badgeColor).matches()) {
                badgeColor = defaultBadgeColor(name);
            }
            if (!BADGE_TEXT_PATTERN.matcher(badgeText).matches()) {
                badgeText = firstBadgeCharacter(name);
            }

            normalized.add(new OrderSourceOption(name, identifier, badgeColor, badgeText));
        }

        return List.copyOf(normalized);
    }

    private boolean sameOrderSourceOptions(List<OrderSourceOption> left, List<OrderSourceOption> right) {
        if (left.size() != right.size()) {
            return false;
        }

        for (int index = 0; index < left.size(); index++) {
            OrderSourceOption leftOption = left.get(index);
            OrderSourceOption rightOption = right.get(index);
            if (!trim(leftOption.getName()).equals(rightOption.getName())
                    || !trim(leftOption.getIdentifier()).equals(rightOption.getIdentifier())
                    || !trim(leftOption.getBadgeColor()).equals(rightOption.getBadgeColor())
                    || !trim(leftOption.getBadgeText()).equals(rightOption.getBadgeText())) {
                return false;
            }
        }

        return true;
    }

    private void syncWorkOrderSourceMetadata(List<OrderSourceOption> orderSourceOptions) {
        Map<String, OrderSourceOption> optionsByIdentifier = orderSourceOptions.stream()
                .collect(Collectors.toMap(OrderSourceOption::getIdentifier, (option) -> option));

        for (WorkOrder workOrder : workOrderRepository.findAll()) {
            OrderSourceOption option = optionsByIdentifier.get(workOrder.getSourceCode());
            if (option != null) {
                workOrder.updateSourceMetadata(
                        option.getName(),
                        option.getBadgeColor(),
                        option.getBadgeText()
                );
            }
        }
    }

    private String defaultIdentifier(String name, int customIndex) {
        if ("千牛".equalsIgnoreCase(name)) {
            return "QIANNIU";
        }
        if ("小红书".equalsIgnoreCase(name) || "小紅書".equalsIgnoreCase(name)) {
            return "XIAOHONGSHU";
        }
        return "CUSTOM_" + customIndex;
    }

    private String defaultBadgeColor(String name) {
        if ("小红书".equalsIgnoreCase(name) || "小紅書".equalsIgnoreCase(name)) {
            return "#FF5C5C";
        }
        if ("千牛".equalsIgnoreCase(name)) {
            return "#218BFF";
        }
        return DEFAULT_BADGE_COLOR;
    }

    private String firstBadgeCharacter(String value) {
        var matcher = BADGE_TEXT_PATTERN.matcher(value);
        return matcher.find() ? matcher.group() : "其";
    }

    private void validateEmailSenderSettings(
            String emailSender,
            String smtpHost,
            Integer smtpPort,
            SmtpSecurity smtpSecurity,
            String smtpAuthCode
    ) {
        if (!hasText(emailSender)) {
            throw new IllegalArgumentException("寄件 Email 不可为空");
        }

        if (!hasText(smtpHost)) {
            throw new IllegalArgumentException("SMTP 服务器不可为空");
        }

        if (smtpPort == null) {
            throw new IllegalArgumentException("SMTP 端口不可为空");
        }

        if (smtpPort <= 0 || smtpPort > 65535) {
            throw new IllegalArgumentException("SMTP 端口必须介于 1 到 65535");
        }

        if (smtpSecurity == null) {
            throw new IllegalArgumentException("加密方式不可为空");
        }

        if (!hasText(smtpAuthCode)) {
            throw new IllegalArgumentException("授权码不可为空");
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
