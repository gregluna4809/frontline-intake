package com.frontline.coverage;

import com.frontline.model.CallOut;
import com.frontline.model.StaffDuty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class CoverageController {
    private final CoverageService service;

    public CoverageController(CoverageService service) {
        this.service = service;
    }

    @GetMapping("/rotation")
    public List<StaffDuty> rotation(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.rotation(date);
    }

    @GetMapping("/call-outs")
    public List<CallOut> callOuts(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.callOuts(date);
    }

    @PostMapping("/call-outs")
    public CallOut callOut(@Valid @RequestBody CallOutRequest request) {
        return service.callOut(request.staffId(), request.date(), request.reason());
    }

    public record CallOutRequest(@NotNull @Positive Long staffId, @NotNull LocalDate date, String reason) {}
}
