package com.frontline.simulator;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/simulator")
public class SimulatorController {
    private final TrafficSimulator simulator;
    public SimulatorController(TrafficSimulator simulator) { this.simulator = simulator; }
    @PostMapping("/start")
    public TrafficSimulator.Status start() { return simulator.start(); }
    @PostMapping("/stop")
    public TrafficSimulator.Status stop() { return simulator.stop(); }
    @GetMapping("/status")
    public TrafficSimulator.Status status() { return simulator.status(); }
}
