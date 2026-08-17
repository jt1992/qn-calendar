package com.qn.calendar.settings.dto;

import java.util.List;

import com.qn.calendar.settings.constant.ImportUrgentMatchType;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record UpdateImportFieldSettingsRequest(
        @NotNull(message = "字段设置不可为空")
        @Valid
        List<FieldAliases> fields,
        @NotNull(message = "加急判定规则不可为空")
        @Valid
        UrgentRules urgentRules
) {

    public record FieldAliases(
            String key,
            @NotNull(message = "自定义字段名列表不可为空")
            List<String> customAliases
    ) {
    }

    public record UrgentRules(
            @NotNull(message = "自定义加急判定规则列表不可为空")
            @Valid
            List<UrgentRule> custom
    ) {
    }

    public record UrgentRule(
            String text,
            ImportUrgentMatchType matchType
    ) {
    }
}
