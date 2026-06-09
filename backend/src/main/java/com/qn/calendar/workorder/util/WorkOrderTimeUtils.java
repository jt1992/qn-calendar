package com.qn.calendar.workorder.util;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import com.qn.calendar.workorder.entity.WorkOrderSegment;

public final class WorkOrderTimeUtils {

    public static final int SCHEDULE_GRANULARITY_MINUTES = 15;

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

    public static boolean isScheduleBoundary(LocalDateTime time) {
        return time.getSecond() == 0
                && time.getNano() == 0
                && time.getMinute() % SCHEDULE_GRANULARITY_MINUTES == 0;
    }

    public static LocalDateTime roundUpToScheduleBoundary(LocalDateTime time) {
        LocalDateTime truncated = time.withSecond(0).withNano(0);
        int remainder = truncated.getMinute() % SCHEDULE_GRANULARITY_MINUTES;

        if (remainder == 0 && time.getSecond() == 0 && time.getNano() == 0) {
            return truncated;
        }

        return truncated.plusMinutes(remainder == 0
                ? SCHEDULE_GRANULARITY_MINUTES
                : SCHEDULE_GRANULARITY_MINUTES - remainder);
    }
}
