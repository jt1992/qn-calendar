package com.qn.calendar.settings.service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.qn.calendar.settings.constant.ImportUrgentMatchType;
import com.qn.calendar.settings.dto.ImportFieldSettingsResponse;
import com.qn.calendar.settings.dto.UpdateImportFieldSettingsRequest;
import com.qn.calendar.settings.entity.ImportFieldAlias;
import com.qn.calendar.settings.entity.ImportUrgentMatchRule;
import com.qn.calendar.settings.entity.RemarkTagDefinition;
import com.qn.calendar.settings.entity.RemarkTagMatchRule;
import com.qn.calendar.settings.model.ImportFieldKey;
import com.qn.calendar.settings.model.ImportFieldSettingsSnapshot;
import com.qn.calendar.settings.repository.ImportFieldAliasRepository;
import com.qn.calendar.settings.repository.ImportUrgentMatchRuleRepository;
import com.qn.calendar.settings.repository.RemarkTagDefinitionRepository;
import com.qn.calendar.settings.repository.RemarkTagMatchRuleRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ImportFieldSettingsService {

    public static final int MAX_ALIAS_LENGTH = 120;
    public static final int MAX_REMARK_TAG_NAME_LENGTH = 80;
    public static final int MAX_REMARK_TAG_RULE_LENGTH = 120;
    public static final String URGENT_SYSTEM_KEY = "URGENT";
    public static final String DEFAULT_URGENT_NAME = "加急";
    public static final String DEFAULT_URGENT_COLOR = "#FF6F61";

    private static final Pattern COLOR_PATTERN = Pattern.compile("#[0-9A-F]{6}");

    private static final Set<String> OBSOLETE_PAID_AT_ALIASES = Set.of(
            "訂單時間", "订单时间",
            "下單時間", "下单时间",
            "下單日期", "下单日期"
    );

    private static final Map<String, ImportFieldKey> BUILT_IN_HEADER_ALIASES = builtInHeaderAliases();

    private final ImportFieldAliasRepository fieldAliasRepository;
    private final ImportUrgentMatchRuleRepository legacyUrgentMatchRuleRepository;
    private final RemarkTagDefinitionRepository remarkTagRepository;
    private final RemarkTagMatchRuleRepository remarkTagMatchRuleRepository;

    public ImportFieldSettingsService(
            ImportFieldAliasRepository fieldAliasRepository,
            ImportUrgentMatchRuleRepository legacyUrgentMatchRuleRepository,
            RemarkTagDefinitionRepository remarkTagRepository,
            RemarkTagMatchRuleRepository remarkTagMatchRuleRepository
    ) {
        this.fieldAliasRepository = fieldAliasRepository;
        this.legacyUrgentMatchRuleRepository = legacyUrgentMatchRuleRepository;
        this.remarkTagRepository = remarkTagRepository;
        this.remarkTagMatchRuleRepository = remarkTagMatchRuleRepository;
    }

    @Transactional
    public ImportFieldSettingsResponse getSettings() {
        deleteObsoletePaidAtAliases();
        ensureDefaultUrgentTag();
        return toResponse(
                fieldAliasRepository.findAllByOrderByIdAsc(),
                remarkTagRepository.findAllByOrderByDisplayOrderAscIdAsc(),
                remarkTagMatchRuleRepository.findAllInDisplayOrder()
        );
    }

    @Transactional
    public ImportFieldSettingsResponse updateSettings(UpdateImportFieldSettingsRequest request) {
        ensureDefaultUrgentTag();
        List<RemarkTagDefinition> existingTags = remarkTagRepository.findAllByOrderByDisplayOrderAscIdAsc();
        ValidatedSettings validatedSettings = validate(request, existingTags);

        fieldAliasRepository.deleteAllInBatch();
        fieldAliasRepository.flush();
        List<ImportFieldAlias> aliases = fieldAliasRepository.saveAll(validatedSettings.aliases());

        remarkTagMatchRuleRepository.deleteAllInBatch();
        remarkTagMatchRuleRepository.flush();

        Set<Long> retainedIds = validatedSettings.remarkTags().stream()
                .map(ValidatedRemarkTag::existing)
                .filter(Objects::nonNull)
                .map(RemarkTagDefinition::getId)
                .collect(java.util.stream.Collectors.toSet());
        for (RemarkTagDefinition existingTag : existingTags) {
            if (retainedIds.contains(existingTag.getId())) {
                continue;
            }
            if (URGENT_SYSTEM_KEY.equals(existingTag.getSystemKey())) {
                throw new IllegalStateException("系统加急标签不可删除");
            }
            remarkTagRepository.deleteWorkOrderAssignments(existingTag.getId());
            remarkTagRepository.delete(existingTag);
        }
        remarkTagRepository.flush();

        for (ValidatedRemarkTag requestedTag : validatedSettings.remarkTags()) {
            if (requestedTag.existing() != null) {
                requestedTag.existing().stageNormalizedName(
                        "__tmp__" + UUID.randomUUID().toString().replace("-", "")
                );
            }
        }
        remarkTagRepository.flush();

        List<RemarkTagDefinition> savedTags = new ArrayList<>();
        for (ValidatedRemarkTag requestedTag : validatedSettings.remarkTags()) {
            RemarkTagDefinition tag = requestedTag.existing();
            if (tag == null) {
                tag = new RemarkTagDefinition(
                        null,
                        requestedTag.name(),
                        requestedTag.normalizedName(),
                        requestedTag.color(),
                        requestedTag.displayOrder()
                );
            } else {
                tag.update(
                        requestedTag.name(),
                        requestedTag.normalizedName(),
                        requestedTag.color(),
                        requestedTag.displayOrder()
                );
            }
            savedTags.add(remarkTagRepository.save(tag));
        }
        remarkTagRepository.flush();

        List<RemarkTagMatchRule> savedRules = new ArrayList<>();
        for (int tagIndex = 0; tagIndex < validatedSettings.remarkTags().size(); tagIndex++) {
            ValidatedRemarkTag requestedTag = validatedSettings.remarkTags().get(tagIndex);
            RemarkTagDefinition savedTag = savedTags.get(tagIndex);
            for (ValidatedContainsText requestedText : requestedTag.containsTexts()) {
                savedRules.add(new RemarkTagMatchRule(
                        savedTag,
                        requestedText.text(),
                        requestedText.normalizedText(),
                        ImportUrgentMatchType.CONTAINS,
                        requestedText.displayOrder()
                ));
            }
        }
        savedRules = remarkTagMatchRuleRepository.saveAll(savedRules);

        return toResponse(aliases, savedTags, savedRules);
    }

    @Transactional
    public ImportFieldSettingsSnapshot getImportSnapshot() {
        deleteObsoletePaidAtAliases();
        ensureDefaultUrgentTag();

        List<ImportFieldAlias> customAliases = fieldAliasRepository.findAllByOrderByIdAsc();
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

        List<RemarkTagDefinition> tags = remarkTagRepository.findAllByOrderByDisplayOrderAscIdAsc();
        Map<Long, List<RemarkTagMatchRule>> customRulesByTag = rulesByTag(
                remarkTagMatchRuleRepository.findAllInDisplayOrder()
        );
        Set<String> seenRuleValues = new HashSet<>();
        List<ImportFieldSettingsSnapshot.RemarkTagMatcher> matchers = new ArrayList<>();
        for (RemarkTagDefinition tag : tags) {
            List<String> containsValues = new ArrayList<>();
            addContainsTextToSnapshot(tag.getNormalizedName(), seenRuleValues, containsValues);
            for (RemarkTagMatchRule rule : customRulesByTag.getOrDefault(tag.getId(), List.of())) {
                addContainsTextToSnapshot(rule.getNormalizedText(), seenRuleValues, containsValues);
            }
            matchers.add(new ImportFieldSettingsSnapshot.RemarkTagMatcher(
                    tag.getId(),
                    tag.getSystemKey(),
                    containsValues
            ));
        }

        return new ImportFieldSettingsSnapshot(headerAliases, customHeaderAliases, matchers);
    }

    @Transactional
    public void upgradeLegacyData() {
        RemarkTagDefinition urgentTag = ensureDefaultUrgentTag();
        List<RemarkTagDefinition> tags = remarkTagRepository.findAllByOrderByDisplayOrderAscIdAsc();
        List<RemarkTagMatchRule> currentRules = remarkTagMatchRuleRepository.findAllInDisplayOrder();
        Set<String> seenValues = tags.stream()
                .map(RemarkTagDefinition::getNormalizedName)
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));
        Map<Long, Integer> nextDisplayOrders = new HashMap<>();
        for (RemarkTagMatchRule currentRule : currentRules) {
            String normalizedText = normalizeRemarkTagValue(currentRule.getText());
            if (normalizedText.isBlank() || !seenValues.add(normalizedText)) {
                remarkTagMatchRuleRepository.delete(currentRule);
                continue;
            }
            int displayOrder = nextDisplayOrders.getOrDefault(currentRule.getRemarkTag().getId(), 0);
            currentRule.normalizeAsContains(displayOrder);
            nextDisplayOrders.put(currentRule.getRemarkTag().getId(), displayOrder + 1);
        }
        remarkTagMatchRuleRepository.flush();

        int nextUrgentDisplayOrder = nextDisplayOrders.getOrDefault(urgentTag.getId(), 0);
        List<RemarkTagMatchRule> migratedRules = new ArrayList<>();
        for (ImportUrgentMatchRule legacyRule : legacyUrgentMatchRuleRepository.findAllByOrderByIdAsc()) {
            String normalizedText = normalizeRemarkTagValue(legacyRule.getText());
            if (normalizedText.isBlank() || !seenValues.add(normalizedText)) {
                continue;
            }
            migratedRules.add(new RemarkTagMatchRule(
                    urgentTag,
                    legacyRule.getText().trim(),
                    normalizedText,
                    ImportUrgentMatchType.CONTAINS,
                    nextUrgentDisplayOrder++
            ));
        }
        remarkTagMatchRuleRepository.saveAll(migratedRules);
        legacyUrgentMatchRuleRepository.deleteAllInBatch();
        legacyUrgentMatchRuleRepository.flush();
        remarkTagRepository.backfillUrgentAssignments(urgentTag.getId());
    }

    @Transactional(readOnly = true)
    public List<RemarkTagDefinition> findRemarkTagsByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        Map<Long, RemarkTagDefinition> tagsById = new HashMap<>();
        remarkTagRepository.findAllById(ids).forEach((tag) -> tagsById.put(tag.getId(), tag));
        return ids.stream().map(tagsById::get).filter(Objects::nonNull).toList();
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

    public static String normalizeRemarkTagValue(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private ValidatedSettings validate(
            UpdateImportFieldSettingsRequest request,
            List<RemarkTagDefinition> existingTags
    ) {
        if (request == null) {
            throw new IllegalArgumentException("字段设置不可为空");
        }
        List<ImportFieldAlias> aliases = validateAliases(request.fields());
        List<ValidatedRemarkTag> remarkTags = validateRemarkTags(request.remarkTags(), existingTags);
        return new ValidatedSettings(aliases, remarkTags);
    }

    private List<ImportFieldAlias> validateAliases(
            List<UpdateImportFieldSettingsRequest.FieldAliases> requestedFields
    ) {
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
                String alias = validateAndTrimAlias(fieldKey, requestedAlias);
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
        return aliases;
    }

    private List<ValidatedRemarkTag> validateRemarkTags(
            List<UpdateImportFieldSettingsRequest.RemarkTag> requestedTags,
            List<RemarkTagDefinition> existingTags
    ) {
        if (requestedTags == null || requestedTags.isEmpty()) {
            throw new IllegalArgumentException("请至少保留系统加急标签");
        }

        Map<Long, RemarkTagDefinition> existingById = existingTags.stream()
                .collect(java.util.stream.Collectors.toMap(RemarkTagDefinition::getId, (tag) -> tag));
        RemarkTagDefinition urgentTag = existingTags.stream()
                .filter((tag) -> URGENT_SYSTEM_KEY.equals(tag.getSystemKey()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("找不到系统加急标签"));
        Set<Long> seenIds = new HashSet<>();
        Set<String> seenNames = new HashSet<>();
        Set<String> seenContainsTexts = new HashSet<>();
        boolean urgentSeen = false;
        List<ValidatedRemarkTag> validatedTags = new ArrayList<>();

        for (int tagIndex = 0; tagIndex < requestedTags.size(); tagIndex++) {
            UpdateImportFieldSettingsRequest.RemarkTag requestedTag = requestedTags.get(tagIndex);
            if (requestedTag == null) {
                throw new IllegalArgumentException("备注标签设置不可包含空项目");
            }
            String systemKey = normalizeSystemKey(requestedTag.systemKey());
            RemarkTagDefinition existing = requestedTag.id() == null
                    ? null
                    : existingById.get(requestedTag.id());
            if (requestedTag.id() != null && existing == null) {
                throw new IllegalArgumentException("找不到备注标签 ID：" + requestedTag.id());
            }
            if (existing != null && !seenIds.add(existing.getId())) {
                throw new IllegalArgumentException("备注标签不可重复");
            }

            if (URGENT_SYSTEM_KEY.equals(systemKey) && existing == null) {
                existing = urgentTag;
                if (!seenIds.add(existing.getId())) {
                    throw new IllegalArgumentException("系统加急标签不可重复");
                }
            }
            if (existing != null
                    && !normalizeSystemKey(existing.getSystemKey()).equals(systemKey)) {
                throw new IllegalArgumentException("备注标签的系统标识不可修改");
            }
            if (!systemKey.isEmpty() && !URGENT_SYSTEM_KEY.equals(systemKey)) {
                throw new IllegalArgumentException("未知备注标签系统标识：" + systemKey);
            }
            if (existing == null && !systemKey.isEmpty()) {
                throw new IllegalArgumentException("自定义备注标签不可设置系统标识");
            }
            if (URGENT_SYSTEM_KEY.equals(systemKey)) {
                if (urgentSeen) {
                    throw new IllegalArgumentException("系统加急标签不可重复");
                }
                urgentSeen = true;
            }

            String name = trim(requestedTag.name());
            if (name.isEmpty()) {
                throw new IllegalArgumentException("备注标签名称不可为空");
            }
            if (name.length() > MAX_REMARK_TAG_NAME_LENGTH) {
                throw new IllegalArgumentException("备注标签名称最长为 " + MAX_REMARK_TAG_NAME_LENGTH + " 个字符");
            }
            String normalizedName = normalizeRemarkTagValue(name);
            if (!seenNames.add(normalizedName)) {
                throw new IllegalArgumentException("备注标签名称不可重复：“" + name + "”");
            }
            if (seenContainsTexts.contains(normalizedName)) {
                throw new IllegalArgumentException("备注标签名称不可与包含文字重复：“" + name + "”");
            }

            String color = trim(requestedTag.color()).toUpperCase(Locale.ROOT);
            if (!COLOR_PATTERN.matcher(color).matches()) {
                throw new IllegalArgumentException("备注标签颜色必须是六位十六进制色码");
            }
            if (requestedTag.containsTexts() == null) {
                throw new IllegalArgumentException("包含文字列表不可为空");
            }

            List<ValidatedContainsText> containsTexts = new ArrayList<>();
            for (int textIndex = 0; textIndex < requestedTag.containsTexts().size(); textIndex++) {
                String requestedText = requestedTag.containsTexts().get(textIndex);
                if (requestedText == null) {
                    throw new IllegalArgumentException("包含文字不可包含空项目");
                }
                String text = trim(requestedText);
                if (text.isEmpty()) {
                    throw new IllegalArgumentException("包含文字不可为空");
                }
                if (text.length() > MAX_REMARK_TAG_RULE_LENGTH) {
                    throw new IllegalArgumentException("包含文字最长为 " + MAX_REMARK_TAG_RULE_LENGTH + " 个字符");
                }
                String normalizedText = normalizeRemarkTagValue(text);
                if (seenNames.contains(normalizedText)) {
                    throw new IllegalArgumentException("包含文字不可与备注标签名称重复：“" + text + "”");
                }
                if (!seenContainsTexts.add(normalizedText)) {
                    throw new IllegalArgumentException("包含文字不可重复：“" + text + "”");
                }
                containsTexts.add(new ValidatedContainsText(
                        text,
                        normalizedText,
                        textIndex
                ));
            }
            validatedTags.add(new ValidatedRemarkTag(
                    existing,
                    name,
                    normalizedName,
                    color,
                    tagIndex,
                    containsTexts
            ));
        }
        if (!urgentSeen) {
            throw new IllegalArgumentException("系统加急标签不可删除");
        }
        return validatedTags;
    }

    private RemarkTagDefinition ensureDefaultUrgentTag() {
        return remarkTagRepository.findBySystemKey(URGENT_SYSTEM_KEY)
                .orElseGet(() -> remarkTagRepository.saveAndFlush(new RemarkTagDefinition(
                        URGENT_SYSTEM_KEY,
                        DEFAULT_URGENT_NAME,
                        normalizeRemarkTagValue(DEFAULT_URGENT_NAME),
                        DEFAULT_URGENT_COLOR,
                        0
                )));
    }

    private ImportFieldKey parseFieldKey(String requestedKey) {
        String key = trim(requestedKey);
        return ImportFieldKey.fromApiKey(key)
                .orElseThrow(() -> new IllegalArgumentException("未知字段：" + key));
    }

    private String validateAndTrimAlias(ImportFieldKey fieldKey, String requestedAlias) {
        String alias = trim(requestedAlias);
        if (alias.isEmpty()) {
            throw new IllegalArgumentException("自定义字段名不可为空");
        }
        if (alias.length() > MAX_ALIAS_LENGTH) {
            throw new IllegalArgumentException("自定义字段名最长为 " + MAX_ALIAS_LENGTH + " 个字符");
        }
        if (normalizeHeader(alias).isEmpty()) {
            throw new IllegalArgumentException("自定义字段名不可只包含空格、下划线或连字符");
        }
        if (fieldKey == ImportFieldKey.PAID_AT && OBSOLETE_PAID_AT_ALIASES.contains(normalizeHeader(alias))) {
            throw new IllegalArgumentException("自定义字段名“" + alias + "”不能用于订单付款时间");
        }
        return alias;
    }

    private void deleteObsoletePaidAtAliases() {
        fieldAliasRepository.deleteAllByFieldKeyAndNormalizedAliasIn(
                ImportFieldKey.PAID_AT,
                OBSOLETE_PAID_AT_ALIASES
        );
    }

    private ImportFieldSettingsResponse toResponse(
            List<ImportFieldAlias> aliases,
            List<RemarkTagDefinition> tags,
            List<RemarkTagMatchRule> rules
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

        Map<Long, List<RemarkTagMatchRule>> customRulesByTag = rulesByTag(rules);
        List<ImportFieldSettingsResponse.RemarkTagSettings> responseTags = tags.stream()
                .sorted(java.util.Comparator.comparingInt(RemarkTagDefinition::getDisplayOrder)
                        .thenComparing(RemarkTagDefinition::getId))
                .map((tag) -> new ImportFieldSettingsResponse.RemarkTagSettings(
                        tag.getId(),
                        tag.getSystemKey(),
                        tag.getName(),
                        tag.getColor(),
                        customRulesByTag.getOrDefault(tag.getId(), List.of()).stream()
                                .map(RemarkTagMatchRule::getText)
                                .toList()
                ))
                .toList();
        return new ImportFieldSettingsResponse(fields, responseTags);
    }

    private Map<Long, List<RemarkTagMatchRule>> rulesByTag(List<RemarkTagMatchRule> rules) {
        Map<Long, List<RemarkTagMatchRule>> rulesByTag = new LinkedHashMap<>();
        for (RemarkTagMatchRule rule : rules) {
            rulesByTag.computeIfAbsent(rule.getRemarkTag().getId(), ignored -> new ArrayList<>()).add(rule);
        }
        return rulesByTag;
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

    private static void addContainsTextToSnapshot(
            String normalizedText,
            Set<String> seenValues,
            List<String> containsValues
    ) {
        if (!seenValues.add(normalizedText)) {
            throw new IllegalStateException("字段设置存在重复备注标签名称或包含文字，请重新保存设置");
        }
        containsValues.add(normalizedText);
    }

    private static String normalizeSystemKey(String value) {
        return trim(value).toUpperCase(Locale.ROOT);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private record ValidatedContainsText(
            String text,
            String normalizedText,
            int displayOrder
    ) {
    }

    private record ValidatedRemarkTag(
            RemarkTagDefinition existing,
            String name,
            String normalizedName,
            String color,
            int displayOrder,
            List<ValidatedContainsText> containsTexts
    ) {
    }

    private record ValidatedSettings(
            List<ImportFieldAlias> aliases,
            List<ValidatedRemarkTag> remarkTags
    ) {
    }
}
