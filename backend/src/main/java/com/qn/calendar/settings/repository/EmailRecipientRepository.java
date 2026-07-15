package com.qn.calendar.settings.repository;

import java.util.List;
import java.util.Optional;

import com.qn.calendar.settings.entity.EmailRecipient;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface EmailRecipientRepository extends JpaRepository<EmailRecipient, Long> {

    Optional<EmailRecipient> findByEmailIgnoreCase(String email);

    List<EmailRecipient> findAllByOrderByLastUsedAtDescUsageCountDescNameAscEmailAsc();
}
