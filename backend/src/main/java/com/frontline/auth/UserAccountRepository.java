package com.frontline.auth;

import com.frontline.model.Staff;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class UserAccountRepository {
    private final JdbcClient jdbc;

    public UserAccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Account> findByUsername(String username) {
        return jdbc.sql("""
                SELECT u.username, u.password_hash, s.id, s.display_name, s.role
                FROM user_account u JOIN staff s ON s.id = u.staff_id
                WHERE u.username = :username
                """).param("username", username).query((rs, row) -> new Account(
                        rs.getString("username"), rs.getString("password_hash"),
                        new Staff(rs.getLong("id"), rs.getString("display_name"),
                                Staff.Role.valueOf(rs.getString("role"))))).optional();
    }

    public record Account(String username, String passwordHash, Staff staff) { }
}
