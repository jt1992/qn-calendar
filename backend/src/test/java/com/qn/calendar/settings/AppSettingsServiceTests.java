package com.qn.calendar.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import com.qn.calendar.settings.constant.SmtpSecurity;
import com.qn.calendar.settings.dto.UpdateEmailSenderSettingsRequest;
import com.qn.calendar.settings.dto.UpdateAppSettingsRequest;
import com.qn.calendar.settings.dto.UpdateAppSettingsRequest.OrderSourceOptionRequest;
import com.qn.calendar.settings.entity.AppSetting;
import com.qn.calendar.settings.repository.AppSettingRepository;
import com.qn.calendar.settings.service.AppSettingsService;
import com.qn.calendar.workorder.constant.WorkOrderSource;
import com.qn.calendar.workorder.entity.WorkOrder;
import com.qn.calendar.workorder.entity.WorkOrderSegment;
import com.qn.calendar.workorder.entity.WorkOrderSegmentPause;
import com.qn.calendar.workorder.repository.WorkOrderRepository;
import com.qn.calendar.workorder.repository.WorkOrderSegmentPauseRepository;
import com.qn.calendar.workorder.repository.WorkOrderSegmentRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class AppSettingsServiceTests {

    @Autowired
    private AppSettingsService service;

    @Autowired
    private AppSettingRepository repository;

    @Autowired
    private WorkOrderRepository workOrderRepository;

    @Autowired
    private WorkOrderSegmentRepository segmentRepository;

    @Autowired
    private WorkOrderSegmentPauseRepository pauseRepository;

    @BeforeEach
    void setUp() {
        pauseRepository.deleteAll();
        segmentRepository.deleteAll();
        workOrderRepository.deleteAll();
        repository.deleteAll();
    }

    @Test
    void getSettingsReturnsDefaultBaseAmountAndPersistsIt() {
        var settings = service.getSettings();

        assertThat(settings.estimatedHourlyBaseAmount()).isEqualByComparingTo("100");
        assertThat(settings.weekViewDefaultStartTime()).isEqualTo(LocalTime.of(6, 0));
        assertThat(settings.orderSourceOptions()).extracting((option) -> option.name())
                .containsExactly("千牛", "小红书");
        assertThat(settings.emailSender().configured()).isFalse();
        assertThat(repository.findAll()).hasSize(1);
        assertThat(repository.findAll().getFirst().getEstimatedHourlyBaseAmount()).isEqualByComparingTo("100");
    }

    @Test
    void updateSettingsPersistsBaseAmountForLaterReads() {
        service.updateSettings(new UpdateAppSettingsRequest(
                BigDecimal.valueOf(150),
                LocalTime.of(8, 30),
                List.of(
                        source(" 千牛 ", "QIANNIU", "#218BFF", "千"),
                        source("小红书", "XIAOHONGSHU", "#FF5C5C", "书"),
                        source("抖音", "DOUYIN", "#00AA66", "抖")
                )
        ));

        assertThat(service.getSettings().estimatedHourlyBaseAmount()).isEqualByComparingTo("150");
        assertThat(service.getSettings().weekViewDefaultStartTime()).isEqualTo(LocalTime.of(8, 30));
        assertThat(service.getSettings().orderSourceOptions()).extracting((option) -> option.name())
                .containsExactly("千牛", "小红书", "抖音");
        assertThat(service.getEstimatedHourlyBaseAmount()).isEqualByComparingTo("150");
        assertThat(service.getOrderSourceOptions()).extracting((option) -> option.getName())
                .containsExactly("千牛", "小红书", "抖音");
    }

    @Test
    void updateSettingsRejectsWeekViewStartTimeOutsideHalfHourIntervals() {
        assertThatThrownBy(() -> service.updateSettings(new UpdateAppSettingsRequest(
                BigDecimal.valueOf(150),
                LocalTime.of(8, 15),
                defaultSources()
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("周表默认开始时间必须以 30 分钟为单位");
    }

    @Test
    void getSettingsBackfillsWeekViewStartTimeForExistingSettings() {
        repository.save(new AppSetting(1L, BigDecimal.valueOf(100), null));

        var settings = service.getSettings();

        assertThat(settings.weekViewDefaultStartTime()).isEqualTo(LocalTime.of(6, 0));
        assertThat(repository.findById(1L).orElseThrow().getWeekViewDefaultStartTime())
                .isEqualTo(LocalTime.of(6, 0));
        assertThat(settings.orderSourceOptions()).extracting((option) -> option.name())
                .containsExactly("千牛", "小红书");
    }

    @Test
    void updateSettingsRejectsDuplicateOrderSourceOptionsIgnoringCase() {
        assertThatThrownBy(() -> service.updateSettings(new UpdateAppSettingsRequest(
                BigDecimal.valueOf(100),
                LocalTime.of(6, 0),
                List.of(
                        source("Shop", "SHOP", "#112233", "S"),
                        source(" shop ", "SHOP_2", "#445566", "店")
                )
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("订单来源选项不可重复");
    }

    @Test
    void updateSettingsRejectsEmptyOrderSourceOptions() {
        assertThatThrownBy(() -> service.updateSettings(new UpdateAppSettingsRequest(
                BigDecimal.valueOf(100),
                LocalTime.of(6, 0),
                List.of()
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("请至少保留一个订单来源选项");
    }

    @Test
    void updateSettingsRejectsDuplicateOrderSourceIdentifiers() {
        assertThatThrownBy(() -> service.updateSettings(new UpdateAppSettingsRequest(
                BigDecimal.valueOf(100),
                LocalTime.of(6, 0),
                List.of(
                        source("抖音", "DOUYIN", "#112233", "抖"),
                        source("快手", " douyin ", "#445566", "快")
                )
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("订单来源识别文字不可重复");
    }

    @Test
    void updateSettingsRejectsInvalidBadgeMetadata() {
        assertThatThrownBy(() -> service.updateSettings(new UpdateAppSettingsRequest(
                BigDecimal.valueOf(100),
                LocalTime.of(6, 0),
                List.of(source("抖音", "DOUYIN", "red", "抖音"))
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("订单来源标签颜色必须是六位十六进制色码");
    }

    @Test
    void updateSettingsSynchronizesSourceDisplayToExistingWorkOrders() {
        WorkOrder workOrder = workOrderRepository.save(new WorkOrder(
                "ORDER-SOURCE-SYNC",
                null,
                "无任何备注",
                BigDecimal.valueOf(100),
                60,
                false,
                LocalDateTime.of(2026, 8, 30, 18, 0),
                null,
                WorkOrderSource.QIANNIU
        ));

        service.updateSettings(new UpdateAppSettingsRequest(
                BigDecimal.valueOf(100),
                LocalTime.of(6, 0),
                List.of(
                        source("新千牛", "QIANNIU", "#123456", "新"),
                        source("小红书", "XIAOHONGSHU", "#FF5C5C", "书")
                )
        ));

        WorkOrder updated = workOrderRepository.findById(workOrder.getId()).orElseThrow();
        assertThat(updated.getSourceName()).isEqualTo("新千牛");
        assertThat(updated.getSourceBadgeColor()).isEqualTo("#123456");
        assertThat(updated.getSourceBadgeText()).isEqualTo("新");
    }

    @Test
    void deleteOrderSourceReportsImpactAndDeletesItsWorkOrdersAndScheduleRecords() {
        service.updateSettings(new UpdateAppSettingsRequest(
                BigDecimal.valueOf(100),
                LocalTime.of(6, 0),
                List.of(
                        source("千牛", "QIANNIU", "#218BFF", "千"),
                        source("抖音", "DOUYIN", "#00AA66", "抖")
                )
        ));
        WorkOrder douyinOrder = workOrderRepository.save(new WorkOrder(
                "DOUYIN-DELETE-1",
                null,
                "无任何备注",
                BigDecimal.valueOf(200),
                120,
                false,
                LocalDateTime.of(2026, 9, 1, 18, 0),
                null,
                WorkOrderSource.CUSTOM,
                "DOUYIN",
                "抖音",
                "#00AA66",
                "抖"
        ));
        WorkOrder retainedOrder = workOrderRepository.save(new WorkOrder(
                "QIANNIU-RETAIN-1",
                null,
                "无任何备注",
                BigDecimal.valueOf(100),
                60,
                false,
                LocalDateTime.of(2026, 9, 1, 18, 0),
                null,
                WorkOrderSource.QIANNIU
        ));
        WorkOrderSegment segment = segmentRepository.save(new WorkOrderSegment(
                douyinOrder,
                LocalDateTime.of(2026, 8, 30, 10, 0),
                LocalDateTime.of(2026, 8, 30, 12, 0)
        ));
        pauseRepository.save(new WorkOrderSegmentPause(
                segment,
                LocalDateTime.of(2026, 8, 30, 11, 0)
        ));

        var impact = service.getOrderSourceDeletionImpact("douyin");

        assertThat(impact.identifier()).isEqualTo("DOUYIN");
        assertThat(impact.name()).isEqualTo("抖音");
        assertThat(impact.workOrderCount()).isEqualTo(1);

        var result = service.deleteOrderSource("douyin");

        assertThat(result.deletedWorkOrderCount()).isEqualTo(1);
        assertThat(result.settings().orderSourceOptions()).extracting((option) -> option.identifier())
                .containsExactly("QIANNIU");
        assertThat(service.getSettings().orderSourceOptions()).extracting((option) -> option.identifier())
                .containsExactly("QIANNIU");
        assertThat(workOrderRepository.findAll()).extracting(WorkOrder::getOrderNo)
                .containsExactly(retainedOrder.getOrderNo());
        assertThat(segmentRepository.findAll()).isEmpty();
        assertThat(pauseRepository.findAll()).isEmpty();
    }

    @Test
    void deleteOrderSourceRejectsDeletingTheLastOption() {
        service.updateSettings(new UpdateAppSettingsRequest(
                BigDecimal.valueOf(100),
                LocalTime.of(6, 0),
                List.of(source("千牛", "QIANNIU", "#218BFF", "千"))
        ));

        assertThatThrownBy(() -> service.deleteOrderSource("QIANNIU"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("请至少保留一个订单来源选项");
    }

    @Test
    void getRequiredEmailSenderSettingsRejectsMissingConfiguration() {
        assertThatThrownBy(() -> service.getRequiredEmailSenderSettings())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("请先在全局设置中配置寄件者 SMTP");
    }

    @Test
    void updateEmailSenderSettingsPersistsSmtpConfigWithoutReturningAuthCode() {
        var settings = service.updateEmailSenderSettings(new UpdateEmailSenderSettingsRequest(
                " sender@example.com ",
                " smtp.example.com ",
                587,
                SmtpSecurity.STARTTLS,
                " smtp-auth-code "
        ));

        assertThat(settings.emailSender().configured()).isTrue();
        assertThat(settings.emailSender().senderEmailMasked()).isEqualTo("s***@example.com");
        assertThat(settings.emailSender().senderEmail()).isEqualTo("sender@example.com");
        assertThat(settings.emailSender().smtpHost()).isEqualTo("smtp.example.com");
        assertThat(settings.emailSender().smtpPort()).isEqualTo(587);
        assertThat(settings.emailSender().smtpSecurity()).isEqualTo(SmtpSecurity.STARTTLS);
        assertThat(settings.toString()).doesNotContain("smtp-auth-code");

        var emailSenderSettings = service.getRequiredEmailSenderSettings();
        assertThat(emailSenderSettings.senderEmail()).isEqualTo("sender@example.com");
        assertThat(emailSenderSettings.smtpHost()).isEqualTo("smtp.example.com");
        assertThat(emailSenderSettings.smtpPort()).isEqualTo(587);
        assertThat(emailSenderSettings.smtpSecurity()).isEqualTo(SmtpSecurity.STARTTLS);
        assertThat(emailSenderSettings.smtpAuthCode()).isEqualTo("smtp-auth-code");
    }

    @Test
    void updateEmailSenderSettingsRetainsExistingAuthCodeWhenOmitted() {
        service.updateEmailSenderSettings(new UpdateEmailSenderSettingsRequest(
                "sender@example.com",
                "smtp.example.com",
                465,
                SmtpSecurity.SSL,
                "smtp-auth-code"
        ));

        var settings = service.updateEmailSenderSettings(new UpdateEmailSenderSettingsRequest(
                "updated@example.com",
                "smtp.updated.example.com",
                587,
                SmtpSecurity.STARTTLS,
                null
        ));

        assertThat(settings.emailSender().senderEmail()).isEqualTo("updated@example.com");
        assertThat(service.getRequiredEmailSenderSettings().smtpAuthCode()).isEqualTo("smtp-auth-code");
    }

    @Test
    void updateEmailSenderSettingsRequiresAuthCodeForInitialConfiguration() {
        assertThatThrownBy(() -> service.updateEmailSenderSettings(new UpdateEmailSenderSettingsRequest(
                "sender@example.com",
                "smtp.example.com",
                465,
                SmtpSecurity.SSL,
                null
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("授权码不可为空");
    }

    private static List<OrderSourceOptionRequest> defaultSources() {
        return List.of(
                source("千牛", "QIANNIU", "#218BFF", "千"),
                source("小红书", "XIAOHONGSHU", "#FF5C5C", "书")
        );
    }

    private static OrderSourceOptionRequest source(
            String name,
            String identifier,
            String badgeColor,
            String badgeText
    ) {
        return new OrderSourceOptionRequest(name, identifier, badgeColor, badgeText);
    }
}
