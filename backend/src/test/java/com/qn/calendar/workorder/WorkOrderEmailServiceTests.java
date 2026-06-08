package com.qn.calendar.workorder;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.qn.calendar.workorder.dto.WorkOrderSegmentResponse;

import org.junit.jupiter.api.Test;

class WorkOrderEmailServiceTests {

    @Test
    void scheduleTableSpansOrderAcrossFiveMinuteSlotsAndTrimsEmptyTimeRange() {
        WorkOrderSegmentResponse order = order(
                "ORD-001",
                LocalDateTime.of(2026, 6, 8, 12, 0),
                LocalDateTime.of(2026, 6, 8, 15, 5),
                WorkOrderStatus.SCHEDULED
        );

        WorkOrderEmailService.EmailScheduleTable table = WorkOrderEmailService.buildScheduleTable(
                LocalDate.of(2026, 6, 8),
                LocalDate.of(2026, 6, 14),
                List.of(order)
        );

        assertThat(table.rows()).hasSize(37);
        assertThat(table.rows().getFirst().timeLabel()).isEqualTo("12:00:00");
        assertThat(table.rows().getLast().timeLabel()).isEqualTo("15:00:00");

        WorkOrderEmailService.EmailTableCell firstCell = table.rows().getFirst().cells().getFirst();
        assertThat(firstCell.order().orderNo()).isEqualTo("ORD-001");
        assertThat(firstCell.rowSpan()).isEqualTo(37);
        assertThat(table.rows().get(1).cells().getFirst().rendered()).isFalse();
    }

    @Test
    void scheduleTableUsesAdditionalLaneForOverlappingDoneOrder() {
        WorkOrderSegmentResponse scheduled = order(
                "ORD-SCHEDULED",
                LocalDateTime.of(2026, 6, 8, 12, 0),
                LocalDateTime.of(2026, 6, 8, 14, 0),
                WorkOrderStatus.SCHEDULED
        );
        WorkOrderSegmentResponse done = order(
                "ORD-DONE",
                LocalDateTime.of(2026, 6, 8, 13, 0),
                LocalDateTime.of(2026, 6, 8, 15, 0),
                WorkOrderStatus.DONE
        );

        WorkOrderEmailService.EmailScheduleTable table = WorkOrderEmailService.buildScheduleTable(
                LocalDate.of(2026, 6, 8),
                LocalDate.of(2026, 6, 14),
                List.of(scheduled, done)
        );

        assertThat(table.days().getFirst().laneCount()).isEqualTo(2);
        assertThat(table.rows()).hasSize(36);
    }

    private WorkOrderSegmentResponse order(
            String orderNo,
            LocalDateTime scheduledStart,
            LocalDateTime scheduledEnd,
            WorkOrderStatus status
    ) {
        int minutes = Math.toIntExact(java.time.Duration.between(scheduledStart, scheduledEnd).toMinutes());
        return new WorkOrderSegmentResponse(
                1L,
                1L,
                1L,
                orderNo,
                BigDecimal.valueOf(300),
                180,
                minutes,
                minutes,
                false,
                scheduledEnd.plusDays(1),
                status,
                scheduledStart,
                scheduledEnd,
                status == WorkOrderStatus.DONE ? scheduledEnd : null
        );
    }
}
