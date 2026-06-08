package com.qn.calendar.workorder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DemoWorkOrderDataInitializer implements ApplicationRunner {

    private static final LocalDate BASE_DATE = LocalDate.of(2026, 6, 8);

    private final WorkOrderRepository repository;
    private final boolean enabled;

    public DemoWorkOrderDataInitializer(
            WorkOrderRepository repository,
            @Value("${app.demo-data.enabled:true}") boolean enabled
    ) {
        this.repository = repository;
        this.enabled = enabled;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled || repository.count() > 0) {
            return;
        }

        repository.saveAll(List.of(
                demoOrder("ORD-001", "250", true, 180, 1, "18:00:00"),
                demoOrder("ORD-002", "150", false, 120, 1, "12:00:00"),
                demoOrder("ORD-003", "400", true, 240, 3, "17:00:00"),
                demoOrder("ORD-004", "100", false, 60, 2, "23:59:59"),
                demoOrder("ORD-005", "620", true, 420, 4, "16:30:00"),
                demoOrder("ORD-006", "280", false, 180, 5, "20:00:00"),
                demoOrder("ORD-007", "90", false, 60, 6, "11:30:00")
        ));
    }

    private WorkOrder demoOrder(
            String orderNo,
            String price,
            boolean urgent,
            int estimatedMinutes,
            int daysFromBase,
            String latestShipTime
    ) {
        return new WorkOrder(
                orderNo,
                new BigDecimal(price),
                estimatedMinutes,
                urgent,
                BASE_DATE.plusDays(daysFromBase).atTime(LocalTime.parse(latestShipTime))
        );
    }
}
