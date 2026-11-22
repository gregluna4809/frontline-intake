package com.frontline.sandbox;

import java.time.LocalDate;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "frontline.sandbox")
public record SandboxProperties(@DefaultValue("false") boolean enabled,
        @DefaultValue("2026-11-02") LocalDate randomFrom, @DefaultValue("2026-12-31") LocalDate randomTo) {
    public SandboxProperties {
        if (randomFrom == null || randomTo == null || randomFrom.isAfter(randomTo))
            throw new IllegalArgumentException("Invalid sandbox random date range.");
    }
}
