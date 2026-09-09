package com.frontline.model;

import java.time.Instant;

public record Visit(
        long id,
        String ticket,
        VisitType visitType,
        Reason reason,
        String appointmentCategory,
        Status status,
        Long customerId,
        Long resourceId,
        Instant createdAt,
        Instant closedAt,
        long version
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
    public enum AppointmentCategory { REEMPLOYMENT, CAREER_SERVICES, VETERANS }
    public enum Reason {
        PHONE_ACCESS, COMPUTER_ACCESS, DOCUMENT_SERVICES,
        BENEFITS_ASSISTANCE, JOB_SEARCH, OTHER
    }
    public enum Status {
        CHECKED_IN, IDENTIFIED, AT_RESOURCE, WAITING_RESOURCE, WAITING_STAFF, CLOSED, LEFT
    }
}
