package com.frontline.model;

import java.time.Instant;
import java.time.LocalDate;

public record Visit(
        long id,
        String ticket,
        VisitType visitType,
        Reason reason,
        String appointmentCategory,
        AppointmentType appointmentType,
        AppointmentStatus appointmentStatus,
        Long assignedStaffId,
        Status status,
        Long customerId,
        Long resourceId,
        Instant createdAt,
        Instant closedAt,
        long version,
        Long previousVisitId,
        LocalDate businessDate
) {
    public enum VisitType { WALK_IN, APPOINTMENT }
    public Resource.Type requiredResourceType() {
        if (visitType == VisitType.APPOINTMENT) return null;
        return switch (reason) {
            case PHONE_ACCESS -> Resource.Type.PHONE;
            case COMPUTER_ACCESS, JOB_SEARCH -> Resource.Type.COMPUTER;
            case DOCUMENT_SERVICES, BENEFITS_ASSISTANCE, OTHER -> Resource.Type.STATION;
        };
    }
    public enum AppointmentType { INITIAL, FOLLOWUP }
    public enum AppointmentStatus { SCHEDULED, RESCHEDULED, NOT_ATTENDED_FTR, FTR_REPORTED }
    public enum AppointmentCategory { MANDATORY, JOB_SEARCH_ASSISTANCE }
    public enum Reason {
        PHONE_ACCESS, COMPUTER_ACCESS, DOCUMENT_SERVICES,
        BENEFITS_ASSISTANCE, JOB_SEARCH, OTHER
    }
    public enum Status {
        CHECKED_IN, IDENTIFIED, AT_RESOURCE, WAITING_RESOURCE, WAITING_STAFF, CLOSED, LEFT
    }
}
