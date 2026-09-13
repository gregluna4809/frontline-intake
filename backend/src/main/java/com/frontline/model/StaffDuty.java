package com.frontline.model;

public record StaffDuty(long staffId, String displayName, DutyType dutyType) {
    public enum DutyType { APPOINTMENTS, RESOURCE_ROOM }
}
