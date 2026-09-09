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
        jdbc.execute("ALTER SEQUENCE appointment_ticket_sequence RESTART WITH 1");
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
                        .content("{\"visitType\":\"WALK_IN\",\"reason\":\"PHONE_ACCESS\"}"))
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
                "{}", "{\"visitType\":\"WALK_IN\",\"reason\":null}", "{\"visitType\":\"WALK_IN\",\"reason\":\"INVALID\"}",
                "{\"visitType\":\"WALK_IN\",\"reason\":0}", "{\"visitType\":\"WALK_IN\",\"reason\":\"PHONE_ACCESS\",\"status\":\"CLOSED\"}",
                "{\"visitType\":\"WALK_IN\",\"reason\":\"PHONE_ACCESS\",\"displayName\":\"unexpected\"}"
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
        assertThatThrownBy(() -> service.checkIn(Visit.VisitType.WALK_IN, Visit.Reason.PHONE_ACCESS, null))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM visit", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM visit_event", Integer.class)).isZero();
    }

    @Test
    void concurrentCheckInsReceiveUniqueTickets() throws Exception {
        var tasks = new ArrayList<Callable<String>>();
        for (int i = 0; i < 20; i++) {
            tasks.add(() -> service.checkIn(Visit.VisitType.WALK_IN, Visit.Reason.COMPUTER_ACCESS, null).ticket());
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
        assertThat(service.checkIn(Visit.VisitType.WALK_IN, Visit.Reason.OTHER, null).ticket()).isEqualTo("B9999");
        assertThat(service.checkIn(Visit.VisitType.WALK_IN, Visit.Reason.OTHER, null).ticket()).isEqualTo("B10000");
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
        Visit visit = service.checkIn(Visit.VisitType.WALK_IN, Visit.Reason.PHONE_ACCESS, null);
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
        Visit visit = service.checkIn(Visit.VisitType.WALK_IN, Visit.Reason.PHONE_ACCESS, null);
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
        Visit visit = service.checkIn(Visit.VisitType.WALK_IN, Visit.Reason.OTHER, null);
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
        Visit visit = service.checkIn(Visit.VisitType.WALK_IN, Visit.Reason.PHONE_ACCESS, null);
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


    @Test
    void closeFreesResourceAndInventoryShowsOnlyCurrentOccupant() throws Exception {
        Visit visit = atResource(Visit.Reason.PHONE_ACCESS);
        mvc.perform(get("/api/resources"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(16))
                .andExpect(jsonPath("$[?(@.label == 'Phone 01')].ticket")
                        .value(org.hamcrest.Matchers.contains(visit.ticket())))
                .andExpect(jsonPath("$[?(@.label == 'Phone 01')].customer.displayName")
                        .value(org.hamcrest.Matchers.contains("Demo Customer 001")));
        mvc.perform(post("/api/visits/{id}/close", visit.id()).header("If-Match", "\"2\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visit.status").value("CLOSED"))
                .andExpect(jsonPath("$.visit.closedAt").isNotEmpty())
                .andExpect(jsonPath("$.visit.resourceId").value(visit.resourceId()))
                .andExpect(jsonPath("$.events[3].actor").value("RESOURCE_ROOM"));
        assertThat(jdbc.queryForObject("SELECT status FROM resource WHERE id = ?", String.class, visit.resourceId()))
                .isEqualTo("FREE");
        mvc.perform(get("/api/resources"))
                .andExpect(jsonPath("$[?(@.label == 'Phone 01')].ticket")
                        .value(org.hamcrest.Matchers.contains(org.hamcrest.Matchers.nullValue())));
        Visit next = atResource(Visit.Reason.PHONE_ACCESS);
        assertThat(next.resourceId()).isEqualTo(visit.resourceId());
        mvc.perform(get("/api/resources"))
                .andExpect(jsonPath("$[?(@.label == 'Phone 01')].ticket")
                        .value(org.hamcrest.Matchers.contains(next.ticket())));
    }

    @Test
    void closeFromWrongStatusAndStaleCloseDoNotReleaseAnything() throws Exception {
        Visit checkedIn = service.checkIn(Visit.VisitType.WALK_IN, Visit.Reason.PHONE_ACCESS, null);
        mvc.perform(post("/api/visits/{id}/close", checkedIn.id()).header("If-Match", "\"0\""))
                .andExpect(status().isConflict());
        Visit occupied = atResource(Visit.Reason.PHONE_ACCESS);
        mvc.perform(post("/api/visits/{id}/close", occupied.id()).header("If-Match", "\"1\""))
                .andExpect(status().isPreconditionFailed());
        assertThat(jdbc.queryForObject("SELECT status FROM resource WHERE id = ?", String.class, occupied.resourceId()))
                .isEqualTo("OCCUPIED");
        assertThat(service.detail(occupied.id()).events()).hasSize(3);
    }

    @ParameterizedTest
    @CsvSource({"CHECKED_IN", "IDENTIFIED", "AT_RESOURCE", "WAITING_RESOURCE", "WAITING_STAFF"})
    void leftAcceptsEveryActiveStatusAndFreesResourceWhenPresent(Visit.Status from) throws Exception {
        Visit visit;
        if (from == Visit.Status.WAITING_STAFF) {
            visit = service.checkIn(Visit.VisitType.APPOINTMENT, null, Visit.AppointmentCategory.REEMPLOYMENT);
            service.identify(visit.id(), 1, 0);
            visit = service.route(visit.id(), 1).visit();
        } else {
            visit = service.checkIn(Visit.VisitType.WALK_IN, Visit.Reason.PHONE_ACCESS, null);
            if (from != Visit.Status.CHECKED_IN) visit = service.identify(visit.id(), 1, 0).visit();
            if (from == Visit.Status.WAITING_RESOURCE) {
                jdbc.update("UPDATE resource SET status = 'OCCUPIED' WHERE type = 'PHONE'");
            }
            if (from == Visit.Status.AT_RESOURCE || from == Visit.Status.WAITING_RESOURCE) {
                visit = service.route(visit.id(), 1).visit();
            }
        }
        mvc.perform(post("/api/visits/{id}/left", visit.id()).header("If-Match", "\"" + visit.version() + "\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visit.status").value("LEFT"))
                .andExpect(jsonPath("$.visit.closedAt").isNotEmpty())
                .andExpect(jsonPath("$.visit.version").value(visit.version() + 1));
        var events = service.detail(visit.id()).events();
        assertThat(events.getLast().fromStatus()).isEqualTo(from);
        assertThat(events.getLast().toStatus()).isEqualTo(Visit.Status.LEFT);
        if (visit.resourceId() != null) {
            assertThat(jdbc.queryForObject("SELECT status FROM resource WHERE id = ?", String.class, visit.resourceId()))
                    .isEqualTo("FREE");
        }
    }

    @ParameterizedTest
    @CsvSource({"CLOSED", "LEFT"})
    void leftRejectsCompletedVisitAndCannotFreeAReusedResource(Visit.Status terminal) throws Exception {
        Visit old = atResource(Visit.Reason.PHONE_ACCESS);
        if (terminal == Visit.Status.CLOSED) service.close(old.id(), 2);
        else service.left(old.id(), 2);
        Visit current = atResource(Visit.Reason.PHONE_ACCESS);
        mvc.perform(post("/api/visits/{id}/left", old.id()).header("If-Match", "\"3\""))
                .andExpect(status().isConflict());
        assertThat(current.resourceId()).isEqualTo(old.resourceId());
        assertThat(jdbc.queryForObject("SELECT status FROM resource WHERE id = ?", String.class, current.resourceId()))
                .isEqualTo("OCCUPIED");
    }

    @Test
    void assignUsesFreedLowestLabelAndPreservesReasonAndCustomer() throws Exception {
        List<Visit> occupied = new ArrayList<>();
        for (int i = 0; i < 6; i++) occupied.add(atResource(Visit.Reason.PHONE_ACCESS));
        Visit waiting = atResource(Visit.Reason.PHONE_ACCESS);
        assertThat(waiting.status()).isEqualTo(Visit.Status.WAITING_RESOURCE);
        mvc.perform(get("/api/visits").param("status", "WAITING_RESOURCE"))
                .andExpect(jsonPath("$[0].requiredResourceType").value("PHONE"))
                .andExpect(jsonPath("$[0].customer.displayName").value("Demo Customer 001"))
                .andExpect(jsonPath("$[0].waitingSince").isNotEmpty());
        service.close(occupied.get(1).id(), 2);
        service.close(occupied.get(0).id(), 2);
        mvc.perform(post("/api/visits/{id}/assign", waiting.id()).header("If-Match", "\"2\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visit.status").value("AT_RESOURCE"))
                .andExpect(jsonPath("$.visit.version").value(3))
                .andExpect(jsonPath("$.visit.reason").value("PHONE_ACCESS"))
                .andExpect(jsonPath("$.customer.displayName").value("Demo Customer 001"))
                .andExpect(jsonPath("$.resource.label").value("Phone 01"))
                .andExpect(jsonPath("$.resource.status").value("OCCUPIED"))
                .andExpect(jsonPath("$.events[3].fromStatus").value("WAITING_RESOURCE"))
                .andExpect(jsonPath("$.events[3].actor").value("RESOURCE_ROOM"));
        mvc.perform(get("/api/visits").param("status", "WAITING_RESOURCE")).andExpect(content().json("[]"));
    }

    @Test
    void assignWithNoFreeResourceReturnsConflictWithoutEventOrVersionChange() throws Exception {
        jdbc.update("UPDATE resource SET status = 'OCCUPIED' WHERE type = 'STATION'");
        Visit waiting = atResource(Visit.Reason.DOCUMENT_SERVICES);
        mvc.perform(post("/api/visits/{id}/assign", waiting.id()).header("If-Match", "\"2\""))
                .andExpect(status().isConflict());
        var detail = service.detail(waiting.id());
        assertThat(detail.visit().status()).isEqualTo(Visit.Status.WAITING_RESOURCE);
        assertThat(detail.visit().version()).isEqualTo(2);
        assertThat(detail.events()).hasSize(3);
    }

    @Test
    void assignRejectsWrongStatusAndStaleVersion() throws Exception {
        Visit checkedIn = service.checkIn(Visit.VisitType.WALK_IN, Visit.Reason.PHONE_ACCESS, null);
        mvc.perform(post("/api/visits/{id}/assign", checkedIn.id()).header("If-Match", "\"0\""))
                .andExpect(status().isConflict());
        jdbc.update("UPDATE resource SET status = 'OCCUPIED' WHERE type = 'PHONE'");
        Visit waiting = atResource(Visit.Reason.PHONE_ACCESS);
        jdbc.update("UPDATE resource SET status = 'FREE' WHERE label = 'Phone 01'");
        mvc.perform(post("/api/visits/{id}/assign", waiting.id()).header("If-Match", "\"1\""))
                .andExpect(status().isPreconditionFailed());
        assertThat(jdbc.queryForObject("SELECT status FROM resource WHERE label = 'Phone 01'", String.class))
                .isEqualTo("FREE");
    }

    @ParameterizedTest
    @CsvSource({"close", "left"})
    void failedTerminalEventRollsBackReleaseAndClosedAt(String action) {
        Visit visit = atResource(Visit.Reason.COMPUTER_ACCESS);
        doThrow(new IllegalStateException("Simulated terminal event failure"))
                .when(repository).recordTransition(any(), any());
        assertThatThrownBy(() -> {
            if (action.equals("close")) service.close(visit.id(), 2);
            else service.left(visit.id(), 2);
        }).isInstanceOf(IllegalStateException.class);
        var detail = service.detail(visit.id());
        assertThat(detail.visit().status()).isEqualTo(Visit.Status.AT_RESOURCE);
        assertThat(detail.visit().version()).isEqualTo(2);
        assertThat(detail.visit().closedAt()).isNull();
        assertThat(detail.events()).hasSize(3);
        assertThat(detail.resource().status()).isEqualTo(Resource.Status.OCCUPIED);
    }

    @Test
    void failedAssignEventRollsBackResourceReservation() {
        jdbc.update("UPDATE resource SET status = 'OCCUPIED' WHERE type = 'PHONE'");
        Visit visit = atResource(Visit.Reason.PHONE_ACCESS);
        jdbc.update("UPDATE resource SET status = 'FREE' WHERE label = 'Phone 01'");
        doThrow(new IllegalStateException("Simulated assign event failure"))
                .when(repository).recordTransition(any(), any());
        assertThatThrownBy(() -> service.assign(visit.id(), 2)).isInstanceOf(IllegalStateException.class);
        assertThat(service.detail(visit.id()).visit().status()).isEqualTo(Visit.Status.WAITING_RESOURCE);
        assertThat(service.detail(visit.id()).events()).hasSize(3);
        assertThat(jdbc.queryForObject("SELECT status FROM resource WHERE label = 'Phone 01'", String.class))
                .isEqualTo("FREE");
    }

    @Test
    void concurrentCloseAndLeftFinishExactlyOnce() throws Exception {
        Visit visit = atResource(Visit.Reason.PHONE_ACCESS);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var results = executor.invokeAll(List.<Callable<Integer>>of(
                    () -> terminalResult(visit.id(), true), () -> terminalResult(visit.id(), false)));
            assertThat(List.of(results.get(0).get(), results.get(1).get()))
                    .containsExactlyInAnyOrder(200, 412);
        }
        assertThat(service.detail(visit.id()).events()).hasSize(4);
        assertThat(jdbc.queryForObject("SELECT status FROM resource WHERE id = ?", String.class, visit.resourceId()))
                .isEqualTo("FREE");
    }

    @Test
    void competingWaitingVisitsCannotShareLastFreeResource() throws Exception {
        jdbc.update("UPDATE resource SET status = 'OCCUPIED' WHERE type = 'PHONE'");
        Visit first = atResource(Visit.Reason.PHONE_ACCESS);
        Visit second = atResource(Visit.Reason.PHONE_ACCESS);
        jdbc.update("UPDATE resource SET status = 'FREE' WHERE label = 'Phone 01'");
        try (var executor = Executors.newFixedThreadPool(2)) {
            var results = executor.invokeAll(List.<Callable<Integer>>of(
                    () -> assignResult(first.id()), () -> assignResult(second.id())));
            assertThat(List.of(results.get(0).get(), results.get(1).get()))
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM visit WHERE status = 'AT_RESOURCE'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM visit WHERE status = 'WAITING_RESOURCE'", Integer.class))
                .isEqualTo(1);
    }

    private int terminalResult(long id, boolean close) {
        try {
            if (close) service.close(id, 2);
            else service.left(id, 2);
            return 200;
        } catch (ResponseStatusException exception) {
            return exception.getStatusCode().value();
        }
    }

    private int assignResult(long id) {
        try {
            service.assign(id, 2);
            return 200;
        } catch (ResponseStatusException exception) {
            return exception.getStatusCode().value();
        }
    }

    private Visit atResource(Visit.Reason reason) {
        Visit identified = identified(reason);
        return service.route(identified.id(), 1).visit();
    }

    private Visit identified(Visit.Reason reason) {
        Visit visit = service.checkIn(Visit.VisitType.WALK_IN, reason, null);
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

    @ParameterizedTest
    @CsvSource({"REEMPLOYMENT", "CAREER_SERVICES", "VETERANS"})
    void kioskCreatesAppointmentWithIndependentTicketSequence(String category) throws Exception {
        service.checkIn(Visit.VisitType.WALK_IN, Visit.Reason.PHONE_ACCESS, null);
        service.checkIn(Visit.VisitType.WALK_IN, Visit.Reason.PHONE_ACCESS, null);
        mvc.perform(post("/api/visits").contentType("application/json")
                        .content("""
                                {"visitType":"APPOINTMENT","appointmentCategory":"%s"}
                                """.formatted(category)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ticket").value("A0001"))
                .andExpect(jsonPath("$.visitType").value("APPOINTMENT"))
                .andExpect(jsonPath("$.appointmentCategory").value(category))
                .andExpect(jsonPath("$.reason").isEmpty())
                .andExpect(jsonPath("$.status").value("CHECKED_IN"));
        Visit second = service.checkIn(Visit.VisitType.APPOINTMENT, null, Visit.AppointmentCategory.REEMPLOYMENT);
        assertThat(second.ticket()).isEqualTo("A0002");
        assertThat(service.checkIn(Visit.VisitType.WALK_IN, Visit.Reason.OTHER, null).ticket()).isEqualTo("B0003");
        var event = service.detail(second.id()).events().getFirst();
        assertThat(event.fromStatus()).isNull();
        assertThat(event.toStatus()).isEqualTo(Visit.Status.CHECKED_IN);
        assertThat(event.actor()).isEqualTo("KIOSK");
    }

    @Test
    void rejectsMixedMissingAndInvalidIntakeFieldsWithoutWriting() throws Exception {
        for (String body : new String[] {
                """
                {"visitType":"WALK_IN","reason":"PHONE_ACCESS","appointmentCategory":"REEMPLOYMENT"}
                """,
                """
                {"visitType":"APPOINTMENT","reason":"PHONE_ACCESS","appointmentCategory":"REEMPLOYMENT"}
                """,
                """
                {"visitType":"WALK_IN","appointmentCategory":"REEMPLOYMENT"}
                """,
                """
                {"visitType":"APPOINTMENT","reason":"PHONE_ACCESS"}
                """,
                """
                {"visitType":"WALK_IN"}
                """,
                """
                {"visitType":"APPOINTMENT"}
                """,
                """
                {"reason":"PHONE_ACCESS"}
                """,
                """
                {"visitType":"APPOINTMENT","appointmentCategory":"INVALID"}
                """,
                """
                {"visitType":"APPOINTMENT","appointmentCategory":""}
                """
        }) {
            mvc.perform(post("/api/visits").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM visit", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM visit_event", Integer.class)).isZero();
    }

    @Test
    void appointmentsQueueIncludesCustomerAndTimeEnteredWaitingStaffThenCloses() throws Exception {
        Visit visit = service.checkIn(Visit.VisitType.APPOINTMENT, null, Visit.AppointmentCategory.CAREER_SERVICES);
        jdbc.update("UPDATE visit SET created_at = CURRENT_TIMESTAMP - interval '2 hours' WHERE id = ?", visit.id());
        service.identify(visit.id(), 1, 0);
        var routed = service.route(visit.id(), 1);
        var queue = service.list(Visit.Status.WAITING_STAFF);
        assertThat(queue).hasSize(1);
        assertThat(queue.getFirst().waitingSince()).isEqualTo(routed.events().getLast().createdAt());
        assertThat(queue.getFirst().waitingSince()).isAfter(routed.visit().createdAt());
        mvc.perform(get("/api/visits").param("status", "WAITING_STAFF"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ticket").value("A0001"))
                .andExpect(jsonPath("$[0].customer.displayName").value("Demo Customer 001"))
                .andExpect(jsonPath("$[0].appointmentCategory").value("CAREER_SERVICES"))
                .andExpect(jsonPath("$[0].waitingSince").isNotEmpty());
        mvc.perform(post("/api/visits/{id}/close", visit.id()).header("If-Match", "\"2\""))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"3\""))
                .andExpect(jsonPath("$.visit.status").value("CLOSED"))
                .andExpect(jsonPath("$.visit.closedAt").isNotEmpty())
                .andExpect(jsonPath("$.events[3].fromStatus").value("WAITING_STAFF"))
                .andExpect(jsonPath("$.events[3].toStatus").value("CLOSED"))
                .andExpect(jsonPath("$.events[3].actor").value("APPOINTMENTS"));
        mvc.perform(get("/api/visits").param("status", "WAITING_STAFF")).andExpect(content().json("[]"));
    }

    @Test
    void appointmentCloseRejectsWrongStatusAndStaleVersion() throws Exception {
        Visit visit = service.checkIn(Visit.VisitType.APPOINTMENT, null, Visit.AppointmentCategory.VETERANS);
        mvc.perform(post("/api/visits/{id}/close", visit.id()).header("If-Match", "\"0\""))
                .andExpect(status().isConflict());
        service.identify(visit.id(), 1, 0);
        service.route(visit.id(), 1);
        mvc.perform(post("/api/visits/{id}/close", visit.id()).header("If-Match", "\"1\""))
                .andExpect(status().isPreconditionFailed());
        assertThat(service.detail(visit.id()).visit().closedAt()).isNull();
        service.close(visit.id(), 2);
        mvc.perform(post("/api/visits/{id}/close", visit.id()).header("If-Match", "\"3\""))
                .andExpect(status().isConflict());
        assertThat(service.detail(visit.id()).events()).hasSize(4);
    }

    @Test
    void failedAppointmentCloseEventRollsBackClosedAtAndVersion() {
        Visit visit = service.checkIn(Visit.VisitType.APPOINTMENT, null, Visit.AppointmentCategory.REEMPLOYMENT);
        service.identify(visit.id(), 1, 0);
        service.route(visit.id(), 1);
        doThrow(new IllegalStateException("Simulated close event failure"))
                .when(repository).recordTransition(any(), any());
        assertThatThrownBy(() -> service.close(visit.id(), 2)).isInstanceOf(IllegalStateException.class);
        var detail = service.detail(visit.id());
        assertThat(detail.visit().status()).isEqualTo(Visit.Status.WAITING_STAFF);
        assertThat(detail.visit().closedAt()).isNull();
        assertThat(detail.visit().version()).isEqualTo(2);
        assertThat(detail.events()).hasSize(3);
    }
}
