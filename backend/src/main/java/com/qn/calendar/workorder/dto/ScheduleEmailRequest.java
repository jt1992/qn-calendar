package com.qn.calendar.workorder.dto;

import java.time.LocalDate;
import java.util.List;

import com.qn.calendar.workorder.ScheduleEmailViewType;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public record ScheduleEmailRequest(
        @NotEmpty List<@NotBlank @Email String> to,
        @NotBlank String subject,
        @NotNull LocalDate dateFrom,
        @NotNull LocalDate dateTo,
        @NotNull ScheduleEmailViewType viewType
) {
}
