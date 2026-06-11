package com.qn.calendar.settings;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import com.qn.calendar.settings.dto.UpdateAppSettingsRequest;
import com.qn.calendar.settings.repository.AppSettingRepository;
import com.qn.calendar.settings.service.AppSettingsService;

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

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    @Test
    void getSettingsReturnsDefaultBaseAmountAndPersistsIt() {
        var settings = service.getSettings();

        assertThat(settings.estimatedHourlyBaseAmount()).isEqualByComparingTo("100");
        assertThat(repository.findAll()).hasSize(1);
        assertThat(repository.findAll().getFirst().getEstimatedHourlyBaseAmount()).isEqualByComparingTo("100");
    }

    @Test
    void updateSettingsPersistsBaseAmountForLaterReads() {
        service.updateSettings(new UpdateAppSettingsRequest(BigDecimal.valueOf(150)));

        assertThat(service.getSettings().estimatedHourlyBaseAmount()).isEqualByComparingTo("150");
        assertThat(service.getEstimatedHourlyBaseAmount()).isEqualByComparingTo("150");
    }
}
