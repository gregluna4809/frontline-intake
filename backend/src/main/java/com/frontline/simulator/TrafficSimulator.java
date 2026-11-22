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
    private boolean running;
    private long createdSinceStart;
    public TrafficSimulator(SimulatorEngine engine, SimulatorRepository repository, SimulatorProperties properties,
                            BusinessCalendar calendar, Clock businessClock) {
        this.engine = engine; this.repository = repository; this.properties = properties;
        this.calendar = calendar; this.clock = businessClock; running = properties.enabled();
    }
    public synchronized Status start() {
        if (!running) { createdSinceStart = 0; running = true; }
        return status();
    }
    public synchronized Status stop() { running = false; return status(); }
    public synchronized Status status() {
        var date = calendar.calendarDate();
        var day = repository.day(date).orElse(null);
        return new Status(running, properties.arrivalsPerMinute(), createdSinceStart,
                day == null ? 0 : day.targetArrivals(), day == null ? 0 : day.arrivalsSoFar(), repository.openTasks(date),
                properties.scenario(), repository.openHelp(date), repository.appointmentsWaiting(date), repository.appointmentsBeingServed(date));
    }
    @Scheduled(initialDelayString = "${simulator.tick-seconds:5}000", fixedDelayString = "${simulator.tick-seconds:5}000")
    public synchronized void tick() {
        if (!running || !calendar.isOpenNow()) return;
        createdSinceStart += engine.tick(calendar.currentBusinessDate(), clock.instant());
    }
    public record Status(boolean running, int arrivalsPerMinute, long createdSinceStart,
                         int targetArrivals, int arrivalsSoFar, long openTaskCount, SimulatorProperties.Scenario scenario,
                         long openHelpRequests, long appointmentsWaiting, long appointmentsBeingServed) {}
}
