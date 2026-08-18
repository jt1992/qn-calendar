package com.qn.calendar.settings.dto;

import java.util.List;

public record ImportFieldSettingsResponse(
        List<FieldSettings> fields,
        List<RemarkTagSettings> remarkTags
) {

    public record FieldSettings(
            String key,
            String label,
            boolean required,
            List<String> builtInAliases,
            List<String> customAliases
    ) {
    }

    public record RemarkTagSettings(
            Long id,
            String systemKey,
            String name,
            String color,
            List<String> containsTexts
    ) {
    }
}
