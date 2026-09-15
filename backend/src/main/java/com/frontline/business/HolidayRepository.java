package com.frontline.business;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class HolidayRepository {
    private final JdbcClient jdbc;

    public HolidayRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public Set<LocalDate> dates() {
        return new HashSet<>(jdbc.sql("SELECT date FROM holiday").query(LocalDate.class).list());
    }
}
