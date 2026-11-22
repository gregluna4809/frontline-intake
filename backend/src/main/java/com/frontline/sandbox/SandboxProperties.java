package com.frontline.sandbox;

import java.time.LocalDate;
import java.time.LocalTime;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "frontline.sandbox")
public record SandboxProperties(@DefaultValue("false") boolean enabled,
        @DefaultValue("2026-11-02") LocalDate randomFrom, @DefaultValue("2026-12-31") LocalDate randomTo,
        @DefaultValue("15:30") LocalTime rollAt, @DefaultValue("10:15") LocalTime autoStartTime,
        @DefaultValue("1.4") double trafficMultiplier) {
    public SandboxProperties {
        if (randomFrom == null || randomTo == null || randomFrom.isAfter(randomTo))
            throw new IllegalArgumentException("Invalid sandbox random date range.");
    }
}
