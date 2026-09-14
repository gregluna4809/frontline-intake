package com.frontline.simulator;
import com.frontline.model.Visit;
import com.frontline.visit.VisitService;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@EnableConfigurationProperties(SimulatorProperties.class)
public class TrafficSimulator {
    private static final Logger log = LoggerFactory.getLogger(TrafficSimulator.class);
    private final VisitService visits;
    private final SimulatorProperties properties;
    private boolean running;
    private long createdSinceStart;

    public TrafficSimulator(VisitService visits, SimulatorProperties properties) {
        this.visits = visits;
        this.properties = properties;
        running = properties.enabled();
    }
    public synchronized Status start() {
        if (!running) { createdSinceStart = 0; running = true; }
        return status();
    }
    public synchronized Status stop() { running = false; return status(); }
    public synchronized Status status() {
        return new Status(running, properties.arrivalsPerMinute(), createdSinceStart);
    }
    @Scheduled(initialDelay = 60000, fixedDelay = 60000)
    public synchronized void tick() {
        if (!running) return;
        for (int i = 0; i < properties.arrivalsPerMinute(); i++) {
            try {
                String choice = draw(properties.weights());
                if (choice.equals("APPOINTMENT")) {
                    visits.checkIn(Visit.VisitType.APPOINTMENT, null,
                        Visit.AppointmentCategory.valueOf(draw(properties.appointmentWeights())), "SIMULATOR");
                } else {
                    visits.checkIn(Visit.VisitType.WALK_IN, Visit.Reason.valueOf(choice), null, "SIMULATOR");
                }
                createdSinceStart++;
            } catch (Exception exception) { log.error("Simulator arrival failed", exception); }
        }
    }
    private String draw(Map<String,Integer> weights) {
        return draw(weights, ThreadLocalRandom.current().nextInt(weights.values().stream().mapToInt(Integer::intValue).sum()));
    }
    static String draw(Map<String,Integer> weights, int remaining) {
        for (var entry : weights.entrySet()) {
            remaining -= entry.getValue();
            if (remaining < 0) return entry.getKey();
        }
        throw new IllegalStateException("No simulator choice selected.");
    }
    public record Status(boolean running, int arrivalsPerMinute, long createdSinceStart) {}
}
