package com.frontline.simulator;

import com.frontline.business.BusinessCalendar;
import java.time.*;
import java.util.*;

public final class ArrivalSchedule {
    private ArrivalSchedule() {}
    public static List<Instant> times(SimulatorRepository.Day day, SimulatorProperties settings) {
        Random random = new Random(day.seed());
        List<Instant> times = new ArrayList<>();
        int total = settings.curve().values().stream().mapToInt(Integer::intValue).sum();
        for (int n = 0; n < day.targetArrivals(); n++) {
            String slot = draw(settings.curve(), random.nextInt(total));
            LocalTime start = LocalTime.parse(slot);
            LocalTime end = start.plusMinutes(30).isAfter(LocalTime.of(16, 40)) ? LocalTime.of(16, 40) : start.plusMinutes(30);
            times.add(day.businessDate().atTime(start).atZone(BusinessCalendar.ZONE).toInstant()
                    .plusSeconds(random.nextLong(Duration.between(start, end).toSeconds())));
        }
        times.sort(Comparator.naturalOrder());
        return List.copyOf(times);
    }
    public static String draw(Map<String, Integer> weights, int position) {
        for (var entry : weights.entrySet()) {
            position -= entry.getValue();
            if (position < 0) return entry.getKey();
        }
        throw new IllegalArgumentException("Draw is outside weight range.");
    }
    public static String draw(Map<String, Integer> weights, Random random) {
        return draw(weights, random.nextInt(weights.values().stream().mapToInt(Integer::intValue).sum()));
    }
}
