package com.qn.calendar.settings.service;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.qn.calendar.settings.constant.SmtpSecurity;
import com.qn.calendar.settings.dto.AppSettingsResponse;
import com.qn.calendar.settings.dto.UpdateEmailSenderSettingsRequest;
import com.qn.calendar.settings.dto.UpdateAppSettingsRequest;
import com.qn.calendar.settings.entity.AppSetting;
import com.qn.calendar.settings.model.EmailSenderSettings;
import com.qn.calendar.settings.repository.AppSettingRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AppSettingsService {

    private static final Long APP_SETTING_ID = 1L;

    public static final BigDecimal DEFAULT_ESTIMATED_HOURLY_BASE_AMOUNT = BigDecimal.valueOf(100);
    public static final LocalTime DEFAULT_WEEK_VIEW_START_TIME = LocalTime.of(6, 0);
    public static final List<String> DEFAULT_ORDER_SOURCE_OPTIONS = List.of("千牛", "小红书");

    private final AppSettingRepository repository;

    public AppSettingsService(AppSettingRepository repository) {
        this.repository = repository;
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
    public List<String> getOrderSourceOptions() {
        return repository.findById(APP_SETTING_ID)
                .map(AppSetting::getOrderSourceOptions)
                .filter((options) -> !options.isEmpty())
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
        List<String> orderSourceOptions = normalizeOrderSourceOptions(request.orderSourceOptions());

        AppSetting appSetting = repository.findById(APP_SETTING_ID)
                .orElseGet(() -> new AppSetting(
                        APP_SETTING_ID,
                        DEFAULT_ESTIMATED_HOURLY_BASE_AMOUNT,
                        DEFAULT_WEEK_VIEW_START_TIME
                ));

        appSetting.updateBasicSettings(
                request.estimatedHourlyBaseAmount(),
                request.weekViewDefaultStartTime(),
                orderSourceOptions
        );
        return AppSettingsResponse.from(repository.save(appSetting));
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

        if (appSetting.getWeekViewDefaultStartTime() == null || appSetting.getOrderSourceOptions().isEmpty()) {
            appSetting.updateBasicSettings(
                    appSetting.getEstimatedHourlyBaseAmount(),
                    appSetting.getWeekViewDefaultStartTime() == null
                            ? DEFAULT_WEEK_VIEW_START_TIME
                            : appSetting.getWeekViewDefaultStartTime(),
                    appSetting.getOrderSourceOptions().isEmpty()
                            ? DEFAULT_ORDER_SOURCE_OPTIONS
                            : appSetting.getOrderSourceOptions()
            );
        }

        return appSetting;
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

    private List<String> normalizeOrderSourceOptions(List<String> orderSourceOptions) {
        if (orderSourceOptions == null || orderSourceOptions.isEmpty()) {
            throw new IllegalArgumentException("请至少保留一个订单来源选项");
        }
        if (orderSourceOptions.size() > 20) {
            throw new IllegalArgumentException("订单来源选项最多为 20 个");
        }

        List<String> normalizedOptions = new ArrayList<>();
        Set<String> normalizedNames = new HashSet<>();

        for (String option : orderSourceOptions) {
            String normalizedOption = trim(option);
            if (!hasText(normalizedOption)) {
                throw new IllegalArgumentException("订单来源选项不可为空");
            }
            if (normalizedOption.length() > 80) {
                throw new IllegalArgumentException("订单来源选项最长为 80 个字符");
            }
            if (!normalizedNames.add(normalizedOption.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("订单来源选项不可重复");
            }
            normalizedOptions.add(normalizedOption);
        }

        return List.copyOf(normalizedOptions);
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
