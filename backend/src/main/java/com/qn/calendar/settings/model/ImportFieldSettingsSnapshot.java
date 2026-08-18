package com.qn.calendar.settings.model;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record ImportFieldSettingsSnapshot(
        Map<String, ImportFieldKey> headerAliases,
        Set<String> customHeaderAliases,
        List<RemarkTagMatcher> remarkTags
) {

    public ImportFieldSettingsSnapshot {
        headerAliases = Map.copyOf(headerAliases);
        customHeaderAliases = Collections.unmodifiableSet(new LinkedHashSet<>(customHeaderAliases));
        remarkTags = List.copyOf(remarkTags);
    }

    public List<Long> matchingRemarkTagIds(String normalizedValue) {
        return remarkTags.stream()
                .filter((tag) -> tag.matches(normalizedValue))
                .map(RemarkTagMatcher::id)
                .toList();
    }

    public boolean containsSystemTag(List<Long> tagIds, String systemKey) {
        Set<Long> matchedIds = Set.copyOf(tagIds);
        return remarkTags.stream()
                .anyMatch((tag) -> systemKey.equals(tag.systemKey()) && matchedIds.contains(tag.id()));
    }

    public record RemarkTagMatcher(
            Long id,
            String systemKey,
            List<String> containsValues
    ) {

        public RemarkTagMatcher {
            containsValues = List.copyOf(containsValues);
        }

        public boolean matches(String normalizedValue) {
            return normalizedValue != null
                    && !normalizedValue.isEmpty()
                    && containsValues.stream().anyMatch(normalizedValue::contains);
        }
    }
}
