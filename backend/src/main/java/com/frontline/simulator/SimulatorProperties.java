package com.frontline.simulator;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties(prefix = "simulator")
public record SimulatorProperties(boolean enabled, int arrivalsPerMinute, Map<String,Integer> weights,
                                  Map<String,Integer> appointmentWeights) {
    public SimulatorProperties {
        if (arrivalsPerMinute < 0) throw new IllegalArgumentException("Simulator rate must be nonnegative.");
        weights = validate(weights, "PHONE_ACCESS","APPOINTMENT","COMPUTER_ACCESS","JOB_SEARCH","DOCUMENT_SERVICES","BENEFITS_ASSISTANCE","OTHER");
        appointmentWeights = validate(appointmentWeights, "MANDATORY","JOB_SEARCH_ASSISTANCE");
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
        return java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(weights));
    }
}
