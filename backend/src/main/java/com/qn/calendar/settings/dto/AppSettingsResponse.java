package com.qn.calendar.settings.dto;

import java.math.BigDecimal;

import com.qn.calendar.settings.entity.AppSetting;

public record AppSettingsResponse(BigDecimal estimatedHourlyBaseAmount) {

    public static AppSettingsResponse from(AppSetting appSetting) {
        return new AppSettingsResponse(appSetting.getEstimatedHourlyBaseAmount());
    }
}
