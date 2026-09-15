package com.frontline.admin;

import com.frontline.model.Staff;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final AdminService service;
    public AdminController(AdminService service) { this.service = service; }

    @GetMapping("/staff")
    public List<AdminRepository.StaffAccount> staff() { return service.staff(); }

    @PostMapping("/staff")
    public ResponseEntity<Staff> createStaff(@Valid @RequestBody AdminService.StaffInput input) {
        Staff person = service.createStaff(input);
        return ResponseEntity.created(URI.create("/api/admin/staff/" + person.id())).body(person);
    }

    @PutMapping("/staff/{id}")
    public Staff editStaff(@PathVariable long id, @Valid @RequestBody AdminService.StaffInput input) {
        return service.editStaff(id, input);
    }

    @GetMapping("/rotation")
    public List<AdminRepository.RotationRow> rotation() { return service.rotation(); }

    @PutMapping("/rotation")
    public List<AdminRepository.RotationRow> editRotation(@Valid @RequestBody AdminService.RotationInput input) {
        return service.editRotation(input);
    }

    @GetMapping("/holidays")
    public List<AdminRepository.Holiday> holidays() { return service.holidays(); }

    @PostMapping("/holidays")
    public ResponseEntity<AdminRepository.Holiday> addHoliday(@Valid @RequestBody AdminService.HolidayInput input) {
        var holiday = service.addHoliday(input);
        return ResponseEntity.created(URI.create("/api/admin/holidays/" + holiday.id())).body(holiday);
    }

    @DeleteMapping("/holidays/{id}")
    public ResponseEntity<Void> removeHoliday(@PathVariable long id) {
        service.removeHoliday(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/accounts")
    public ResponseEntity<AdminRepository.Account> createAccount(@Valid @RequestBody AdminService.AccountInput input) {
        var account = service.createAccount(input);
        return ResponseEntity.created(URI.create("/api/admin/accounts/" + account.id())).body(account);
    }

    @PutMapping("/accounts/{id}/reset-password")
    public AdminRepository.Account resetPassword(@PathVariable long id, @Valid @RequestBody AdminService.PasswordInput input) {
        return service.resetPassword(id, input);
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ProblemDetail duplicate(DuplicateKeyException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "That holiday date, username, or staff account already exists.");
    }
}
