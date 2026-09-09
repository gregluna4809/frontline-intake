package com.frontline.resource;

import com.frontline.model.Resource;
import com.frontline.model.Customer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ResourceRepository {
    private final JdbcClient jdbc;

    public ResourceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Resource> findById(long id) {
        return jdbc.sql("SELECT * FROM resource WHERE id = :id")
                .param("id", id).query(this::mapResource).optional();
    }

    public List<ResourceInventoryItem> inventory() {
        return jdbc.sql("""
                SELECT r.*, v.id AS visit_id, v.ticket, v.version AS visit_version,
                       c.id AS customer_id, c.display_name, c.language, c.veteran,
                       (SELECT max(e.created_at) FROM visit_event e
                        WHERE e.visit_id = v.id AND e.to_status = 'AT_RESOURCE') AS assigned_at
                FROM resource r
                LEFT JOIN visit v ON v.resource_id = r.id AND v.status = 'AT_RESOURCE' AND r.status = 'OCCUPIED'
                LEFT JOIN customer c ON c.id = v.customer_id
                ORDER BY r.type, r.label, r.id
                """).query((rs, row) -> {
                    Long customerId = rs.getObject("customer_id", Long.class);
                    Customer customer = customerId == null ? null : new Customer(customerId,
                            rs.getString("display_name"), Customer.Language.valueOf(rs.getString("language")),
                            rs.getBoolean("veteran"));
                    Timestamp assignedAt = rs.getTimestamp("assigned_at");
                    return new ResourceInventoryItem(mapResource(rs, row),
                            rs.getObject("visit_id", Long.class), rs.getString("ticket"), customer,
                            rs.getObject("visit_version", Long.class),
                            assignedAt == null ? null : assignedAt.toInstant());
                }).list();
    }

    public void release(long id) {
        jdbc.sql("UPDATE resource SET status = 'FREE' WHERE id = :id")
                .param("id", id).update();
    }

    public Optional<Resource> assignLowestFree(Resource.Type type) {
        // Lock this small inventory in label order. Competing assignments wait,
        // then inspect the committed status instead of skipping a lower label.
        var resources = jdbc.sql("""
                SELECT * FROM resource WHERE type = :type
                ORDER BY label, id FOR UPDATE
                """)
                .param("type", type.name()).query(this::mapResource).list();
        return resources.stream().filter(resource -> resource.status() == Resource.Status.FREE)
                .findFirst().map(resource -> {
                    jdbc.sql("UPDATE resource SET status = 'OCCUPIED' WHERE id = :id")
                            .param("id", resource.id()).update();
                    return new Resource(resource.id(), resource.type(), resource.label(), Resource.Status.OCCUPIED);
                });
    }

    private Resource mapResource(ResultSet rs, int row) throws SQLException {
        return new Resource(rs.getLong("id"), Resource.Type.valueOf(rs.getString("type")),
                rs.getString("label"), Resource.Status.valueOf(rs.getString("status")));
    }
}
