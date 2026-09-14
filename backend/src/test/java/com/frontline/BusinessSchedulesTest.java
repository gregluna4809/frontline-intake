package com.frontline;

import com.frontline.business.BusinessCalendar;
import com.frontline.business.BusinessDayService;
import com.frontline.business.BusinessSchedules;
import com.frontline.business.TicketSequenceService;
import com.frontline.simulator.SimulatorProperties;
import com.frontline.simulator.TrafficSimulator;
import com.frontline.visit.VisitService;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BusinessSchedulesTest {
    @Test
    void minuteControlStartsOnlyWhenOpenAndStopsAfterClosing() {
        Clock clock = mock(Clock.class);
        var simulator = spy(new TrafficSimulator(mock(VisitService.class), new SimulatorProperties(false, 1,
                Map.of("PHONE_ACCESS", 1), Map.of("MANDATORY", 1))));
        var schedules = new BusinessSchedules(new BusinessCalendar(clock), simulator, mock(TicketSequenceService.class), mock(BusinessDayService.class));
        when(clock.instant()).thenReturn(Instant.parse("2026-09-08T12:29:00Z"));
        schedules.reconcileSimulator();
        assertThat(simulator.status().running()).isFalse();
        when(clock.instant()).thenReturn(Instant.parse("2026-09-08T12:30:00Z"));
        schedules.reconcileSimulator();
        schedules.reconcileSimulator();
        verify(simulator, times(1)).start();
        assertThat(simulator.status().running()).isTrue();
        when(clock.instant()).thenReturn(Instant.parse("2026-09-08T20:45:00Z"));
        schedules.reconcileSimulator();
        schedules.reconcileSimulator();
        verify(simulator, times(1)).stop();
        assertThat(simulator.status().running()).isFalse();
    }

    @Test
    void openingResetSkipsWeekendsAndHolidays() {
        Clock clock = mock(Clock.class);
        TicketSequenceService tickets = mock(TicketSequenceService.class);
        var schedules = new BusinessSchedules(new BusinessCalendar(clock), mock(TrafficSimulator.class), tickets, mock(BusinessDayService.class));
        for (String instant : new String[] {"2026-09-07T12:30:00Z", "2026-09-12T12:30:00Z",
                "2026-09-13T12:30:00Z", "2026-11-27T13:30:00Z"}) {
            when(clock.instant()).thenReturn(Instant.parse(instant));
            schedules.resetAtOpening();
        }
        verifyNoInteractions(tickets);
        when(clock.instant()).thenReturn(Instant.parse("2026-09-08T12:30:00Z"));
        schedules.resetAtOpening();
        verify(tickets).resetForOpening();
    }
}
