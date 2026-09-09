package com.frontline.resource;

import com.frontline.model.Resource;
import java.sql.ResultSet;
import java.sql.SQLException;
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
