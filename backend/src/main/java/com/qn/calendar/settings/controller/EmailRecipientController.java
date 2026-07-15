package com.qn.calendar.settings.controller;

import java.util.List;

import com.qn.calendar.settings.dto.EmailRecipientRequest;
import com.qn.calendar.settings.dto.EmailRecipientResponse;
import com.qn.calendar.settings.service.EmailRecipientService;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/email-recipients")
public class EmailRecipientController {

    private final EmailRecipientService service;

    public EmailRecipientController(EmailRecipientService service) {
        this.service = service;
    }

    @GetMapping
    public List<EmailRecipientResponse> getRecipients() {
        return service.getRecipients();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EmailRecipientResponse createRecipient(@Valid @RequestBody EmailRecipientRequest request) {
        return service.createRecipient(request);
    }

    @PutMapping("/{id}")
    public EmailRecipientResponse updateRecipient(
            @PathVariable Long id,
            @Valid @RequestBody EmailRecipientRequest request
    ) {
        return service.updateRecipient(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRecipient(@PathVariable Long id) {
        service.deleteRecipient(id);
    }
}
