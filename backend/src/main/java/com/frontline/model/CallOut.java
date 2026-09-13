package com.frontline.model;

import java.time.Instant;
import java.time.LocalDate;

public record CallOut(long id, long staffId, LocalDate date, String reason, Instant createdAt) {}
