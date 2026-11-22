package com.frontline.simulator;

import com.frontline.business.BusinessCalendar;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@EnableConfigurationProperties(SimulatorProperties.class)
public class TrafficSimulator {
    private final SimulatorEngine engine;
    private final SimulatorRepository repository;
    private final SimulatorProperties properties;
    private final BusinessCalendar calendar;
    private final Clock clock;
    private final boolean javaEngineEnabled;
    private boolean running;
    private long createdSinceStart;
    public TrafficSimulator(SimulatorEngine engine, SimulatorRepository repository, SimulatorProperties properties,
                            BusinessCalendar calendar, Clock businessClock) {
        this(engine, repository, properties, calendar, businessClock, true);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public TrafficSimulator(SimulatorEngine engine, SimulatorRepository repository, SimulatorProperties properties,
                            BusinessCalendar calendar, Clock businessClock,
                            @org.springframework.beans.factory.annotation.Value("${simulator.java-engine.enabled:true}") boolean javaEngineEnabled) {
        this.engine = engine; this.repository = repository; this.properties = properties;
        this.calendar = calendar; this.clock = businessClock; this.javaEngineEnabled = javaEngineEnabled;
        running = javaEngineEnabled && properties.enabled();
    }
    public synchronized Status start() {
        requireJavaEngine();
        if (!running) { createdSinceStart = 0; running = true; }
        return status();
    }
    public synchronized Status stop() { running = false; return status(); }
    public synchronized Status status() {
        var date = calendar.calendarDate();
        var day = repository.day(date).orElse(null);
        return new Status(javaEngineEnabled, running, properties.arrivalsPerMinute(), createdSinceStart,
                day == null ? 0 : day.targetArrivals(), day == null ? 0 : day.arrivalsSoFar(), repository.openTasks(date),
                engine.scenario(), repository.openHelp(date), repository.appointmentsWaiting(date), repository.appointmentsBeingServed(date));
    }
    @Scheduled(initialDelayString = "${simulator.tick-seconds:5}000", fixedDelayString = "${simulator.tick-seconds:5}000")
    public synchronized void tick() {
        if (javaEngineDisabled() || !running || warming() || !calendar.isOpenNow()) return;
        createdSinceStart += engine.tick(calendar.currentBusinessDate(), clock.instant());
    }
    public boolean javaEngineDisabled() { return !javaEngineEnabled; }
    public void requireJavaEngine() {
        if (javaEngineDisabled()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, "The Java simulator is disabled.");
        }
    }
    public boolean sandboxEnabled() { return engine.sandboxEnabled(); }
    public boolean warming() { return engine.warming(); }
    public record Status(boolean enabled, boolean running, int arrivalsPerMinute, long createdSinceStart,
                         int targetArrivals, int arrivalsSoFar, long openTaskCount, SimulatorProperties.Scenario scenario,
                         long openHelpRequests, long appointmentsWaiting, long appointmentsBeingServed) {}
}
