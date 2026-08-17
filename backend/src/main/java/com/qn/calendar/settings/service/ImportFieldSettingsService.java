package com.qn.calendar.settings.service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.qn.calendar.settings.constant.ImportUrgentMatchType;
import com.qn.calendar.settings.dto.ImportFieldSettingsResponse;
import com.qn.calendar.settings.dto.UpdateImportFieldSettingsRequest;
import com.qn.calendar.settings.entity.ImportFieldAlias;
import com.qn.calendar.settings.entity.ImportUrgentMatchRule;
import com.qn.calendar.settings.model.ImportFieldSettingsSnapshot;
import com.qn.calendar.settings.model.ImportFieldKey;
import com.qn.calendar.settings.repository.ImportFieldAliasRepository;
import com.qn.calendar.settings.repository.ImportUrgentMatchRuleRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ImportFieldSettingsService {

    public static final int MAX_ALIAS_LENGTH = 120;
    public static final int MAX_URGENT_RULE_LENGTH = 120;

    private static final List<UrgentRuleDefinition> BUILT_IN_URGENT_RULES = List.of(
            new UrgentRuleDefinition("true", ImportUrgentMatchType.EXACT),
            new UrgentRuleDefinition("yes", ImportUrgentMatchType.EXACT),
            new UrgentRuleDefinition("y", ImportUrgentMatchType.EXACT),
            new UrgentRuleDefinition("1", ImportUrgentMatchType.EXACT),
            new UrgentRuleDefinition("是", ImportUrgentMatchType.EXACT),
            new UrgentRuleDefinition("加急", ImportUrgentMatchType.CONTAINS),
            new UrgentRuleDefinition("急件", ImportUrgentMatchType.CONTAINS)
    );

    private static final Map<String, ImportFieldKey> BUILT_IN_HEADER_ALIASES = builtInHeaderAliases();
    private static final Set<String> BUILT_IN_URGENT_VALUES = builtInUrgentValues();

    private final ImportFieldAliasRepository fieldAliasRepository;
    private final ImportUrgentMatchRuleRepository urgentMatchRuleRepository;

    public ImportFieldSettingsService(
            ImportFieldAliasRepository fieldAliasRepository,
            ImportUrgentMatchRuleRepository urgentMatchRuleRepository
    ) {
        this.fieldAliasRepository = fieldAliasRepository;
        this.urgentMatchRuleRepository = urgentMatchRuleRepository;
    }

    @Transactional(readOnly = true)
    public ImportFieldSettingsResponse getSettings() {
        List<ImportFieldAlias> aliases = fieldAliasRepository.findAllByOrderByIdAsc();
        List<ImportUrgentMatchRule> urgentRules = urgentMatchRuleRepository.findAllByOrderByIdAsc();
        return toResponse(aliases, urgentRules);
    }

    @Transactional
    public ImportFieldSettingsResponse updateSettings(UpdateImportFieldSettingsRequest request) {
        ValidatedSettings validatedSettings = validate(request);

        fieldAliasRepository.deleteAllInBatch();
        fieldAliasRepository.flush();
        urgentMatchRuleRepository.deleteAllInBatch();
        urgentMatchRuleRepository.flush();

        List<ImportFieldAlias> aliases = fieldAliasRepository.saveAll(validatedSettings.aliases());
        List<ImportUrgentMatchRule> urgentRules = urgentMatchRuleRepository.saveAll(validatedSettings.urgentRules());
        return toResponse(aliases, urgentRules);
    }

    @Transactional(readOnly = true)
    public ImportFieldSettingsSnapshot getImportSnapshot() {
        List<ImportFieldAlias> customAliases = fieldAliasRepository.findAllByOrderByIdAsc();
        List<ImportUrgentMatchRule> customUrgentRules = urgentMatchRuleRepository.findAllByOrderByIdAsc();

        Map<String, ImportFieldKey> headerAliases = new LinkedHashMap<>(BUILT_IN_HEADER_ALIASES);
        Set<String> customHeaderAliases = new LinkedHashSet<>();
        for (ImportFieldAlias customAlias : customAliases) {
            ImportFieldKey existing = headerAliases.putIfAbsent(
                    customAlias.getNormalizedAlias(),
                    customAlias.getFieldKey()
            );
            if (existing != null) {
                throw new IllegalStateException("字段设置存在重复字段名，请重新保存设置");
            }
            customHeaderAliases.add(customAlias.getNormalizedAlias());
        }

        Set<String> urgentExactValues = new LinkedHashSet<>();
        List<String> urgentContainsValues = new ArrayList<>();
        Set<String> seenUrgentValues = new HashSet<>();
        for (UrgentRuleDefinition rule : BUILT_IN_URGENT_RULES) {
            addUrgentRuleToSnapshot(
                    normalizeUrgentValue(rule.text()),
                    rule.matchType(),
                    seenUrgentValues,
                    urgentExactValues,
                    urgentContainsValues
            );
        }
        for (ImportUrgentMatchRule rule : customUrgentRules) {
            addUrgentRuleToSnapshot(
                    rule.getNormalizedText(),
                    rule.getMatchType(),
                    seenUrgentValues,
                    urgentExactValues,
                    urgentContainsValues
            );
        }

        return new ImportFieldSettingsSnapshot(
                headerAliases,
                customHeaderAliases,
                urgentExactValues,
                urgentContainsValues
        );
    }

    public static String normalizeHeader(String value) {
        if (value == null) {
            return "";
        }

        String normalizedCase = value.trim().toLowerCase(Locale.ROOT);
        StringBuilder normalized = new StringBuilder(normalizedCase.length());
        normalizedCase.codePoints()
                .filter((codePoint) -> codePoint != '_' && codePoint != '-')
                .filter((codePoint) -> !Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint))
                .forEach(normalized::appendCodePoint);
        return normalized.toString();
    }

    public static String normalizeUrgentValue(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private ValidatedSettings validate(UpdateImportFieldSettingsRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("字段设置不可为空");
        }

        List<UpdateImportFieldSettingsRequest.FieldAliases> requestedFields = request.fields();
        if (requestedFields == null) {
            throw new IllegalArgumentException("字段设置不可为空");
        }

        if (requestedFields.size() != ImportFieldKey.values().length) {
            throw new IllegalArgumentException("字段设置必须且只能包含全部 7 个字段");
        }

        Map<String, ImportFieldKey> aliasOwners = new LinkedHashMap<>(BUILT_IN_HEADER_ALIASES);
        Set<String> builtInAliases = BUILT_IN_HEADER_ALIASES.keySet();
        Set<ImportFieldKey> seenFields = new HashSet<>();
        List<ImportFieldAlias> aliases = new ArrayList<>();

        for (UpdateImportFieldSettingsRequest.FieldAliases requestedField : requestedFields) {
            if (requestedField == null) {
                throw new IllegalArgumentException("字段设置不可包含空项目");
            }

            ImportFieldKey fieldKey = parseFieldKey(requestedField.key());
            if (!seenFields.add(fieldKey)) {
                throw new IllegalArgumentException("字段设置重复：" + fieldKey.getLabel());
            }
            if (requestedField.customAliases() == null) {
                throw new IllegalArgumentException(fieldKey.getLabel() + "的自定义字段名列表不可为空");
            }

            for (String requestedAlias : requestedField.customAliases()) {
                String alias = validateAndTrimAlias(requestedAlias);
                String normalizedAlias = normalizeHeader(alias);
                ImportFieldKey existingOwner = aliasOwners.putIfAbsent(normalizedAlias, fieldKey);
                if (existingOwner != null) {
                    if (builtInAliases.contains(normalizedAlias)) {
                        throw new IllegalArgumentException("自定义字段名“" + alias + "”与系统字段名冲突");
                    }
                    throw new IllegalArgumentException("自定义字段名标准化后不可重复：“" + alias + "”");
                }
                aliases.add(new ImportFieldAlias(fieldKey, alias, normalizedAlias));
            }
        }

        if (seenFields.size() != ImportFieldKey.values().length) {
            throw new IllegalArgumentException("字段设置必须且只能包含全部 7 个字段");
        }

        List<ImportUrgentMatchRule> urgentRules = validateUrgentRules(request.urgentRules());
        return new ValidatedSettings(aliases, urgentRules);
    }

    private ImportFieldKey parseFieldKey(String requestedKey) {
        String key = requestedKey == null ? "" : requestedKey.trim();
        return ImportFieldKey.fromApiKey(key)
                .orElseThrow(() -> new IllegalArgumentException("未知字段：" + key));
    }

    private String validateAndTrimAlias(String requestedAlias) {
        String alias = requestedAlias == null ? "" : requestedAlias.trim();
        if (alias.isEmpty()) {
            throw new IllegalArgumentException("自定义字段名不可为空");
        }
        if (alias.length() > MAX_ALIAS_LENGTH) {
            throw new IllegalArgumentException("自定义字段名最长为 " + MAX_ALIAS_LENGTH + " 个字符");
        }
        if (normalizeHeader(alias).isEmpty()) {
            throw new IllegalArgumentException("自定义字段名不可只包含空格、下划线或连字符");
        }
        return alias;
    }

    private List<ImportUrgentMatchRule> validateUrgentRules(
            UpdateImportFieldSettingsRequest.UrgentRules requestedRules
    ) {
        if (requestedRules == null || requestedRules.custom() == null) {
            throw new IllegalArgumentException("自定义加急判定规则列表不可为空");
        }

        Set<String> seenValues = new HashSet<>(BUILT_IN_URGENT_VALUES);
        List<ImportUrgentMatchRule> urgentRules = new ArrayList<>();
        for (UpdateImportFieldSettingsRequest.UrgentRule requestedRule : requestedRules.custom()) {
            if (requestedRule == null) {
                throw new IllegalArgumentException("加急判定规则不可包含空项目");
            }

            String text = requestedRule.text() == null ? "" : requestedRule.text().trim();
            if (text.isEmpty()) {
                throw new IllegalArgumentException("加急判定文字不可为空");
            }
            if (text.length() > MAX_URGENT_RULE_LENGTH) {
                throw new IllegalArgumentException("加急判定文字最长为 " + MAX_URGENT_RULE_LENGTH + " 个字符");
            }
            if (requestedRule.matchType() == null) {
                throw new IllegalArgumentException("加急判定方式不可为空");
            }

            String normalizedText = normalizeUrgentValue(text);
            if (!seenValues.add(normalizedText)) {
                if (BUILT_IN_URGENT_VALUES.contains(normalizedText)) {
                    throw new IllegalArgumentException("加急判定文字“" + text + "”与系统规则冲突");
                }
                throw new IllegalArgumentException("加急判定文字不可重复：“" + text + "”");
            }
            urgentRules.add(new ImportUrgentMatchRule(
                    text,
                    normalizedText,
                    requestedRule.matchType()
            ));
        }
        return urgentRules;
    }

    private ImportFieldSettingsResponse toResponse(
            List<ImportFieldAlias> aliases,
            List<ImportUrgentMatchRule> urgentRules
    ) {
        Map<ImportFieldKey, List<String>> customAliases = new EnumMap<>(ImportFieldKey.class);
        for (ImportFieldKey fieldKey : ImportFieldKey.values()) {
            customAliases.put(fieldKey, new ArrayList<>());
        }
        for (ImportFieldAlias alias : aliases) {
            customAliases.get(alias.getFieldKey()).add(alias.getAlias());
        }

        List<ImportFieldSettingsResponse.FieldSettings> fields = java.util.Arrays.stream(ImportFieldKey.values())
                .map((fieldKey) -> new ImportFieldSettingsResponse.FieldSettings(
                        fieldKey.getApiKey(),
                        fieldKey.getLabel(),
                        fieldKey.isRequired(),
                        fieldKey.getBuiltInAliases(),
                        List.copyOf(customAliases.get(fieldKey))
                ))
                .toList();

        List<ImportFieldSettingsResponse.UrgentRule> builtInRules = BUILT_IN_URGENT_RULES.stream()
                .map((rule) -> new ImportFieldSettingsResponse.UrgentRule(rule.text(), rule.matchType()))
                .toList();
        List<ImportFieldSettingsResponse.UrgentRule> customRules = urgentRules.stream()
                .map((rule) -> new ImportFieldSettingsResponse.UrgentRule(
                        rule.getText(),
                        rule.getMatchType()
                ))
                .toList();

        return new ImportFieldSettingsResponse(
                fields,
                new ImportFieldSettingsResponse.UrgentRules(builtInRules, customRules)
        );
    }

    private static Map<String, ImportFieldKey> builtInHeaderAliases() {
        Map<String, ImportFieldKey> aliases = new LinkedHashMap<>();
        for (ImportFieldKey fieldKey : ImportFieldKey.values()) {
            for (String alias : fieldKey.getBuiltInAliases()) {
                String normalizedAlias = normalizeHeader(alias);
                ImportFieldKey existing = aliases.putIfAbsent(normalizedAlias, fieldKey);
                if (existing != null) {
                    throw new IllegalStateException("系统字段名重复：" + alias);
                }
            }
        }
        return Map.copyOf(aliases);
    }

    private static Set<String> builtInUrgentValues() {
        Set<String> values = new HashSet<>();
        for (UrgentRuleDefinition rule : BUILT_IN_URGENT_RULES) {
            String normalizedValue = normalizeUrgentValue(rule.text());
            if (!values.add(normalizedValue)) {
                throw new IllegalStateException("系统加急判定文字重复：" + rule.text());
            }
        }
        return Set.copyOf(values);
    }

    private static void addUrgentRuleToSnapshot(
            String normalizedText,
            ImportUrgentMatchType matchType,
            Set<String> seenValues,
            Set<String> exactValues,
            List<String> containsValues
    ) {
        if (!seenValues.add(normalizedText)) {
            throw new IllegalStateException("字段设置存在重复加急判定文字，请重新保存设置");
        }
        if (matchType == ImportUrgentMatchType.EXACT) {
            exactValues.add(normalizedText);
        } else {
            containsValues.add(normalizedText);
        }
    }

    private record UrgentRuleDefinition(
            String text,
            ImportUrgentMatchType matchType
    ) {
    }

    private record ValidatedSettings(
            List<ImportFieldAlias> aliases,
            List<ImportUrgentMatchRule> urgentRules
    ) {
    }
}
