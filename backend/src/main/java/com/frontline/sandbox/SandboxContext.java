package com.frontline.sandbox;

import org.springframework.stereotype.Component;
import com.frontline.simulator.SimulatorProperties.Scenario;

@Component
public class SandboxContext {
    private final SandboxProperties properties;
    private final SandboxStateRepository repository;
    public SandboxContext(SandboxProperties properties, SandboxStateRepository repository) { this.properties = properties; this.repository = repository; }
    public boolean enabled() { return properties.enabled(); }
    public boolean warming() { return enabled() && repository.state().warming(); }
    public int speed() { return enabled() ? repository.state().speed() : 1; }
    public Scenario scenario(Scenario live) { return enabled() ? repository.state().scenario() : live; }
}
