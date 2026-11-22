package com.frontline.simulator;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

public final class SimulatorTestSettings {
    private SimulatorTestSettings() {}
    public static SimulatorProperties settings(Map<String,Integer> weights, Map<String,Integer> appointments, double early) {
        int[] values = {3, 6, 6, 6, 6, 2, 2, 2, 2, 6, 6, 6, 6, 6, 4, 2, 1};
        Map<String,Integer> curve = new TreeMap<>();
        for (int i = 0; i < values.length; i++) curve.put(LocalTime.of(8,30).plusMinutes(i * 30L).toString(), values[i]);
        return new SimulatorProperties(false, 4, weights, appointments,
                List.of(new SimulatorProperties.DailyRange(null, 200, 250), new SimulatorProperties.DailyRange(LocalDate.parse("2026-11-30"), 250, 300)),
                curve, new SimulatorProperties.Range(1,4), new SimulatorProperties.SessionMinutes(
                    new SimulatorProperties.Range(15,30), new SimulatorProperties.Range(20,45),
                    new SimulatorProperties.Range(30,60), new SimulatorProperties.Range(5,15)), early, 5);
    }
}
