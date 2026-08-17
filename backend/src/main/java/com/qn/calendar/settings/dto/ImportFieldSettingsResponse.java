package com.qn.calendar.settings.dto;

import java.util.List;

import com.qn.calendar.settings.constant.ImportUrgentMatchType;

public record ImportFieldSettingsResponse(
        List<FieldSettings> fields,
        UrgentRules urgentRules
) {

    public record FieldSettings(
            String key,
            String label,
            boolean required,
            List<String> builtInAliases,
            List<String> customAliases
    ) {
    }

    public record UrgentRules(
            List<UrgentRule> builtIn,
            List<UrgentRule> custom
    ) {
    }

    public record UrgentRule(
            String text,
            ImportUrgentMatchType matchType
    ) {
    }
}
