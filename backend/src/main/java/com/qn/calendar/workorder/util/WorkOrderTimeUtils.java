package com.qn.calendar.workorder.util;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import com.qn.calendar.workorder.entity.WorkOrderSegment;
import com.qn.calendar.workorder.entity.WorkOrderSegmentPause;

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

    public static int pauseMinutes(
            List<WorkOrderSegmentPause> pauses,
            List<WorkOrderSegment> segments,
            LocalDateTime fallbackEnd
    ) {
        return pauses.stream()
                .mapToInt((pause) -> pauseMinutes(pause, segments, fallbackEnd))
                .sum();
    }

    public static int pauseMinutes(
            WorkOrderSegmentPause pause,
            List<WorkOrderSegment> segments,
            LocalDateTime fallbackEnd
    ) {
        LocalDateTime end = pause.getResumedAt() == null ? fallbackEnd : pause.getResumedAt();

        if (end == null || !end.isAfter(pause.getPausedAt())) {
            return 0;
        }

        long overlappingSeconds = segments.stream()
                .mapToLong((segment) -> overlapSeconds(
                        pause.getPausedAt(),
                        end,
                        segment.getScheduledStart(),
                        segment.getScheduledEnd()
                ))
                .sum();
        return Math.toIntExact(overlappingSeconds / 60);
    }

    private static long overlapSeconds(
            LocalDateTime firstStart,
            LocalDateTime firstEnd,
            LocalDateTime secondStart,
            LocalDateTime secondEnd
    ) {
        LocalDateTime overlapStart = firstStart.isAfter(secondStart) ? firstStart : secondStart;
        LocalDateTime overlapEnd = firstEnd.isBefore(secondEnd) ? firstEnd : secondEnd;
        return overlapEnd.isAfter(overlapStart)
                ? Duration.between(overlapStart, overlapEnd).toSeconds()
                : 0;
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
