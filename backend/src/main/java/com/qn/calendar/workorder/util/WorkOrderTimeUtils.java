package com.qn.calendar.workorder.util;

import java.time.Duration;
import java.util.List;

import com.qn.calendar.workorder.entity.WorkOrderSegment;

public final class WorkOrderTimeUtils {

    private WorkOrderTimeUtils() {
    }

    public static int segmentMinutes(WorkOrderSegment segment) {
        return Math.toIntExact(Duration.between(
                segment.getScheduledStart(),
                segment.getScheduledEnd()
        ).toMinutes());
    }

    public static int totalMinutes(List<WorkOrderSegment> segments) {
        return segments.stream()
                .mapToInt(WorkOrderTimeUtils::segmentMinutes)
                .sum();
    }
}
