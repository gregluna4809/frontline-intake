package com.frontline.sandbox;

import java.time.LocalTime;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.frontline.simulator.SimulatorProperties.Scenario;

@Component
@ConditionalOnProperty(name = "frontline.sandbox.enabled", havingValue = "true")
public class SandboxNightlyReset {
    private final SandboxService service;
    public SandboxNightlyReset(SandboxService service) { this.service = service; }
    @Scheduled(cron = "0 0 3 * * *", zone = "America/New_York")
    public void reset() { service.start(new SandboxService.DayInput(SandboxService.Mode.NEXT,null,LocalTime.of(9,30),5,Scenario.SHIFT_TO_ONLINE),true); }
}
