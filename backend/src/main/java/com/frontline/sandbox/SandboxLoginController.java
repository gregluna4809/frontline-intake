package com.frontline.sandbox;

import com.frontline.auth.AuthController;
import com.frontline.model.Staff;
import com.frontline.staff.StaffRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class SandboxLoginController {
    private final SandboxProperties properties;
    private final StaffRepository staff;
    private final AuthController auth;

    public SandboxLoginController(SandboxProperties properties, StaffRepository staff, AuthController auth) {
        this.properties = properties; this.staff = staff; this.auth = auth;
    }

    @PostMapping("/api/sandbox/login")
    public AuthController.LoginResponse login(@RequestBody LoginInput input) {
        if (!properties.enabled()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (input.role() == null || input.role() == Staff.Role.ADMINISTRATOR)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a sandbox staff role.");
        var person = staff.findActive().stream().filter(value -> value.role() == input.role())
                .min(java.util.Comparator.comparingLong(Staff::id))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No active staff for this role."));
        return auth.issueToken(person, "sandbox-staff-" + person.id());
    }

    public record LoginInput(Staff.Role role) {}
}
