package com.frontline.simulator;
import java.util.*;
import java.time.LocalDate;
import java.time.LocalTime;
import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties(prefix = "simulator")
public record SimulatorProperties(boolean enabled, int arrivalsPerMinute, Map<String,Integer> weights,
                                  Map<String,Integer> appointmentWeights, List<DailyRange> dailyArrivals,
                                  Map<String,Integer> curve, Range clerkDelayMinutes, SessionMinutes sessionMinutes,
                                  double leaveEarlyRate, int tickSeconds) {
    public SimulatorProperties {
        if (arrivalsPerMinute < 0) throw new IllegalArgumentException("Simulator rate must be nonnegative.");
        weights = validate(weights, "PHONE_ACCESS","UI_ACCOUNT","APPOINTMENT","COMPUTER_ACCESS","JOB_SEARCH","DOCUMENT_SERVICES","BENEFITS_ASSISTANCE","OTHER");
        appointmentWeights = validate(appointmentWeights, "MANDATORY","JOB_SEARCH_ASSISTANCE");
        if (dailyArrivals == null || dailyArrivals.isEmpty() || dailyArrivals.stream().anyMatch(Objects::isNull)
                || dailyArrivals.stream().filter(range -> range.effectiveFrom() == null).count() != 1
                || dailyArrivals.stream().map(DailyRange::effectiveFrom).distinct().count() != dailyArrivals.size())
            throw new IllegalArgumentException("Daily ranges require one base and unique effective dates.");
        dailyArrivals = List.copyOf(dailyArrivals);
        if (curve == null || curve.size() != 17) throw new IllegalArgumentException("Curve requires all 17 half-hour slots.");
        var sorted = new TreeMap<String,Integer>();
        long total = 0;
        for (int i = 0; i < 17; i++) {
            String key = LocalTime.of(8, 30).plusMinutes(i * 30L).toString();
            Integer weight = curve.get(key);
            if (weight == null || weight < 0) throw new IllegalArgumentException("Invalid curve slot: " + key);
            sorted.put(key, weight); total += weight;
        }
        if (total <= 0 || total > Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid curve total.");
        curve = Collections.unmodifiableMap(sorted);
        if (clerkDelayMinutes == null || sessionMinutes == null || tickSeconds <= 0
                || !Double.isFinite(leaveEarlyRate) || leaveEarlyRate < 0 || leaveEarlyRate > 1)
            throw new IllegalArgumentException("Invalid simulator timing or leave-early rate.");
    }
    private static Map<String,Integer> validate(Map<String,Integer> weights, String... allowed) {
        if (weights == null || weights.isEmpty()) throw new IllegalArgumentException("Simulator weights required.");
        long total = 0;
        for (var entry : weights.entrySet()) {
            if (!java.util.List.of(allowed).contains(entry.getKey()) || entry.getValue() == null || entry.getValue() < 0)
                throw new IllegalArgumentException("Invalid simulator weight: " + entry.getKey());
            total += entry.getValue();
        }
        if (total <= 0 || total > Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid simulator weight total.");
        return Collections.unmodifiableMap(new TreeMap<>(weights));
    }
    public DailyRange rangeFor(LocalDate date) {
        return dailyArrivals.stream().filter(range -> range.effectiveFrom() == null || !range.effectiveFrom().isAfter(date))
                .max(Comparator.comparing(DailyRange::effectiveFrom, Comparator.nullsFirst(Comparator.naturalOrder()))).orElseThrow();
    }
    public record Range(int min, int max) {
        public Range { if (min <= 0 || max < min || max == Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid minute range."); }
        public int draw(Random random) { return random.nextInt(min, max + 1); }
    }
    public record DailyRange(LocalDate effectiveFrom, int min, int max) {
        public DailyRange { if (min < 0 || max < min || max == Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid arrival range."); }
        public int draw(Random random) { return random.nextInt(min, max + 1); }
    }
    public record SessionMinutes(Range phone, Range uiAccount, Range computer, Range station) {
        public SessionMinutes { if (phone == null || uiAccount == null || computer == null || station == null) throw new IllegalArgumentException("All session ranges required."); }
    }
}
