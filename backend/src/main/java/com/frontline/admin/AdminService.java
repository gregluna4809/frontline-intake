package com.frontline.admin;

import com.frontline.business.BusinessDayService;
import com.frontline.model.Staff;
import com.frontline.model.StaffDuty.DutyType;
import com.frontline.staff.StaffRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdminService {
    private final AdminRepository repository;
    private final StaffRepository staff;
    private final BusinessDayService days;
    private final PasswordEncoder passwords;

    public AdminService(AdminRepository repository, StaffRepository staff, BusinessDayService days, PasswordEncoder passwords) {
        this.repository = repository; this.staff = staff; this.days = days; this.passwords = passwords;
    }

    @Transactional(readOnly = true)
    public List<AdminRepository.StaffAccount> staff() { return repository.staff(); }

    @Transactional
    public Staff createStaff(StaffInput input) {
        days.freezeCompletedDays();
        long id = repository.createStaff(input.displayName().strip(), input.role());
        if (Boolean.FALSE.equals(input.active())) repository.editStaff(id, input.displayName().strip(), input.role(), false);
        if (input.role() == Staff.Role.LSR) repository.ensureRotation(id);
        return requireStaff(id);
    }

    @Transactional
    public Staff editStaff(long id, StaffInput input) {
        // Shares the operational mutation lock so deactivation cannot race assignment.
        days.freezeCompletedDays();
        Staff previous = requireStaff(id);
        boolean active = input.active() == null ? previous.active() : input.active();
        if (previous.active() && previous.role() == Staff.Role.ADMINISTRATOR
                && (!active || input.role() != Staff.Role.ADMINISTRATOR)
                && repository.activeAdministratorsWithAccounts() <= 1 && repository.hasAccount(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Keep at least one active administrator with a login account.");
        }
        repository.editStaff(id, input.displayName().strip(), input.role(), active);
        if (input.role() == Staff.Role.LSR) repository.ensureRotation(id);
        return requireStaff(id);
    }

    @Transactional(readOnly = true)
    public List<AdminRepository.RotationRow> rotation() { return repository.rotation(); }

    @Transactional
    public List<AdminRepository.RotationRow> editRotation(RotationInput input) {
        days.freezeCompletedDays();
        var seen = new HashSet<String>();
        for (var assignment : input.assignments()) {
            Staff person = requireStaff(assignment.staffId());
            if (!person.active() || person.role() != Staff.Role.LSR) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Rotation assignments require an active LSR.");
            }
            if (!seen.add(assignment.staffId() + ":" + assignment.dayOfWeek())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Each staff/day assignment must appear only once.");
            }
            repository.setDuty(assignment.staffId(), assignment.dayOfWeek(), assignment.dutyType());
        }
        return repository.rotation();
    }

    @Transactional(readOnly = true)
    public List<AdminRepository.Holiday> holidays() { return repository.holidays(); }

    @Transactional
    public AdminRepository.Holiday addHoliday(HolidayInput input) {
        days.freezeCompletedDays();
        return repository.addHoliday(input.date(), input.label().strip());
    }

    @Transactional
    public void removeHoliday(long id) {
        days.freezeCompletedDays();
        if (!repository.removeHoliday(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Holiday not found.");
    }

    @Transactional
    public AdminRepository.Account createAccount(AccountInput input) {
        days.freezeCompletedDays();
        if (!requireStaff(input.staffId()).active()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Reactivate this staff member before creating an account.");
        }
        return repository.createAccount(input.staffId(), input.username(), passwordHash(input.password()));
    }

    @Transactional
    public AdminRepository.Account resetPassword(long id, PasswordInput input) {
        var account = repository.account(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found."));
        repository.resetPassword(id, passwordHash(input.password()));
        return account;
    }

    private Staff requireStaff(long id) {
        return staff.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Staff member not found."));
    }

    private String passwordHash(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must be at most 72 UTF-8 bytes.");
        }
        return passwords.encode(password);
    }

    public record StaffInput(@NotBlank @Size(max = 100) String displayName, @NotNull Staff.Role role, Boolean active) { }
    public record RotationInput(@NotEmpty @Size(max = 7000) List<@NotNull @Valid Assignment> assignments) { }
    public record Assignment(@Positive long staffId, @Min(1) @Max(7) int dayOfWeek, @NotNull DutyType dutyType) { }
    public record HolidayInput(@NotNull LocalDate date, @NotBlank @Size(max = 100) String label) { }
    public record AccountInput(@Positive long staffId,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,99}") String username,
            @NotBlank @Size(min = 8, max = 72) String password) { }
    public record PasswordInput(@NotBlank @Size(min = 8, max = 72) String password) { }
}
