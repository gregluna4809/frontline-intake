package com.frontline.admin;

import com.frontline.model.Staff;
import com.frontline.model.StaffDuty.DutyType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AdminRepository {
    private final JdbcClient jdbc;
    public AdminRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public List<StaffAccount> staff() {
        return jdbc.sql("""
                SELECT s.*, u.id AS account_id, u.username FROM staff s
                LEFT JOIN user_account u ON u.staff_id = s.id ORDER BY s.display_name, s.id
                """).query((rs, row) -> new StaffAccount(rs.getLong("id"), rs.getString("display_name"),
                        Staff.Role.valueOf(rs.getString("role")), rs.getBoolean("active"),
                        rs.getObject("account_id", Long.class), rs.getString("username"))).list();
    }

    public long createStaff(String displayName, Staff.Role role) {
        return jdbc.sql("INSERT INTO staff (display_name, role) VALUES (:name, :role) RETURNING id")
                .param("name", displayName).param("role", role.name()).query(Long.class).single();
    }

    public void editStaff(long id, String displayName, Staff.Role role, boolean active) {
        jdbc.sql("UPDATE staff SET display_name = :name, role = :role, active = :active WHERE id = :id")
                .param("id", id).param("name", displayName).param("role", role.name()).param("active", active).update();
    }

    public long activeAdministratorsWithAccounts() {
        return jdbc.sql("""
                SELECT count(*) FROM staff s JOIN user_account u ON u.staff_id = s.id
                WHERE s.active AND s.role = 'ADMINISTRATOR'
                """).query(Long.class).single();
    }

    public boolean hasAccount(long staffId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM user_account WHERE staff_id = :id)")
                .param("id", staffId).query(Boolean.class).single();
    }

    public void ensureRotation(long id) {
        jdbc.sql("""
                INSERT INTO staff_rotation (staff_id, day_of_week, duty_type)
                SELECT :id, d, 'APPOINTMENTS' FROM generate_series(1, 7) AS d
                ON CONFLICT (staff_id, day_of_week) DO NOTHING
                """).param("id", id).update();
    }

    public List<RotationRow> rotation() {
        return jdbc.sql("""
                SELECT s.id, s.display_name, r.day_of_week, r.duty_type
                FROM staff s JOIN staff_rotation r ON r.staff_id = s.id
                WHERE s.active AND s.role = 'LSR' ORDER BY s.display_name, s.id, r.day_of_week
                """).query((rs, row) -> new RotationRow(rs.getLong("id"), rs.getString("display_name"),
                        rs.getInt("day_of_week"), DutyType.valueOf(rs.getString("duty_type")))).list();
    }

    public void setDuty(long staffId, int day, DutyType duty) {
        jdbc.sql("""
                INSERT INTO staff_rotation (staff_id, day_of_week, duty_type) VALUES (:id, :day, :duty)
                ON CONFLICT (staff_id, day_of_week) DO UPDATE SET duty_type = EXCLUDED.duty_type
                """).param("id", staffId).param("day", day).param("duty", duty.name()).update();
    }

    public List<Holiday> holidays() {
        return jdbc.sql("SELECT * FROM holiday ORDER BY date, id").query(Holiday.class).list();
    }

    public Holiday addHoliday(LocalDate date, String label) {
        return jdbc.sql("INSERT INTO holiday (date, label) VALUES (:date, :label) RETURNING *")
                .param("date", date).param("label", label).query(Holiday.class).single();
    }

    public boolean removeHoliday(long id) {
        return jdbc.sql("DELETE FROM holiday WHERE id = :id").param("id", id).update() == 1;
    }

    public Account createAccount(long staffId, String username, String hash) {
        return jdbc.sql("""
                INSERT INTO user_account (staff_id, username, password_hash) VALUES (:staff, :username, :hash)
                RETURNING id, staff_id, username, created_at
                """).param("staff", staffId).param("username", username).param("hash", hash).query(Account.class).single();
    }

    public Optional<Account> account(long id) {
        return jdbc.sql("SELECT id, staff_id, username, created_at FROM user_account WHERE id = :id")
                .param("id", id).query(Account.class).optional();
    }

    public void resetPassword(long id, String hash) {
        jdbc.sql("UPDATE user_account SET password_hash = :hash WHERE id = :id")
                .param("id", id).param("hash", hash).update();
    }

    public record StaffAccount(long id, String displayName, Staff.Role role, boolean active, Long accountId, String username) { }
    public record RotationRow(long staffId, String displayName, int dayOfWeek, DutyType dutyType) { }
    public record Holiday(long id, LocalDate date, String label) { }
    public record Account(long id, long staffId, String username, Instant createdAt) { }
}
