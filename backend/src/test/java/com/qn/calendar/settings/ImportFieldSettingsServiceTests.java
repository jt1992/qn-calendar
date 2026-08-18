package com.qn.calendar.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qn.calendar.settings.constant.ImportUrgentMatchType;
import com.qn.calendar.settings.dto.ImportFieldSettingsResponse;
import com.qn.calendar.settings.dto.UpdateImportFieldSettingsRequest;
import com.qn.calendar.settings.entity.ImportFieldAlias;
import com.qn.calendar.settings.entity.ImportUrgentMatchRule;
import com.qn.calendar.settings.entity.RemarkTagDefinition;
import com.qn.calendar.settings.entity.RemarkTagMatchRule;
import com.qn.calendar.settings.model.ImportFieldKey;
import com.qn.calendar.settings.repository.ImportFieldAliasRepository;
import com.qn.calendar.settings.repository.ImportUrgentMatchRuleRepository;
import com.qn.calendar.settings.repository.RemarkTagDefinitionRepository;
import com.qn.calendar.settings.repository.RemarkTagMatchRuleRepository;
import com.qn.calendar.settings.service.ImportFieldSettingsService;
import com.qn.calendar.workorder.entity.WorkOrder;
import com.qn.calendar.workorder.repository.WorkOrderRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class ImportFieldSettingsServiceTests {

    @Autowired
    private ImportFieldSettingsService service;

    @Autowired
    private ImportFieldAliasRepository fieldAliasRepository;

    @Autowired
    private ImportUrgentMatchRuleRepository urgentMatchRuleRepository;

    @Autowired
    private RemarkTagDefinitionRepository remarkTagRepository;

    @Autowired
    private RemarkTagMatchRuleRepository remarkTagMatchRuleRepository;

    @Autowired
    private WorkOrderRepository workOrderRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        workOrderRepository.deleteAll();
        remarkTagRepository.deleteAllWorkOrderAssignments();
        remarkTagMatchRuleRepository.deleteAllInBatch();
        remarkTagRepository.deleteAllInBatch();
        fieldAliasRepository.deleteAllInBatch();
        urgentMatchRuleRepository.deleteAllInBatch();
        service.getSettings();
    }

    @Test
    void getSettingsReturnsDefaultUrgentTagWithoutPresetContainsTexts() {
        ImportFieldSettingsResponse settings = service.getSettings();

        assertThat(settings.fields())
                .extracting(ImportFieldSettingsResponse.FieldSettings::key)
                .containsExactly(
                        "orderNo",
                        "price",
                        "latestShipTime",
                        "urgent",
                        "buyerMessage",
                        "merchantRemark",
                        "paidAt"
                );
        assertThat(field(settings, "orderNo").required()).isTrue();
        assertThat(field(settings, "price").required()).isTrue();
        assertThat(field(settings, "latestShipTime").required()).isTrue();
        assertThat(field(settings, "urgent").required()).isFalse();
        assertThat(settings.fields()).allSatisfy((field) -> assertThat(field.customAliases()).isEmpty());
        assertThat(settings.remarkTags()).singleElement().satisfies((tag) -> {
            assertThat(tag.id()).isNotNull();
            assertThat(tag.systemKey()).isEqualTo("URGENT");
            assertThat(tag.name()).isEqualTo("加急");
            assertThat(tag.color()).isEqualTo("#FF6F61");
            assertThat(tag.containsTexts()).isEmpty();
        });
        assertThat(fieldAliasRepository.count()).isZero();
        assertThat(urgentMatchRuleRepository.count()).isZero();
        assertThat(remarkTagRepository.count()).isEqualTo(1);
        assertThat(remarkTagMatchRuleRepository.count()).isZero();
    }

    @Test
    void settingsJsonOnlyExposesContainsTextsForRemarkTagMatching() throws Exception {
        String json = objectMapper.writeValueAsString(service.getSettings());

        assertThat(json).contains("\"fields\"", "\"remarkTags\"", "\"containsTexts\"");
        assertThat(json).doesNotContain(
                "\"urgentRules\"",
                "\"builtInRules\"",
                "\"customRules\"",
                "\"matchType\""
        );
    }

    @Test
    void defaultsOnlyUseQianniuAndXiaohongshuExportHeaders() {
        ImportFieldSettingsResponse settings = service.getSettings();

        assertThat(field(settings, "orderNo").builtInAliases())
                .containsExactly("订单编号", "订单号");
        assertThat(field(settings, "price").builtInAliases())
                .containsExactly("买家实付金额", "用户应付金额(元)");
        assertThat(field(settings, "latestShipTime").builtInAliases())
                .containsExactly("应发货时间", "承诺发货时间");
        assertThat(field(settings, "urgent").builtInAliases())
                .containsExactly("备注标签", "包裹备注标记");
        assertThat(field(settings, "buyerMessage").builtInAliases())
                .containsExactly("买家留言", "用户备注");
        assertThat(field(settings, "merchantRemark").builtInAliases())
                .containsExactly("商家备注", "包裹备注信息");
        assertThat(field(settings, "paidAt").builtInAliases())
                .containsExactly("订单付款时间", "支付时间");
    }

    @Test
    void updateSettingsReplacesAndReturnsContainsTexts() {
        service.updateSettings(request(
                Map.of(
                        ImportFieldKey.ORDER_NO, List.of(" 自定义订单号 "),
                        ImportFieldKey.PRICE, List.of("实收金额")
                ),
                List.of(" 红旗 ", "特别紧急")
        ));

        ImportFieldSettingsResponse settings = service.getSettings();

        assertThat(field(settings, "orderNo").customAliases()).containsExactly("自定义订单号");
        assertThat(field(settings, "price").customAliases()).containsExactly("实收金额");
        assertThat(field(settings, "urgent").customAliases()).isEmpty();
        assertThat(settings.remarkTags().getFirst().containsTexts())
                .containsExactly("红旗", "特别紧急");
        assertThat(fieldAliasRepository.count()).isEqualTo(2);
        assertThat(urgentMatchRuleRepository.count()).isZero();
        assertThat(remarkTagMatchRuleRepository.findAllInDisplayOrder())
                .allSatisfy((rule) -> assertThat(rule.getMatchType()).isEqualTo(ImportUrgentMatchType.CONTAINS));
    }

    @Test
    void updateSettingsRejectsNormalizedAliasDuplicatesAcrossFields() {
        assertThatThrownBy(() -> service.updateSettings(request(
                Map.of(
                        ImportFieldKey.ORDER_NO, List.of("My Field"),
                        ImportFieldKey.PRICE, List.of("my_field")
                ),
                List.of()
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("自定义字段名标准化后不可重复：“my_field”");

        assertThat(fieldAliasRepository.count()).isZero();
    }

    @Test
    void updateSettingsRejectsAliasConflictWithBuiltInDefaults() {
        assertThatThrownBy(() -> service.updateSettings(request(
                Map.of(ImportFieldKey.ORDER_NO, List.of(" 订单号 ")),
                List.of()
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("自定义字段名“订单号”与系统字段名冲突");
    }

    @Test
    void removesAndRejectsObsoletePaidAtAliases() {
        fieldAliasRepository.save(new ImportFieldAlias(
                ImportFieldKey.PAID_AT,
                "订单时间",
                ImportFieldSettingsService.normalizeHeader("订单时间")
        ));

        ImportFieldSettingsResponse settings = service.getSettings();

        assertThat(field(settings, "paidAt").customAliases()).isEmpty();
        assertThat(fieldAliasRepository.count()).isZero();
        assertThatThrownBy(() -> service.updateSettings(request(
                Map.of(ImportFieldKey.PAID_AT, List.of("下单时间")),
                List.of()
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("自定义字段名“下单时间”不能用于订单付款时间");
    }

    @Test
    void updateSettingsRejectsUnknownOrRepeatedFieldKeys() {
        List<UpdateImportFieldSettingsRequest.FieldAliases> unknownFields = new ArrayList<>(emptyFields());
        unknownFields.set(0, new UpdateImportFieldSettingsRequest.FieldAliases("unknown", List.of()));

        assertThatThrownBy(() -> service.updateSettings(new UpdateImportFieldSettingsRequest(
                unknownFields,
                List.of(urgentTag(List.of()))
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("未知字段：unknown");

        List<UpdateImportFieldSettingsRequest.FieldAliases> repeatedFields = new ArrayList<>(emptyFields());
        repeatedFields.set(1, new UpdateImportFieldSettingsRequest.FieldAliases("orderNo", List.of()));

        assertThatThrownBy(() -> service.updateSettings(new UpdateImportFieldSettingsRequest(
                repeatedFields,
                List.of(urgentTag(List.of()))
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("字段设置重复：订单编号");
    }

    @Test
    void updateSettingsRejectsEmptyNullAndOverlongContainsTexts() {
        assertThatThrownBy(() -> service.updateSettings(request(
                Map.of(ImportFieldKey.ORDER_NO, List.of(" _ - ")),
                List.of()
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("自定义字段名不可只包含空格、下划线或连字符");

        assertThatThrownBy(() -> service.updateSettings(request(Map.of(), List.of("x".repeat(121)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("包含文字最长为 120 个字符");

        assertThatThrownBy(() -> service.updateSettings(request(Map.of(), List.of("   "))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("包含文字不可为空");

        assertThatThrownBy(() -> service.updateSettings(new UpdateImportFieldSettingsRequest(
                emptyFields(),
                List.of(urgentTag(null))
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("包含文字列表不可为空");

        List<String> containsNullItem = new ArrayList<>();
        containsNullItem.add(null);
        assertThatThrownBy(() -> service.updateSettings(request(Map.of(), containsNullItem)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("包含文字不可包含空项目");

        assertThatThrownBy(() -> service.updateSettings(new UpdateImportFieldSettingsRequest(
                emptyFields(),
                List.of(new UpdateImportFieldSettingsRequest.RemarkTag(
                        null,
                        "URGENT",
                        null,
                        "#FF6F61",
                        List.of()
                ))
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("备注标签名称不可为空");

        assertThatThrownBy(() -> service.updateSettings(new UpdateImportFieldSettingsRequest(
                emptyFields(),
                List.of(new UpdateImportFieldSettingsRequest.RemarkTag(
                        null,
                        "URGENT",
                        "x".repeat(81),
                        "#FF6F61",
                        List.of()
                ))
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("备注标签名称最长为 80 个字符");
    }

    @Test
    void updateSettingsRejectsContainsTextDuplicatesAndNameConflictsGlobally() {
        assertThatThrownBy(() -> service.updateSettings(request(
                Map.of(),
                List.of("Red Flag", " red flag ")
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("包含文字不可重复：“red flag”");

        assertThatThrownBy(() -> service.updateSettings(request(Map.of(), List.of(" 加急 "))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("包含文字不可与备注标签名称重复：“加急”");

        assertThatThrownBy(() -> service.updateSettings(new UpdateImportFieldSettingsRequest(
                emptyFields(),
                List.of(
                        urgentTag(List.of("延后")),
                        customTag("延后", "#3B82F6", List.of())
                )
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("备注标签名称不可与包含文字重复：“延后”");
    }

    @Test
    void invalidReplacementLeavesPreviousSnapshotUnchanged() {
        service.updateSettings(request(
                Map.of(ImportFieldKey.ORDER_NO, List.of("旧订单号")),
                List.of("红旗")
        ));

        assertThatThrownBy(() -> service.updateSettings(request(
                Map.of(ImportFieldKey.ORDER_NO, List.of("订单号")),
                List.of()
        )))
                .isInstanceOf(IllegalArgumentException.class);

        ImportFieldSettingsResponse settings = service.getSettings();
        assertThat(field(settings, "orderNo").customAliases()).containsExactly("旧订单号");
        assertThat(settings.remarkTags().getFirst().containsTexts()).containsExactly("红旗");
    }

    @Test
    void importSnapshotMatchesTagNameAndCustomContainsTextsOnly() {
        ImportFieldSettingsResponse configured = service.updateSettings(request(
                Map.of(ImportFieldKey.ORDER_NO, List.of(" My_Order-No ")),
                List.of(" RED FLAG ", "优先")
        ));

        var snapshot = service.getImportSnapshot();
        Long urgentId = configured.remarkTags().getFirst().id();

        assertThat(snapshot.headerAliases())
                .containsEntry("订单号", ImportFieldKey.ORDER_NO)
                .containsEntry("用户应付金额(元)", ImportFieldKey.PRICE)
                .containsEntry("承诺发货时间", ImportFieldKey.LATEST_SHIP_TIME)
                .containsEntry("包裹备注标记", ImportFieldKey.URGENT)
                .doesNotContainKeys("加急", "急件")
                .containsEntry("myorderno", ImportFieldKey.ORDER_NO);
        assertThat(snapshot.customHeaderAliases()).containsExactly("myorderno");
        assertThat(snapshot.matchingRemarkTagIds(normalize("请加急处理"))).containsExactly(urgentId);
        assertThat(snapshot.matchingRemarkTagIds(normalize("RED FLAG order"))).containsExactly(urgentId);
        assertThat(snapshot.matchingRemarkTagIds(normalize("true yes 急件 是"))).isEmpty();
        assertThat(ImportFieldSettingsService.normalizeHeader("  MY_ Field-name\t"))
                .isEqualTo("myfieldname");
    }

    @Test
    void supportsOrderedCustomTagsAndMatchesMultipleTagsFromOneValue() {
        Long urgentId = service.getSettings().remarkTags().getFirst().id();

        ImportFieldSettingsResponse settings = service.updateSettings(new UpdateImportFieldSettingsRequest(
                emptyFields(),
                List.of(
                        urgentTag(urgentId, List.of("赶快")),
                        customTag("延后", "#3b82f6", List.of("稍后")),
                        customTag("不限时间", "#22c55e", List.of())
                )
        ));

        assertThat(settings.remarkTags())
                .extracting(ImportFieldSettingsResponse.RemarkTagSettings::name)
                .containsExactly("加急", "延后", "不限时间");
        assertThat(settings.remarkTags())
                .extracting(ImportFieldSettingsResponse.RemarkTagSettings::color)
                .containsExactly("#FF6F61", "#3B82F6", "#22C55E");

        var snapshot = service.getImportSnapshot();
        List<Long> matches = snapshot.matchingRemarkTagIds(normalize("需要加急并稍后处理"));
        assertThat(matches).containsExactly(
                settings.remarkTags().get(0).id(),
                settings.remarkTags().get(1).id()
        );
        assertThat(snapshot.containsSystemTag(matches, "URGENT")).isTrue();

        ImportFieldSettingsResponse.RemarkTagSettings delayTag = settings.remarkTags().get(1);
        ImportFieldSettingsResponse.RemarkTagSettings noLimitTag = settings.remarkTags().get(2);
        ImportFieldSettingsResponse reordered = service.updateSettings(new UpdateImportFieldSettingsRequest(
                emptyFields(),
                List.of(
                        urgentTag(settings.remarkTags().getFirst().id(), List.of("赶快")),
                        new UpdateImportFieldSettingsRequest.RemarkTag(
                                noLimitTag.id(), null, "不限时", "#22C55E", List.of()
                        ),
                        new UpdateImportFieldSettingsRequest.RemarkTag(
                                delayTag.id(), null, "延后", "#3B82F6", List.of("稍后")
                        )
                )
        ));

        assertThat(reordered.remarkTags())
                .extracting(ImportFieldSettingsResponse.RemarkTagSettings::id)
                .containsExactly(settings.remarkTags().getFirst().id(), noLimitTag.id(), delayTag.id());
        assertThat(reordered.remarkTags())
                .extracting(ImportFieldSettingsResponse.RemarkTagSettings::name)
                .containsExactly("加急", "不限时", "延后");
    }

    @Test
    void rejectsNormalizedNamesAndContainsTextsDuplicatedAcrossTags() {
        Long urgentId = service.getSettings().remarkTags().getFirst().id();

        assertThatThrownBy(() -> service.updateSettings(new UpdateImportFieldSettingsRequest(
                emptyFields(),
                List.of(
                        urgentTag(urgentId, List.of()),
                        customTag(" 加急 ", "#3B82F6", List.of())
                )
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("备注标签名称不可重复：“加急”");

        assertThatThrownBy(() -> service.updateSettings(new UpdateImportFieldSettingsRequest(
                emptyFields(),
                List.of(
                        urgentTag(urgentId, List.of("红旗")),
                        customTag("延后", "#3B82F6", List.of(" 红旗 "))
                )
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("包含文字不可重复：“红旗”");
    }

    @Test
    void deletingCustomTagClearsAssignmentsWithoutDeletingWorkOrder() {
        Long urgentId = service.getSettings().remarkTags().getFirst().id();
        ImportFieldSettingsResponse configured = service.updateSettings(new UpdateImportFieldSettingsRequest(
                emptyFields(),
                List.of(
                        urgentTag(urgentId, List.of()),
                        customTag("延后", "#3B82F6", List.of("稍后"))
                )
        ));
        Long delayTagId = configured.remarkTags().get(1).id();
        WorkOrder workOrder = new WorkOrder(
                "TAG-DELETE-001",
                BigDecimal.valueOf(100),
                60,
                false,
                LocalDateTime.of(2026, 9, 1, 18, 0)
        );
        workOrder.replaceRemarkTags(service.findRemarkTagsByIds(List.of(delayTagId)));
        Long workOrderId = workOrderRepository.save(workOrder).getId();

        service.updateSettings(new UpdateImportFieldSettingsRequest(
                emptyFields(),
                List.of(urgentTag(urgentId, List.of()))
        ));

        assertThat(workOrderRepository.findById(workOrderId)).get().satisfies((saved) -> {
            assertThat(saved.getRemarkTags()).isEmpty();
            assertThat(saved.isUrgent()).isFalse();
        });
        assertThat(remarkTagRepository.findById(delayTagId)).isEmpty();
    }

    @Test
    void legacyUpgradeNormalizesRulesRemovesNameDuplicatesAndBackfillsUrgentAssignments() {
        RemarkTagDefinition urgentTag = remarkTagRepository.findBySystemKey("URGENT").orElseThrow();
        remarkTagMatchRuleRepository.saveAll(List.of(
                new RemarkTagMatchRule(
                        urgentTag,
                        "加急",
                        "加急",
                        ImportUrgentMatchType.EXACT,
                        0
                ),
                new RemarkTagMatchRule(
                        urgentTag,
                        "旧红旗",
                        "旧红旗",
                        ImportUrgentMatchType.EXACT,
                        1
                )
        ));
        urgentMatchRuleRepository.saveAll(List.of(
                new ImportUrgentMatchRule(" 红旗 ", "红旗", ImportUrgentMatchType.EXACT),
                new ImportUrgentMatchRule("加急", "加急", ImportUrgentMatchType.EXACT)
        ));
        WorkOrder workOrder = workOrderRepository.save(new WorkOrder(
                "TAG-UPGRADE-001",
                BigDecimal.valueOf(100),
                60,
                true,
                LocalDateTime.of(2026, 9, 1, 18, 0)
        ));

        service.upgradeLegacyData();
        service.upgradeLegacyData();

        assertThat(urgentMatchRuleRepository.count()).isZero();
        assertThat(remarkTagMatchRuleRepository.findAllInDisplayOrder())
                .extracting(RemarkTagMatchRule::getText)
                .containsExactly("旧红旗", "红旗");
        assertThat(remarkTagMatchRuleRepository.findAllInDisplayOrder())
                .allSatisfy((rule) -> assertThat(rule.getMatchType()).isEqualTo(ImportUrgentMatchType.CONTAINS));
        assertThat(service.getSettings().remarkTags().getFirst().containsTexts())
                .containsExactly("旧红旗", "红旗");
        assertThat(workOrderRepository.findById(workOrder.getId())).get().satisfies((saved) -> {
            assertThat(saved.getRemarkTags())
                    .extracting(RemarkTagDefinition::getSystemKey)
                    .containsExactly("URGENT");
            assertThat(saved.isUrgent()).isTrue();
        });
    }

    private ImportFieldSettingsResponse.FieldSettings field(
            ImportFieldSettingsResponse settings,
            String key
    ) {
        return settings.fields().stream()
                .filter((field) -> field.key().equals(key))
                .findFirst()
                .orElseThrow();
    }

    private UpdateImportFieldSettingsRequest request(
            Map<ImportFieldKey, List<String>> aliases,
            List<String> containsTexts
    ) {
        List<UpdateImportFieldSettingsRequest.FieldAliases> fields = java.util.Arrays.stream(ImportFieldKey.values())
                .map((fieldKey) -> new UpdateImportFieldSettingsRequest.FieldAliases(
                        fieldKey.getApiKey(),
                        aliases.getOrDefault(fieldKey, List.of())
                ))
                .toList();
        return new UpdateImportFieldSettingsRequest(fields, List.of(urgentTag(containsTexts)));
    }

    private List<UpdateImportFieldSettingsRequest.FieldAliases> emptyFields() {
        return java.util.Arrays.stream(ImportFieldKey.values())
                .map((fieldKey) -> new UpdateImportFieldSettingsRequest.FieldAliases(
                        fieldKey.getApiKey(),
                        List.of()
                ))
                .toList();
    }

    private UpdateImportFieldSettingsRequest.RemarkTag urgentTag(List<String> containsTexts) {
        return urgentTag(null, containsTexts);
    }

    private UpdateImportFieldSettingsRequest.RemarkTag urgentTag(Long id, List<String> containsTexts) {
        return new UpdateImportFieldSettingsRequest.RemarkTag(
                id,
                "URGENT",
                "加急",
                "#FF6F61",
                containsTexts
        );
    }

    private UpdateImportFieldSettingsRequest.RemarkTag customTag(
            String name,
            String color,
            List<String> containsTexts
    ) {
        return new UpdateImportFieldSettingsRequest.RemarkTag(null, null, name, color, containsTexts);
    }

    private String normalize(String value) {
        return ImportFieldSettingsService.normalizeRemarkTagValue(value);
    }
}
