package com.qn.calendar.workorder;

import java.time.LocalDate;
import java.util.List;

import com.qn.calendar.workorder.dto.ImportWorkOrderResponse;
import com.qn.calendar.workorder.dto.ScheduleEmailRequest;
import com.qn.calendar.workorder.dto.ScheduleWorkOrderRequest;
import com.qn.calendar.workorder.dto.UpdateWorkOrderDurationRequest;
import com.qn.calendar.workorder.dto.WorkOrderResponse;
import com.qn.calendar.workorder.dto.WorkOrderSegmentListResponse;
import com.qn.calendar.workorder.dto.WorkOrderSegmentResponse;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/work-orders")
public class WorkOrderController {

    private final WorkOrderImportService importService;
    private final WorkOrderService workOrderService;
    private final WorkOrderScheduleService scheduleService;
    private final WorkOrderSegmentService segmentService;
    private final WorkOrderEmailService emailService;

    public WorkOrderController(
            WorkOrderImportService importService,
            WorkOrderService workOrderService,
            WorkOrderScheduleService scheduleService,
            WorkOrderSegmentService segmentService,
            WorkOrderEmailService emailService
    ) {
        this.importService = importService;
        this.workOrderService = workOrderService;
        this.scheduleService = scheduleService;
        this.segmentService = segmentService;
        this.emailService = emailService;
    }

    @PostMapping("/import")
    public ImportWorkOrderResponse importWorkOrders(@RequestParam("file") MultipartFile file) {
        return importService.importXlsx(file);
    }

    @GetMapping("/pending")
    public List<WorkOrderResponse> pendingWorkOrders() {
        return workOrderService.getPendingWorkOrders();
    }

    @GetMapping("/calendar")
    public List<WorkOrderSegmentResponse> calendarWorkOrders(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo
    ) {
        return workOrderService.getCalendarWorkOrders(dateFrom, dateTo);
    }

    @PatchMapping("/{id}/schedule")
    public WorkOrderSegmentListResponse scheduleWorkOrder(
            @PathVariable Long id,
            @Valid @RequestBody ScheduleWorkOrderRequest request
    ) {
        return scheduleService.schedule(id, request);
    }

    @PatchMapping("/segments/{segmentId}")
    public WorkOrderSegmentListResponse updateSegment(
            @PathVariable Long segmentId,
            @Valid @RequestBody ScheduleWorkOrderRequest request
    ) {
        return segmentService.updateSegment(segmentId, request);
    }

    @DeleteMapping("/segments/{segmentId}")
    public WorkOrderSegmentListResponse deleteSegment(@PathVariable Long segmentId) {
        return segmentService.deleteSegment(segmentId);
    }

    @PatchMapping("/{id}/duration")
    public WorkOrderResponse updateDuration(
            @PathVariable Long id,
            @Valid @RequestBody UpdateWorkOrderDurationRequest request
    ) {
        return WorkOrderResponse.from(workOrderService.updateDuration(id, request.actualMinutes()));
    }

    @PatchMapping("/{id}/unschedule")
    public WorkOrderResponse unschedule(@PathVariable Long id) {
        return WorkOrderResponse.from(workOrderService.unschedule(id));
    }

    @PatchMapping("/{id}/done")
    public WorkOrderResponse markAsDone(@PathVariable Long id) {
        return WorkOrderResponse.from(workOrderService.markAsDone(id));
    }

    @PatchMapping("/{id}/reopen")
    public WorkOrderResponse reopen(@PathVariable Long id) {
        return WorkOrderResponse.from(workOrderService.reopen(id));
    }

    @PostMapping("/schedule-email")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void sendScheduleEmail(@Valid @RequestBody ScheduleEmailRequest request) {
        emailService.sendScheduleEmail(request);
    }
}
