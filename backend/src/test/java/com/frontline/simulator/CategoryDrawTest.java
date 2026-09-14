package com.frontline.simulator;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CategoryDrawTest {
    @Test
    void categoryDrawAllocatesExactlyNinetyFiveAndFiveOfOneHundredPositions() {
        var weights = new LinkedHashMap<String, Integer>();
        weights.put("MANDATORY", 95);
        weights.put("JOB_SEARCH_ASSISTANCE", 5);
        var results = IntStream.range(0, 100).mapToObj(draw -> TrafficSimulator.draw(weights, draw)).toList();
        assertThat(results.stream().filter("MANDATORY"::equals).count()).isEqualTo(95);
        assertThat(results.stream().filter("JOB_SEARCH_ASSISTANCE"::equals).count()).isEqualTo(5);
        assertThat(TrafficSimulator.draw(weights, 94)).isEqualTo("MANDATORY");
        assertThat(TrafficSimulator.draw(weights, 95)).isEqualTo("JOB_SEARCH_ASSISTANCE");
        assertThatThrownBy(() -> new SimulatorProperties(false, 1, Map.of("APPOINTMENT", 1), Map.of("REEMPLOYMENT", 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
