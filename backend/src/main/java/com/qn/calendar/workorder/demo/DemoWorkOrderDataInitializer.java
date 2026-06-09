package com.qn.calendar.workorder.demo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import com.qn.calendar.workorder.entity.WorkOrder;
import com.qn.calendar.workorder.repository.WorkOrderRepository;

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
            @Value("${APP_DEMO_DATA_ENABLED:true}") boolean enabled
    ) {
        this.repository = repository;
        this.enabled = enabled;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }

        List<WorkOrder> demoOrders = List.of(
                demoOrder("ORD-001", "250", true, 180, 1, "18:00:00"),
                demoOrder("ORD-002", "150", false, 120, 1, "12:00:00"),
                demoOrder("ORD-003", "400", true, 240, 3, "17:00:00"),
                demoOrder("ORD-004", "100", false, 60, 2, "23:59:59"),
                demoOrder("ORD-005", "620", true, 420, 4, "16:30:00"),
                demoOrder("ORD-006", "280", false, 180, 5, "20:00:00"),
                demoOrder("ORD-007", "90", false, 60, 6, "11:30:00"),
                demoOrder("ORD-008", "180", true, 120, 6, "16:00:00"),
                demoOrder("ORD-009", "340", false, 240, 7, "14:30:00"),
                demoOrder("ORD-010", "75", false, 60, 7, "18:30:00"),
                demoOrder("ORD-011", "510", true, 360, 8, "10:00:00"),
                demoOrder("ORD-012", "220", false, 180, 8, "15:30:00"),
                demoOrder("ORD-013", "130", false, 120, 9, "12:00:00"),
                demoOrder("ORD-014", "460", true, 300, 9, "19:00:00"),
                demoOrder("ORD-015", "310", false, 240, 10, "17:30:00")
        );

        repository.saveAll(demoOrders.stream()
                .filter(order -> !repository.existsByOrderNo(order.getOrderNo()))
                .toList());
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
