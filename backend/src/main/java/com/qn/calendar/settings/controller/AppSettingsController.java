package com.qn.calendar.settings.controller;

import com.qn.calendar.settings.dto.AppSettingsResponse;
import com.qn.calendar.settings.dto.ImportFieldSettingsResponse;
import com.qn.calendar.settings.dto.UpdateImportFieldSettingsRequest;
import com.qn.calendar.settings.dto.UpdateEmailSenderSettingsRequest;
import com.qn.calendar.settings.dto.UpdateAppSettingsRequest;
import com.qn.calendar.settings.service.AppSettingsService;
import com.qn.calendar.settings.service.ImportFieldSettingsService;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/settings")
public class AppSettingsController {

    private final AppSettingsService service;
    private final ImportFieldSettingsService importFieldSettingsService;

    public AppSettingsController(
            AppSettingsService service,
            ImportFieldSettingsService importFieldSettingsService
    ) {
        this.service = service;
        this.importFieldSettingsService = importFieldSettingsService;
    }

    @GetMapping
    public AppSettingsResponse getSettings() {
        return service.getSettings();
    }

    @PutMapping
    public AppSettingsResponse updateSettings(@Valid @RequestBody UpdateAppSettingsRequest request) {
        return service.updateSettings(request);
    }

    @PutMapping("/email-sender")
    public AppSettingsResponse updateEmailSenderSettings(
            @Valid @RequestBody UpdateEmailSenderSettingsRequest request
    ) {
        return service.updateEmailSenderSettings(request);
    }

    @GetMapping("/import-fields")
    public ImportFieldSettingsResponse getImportFieldSettings() {
        return importFieldSettingsService.getSettings();
    }

    @PutMapping("/import-fields")
    public ImportFieldSettingsResponse updateImportFieldSettings(
            @Valid @RequestBody UpdateImportFieldSettingsRequest request
    ) {
        return importFieldSettingsService.updateSettings(request);
    }
}
