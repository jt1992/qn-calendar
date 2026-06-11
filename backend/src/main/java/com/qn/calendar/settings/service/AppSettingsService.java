package com.qn.calendar.settings.service;

import java.math.BigDecimal;

import com.qn.calendar.settings.dto.AppSettingsResponse;
import com.qn.calendar.settings.dto.UpdateAppSettingsRequest;
import com.qn.calendar.settings.entity.AppSetting;
import com.qn.calendar.settings.repository.AppSettingRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AppSettingsService {

    private static final Long APP_SETTING_ID = 1L;

    public static final BigDecimal DEFAULT_ESTIMATED_HOURLY_BASE_AMOUNT = BigDecimal.valueOf(100);

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

    @Transactional
    public AppSettingsResponse updateSettings(UpdateAppSettingsRequest request) {
        validateEstimatedHourlyBaseAmount(request.estimatedHourlyBaseAmount());

        AppSetting appSetting = repository.findById(APP_SETTING_ID)
                .orElseGet(() -> new AppSetting(APP_SETTING_ID, DEFAULT_ESTIMATED_HOURLY_BASE_AMOUNT));

        appSetting.updateEstimatedHourlyBaseAmount(request.estimatedHourlyBaseAmount());
        return AppSettingsResponse.from(repository.save(appSetting));
    }

    private AppSetting getOrCreateSettings() {
        return repository.findById(APP_SETTING_ID)
                .orElseGet(() -> repository.save(new AppSetting(
                        APP_SETTING_ID,
                        DEFAULT_ESTIMATED_HOURLY_BASE_AMOUNT
                )));
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
}
