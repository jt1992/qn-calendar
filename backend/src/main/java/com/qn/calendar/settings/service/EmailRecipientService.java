package com.qn.calendar.settings.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

import com.qn.calendar.settings.dto.EmailRecipientRequest;
import com.qn.calendar.settings.dto.EmailRecipientResponse;
import com.qn.calendar.settings.entity.EmailRecipient;
import com.qn.calendar.settings.repository.EmailRecipientRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class EmailRecipientService {

    private final EmailRecipientRepository repository;

    public EmailRecipientService(EmailRecipientRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<EmailRecipientResponse> getRecipients() {
        return repository.findAllByOrderByLastUsedAtDescUsageCountDescNameAscEmailAsc()
                .stream()
                .map(EmailRecipientResponse::from)
                .toList();
    }

    @Transactional
    public EmailRecipientResponse createRecipient(EmailRecipientRequest request) {
        String email = normalizeEmail(request.email());
        ensureEmailAvailable(email, null);

        EmailRecipient recipient = new EmailRecipient(normalizeRequiredName(request.name()), email);
        return EmailRecipientResponse.from(repository.save(recipient));
    }

    @Transactional
    public EmailRecipientResponse updateRecipient(Long id, EmailRecipientRequest request) {
        EmailRecipient recipient = findRecipient(id);
        String email = normalizeEmail(request.email());
        ensureEmailAvailable(email, id);

        recipient.update(normalizeRequiredName(request.name()), email);
        return EmailRecipientResponse.from(repository.save(recipient));
    }

    @Transactional
    public void deleteRecipient(Long id) {
        repository.delete(findRecipient(id));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordUsed(List<String> emails) {
        if (emails == null) {
            return;
        }

        LocalDateTime usedAt = LocalDateTime.now();
        emails.stream()
                .filter(StringUtils::hasText)
                .map(this::normalizeEmail)
                .distinct()
                .forEach((email) -> {
                    EmailRecipient recipient = repository.findByEmailIgnoreCase(email)
                            .orElseGet(() -> new EmailRecipient(null, email));
                    recipient.markUsed(usedAt);
                    repository.save(recipient);
                });
    }

    private EmailRecipient findRecipient(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("找不到 Email 收件者"));
    }

    private void ensureEmailAvailable(String email, Long currentId) {
        repository.findByEmailIgnoreCase(email)
                .filter((recipient) -> !recipient.getId().equals(currentId))
                .ifPresent((recipient) -> {
                    throw new IllegalArgumentException("收件 Email 已存在");
                });
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeRequiredName(String name) {
        if (!StringUtils.hasText(name)) {
            throw new IllegalArgumentException("收件人姓名不可为空");
        }

        return name.trim();
    }
}
