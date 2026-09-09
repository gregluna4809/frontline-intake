package com.frontline.model;

import java.time.Instant;

public record VisitEvent(
        long id,
        long visitId,
        Visit.Status fromStatus,
        Visit.Status toStatus,
        String actor,
        Instant createdAt
) {}
