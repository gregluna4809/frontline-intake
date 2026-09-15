package com.frontline.report;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record ReportSummary(
        LocalDate businessDate,
        Map<String, Long> volumeByCategory,
        Ftr ftr,
        Double avgResourceWaitMinutes,
        Double avgAppointmentDurationMinutes,
        List<StaffWorkload> staffWorkload,
        long callOutCount,
        long rescheduleCount
) {
    public record Ftr(long scheduledAppointments, long ftrCount, double ftrRate) {}
    public record StaffWorkload(long staffId, String displayName, long closedCount) {}
}
