package com.frontline.simulator;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
@RestController
@RequestMapping("/api/simulator")
public class SimulatorController {
    private final TrafficSimulator simulator;
    private final SimulatorRepository repository;
    public SimulatorController(TrafficSimulator simulator, SimulatorRepository repository) { this.simulator = simulator; this.repository = repository; }
    @PostMapping("/start")
    public TrafficSimulator.Status start() { return simulator.start(); }
    @PostMapping("/stop")
    public TrafficSimulator.Status stop() {
        if (simulator.sandboxEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The sandbox simulator cannot be stopped.");
        }
        return simulator.stop();
    }
    @GetMapping("/status")
    public TrafficSimulator.Status status() { return simulator.status(); }
    @GetMapping("/switches")
    public java.util.List<SimulatorRepository.Switch> switches() { return repository.switches(); }
    @PutMapping("/switches/{role}")
    public SimulatorRepository.Switch setSwitch(@PathVariable String role,
            @jakarta.validation.Valid @RequestBody SwitchInput input) { return repository.setSwitch(role, input.enabled()); }
    public record SwitchInput(@jakarta.validation.constraints.NotNull Boolean enabled) {}
}
