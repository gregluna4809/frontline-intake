package com.frontline.customer;

import com.frontline.model.Customer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CustomerRepository {
    private final JdbcClient jdbc;

    public CustomerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

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
