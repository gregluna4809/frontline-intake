package com.frontline;

import com.frontline.model.Resource;
import com.frontline.model.Visit;
import com.frontline.resource.ResourceRepository;
import com.frontline.visit.VisitService;
import com.frontline.business.BusinessDayService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;

@SpringBootTest(properties = "business.scheduling.enabled=false")
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(AuthenticatedApiTestConfiguration.class)
@Testcontainers
class RoomResourceApiTest {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");
    @MockitoBean(name = "businessClock") Clock clock;
    @Autowired ResourceRepository resources;
    @Autowired VisitService visits;
    @Autowired BusinessDayService days;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    @BeforeEach
    void reset() {
        jdbc.execute("UPDATE resource SET status = 'FREE', active_visit_id = NULL");
        jdbc.execute("DELETE FROM help_request");
        jdbc.execute("DELETE FROM resource_session");
        jdbc.execute("DELETE FROM visit_event");
        jdbc.execute("DELETE FROM visit");
        jdbc.execute("TRUNCATE business_day_snapshot, business_day_rollover");
        at("2026-11-25");
    }

    private void at(String date) {
        when(clock.instant()).thenReturn(Instant.parse(date + "T17:00:00Z"));
    }

    private Visit routePhone() {
        Visit visit = visits.checkIn(Visit.VisitType.WALK_IN, Visit.Reason.PHONE_ACCESS, null);
        visits.identify(visit.id(), 1, 0);
        return visits.route(visit.id(), 1).visit();
    }

    private void assertCounts(String date, long phones) {
        var inventory = resources.inventory(LocalDate.parse(date));
        assertThat(inventory.stream().filter(item -> item.resource().type() == Resource.Type.PHONE).count()).isEqualTo(phones);
        assertThat(inventory.stream().filter(item -> item.resource().type() == Resource.Type.COMPUTER).count()).isEqualTo(31);
        assertThat(inventory.stream().filter(item -> item.resource().type() == Resource.Type.STATION).count()).isEqualTo(2);
    }

    @Test
    void activeCountsBeforeAndAfterRoomTransition() {
        assertCounts("2026-11-25", 18);
        assertCounts("2026-11-30", 7);
    }

    @Test
    void orientationPhonesIncludeLastActiveDateWithoutCalendarFiltering() {
        assertThat(resources.inventory(LocalDate.parse("2026-11-29")))
                .filteredOn(item -> item.roomCode().equals("ORIENTATION_ROOM")).hasSize(11);
        assertThat(resources.inventory(LocalDate.parse("2026-11-30")))
                .noneMatch(item -> item.roomCode().equals("ORIENTATION_ROOM"));
    }

    @Test
    void routingAfterTransitionWaitsWhenAllResourceRoomPhonesAreOccupied() {
        at("2026-11-30");
        days.resetResourcesForOpening();
        jdbc.update("UPDATE resource SET status = 'OCCUPIED' WHERE type = 'PHONE' AND room_id = (SELECT id FROM room WHERE code = 'RESOURCE_ROOM')");
        Visit visit = visits.checkIn(Visit.VisitType.WALK_IN, Visit.Reason.UI_ACCOUNT, null);
        visits.identify(visit.id(), 1, 0);
        visit = visits.phoneOverride(visit.id(), 1, "Synthetic phone access").visit();
        assertThat(visit.status()).isEqualTo(Visit.Status.WAITING_RESOURCE);
        assertThat(visit.resourceId()).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM resource WHERE label LIKE 'OR-%' AND status = 'FREE'", Integer.class)).isEqualTo(11);
    }

    @Test
    void routingPrefersResourceRoomThenLowestOrientationPhone() {
        Visit first = routePhone();
        assertThat(resources.findById(first.resourceId()).orElseThrow().label()).isEqualTo("Phone 01");
        jdbc.update("UPDATE resource SET status = 'OCCUPIED' WHERE type = 'PHONE' AND room_id = (SELECT id FROM room WHERE code = 'RESOURCE_ROOM')");
        Visit next = routePhone();
        assertThat(resources.findById(next.resourceId()).orElseThrow().label()).isEqualTo("OR-Phone 01");
    }

    @Test
    void inventoryEndpointIncludesRoomMetadataAndExcludesInactiveRooms() throws Exception {
        mvc.perform(get("/api/resources").param("businessDate", "2026-11-30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(40))
                .andExpect(jsonPath("$[*].roomCode", everyItem(is("RESOURCE_ROOM"))))
                .andExpect(jsonPath("$[*].roomName", everyItem(is("Resource Room"))));
    }

    @Test
    void snapshotsIncludeRoomMetadataAndKeepExistingPayloadsFrozen() {
        at("2026-11-30");
        var snapshot = days.snapshot(LocalDate.parse("2026-11-25"));
        assertThat(snapshot.get("resources")).hasSize(51);
        assertThat(snapshot.get("resources").get(0).get("roomCode").asText()).isEqualTo("RESOURCE_ROOM");
        jdbc.update("UPDATE room SET name = 'Synthetic Room' WHERE code = 'RESOURCE_ROOM'");
        try {
            assertThat(days.snapshot(LocalDate.parse("2026-11-25"))).isEqualTo(snapshot);
        } finally {
            jdbc.update("UPDATE room SET name = 'Resource Room' WHERE code = 'RESOURCE_ROOM'");
        }
    }
}
