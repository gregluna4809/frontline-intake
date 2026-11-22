package com.frontline.staff;

import com.frontline.model.Staff;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class StaffRepository {
    private final JdbcClient jdbc;

    public StaffRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Staff> findAll() {
        return jdbc.sql("SELECT * FROM staff ORDER BY (display_name LIKE 'Bot %'), role, display_name, id")
                .query(this::mapStaff).list();
    }

    public List<Staff> findActive() {
        return jdbc.sql("SELECT * FROM staff WHERE active ORDER BY (display_name LIKE 'Bot %'), role, display_name, id")
                .query(this::mapStaff).list();
    }

    public Optional<Staff> findById(long id) {
        return jdbc.sql("SELECT * FROM staff WHERE id = :id")
                .param("id", id).query(this::mapStaff).optional();
    }

    private Staff mapStaff(ResultSet rs, int row) throws SQLException {
        return new Staff(rs.getLong("id"), rs.getString("display_name"),
                Staff.Role.valueOf(rs.getString("role")), rs.getBoolean("active"));
    }
}
