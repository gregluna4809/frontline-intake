package com.frontline.customer;

import com.frontline.model.Customer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CustomerRepository {
    private final JdbcClient jdbc;

    private final Clock clock;

    public CustomerRepository(JdbcClient jdbc, Clock businessClock) {
        this.jdbc = jdbc;
        this.clock = businessClock;
    }

    public CreatedCustomer create(String displayName, Customer.Language language, boolean veteran) {
        return jdbc.sql("""
                INSERT INTO customer (display_name, language, veteran, created_at)
                VALUES (:name, :language, :veteran, :now)
                RETURNING id, display_name, language, veteran, created_at
                """).param("name", displayName).param("language", language.name()).param("veteran", veteran)
                .param("now", Timestamp.from(clock.instant()))
                .query((rs, row) -> new CreatedCustomer(rs.getLong("id"), rs.getString("display_name"),
                        Customer.Language.valueOf(rs.getString("language")), rs.getBoolean("veteran"),
                        rs.getTimestamp("created_at").toInstant())).single();
    }

    public record CreatedCustomer(long id, String displayName, Customer.Language language, boolean veteran, Instant createdAt) {}

    public List<Customer> search(String query) {
        return jdbc.sql("""
                SELECT * FROM customer
                WHERE strpos(lower(display_name), lower(:query)) > 0
                ORDER BY display_name, id LIMIT 20
                """)
                .param("query", query.strip())
                .query(this::mapCustomer).list();
    }

    public Optional<Customer> findById(long id) {
        return jdbc.sql("SELECT * FROM customer WHERE id = :id")
                .param("id", id).query(this::mapCustomer).optional();
    }

    private Customer mapCustomer(ResultSet rs, int row) throws SQLException {
        return new Customer(rs.getLong("id"), rs.getString("display_name"),
                Customer.Language.valueOf(rs.getString("language")), rs.getBoolean("veteran"));
    }
}
