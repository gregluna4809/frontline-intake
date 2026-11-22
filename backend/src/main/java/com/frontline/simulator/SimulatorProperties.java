package com.frontline.simulator;
import java.util.*;
import java.time.LocalDate;
import java.time.LocalTime;
import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties(prefix = "simulator")
public record SimulatorProperties(boolean enabled, int arrivalsPerMinute, Map<String,Integer> weights,
                                  Map<String,Integer> appointmentWeights, List<DailyRange> dailyArrivals,
                                  Map<String,Integer> curve, Range clerkDelayMinutes, SessionMinutes sessionMinutes,
                                  double leaveEarlyRate, int tickSeconds, Scenario scenario,
                                  Map<String,Double> helpRate, HelpOutcomes helpOutcomes,
                                  Range helpClaimDelayMinutes, Range helpWorkMinutes,
                                  Range appointmentMinutes, Range referredOutLeaveMinutes) {
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
        if (scenario == null || helpOutcomes == null || helpClaimDelayMinutes == null || helpWorkMinutes == null
                || appointmentMinutes == null || referredOutLeaveMinutes == null)
            throw new IllegalArgumentException("All simulator help and appointment settings are required.");
        if (helpRate == null || !helpRate.keySet().equals(Set.of("UI_ACCOUNT", "COMPUTER_ACCESS", "JOB_SEARCH")))
            throw new IllegalArgumentException("Help rates require UI_ACCOUNT, COMPUTER_ACCESS and JOB_SEARCH only.");
        helpRate.forEach((reason, rate) -> { if (rate == null || !Double.isFinite(rate) || rate < 0 || rate > 1) throw new IllegalArgumentException("Invalid help rate: " + reason); });
        helpRate = Collections.unmodifiableMap(new TreeMap<>(helpRate));
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
    public enum Scenario { SHIFT_TO_ONLINE, BEHAVIOR_UNCHANGED }
    public record OutcomeRates(double needsPhone, double referredOut) {
        public OutcomeRates {
            if (!Double.isFinite(needsPhone) || !Double.isFinite(referredOut) || needsPhone < 0 || referredOut < 0 || needsPhone + referredOut > 1)
                throw new IllegalArgumentException("Invalid help outcome probabilities.");
        }
        public com.frontline.model.HelpRequest.Outcome draw(Random random) {
            double value = random.nextDouble();
            return value < needsPhone ? com.frontline.model.HelpRequest.Outcome.NEEDS_PHONE
                    : value < needsPhone + referredOut ? com.frontline.model.HelpRequest.Outcome.REFERRED_OUT
                    : com.frontline.model.HelpRequest.Outcome.RESOLVED_ONLINE;
        }
    }
    public record HelpOutcomes(OutcomeRates shiftToOnline, OutcomeRates behaviorUnchanged, OutcomeRates computer) {
        public HelpOutcomes {
            if (shiftToOnline == null || behaviorUnchanged == null || computer == null || computer.needsPhone() != 0)
                throw new IllegalArgumentException("All help outcomes required; computer access/job search cannot need a phone.");
        }
        public com.frontline.model.HelpRequest.Outcome draw(com.frontline.model.Visit.Reason reason, Scenario scenario, Random random) {
            return (reason == com.frontline.model.Visit.Reason.UI_ACCOUNT
                    ? scenario == Scenario.SHIFT_TO_ONLINE ? shiftToOnline : behaviorUnchanged : computer).draw(random);
        }
    }
}
