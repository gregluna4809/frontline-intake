package com.frontline.business;

import com.frontline.simulator.TrafficSimulator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "business.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class BusinessSchedules {
    private final BusinessCalendar calendar;
    private final TrafficSimulator simulator;
    private final TicketSequenceService tickets;
    private final BusinessDayService days;

    public BusinessSchedules(BusinessCalendar calendar, TrafficSimulator simulator, TicketSequenceService tickets, BusinessDayService days) {
        this.calendar = calendar;
        this.simulator = simulator;
        this.tickets = tickets;
        this.days = days;
    }

    @Scheduled(cron = "0 * * * * *", zone = BusinessCalendar.TIME_ZONE)
    public void reconcileSimulator() {
        if (simulator.sandboxEnabled() || simulator.warming()) return;
        days.freezeCompletedDays();
        boolean open = calendar.isOpenNow();
        boolean running = simulator.status().running();
        if (open && !running) simulator.start();
        else if (!open && running) simulator.stop();
    }

    @Scheduled(cron = "0 30 8 * * MON-FRI", zone = BusinessCalendar.TIME_ZONE)
    public void resetAtOpening() {
        if (simulator.sandboxEnabled() || simulator.warming()) return;
        if (calendar.isOpenNow()) tickets.resetForOpening();
    }
}
