package com.frontline.sandbox;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/sandbox")
public class SandboxController {
    private final SandboxService service;
    public SandboxController(SandboxService service) { this.service = service; }
    @GetMapping("/state") public SandboxService.StateResponse state() { return service.state(); }
    @PostMapping("/day") public SandboxService.DayResponse day(@RequestBody SandboxService.DayInput input) { return service.start(input); }
}
