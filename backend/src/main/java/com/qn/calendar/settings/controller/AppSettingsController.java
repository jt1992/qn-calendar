package com.qn.calendar.settings.controller;

import com.qn.calendar.settings.dto.AppSettingsResponse;
import com.qn.calendar.settings.dto.UpdateAppSettingsRequest;
import com.qn.calendar.settings.service.AppSettingsService;

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

    public AppSettingsController(AppSettingsService service) {
        this.service = service;
    }

    @GetMapping
    public AppSettingsResponse getSettings() {
        return service.getSettings();
    }

    @PutMapping
    public AppSettingsResponse updateSettings(@Valid @RequestBody UpdateAppSettingsRequest request) {
        return service.updateSettings(request);
    }
}
