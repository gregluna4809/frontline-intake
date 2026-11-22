package com.frontline.model;

import java.time.Instant;
import java.time.LocalDate;

public record HelpRequest(long id, long visitId, long sessionId, LocalDate businessDate,
        Instant raisedAt, long raisedByStaffId, Long claimedByStaffId, Instant claimedAt,
        Instant closedAt, Long closedByStaffId, Outcome outcome, long version,
        String ticket, long resourceId, String resourceLabel, String roomCode, String roomName,
        String raisedByName, String claimedByName, long ageSeconds) {
    public enum Outcome { RESOLVED_ONLINE, NEEDS_PHONE, REFERRED_OUT, VISIT_ENDED, EXPIRED }
}
