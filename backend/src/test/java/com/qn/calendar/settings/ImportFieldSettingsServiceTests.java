package com.qn.calendar.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.qn.calendar.settings.constant.ImportUrgentMatchType;
import com.qn.calendar.settings.dto.ImportFieldSettingsResponse;
import com.qn.calendar.settings.dto.UpdateImportFieldSettingsRequest;
import com.qn.calendar.settings.model.ImportFieldKey;
import com.qn.calendar.settings.repository.ImportFieldAliasRepository;
import com.qn.calendar.settings.repository.ImportUrgentMatchRuleRepository;
import com.qn.calendar.settings.service.ImportFieldSettingsService;

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

    @BeforeEach
    void setUp() {
        fieldAliasRepository.deleteAllInBatch();
        urgentMatchRuleRepository.deleteAllInBatch();
    }

    @Test
    void getSettingsReturnsBuiltInDefaultsWithoutPersistingThem() {
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
        assertThat(settings.urgentRules().builtIn())
                .containsExactly(
                        new ImportFieldSettingsResponse.UrgentRule("true", ImportUrgentMatchType.EXACT),
                        new ImportFieldSettingsResponse.UrgentRule("yes", ImportUrgentMatchType.EXACT),
                        new ImportFieldSettingsResponse.UrgentRule("y", ImportUrgentMatchType.EXACT),
                        new ImportFieldSettingsResponse.UrgentRule("1", ImportUrgentMatchType.EXACT),
                        new ImportFieldSettingsResponse.UrgentRule("是", ImportUrgentMatchType.EXACT),
                        new ImportFieldSettingsResponse.UrgentRule("加急", ImportUrgentMatchType.CONTAINS),
                        new ImportFieldSettingsResponse.UrgentRule("急件", ImportUrgentMatchType.CONTAINS)
                );
        assertThat(settings.urgentRules().custom()).isEmpty();
        assertThat(fieldAliasRepository.count()).isZero();
        assertThat(urgentMatchRuleRepository.count()).isZero();
    }

    @Test
    void defaultsIncludeExistingAndXiaohongshuFieldAliases() {
        ImportFieldSettingsResponse settings = service.getSettings();

        assertThat(field(settings, "orderNo").builtInAliases())
                .containsExactly("訂單編號", "订单编号", "订单号");
        assertThat(field(settings, "price").builtInAliases())
                .contains("买家实付金额", "用户应付金额(元)");
        assertThat(field(settings, "latestShipTime").builtInAliases())
                .contains("应发货时间", "承诺发货时间");
        assertThat(field(settings, "urgent").builtInAliases())
                .contains("备注标签", "包裹备注标记");
        assertThat(field(settings, "buyerMessage").builtInAliases())
                .contains("买家留言", "用户备注");
        assertThat(field(settings, "merchantRemark").builtInAliases())
                .contains("商家备注", "包裹备注信息");
        assertThat(field(settings, "paidAt").builtInAliases())
                .contains("订单付款时间", "支付时间");
        assertThat(settings.fields())
                .flatExtracting(ImportFieldSettingsResponse.FieldSettings::builtInAliases)
                .noneMatch((alias) -> alias.matches(".*[A-Za-z].*"));
    }

    @Test
    void updateSettingsReplacesAndReturnsCustomSnapshot() {
        service.updateSettings(request(
                Map.of(
                        ImportFieldKey.ORDER_NO, List.of(" 自定义订单号 "),
                        ImportFieldKey.PRICE, List.of("实收金额")
                ),
                List.of(
                        rule(" 红旗 ", ImportUrgentMatchType.EXACT),
                        rule("特别紧急", ImportUrgentMatchType.CONTAINS)
                )
        ));

        ImportFieldSettingsResponse settings = service.getSettings();

        assertThat(field(settings, "orderNo").customAliases()).containsExactly("自定义订单号");
        assertThat(field(settings, "price").customAliases()).containsExactly("实收金额");
        assertThat(field(settings, "urgent").customAliases()).isEmpty();
        assertThat(settings.urgentRules().custom())
                .containsExactly(
                        new ImportFieldSettingsResponse.UrgentRule("红旗", ImportUrgentMatchType.EXACT),
                        new ImportFieldSettingsResponse.UrgentRule("特别紧急", ImportUrgentMatchType.CONTAINS)
                );
        assertThat(fieldAliasRepository.count()).isEqualTo(2);
        assertThat(urgentMatchRuleRepository.count()).isEqualTo(2);
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
    void updateSettingsRejectsUnknownOrRepeatedFieldKeys() {
        List<UpdateImportFieldSettingsRequest.FieldAliases> unknownFields = new ArrayList<>(
                request(Map.of(), List.of()).fields()
        );
        unknownFields.set(0, new UpdateImportFieldSettingsRequest.FieldAliases("unknown", List.of()));

        assertThatThrownBy(() -> service.updateSettings(new UpdateImportFieldSettingsRequest(
                unknownFields,
                new UpdateImportFieldSettingsRequest.UrgentRules(List.of())
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("未知字段：unknown");

        List<UpdateImportFieldSettingsRequest.FieldAliases> repeatedFields = new ArrayList<>(
                request(Map.of(), List.of()).fields()
        );
        repeatedFields.set(1, new UpdateImportFieldSettingsRequest.FieldAliases("orderNo", List.of()));

        assertThatThrownBy(() -> service.updateSettings(new UpdateImportFieldSettingsRequest(
                repeatedFields,
                new UpdateImportFieldSettingsRequest.UrgentRules(List.of())
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("字段设置重复：订单编号");
    }

    @Test
    void updateSettingsRejectsEmptyAndOverlongValues() {
        assertThatThrownBy(() -> service.updateSettings(request(
                Map.of(ImportFieldKey.ORDER_NO, List.of(" _ - ")),
                List.of()
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("自定义字段名不可只包含空格、下划线或连字符");

        assertThatThrownBy(() -> service.updateSettings(request(
                Map.of(),
                List.of(rule("x".repeat(121), ImportUrgentMatchType.EXACT))
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("加急判定文字最长为 120 个字符");
    }

    @Test
    void updateSettingsRejectsUrgentRuleDuplicatesAndBuiltInConflicts() {
        assertThatThrownBy(() -> service.updateSettings(request(
                Map.of(),
                List.of(
                        rule("Red Flag", ImportUrgentMatchType.EXACT),
                        rule(" red flag ", ImportUrgentMatchType.CONTAINS)
                )
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("加急判定文字不可重复：“red flag”");

        assertThatThrownBy(() -> service.updateSettings(request(
                Map.of(),
                List.of(rule(" YES ", ImportUrgentMatchType.EXACT))
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("加急判定文字“YES”与系统规则冲突");
    }

    @Test
    void invalidReplacementLeavesPreviousSnapshotUnchanged() {
        service.updateSettings(request(
                Map.of(ImportFieldKey.ORDER_NO, List.of("旧订单号")),
                List.of(rule("红旗", ImportUrgentMatchType.EXACT))
        ));

        assertThatThrownBy(() -> service.updateSettings(request(
                Map.of(ImportFieldKey.ORDER_NO, List.of("订单号")),
                List.of()
        )))
                .isInstanceOf(IllegalArgumentException.class);

        ImportFieldSettingsResponse settings = service.getSettings();
        assertThat(field(settings, "orderNo").customAliases()).containsExactly("旧订单号");
        assertThat(settings.urgentRules().custom())
                .containsExactly(new ImportFieldSettingsResponse.UrgentRule(
                        "红旗",
                        ImportUrgentMatchType.EXACT
                ));
    }

    @Test
    void importSnapshotCombinesNormalizedBuiltInAndCustomRules() {
        service.updateSettings(request(
                Map.of(ImportFieldKey.ORDER_NO, List.of(" My_Order-No ")),
                List.of(
                        rule(" RED FLAG ", ImportUrgentMatchType.EXACT),
                        rule("优先", ImportUrgentMatchType.CONTAINS)
                )
        ));

        var snapshot = service.getImportSnapshot();

        assertThat(snapshot.headerAliases())
                .containsEntry("订单号", ImportFieldKey.ORDER_NO)
                .containsEntry("用户应付金额(元)", ImportFieldKey.PRICE)
                .containsEntry("承诺发货时间", ImportFieldKey.LATEST_SHIP_TIME)
                .containsEntry("包裹备注标记", ImportFieldKey.URGENT)
                .containsEntry("myorderno", ImportFieldKey.ORDER_NO);
        assertThat(snapshot.customHeaderAliases()).containsExactly("myorderno");
        assertThat(snapshot.urgentExactValues()).contains("true", "yes", "red flag");
        assertThat(snapshot.urgentContainsValues()).containsExactly("加急", "急件", "优先");
        assertThat(ImportFieldSettingsService.normalizeHeader("  MY_ Field-name\t"))
                .isEqualTo("myfieldname");
        assertThat(ImportFieldSettingsService.normalizeUrgentValue("  ReD Flag  "))
                .isEqualTo("red flag");
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
            List<UpdateImportFieldSettingsRequest.UrgentRule> urgentRules
    ) {
        List<UpdateImportFieldSettingsRequest.FieldAliases> fields = java.util.Arrays.stream(ImportFieldKey.values())
                .map((fieldKey) -> new UpdateImportFieldSettingsRequest.FieldAliases(
                        fieldKey.getApiKey(),
                        aliases.getOrDefault(fieldKey, List.of())
                ))
                .toList();
        return new UpdateImportFieldSettingsRequest(
                fields,
                new UpdateImportFieldSettingsRequest.UrgentRules(urgentRules)
        );
    }

    private UpdateImportFieldSettingsRequest.UrgentRule rule(
            String text,
            ImportUrgentMatchType matchType
    ) {
        return new UpdateImportFieldSettingsRequest.UrgentRule(text, matchType);
    }
}
