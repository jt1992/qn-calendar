package com.qn.calendar.settings.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class RemarkTagDataUpgrade implements ApplicationRunner {

    private final ImportFieldSettingsService importFieldSettingsService;

    public RemarkTagDataUpgrade(ImportFieldSettingsService importFieldSettingsService) {
        this.importFieldSettingsService = importFieldSettingsService;
    }

    @Override
    public void run(ApplicationArguments args) {
        importFieldSettingsService.upgradeLegacyData();
    }
}
