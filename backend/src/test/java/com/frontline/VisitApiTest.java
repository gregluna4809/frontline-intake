package com.frontline;

import com.frontline.model.Visit;
import com.frontline.model.Resource;
import com.frontline.visit.VisitRepository;
import com.frontline.visit.VisitService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class VisitApiTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired VisitService service;
    @MockitoSpyBean VisitRepository repository;

    @BeforeEach
    void clearVisits() {
        jdbc.execute("TRUNCATE visit_event, visit RESTART IDENTITY");
        jdbc.execute("ALTER SEQUENCE visit_ticket_sequence RESTART WITH 1");
        jdbc.execute("UPDATE resource SET status = 'FREE'");
    }

    @Test
    void migrationSeedsExactlyTheSpecifiedSyntheticInventory() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer", Integer.class)).isEqualTo(50);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM customer WHERE display_name ~ '^Demo Customer [0-9]{3}$'",
                Integer.class)).isEqualTo(50);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM resource WHERE type = 'PHONE'", Integer.class))
                .isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM resource WHERE type = 'COMPUTER'", Integer.class))
                .isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM resource WHERE type = 'STATION'", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM resource WHERE status = 'FREE'", Integer.class))
                .isEqualTo(16);
    }

    @Test
    void checkInIsListedAndHasAnEvent() throws Exception {
        mvc.perform(post("/api/visits").contentType("application/json")
                        .content("{\"reason\":\"PHONE_ACCESS\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/visits/1"))
                .andExpect(jsonPath("$.ticket").value("B0001"))
                .andExpect(jsonPath("$.visitType").value("WALK_IN"))
                .andExpect(jsonPath("$.status").value("CHECKED_IN"))
                .andExpect(jsonPath("$.customerId").isEmpty())
                .andExpect(jsonPath("$.resourceId").isEmpty())
                .andExpect(jsonPath("$.closedAt").isEmpty())
                .andExpect(jsonPath("$.version").value(0));
        mvc.perform(get("/api/visits").param("status", "CHECKED_IN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].ticket").value("B0001"));
        mvc.perform(get("/api/visits").param("status", "AT_RESOURCE"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get("/api/visits/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visit.reason").value("PHONE_ACCESS"))
                .andExpect(jsonPath("$.events.length()").value(1))
                .andExpect(jsonPath("$.events[0].fromStatus").isEmpty())
                .andExpect(jsonPath("$.events[0].toStatus").value("CHECKED_IN"))
                .andExpect(jsonPath("$.events[0].actor").value("KIOSK"));
    }

    @Test
    void invalidInputDoesNotCreateVisits() throws Exception {
        for (String body : new String[]{
                "{}", "{\"reason\":null}", "{\"reason\":\"INVALID\"}",
                "{\"reason\":0}", "{\"reason\":\"PHONE_ACCESS\",\"status\":\"CLOSED\"}",
                "{\"reason\":\"PHONE_ACCESS\",\"displayName\":\"unexpected\"}"
        }) {
            mvc.perform(post("/api/visits").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/visits").param("status", "INVALID"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/visits/999")).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM visit", Integer.class)).isZero();
    }

    @Test
    void eventFailureRollsBackVisitCreation() {
        doThrow(new IllegalStateException("Simulated event failure")).when(repository).recordCheckIn(any());
        assertThatThrownBy(() -> service.checkIn(Visit.Reason.PHONE_ACCESS))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM visit", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM visit_event", Integer.class)).isZero();
    }

    @Test
    void concurrentCheckInsReceiveUniqueTickets() throws Exception {
        var tasks = new ArrayList<Callable<String>>();
        for (int i = 0; i < 20; i++) {
            tasks.add(() -> service.checkIn(Visit.Reason.COMPUTER_ACCESS).ticket());
        }
        try (var executor = Executors.newFixedThreadPool(5)) {
            var tickets = new HashSet<String>();
            for (var result : executor.invokeAll(tasks)) tickets.add(result.get());
            assertThat(tickets).hasSize(20).contains("B0001", "B0020");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM visit_event", Integer.class)).isEqualTo(20);
    }

    @Test
    void ticketSequenceExpandsBeyondFourDigits() {
        jdbc.execute("ALTER SEQUENCE visit_ticket_sequence RESTART WITH 9999");
        assertThat(service.checkIn(Visit.Reason.OTHER).ticket()).isEqualTo("B9999");
        assertThat(service.checkIn(Visit.Reason.OTHER).ticket()).isEqualTo("B10000");
    }

    @Test
    void customerSearchIsCaseInsensitiveLiteralAndLimited() throws Exception {
        mvc.perform(get("/api/customers").param("q", "dEmO cUsToMeR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(20))
                .andExpect(jsonPath("$[0].displayName").value("Demo Customer 001"));
        mvc.perform(get("/api/customers").param("q", "  050  "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(50));
        mvc.perform(get("/api/customers").param("q", "%"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get("/api/customers").param("q", "' OR 1=1 --"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    @Test
    void identifyAttachesCustomerAndRecordsTransition() throws Exception {
        Visit visit = service.checkIn(Visit.Reason.PHONE_ACCESS);
        mvc.perform(post("/api/visits/{id}/identify", visit.id())
                        .header("If-Match", "\"0\"").contentType("application/json").content("{\"customerId\":1}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.visit.status").value("IDENTIFIED"))
                .andExpect(jsonPath("$.visit.customerId").value(1))
                .andExpect(jsonPath("$.customer.displayName").value("Demo Customer 001"))
                .andExpect(jsonPath("$.events[1].fromStatus").value("CHECKED_IN"))
                .andExpect(jsonPath("$.events[1].toStatus").value("IDENTIFIED"))
                .andExpect(jsonPath("$.events[1].actor").value("FRONT_DESK"));
        mvc.perform(get("/api/visits").param("status", "CHECKED_IN"))
                .andExpect(content().json("[]"));
    }

    @Test
    void identifyFromWrongStatusAndUnknownCustomerAreRejected() throws Exception {
        Visit visit = service.checkIn(Visit.Reason.PHONE_ACCESS);
        mvc.perform(post("/api/visits/{id}/identify", visit.id())
                        .header("If-Match", "\"0\"").contentType("application/json").content("{\"customerId\":9999}"))
                .andExpect(status().isNotFound());
        assertThat(service.detail(visit.id()).visit().version()).isZero();
        service.identify(visit.id(), 1, 0);
        mvc.perform(post("/api/visits/{id}/identify", visit.id())
                        .header("If-Match", "\"1\"").contentType("application/json").content("{\"customerId\":2}"))
                .andExpect(status().isConflict());
        assertThat(service.detail(visit.id()).visit().customerId()).isEqualTo(1);
        assertThat(service.detail(visit.id()).events()).hasSize(2);
    }

    @ParameterizedTest
    @CsvSource({
            "PHONE_ACCESS,PHONE,Phone 01",
            "COMPUTER_ACCESS,COMPUTER,Computer 01",
            "JOB_SEARCH,COMPUTER,Computer 01",
            "DOCUMENT_SERVICES,STATION,Station 01",
            "BENEFITS_ASSISTANCE,STATION,Station 01",
            "OTHER,STATION,Station 01"
    })
    void routeMapsEveryReasonAndChoosesLowestLabel(Visit.Reason reason, Resource.Type type, String label)
            throws Exception {
        Visit visit = identified(reason);
        mvc.perform(post("/api/visits/{id}/route", visit.id()).header("If-Match", "\"1\""))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.visit.status").value("AT_RESOURCE"))
                .andExpect(jsonPath("$.visit.reason").value(reason.name()))
                .andExpect(jsonPath("$.resource.type").value(type.name()))
                .andExpect(jsonPath("$.resource.label").value(label))
                .andExpect(jsonPath("$.resource.status").value("OCCUPIED"))
                .andExpect(jsonPath("$.events[2].fromStatus").value("IDENTIFIED"))
                .andExpect(jsonPath("$.events[2].toStatus").value("AT_RESOURCE"));
        assertThat(jdbc.queryForObject(
                "SELECT status FROM resource WHERE label = ?", String.class, label)).isEqualTo("OCCUPIED");
    }

    @Test
    void occupiedLowerLabelsAreSkippedAndExhaustionQueuesVisit() throws Exception {
        jdbc.update("UPDATE resource SET status = 'OCCUPIED' WHERE label = 'Phone 01'");
        Visit first = identified(Visit.Reason.PHONE_ACCESS);
        assertThat(service.route(first.id(), 1).resource().label()).isEqualTo("Phone 02");
        jdbc.update("UPDATE resource SET status = 'OCCUPIED' WHERE type = 'PHONE'");
        Visit waiting = identified(Visit.Reason.PHONE_ACCESS);
        mvc.perform(post("/api/visits/{id}/route", waiting.id()).header("If-Match", "\"1\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visit.status").value("WAITING_RESOURCE"))
                .andExpect(jsonPath("$.visit.resourceId").isEmpty())
                .andExpect(jsonPath("$.requiredResourceType").value("PHONE"))
                .andExpect(jsonPath("$.events[2].toStatus").value("WAITING_RESOURCE"));
    }

    @Test
    void appointmentRoutesToWaitingStaffWithoutOccupyingResource() throws Exception {
        Visit visit = service.checkIn(Visit.Reason.OTHER);
        jdbc.update("""
                UPDATE visit SET visit_type = 'APPOINTMENT', reason = NULL,
                    appointment_category = 'Reemployment Appointment' WHERE id = ?
                """, visit.id());
        service.identify(visit.id(), 1, 0);
        mvc.perform(post("/api/visits/{id}/route", visit.id()).header("If-Match", "\"1\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visit.status").value("WAITING_STAFF"))
                .andExpect(jsonPath("$.visit.appointmentCategory").value("Reemployment Appointment"))
                .andExpect(jsonPath("$.resource").isEmpty())
                .andExpect(jsonPath("$.events[2].toStatus").value("WAITING_STAFF"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM resource WHERE status = 'OCCUPIED'", Integer.class))
                .isZero();
    }

    @Test
    void staleMissingAndMalformedVersionsAreRejected() throws Exception {
        Visit visit = identified(Visit.Reason.PHONE_ACCESS);
        mvc.perform(post("/api/visits/{id}/route", visit.id()).header("If-Match", "\"0\""))
                .andExpect(status().isPreconditionFailed());
        mvc.perform(post("/api/visits/{id}/route", visit.id()))
                .andExpect(status().is(428));
        mvc.perform(post("/api/visits/{id}/route", visit.id()).header("If-Match", "*"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/visits/{id}/identify", visit.id())
                        .header("If-Match", "\"0\"").contentType("application/json").content("{\"customerId\":2}"))
                .andExpect(status().isPreconditionFailed());
        assertThat(service.detail(visit.id()).visit().version()).isEqualTo(1);
        assertThat(service.detail(visit.id()).events()).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM resource WHERE status = 'OCCUPIED'", Integer.class))
                .isZero();
    }

    @Test
    void routingRequiresIdentifiedAndCannotRepeat() throws Exception {
        Visit visit = service.checkIn(Visit.Reason.PHONE_ACCESS);
        mvc.perform(post("/api/visits/{id}/route", visit.id()).header("If-Match", "\"0\""))
                .andExpect(status().isConflict());
        service.identify(visit.id(), 1, 0);
        service.route(visit.id(), 1);
        mvc.perform(post("/api/visits/{id}/route", visit.id()).header("If-Match", "\"2\""))
                .andExpect(status().isConflict());
        assertThat(service.detail(visit.id()).events()).hasSize(3);
    }

    @Test
    void failedRoutingEventRollsBackResourceVisitAndVersion() {
        Visit visit = identified(Visit.Reason.PHONE_ACCESS);
        doThrow(new IllegalStateException("Simulated transition event failure"))
                .when(repository).recordTransition(any(), any());
        assertThatThrownBy(() -> service.route(visit.id(), 1)).isInstanceOf(IllegalStateException.class);
        assertRoutingRolledBack(visit.id());
    }

    @Test
    void failedOptimisticUpdateRollsBackReservedResource() {
        Visit visit = identified(Visit.Reason.PHONE_ACCESS);
        doReturn(Optional.empty()).when(repository).transition(any(), any(), any(), any());
        assertThatThrownBy(() -> service.route(visit.id(), 1)).isInstanceOf(ResponseStatusException.class);
        assertRoutingRolledBack(visit.id());
    }

    @Test
    void concurrentRoutingOfSameVisitSucceedsExactlyOnce() throws Exception {
        Visit visit = identified(Visit.Reason.PHONE_ACCESS);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Integer> route = () -> {
            ready.countDown();
            start.await();
            try {
                service.route(visit.id(), 1);
                return 200;
            } catch (ResponseStatusException exception) {
                return exception.getStatusCode().value();
            }
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(route);
            var second = executor.submit(route);
            assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(200, 412);
        }
        assertThat(service.detail(visit.id()).events()).hasSize(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM resource WHERE status = 'OCCUPIED'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void concurrentVisitsCannotShareTheLastFreeResource() throws Exception {
        jdbc.update("UPDATE resource SET status = 'OCCUPIED' WHERE type = 'PHONE' AND label <> 'Phone 01'");
        Visit first = identified(Visit.Reason.PHONE_ACCESS);
        Visit second = identified(Visit.Reason.PHONE_ACCESS);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var results = executor.invokeAll(List.<Callable<Visit.Status>>of(
                    () -> service.route(first.id(), 1).visit().status(),
                    () -> service.route(second.id(), 1).visit().status()));
            assertThat(List.of(results.get(0).get(), results.get(1).get()))
                    .containsExactlyInAnyOrder(Visit.Status.AT_RESOURCE, Visit.Status.WAITING_RESOURCE);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM visit WHERE resource_id IS NOT NULL", Integer.class))
                .isEqualTo(1);
    }

    private Visit identified(Visit.Reason reason) {
        Visit visit = service.checkIn(reason);
        return service.identify(visit.id(), 1, 0).visit();
    }

    private void assertRoutingRolledBack(long id) {
        var detail = service.detail(id);
        assertThat(detail.visit().status()).isEqualTo(Visit.Status.IDENTIFIED);
        assertThat(detail.visit().version()).isEqualTo(1);
        assertThat(detail.visit().resourceId()).isNull();
        assertThat(detail.events()).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM resource WHERE status = 'OCCUPIED'", Integer.class))
                .isZero();
    }
}
