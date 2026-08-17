package com.qn.calendar.settings.model;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record ImportFieldSettingsSnapshot(
        Map<String, ImportFieldKey> headerAliases,
        Set<String> customHeaderAliases,
        Set<String> urgentExactValues,
        List<String> urgentContainsValues
) {

    public ImportFieldSettingsSnapshot {
        headerAliases = Map.copyOf(headerAliases);
        customHeaderAliases = Set.copyOf(customHeaderAliases);
        urgentExactValues = Set.copyOf(urgentExactValues);
        urgentContainsValues = List.copyOf(urgentContainsValues);
    }
}
