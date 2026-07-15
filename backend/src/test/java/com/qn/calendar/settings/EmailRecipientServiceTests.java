package com.qn.calendar.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.qn.calendar.settings.dto.EmailRecipientRequest;
import com.qn.calendar.settings.repository.EmailRecipientRepository;
import com.qn.calendar.settings.service.EmailRecipientService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class EmailRecipientServiceTests {

    @Autowired
    private EmailRecipientService service;

    @Autowired
    private EmailRecipientRepository repository;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    @Test
    void recipientsCanBeCreatedUpdatedAndDeleted() {
        var created = service.createRecipient(new EmailRecipientRequest(
                " 张小姐 ",
                " Sales@Example.com "
        ));

        assertThat(created.name()).isEqualTo("张小姐");
        assertThat(created.email()).isEqualTo("sales@example.com");
        assertThat(created.usageCount()).isZero();

        var updated = service.updateRecipient(created.id(), new EmailRecipientRequest(
                "李小姐",
                "buyer@example.com"
        ));

        assertThat(updated.name()).isEqualTo("李小姐");
        assertThat(updated.email()).isEqualTo("buyer@example.com");

        service.deleteRecipient(created.id());

        assertThat(service.getRecipients()).isEmpty();
    }

    @Test
    void duplicateEmailIsRejectedIgnoringCase() {
        service.createRecipient(new EmailRecipientRequest("张小姐", "sales@example.com"));

        assertThatThrownBy(() -> service.createRecipient(
                new EmailRecipientRequest("李小姐", "SALES@example.com")
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("收件 Email 已存在");
    }

    @Test
    void manuallyManagedRecipientRequiresName() {
        assertThatThrownBy(() -> service.createRecipient(
                new EmailRecipientRequest("  ", "sales@example.com")
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("收件人姓名不可为空");

        var created = service.createRecipient(new EmailRecipientRequest(
                "张小姐",
                "sales@example.com"
        ));

        assertThatThrownBy(() -> service.updateRecipient(
                created.id(),
                new EmailRecipientRequest(null, created.email())
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("收件人姓名不可为空");
    }

    @Test
    void sentRecipientsAreCreatedOnceAndUsageIsIncremented() {
        service.recordUsed(List.of(
                " buyer@example.com ",
                "BUYER@example.com",
                "factory@example.com"
        ));
        service.recordUsed(List.of("buyer@example.com"));

        var recipients = service.getRecipients();

        assertThat(recipients).hasSize(2);
        assertThat(recipients.getFirst().email()).isEqualTo("buyer@example.com");
        assertThat(recipients.getFirst().usageCount()).isEqualTo(2);
        assertThat(recipients.getFirst().lastUsedAt()).isNotNull();
        assertThat(recipients.get(1).email()).isEqualTo("factory@example.com");
        assertThat(recipients.get(1).usageCount()).isEqualTo(1);
    }
}
