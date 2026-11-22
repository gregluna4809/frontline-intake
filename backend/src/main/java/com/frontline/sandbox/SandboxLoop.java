package com.frontline.sandbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "frontline.sandbox.enabled", havingValue = "true")
public class SandboxLoop {
    private final SandboxService service;

    public SandboxLoop(SandboxService service) {
        this.service = service;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(cron = "0 * * * * *")
    public void reconcile() {
        service.reconcile();
    }
}
