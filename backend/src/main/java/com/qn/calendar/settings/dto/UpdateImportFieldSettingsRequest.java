package com.qn.calendar.settings.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record UpdateImportFieldSettingsRequest(
        @NotNull(message = "字段设置不可为空")
        @Valid
        List<FieldAliases> fields,
        @NotNull(message = "备注标签设置不可为空")
        @Valid
        List<RemarkTag> remarkTags
) {

    public record FieldAliases(
            String key,
            @NotNull(message = "自定义字段名列表不可为空")
            List<String> customAliases
    ) {
    }

    public record RemarkTag(
            Long id,
            String systemKey,
            String name,
            String color,
            @NotNull(message = "包含文字列表不可为空")
            List<String> containsTexts
    ) {
    }
}
