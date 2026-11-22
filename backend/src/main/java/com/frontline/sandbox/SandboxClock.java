package com.frontline.sandbox;

import java.time.*;

public final class SandboxClock extends Clock {
    private final SandboxStateRepository repository;
    private final Clock realClock;
    private final ZoneId zone;
    private volatile Instant manual;
    public SandboxClock(SandboxStateRepository repository, Clock realClock, ZoneId zone) {
        this.repository = repository; this.realClock = realClock; this.zone = zone;
    }
    @Override public ZoneId getZone() { return zone; }
    @Override public Clock withZone(ZoneId zone) { return getZone().equals(zone) ? this : new Clock() {
        public ZoneId getZone() { return zone; }
        public Clock withZone(ZoneId next) { return SandboxClock.this.withZone(next); }
        public Instant instant() { return SandboxClock.this.instant(); }
    }; }
    @Override public Instant instant() {
        Instant value = manual;
        if (value != null) return value;
        var state = repository.state();
        if (state.anchorSandbox() == null || state.anchorReal() == null) return realClock.instant();
        if (state.warming()) return state.anchorSandbox();
        return state.anchorSandbox().plus(Duration.between(state.anchorReal(),realClock.instant()).multipliedBy(state.speed()));
    }
    public void drive(Instant instant) { manual = instant; }
    public void release() { manual = null; }
}
