package com.frontline;

import com.frontline.model.Visit;
import com.frontline.model.Resource;
import com.frontline.business.BusinessCalendar;
import com.frontline.visit.VisitRepository;
import com.frontline.visit.VisitService;
import java.time.Clock;
import java.time.Instant;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "business.scheduling.enabled=false")
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(AuthenticatedApiTestConfiguration.class)
@Testcontainers
class VisitApiTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired VisitService service;
    @Autowired BusinessCalendar calendar;
    @Autowired com.frontline.resource.ResourceRepository resources;
    @MockitoSpyBean VisitRepository repository;
    @MockitoBean(name = "businessClock") Clock clock;

    @BeforeEach
    void clearVisits() {
        when(clock.instant()).thenReturn(Instant.parse("2026-11-25T17:00:00Z"));
        jdbc.execute("UPDATE resource SET active_visit_id = NULL, status = 'FREE'");
        jdbc.execute("DELETE FROM help_request");
        jdbc.execute("DELETE FROM resource_session");
        jdbc.execute("DELETE FROM visit_event");
        jdbc.execute("DELETE FROM visit");
        jdbc.execute("ALTER TABLE visit ALTER COLUMN id RESTART WITH 1");
        jdbc.execute("ALTER TABLE visit_event ALTER COLUMN id RESTART WITH 1");
        jdbc.execute("TRUNCATE call_out, business_day_snapshot, business_day_rollover RESTART IDENTITY");
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
                .isEqualTo(18);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM resource WHERE type = 'COMPUTER'", Integer.class))
                .isEqualTo(31);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM resource WHERE type = 'STATION'", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM resource WHERE status = 'FREE'", Integer.class))
                .isEqualTo(51);
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
        markBusinessDayOpened();
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
                    appointment_category = 'MANDATORY', appointment_type = 'INITIAL',
                    appointment_status = 'SCHEDULED' WHERE id = ?
                """, visit.id());
        service.identify(visit.id(), 1, 0);
        mvc.perform(post("/api/visits/{id}/route", visit.id()).header("If-Match", "\"1\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visit.status").value("WAITING_STAFF"))
                .andExpect(jsonPath("$.visit.appointmentCategory").value("MANDATORY"))
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
        markBusinessDayOpened();
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
                .andExpect(jsonPath("$.length()").value(51))
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
            visit = service.checkIn(Visit.VisitType.APPOINTMENT, null, Visit.AppointmentCategory.MANDATORY);
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
        long phoneCount = resources.inventory(calendar.currentBusinessDate()).stream()
                .filter(item -> item.resource().type() == Resource.Type.PHONE).count();
        for (int i = 0; i < phoneCount; i++) occupied.add(atResource(Visit.Reason.PHONE_ACCESS));
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
        markBusinessDayOpened();
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
        markBusinessDayOpened();
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
        markBusinessDayOpened();
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

    private void markBusinessDayOpened() {
        // Preserve manually occupied fixtures when check-in performs opening catch-up.
        jdbc.update("INSERT INTO business_day_rollover (business_date) VALUES (?) ON CONFLICT DO NOTHING",
                calendar.currentBusinessDate());
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
    @CsvSource({"MANDATORY", "JOB_SEARCH_ASSISTANCE"})
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
        Visit second = service.checkIn(Visit.VisitType.APPOINTMENT, null, Visit.AppointmentCategory.MANDATORY);
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
                {"visitType":"WALK_IN","reason":"PHONE_ACCESS","appointmentCategory":"MANDATORY"}
                """,
                """
                {"visitType":"APPOINTMENT","reason":"PHONE_ACCESS","appointmentCategory":"MANDATORY"}
                """,
                """
                {"visitType":"WALK_IN","appointmentCategory":"MANDATORY"}
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
        Visit visit = service.checkIn(Visit.VisitType.APPOINTMENT, null, Visit.AppointmentCategory.JOB_SEARCH_ASSISTANCE);
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
                .andExpect(jsonPath("$[0].appointmentCategory").value("JOB_SEARCH_ASSISTANCE"))
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
        Visit visit = service.checkIn(Visit.VisitType.APPOINTMENT, null, Visit.AppointmentCategory.MANDATORY);
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
        Visit visit = service.checkIn(Visit.VisitType.APPOINTMENT, null, Visit.AppointmentCategory.MANDATORY);
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
    @Test
    void staffMigrationSeedsRolesAndEndpointListsInOrder() throws Exception {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff", Integer.class)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff WHERE role = 'LSR'", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff WHERE role = 'SUPERVISING_LSR'", Integer.class)).isEqualTo(1);
        mvc.perform(get("/api/staff")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(10))
                .andExpect(jsonPath("$[5].displayName").value("Demo Staff 001"))
                .andExpect(jsonPath("$[8].displayName").value("Demo Staff 004"))
                .andExpect(jsonPath("$[9].role").value("SUPERVISING_LSR"))
                .andExpect(jsonPath("$[9].displayName").value("Demo Supervisor 001"));
    }

    @Test
    void appointmentCheckInStartsUnclassifiedWithServerStatus() throws Exception {
        mvc.perform(post("/api/visits").contentType("application/json")
                        .content("""
                                {"visitType":"APPOINTMENT","appointmentCategory":"MANDATORY"}
                                """))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.appointmentType").isEmpty())
                .andExpect(jsonPath("$.appointmentStatus").value("SCHEDULED"));
        mvc.perform(get("/api/visits")).andExpect(jsonPath("$[0].appointmentType").isEmpty());
    }

    @Test
    void clientClassificationAndStatusAreRejectedWithoutWriting() throws Exception {
        for (String value : new String[] {"null", "\"INITIAL\"", "\"FOLLOWUP\"", "\"INVALID\"", "0"}) {
            for (String intake : new String[] {
                    "\"visitType\":\"APPOINTMENT\",\"appointmentCategory\":\"MANDATORY\"",
                    "\"visitType\":\"WALK_IN\",\"reason\":\"OTHER\""}) {
                mvc.perform(post("/api/visits").contentType("application/json")
                        .content("{" + intake + ",\"appointmentType\":" + value + "}"))
                        .andExpect(status().isBadRequest());
            }
        }
        mvc.perform(post("/api/visits").contentType("application/json").content("""
                {"visitType":"APPOINTMENT","appointmentCategory":"MANDATORY","appointmentStatus":"SCHEDULED"}
                """)).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM visit", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM visit_event", Integer.class)).isZero();
    }

    @Test
    void databaseEnforcesAppointmentTypeNullabilityAndAllowedValues() {
        Visit walkIn = service.checkIn(Visit.VisitType.WALK_IN, Visit.Reason.OTHER, null);
        assertThatThrownBy(() -> jdbc.update("UPDATE visit SET appointment_type = 'INITIAL' WHERE id = ?", walkIn.id()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        Visit appointment = service.checkIn(Visit.VisitType.APPOINTMENT, null,
                Visit.AppointmentCategory.MANDATORY);
        assertThat(appointment.appointmentType()).isNull();
        assertThatThrownBy(() -> jdbc.update("UPDATE visit SET appointment_type = 'INVALID' WHERE id = ?", appointment.id()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE visit SET appointment_status = 'INVALID' WHERE id = ?", appointment.id()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void staffAssignmentAndReassignmentRecordEventsAndPreserveWaitingTime() throws Exception {
        Visit visit = waitingStaff();
        var waitingSince = service.list(Visit.Status.WAITING_STAFF).getFirst().waitingSince();
        mvc.perform(post("/api/visits/{id}/assign-staff", visit.id()).header("If-Match", "\"2\"")
                        .contentType("application/json").content("{\"staffId\":1}"))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"3\""))
                .andExpect(jsonPath("$.visit.status").value("WAITING_STAFF"))
                .andExpect(jsonPath("$.visit.assignedStaffId").value(1))
                .andExpect(jsonPath("$.events[3].fromStatus").value("WAITING_STAFF"))
                .andExpect(jsonPath("$.events[3].toStatus").value("WAITING_STAFF"))
                .andExpect(jsonPath("$.events[3].actor").value("APPOINTMENTS"))
                .andExpect(jsonPath("$.events[3].note").value("Assigned staff ID: 1."));
        mvc.perform(post("/api/visits/{id}/assign-staff", visit.id()).header("If-Match", "\"3\"")
                        .contentType("application/json").content("{\"staffId\":2}"))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"4\""))
                .andExpect(jsonPath("$.visit.assignedStaffId").value(2))
                .andExpect(jsonPath("$.events[4].note").value("Reassigned staff from ID: 1 to ID: 2."));
        mvc.perform(get("/api/visits").param("status", "WAITING_STAFF"))
                .andExpect(jsonPath("$[0].assignedStaffId").value(2))
                .andExpect(jsonPath("$[0].appointmentType").value("INITIAL"))
                .andExpect(jsonPath("$[0].appointmentStatus").value("SCHEDULED"));
        assertThat(service.list(Visit.Status.WAITING_STAFF).getFirst().waitingSince()).isEqualTo(waitingSince);
        service.close(visit.id(), 4);
        assertThat(service.detail(visit.id()).visit().assignedStaffId()).isEqualTo(2);
    }

    @ParameterizedTest
    @CsvSource({"false", "true"})
    void staffAssignmentRejectsSupervisorWithoutChangingVisitOrEvents(boolean alreadyAssigned) throws Exception {
        Visit visit = waitingStaff();
        if (alreadyAssigned) service.assignStaff(visit.id(), 1, visit.version());
        var before = service.detail(visit.id());
        Long supervisorId = jdbc.queryForObject(
                "SELECT id FROM staff WHERE role = 'SUPERVISING_LSR'", Long.class);

        mvc.perform(post("/api/visits/{id}/assign-staff", visit.id())
                        .header("If-Match", "\"" + before.visit().version() + "\"")
                        .contentType("application/json").content("{\"staffId\":" + supervisorId + "}"))
                .andExpect(status().isConflict());

        var after = service.detail(visit.id());
        assertThat(after.visit().assignedStaffId()).isEqualTo(before.visit().assignedStaffId());
        assertThat(after.visit().version()).isEqualTo(before.visit().version());
        assertThat(after.visit()).isEqualTo(before.visit());
        assertThat(after.events()).containsExactlyElementsOf(before.events());
    }

    @Test
    void staffAssignmentRequiresVersionValidStaffAndBody() throws Exception {
        Visit visit = waitingStaff();
        mvc.perform(post("/api/visits/{id}/assign-staff", visit.id())
                        .contentType("application/json").content("{\"staffId\":1}"))
                .andExpect(status().is(428));
        for (String version : new String[] {"*", "2", "\"-1\"", "\"9223372036854775808\""}) {
            mvc.perform(post("/api/visits/{id}/assign-staff", visit.id()).header("If-Match", version)
                            .contentType("application/json").content("{\"staffId\":1}"))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/visits/{id}/assign-staff", visit.id()).header("If-Match", "\"1\"")
                        .contentType("application/json").content("{\"staffId\":1}"))
                .andExpect(status().isPreconditionFailed());
        mvc.perform(post("/api/visits/{id}/assign-staff", visit.id()).header("If-Match", "\"2\"")
                        .contentType("application/json").content("{\"staffId\":9999}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/visits/9999/assign-staff").header("If-Match", "\"2\"")
                        .contentType("application/json").content("{\"staffId\":1}"))
                .andExpect(status().isNotFound());
        for (String body : new String[] {"{}", "{\"staffId\":null}", "{\"staffId\":0}", "{\"staffId\":-1}"}) {
            mvc.perform(post("/api/visits/{id}/assign-staff", visit.id()).header("If-Match", "\"2\"")
                            .contentType("application/json").content(body)).andExpect(status().isBadRequest());
        }
        assertThat(service.detail(visit.id()).visit().assignedStaffId()).isNull();
        assertThat(service.detail(visit.id()).visit().version()).isEqualTo(2);
        assertThat(service.detail(visit.id()).events()).hasSize(3);
        service.assignStaff(visit.id(), 1, 2);
        mvc.perform(post("/api/visits/{id}/assign-staff", visit.id()).header("If-Match", "\"2\"")
                        .contentType("application/json").content("{\"staffId\":2}"))
                .andExpect(status().isPreconditionFailed());
        assertThat(service.detail(visit.id()).visit().assignedStaffId()).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({"CHECKED_IN", "IDENTIFIED", "AT_RESOURCE", "WAITING_RESOURCE", "CLOSED", "LEFT"})
    void staffAssignmentRejectsEveryOtherState(Visit.Status status) throws Exception {
        Visit visit = waitingStaff();
        jdbc.update("UPDATE visit SET status = ? WHERE id = ?", status.name(), visit.id());
        mvc.perform(post("/api/visits/{id}/assign-staff", visit.id()).header("If-Match", "\"2\"")
                        .contentType("application/json").content("{\"staffId\":1}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
        assertThat(service.detail(visit.id()).visit().version()).isEqualTo(2);
        assertThat(service.detail(visit.id()).events()).hasSize(3);
    }

    @Test
    void failedStaffAssignmentEventRollsBackStaffAndVersion() {
        Visit visit = waitingStaff();
        service.assignStaff(visit.id(), 1, 2);
        doThrow(new IllegalStateException("Simulated staff event failure"))
                .when(repository).recordTransition(any(), any());
        assertThatThrownBy(() -> service.assignStaff(visit.id(), 2, 3)).isInstanceOf(IllegalStateException.class);
        assertThat(service.detail(visit.id()).visit().assignedStaffId()).isEqualTo(1);
        assertThat(service.detail(visit.id()).visit().version()).isEqualTo(3);
        assertThat(service.detail(visit.id()).events()).hasSize(4);
    }

    @Test
    void rescheduleClosesOldVisitAndCreatesLinkedUnassignedAppointment() throws Exception {
        Visit original = waitingStaff();
        service.assignStaff(original.id(), 1, original.version());
        var before = service.detail(original.id());
        mvc.perform(post("/api/visits/{id}/reschedule", original.id()).header("If-Match", "\"3\""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.closedVisit.visit.status").value("CLOSED"))
                .andExpect(jsonPath("$.closedVisit.visit.closedAt").isNotEmpty())
                .andExpect(jsonPath("$.closedVisit.visit.version").value(4))
                .andExpect(jsonPath("$.replacementVisit.visit.status").value("WAITING_STAFF"))
                .andExpect(jsonPath("$.replacementVisit.visit.assignedStaffId").isEmpty())
                .andExpect(jsonPath("$.replacementVisit.visit.previousVisitId").value(original.id()));
        var queue = service.list(Visit.Status.WAITING_STAFF);
        assertThat(queue).hasSize(1);
        Visit replacement = queue.getFirst().visit();
        assertThat(replacement.id()).isNotEqualTo(original.id());
        assertThat(replacement.ticket()).isEqualTo("A0002");
        assertThat(replacement.customerId()).isEqualTo(original.customerId());
        assertThat(replacement.appointmentCategory()).isEqualTo(original.appointmentCategory());
        assertThat(replacement.appointmentType()).isEqualTo(original.appointmentType());
        assertThat(replacement.appointmentStatus()).isEqualTo(Visit.AppointmentStatus.RESCHEDULED);
        assertThat(replacement.resourceId()).isNull();
        assertThat(replacement.closedAt()).isNull();
        assertThat(replacement.version()).isZero();
        assertThat(queue.getFirst().waitingSince()).isEqualTo(replacement.createdAt());
        var closed = service.detail(original.id());
        assertThat(closed.visit().assignedStaffId()).isEqualTo(1);
        assertThat(closed.events()).hasSize(before.events().size() + 1);
        assertThat(closed.events().getLast().note()).contains("Rescheduled");
        assertThat(service.detail(replacement.id()).events()).singleElement().satisfies(event -> {
            assertThat(event.toStatus()).isEqualTo(Visit.Status.WAITING_STAFF);
            assertThat(event.actor()).isEqualTo("APPOINTMENTS");
            assertThat(event.note()).contains(Long.toString(original.id()));
        });
        var next = service.reschedule(replacement.id(), 0).replacementVisit().visit();
        assertThat(next.previousVisitId()).isEqualTo(replacement.id());
        assertThat(next.ticket()).isEqualTo("A0003");
    }

    @Test
    void rescheduleRequiresCurrentVersionAndCannotRepeat() throws Exception {
        Visit visit = waitingStaff();
        mvc.perform(post("/api/visits/{id}/reschedule", visit.id())).andExpect(status().is(428));
        mvc.perform(post("/api/visits/{id}/reschedule", visit.id()).header("If-Match", "2"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/visits/{id}/reschedule", visit.id()).header("If-Match", "\"1\""))
                .andExpect(status().isPreconditionFailed());
        assertThat(service.detail(visit.id()).visit()).isEqualTo(visit);
        assertThat(service.list(null)).hasSize(1);
        service.reschedule(visit.id(), visit.version());
        mvc.perform(post("/api/visits/{id}/reschedule", visit.id()).header("If-Match", "\"2\""))
                .andExpect(status().isPreconditionFailed());
        mvc.perform(post("/api/visits/{id}/reschedule", visit.id()).header("If-Match", "\"3\""))
                .andExpect(status().isConflict());
        assertThat(service.list(null)).hasSize(2);
    }

    @ParameterizedTest
    @CsvSource({"CHECKED_IN", "IDENTIFIED", "AT_RESOURCE", "WAITING_RESOURCE", "CLOSED", "LEFT"})
    void rescheduleRejectsNonWaitingStates(Visit.Status state) throws Exception {
        Visit visit = waitingStaff();
        jdbc.update("UPDATE visit SET status = ? WHERE id = ?", state.name(), visit.id());
        var before = service.detail(visit.id());
        mvc.perform(post("/api/visits/{id}/reschedule", visit.id()).header("If-Match", "\"2\""))
                .andExpect(status().isConflict());
        assertThat(service.detail(visit.id())).isEqualTo(before);
        assertThat(service.list(null)).hasSize(1);
    }

    @Test
    void failedReplacementEventRollsBackClosureAndNewVisit() {
        Visit visit = waitingStaff();
        service.assignStaff(visit.id(), 1, visit.version());
        var before = service.detail(visit.id());
        doThrow(new IllegalStateException("Simulated replacement event failure"))
                .when(repository).recordRescheduled(any());
        assertThatThrownBy(() -> service.reschedule(visit.id(), before.visit().version()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(service.detail(visit.id())).isEqualTo(before);
        assertThat(service.list(null)).hasSize(1);
    }

    @Test
    void competingReschedulesCreateExactlyOneReplacement() throws Exception {
        Visit visit = waitingStaff();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Integer> reschedule = () -> {
            ready.countDown();
            start.await();
            try {
                service.reschedule(visit.id(), visit.version());
                return 201;
            } catch (ResponseStatusException exception) {
                return exception.getStatusCode().value();
            }
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(reschedule);
            var second = executor.submit(reschedule);
            assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(201, 412);
        }
        assertThat(service.list(null)).hasSize(2);
        assertThat(service.list(Visit.Status.WAITING_STAFF)).hasSize(1);
        assertThat(service.detail(visit.id()).visit().status()).isEqualTo(Visit.Status.CLOSED);
    }

    private Visit waitingStaff() {
        Visit visit = service.checkIn(Visit.VisitType.APPOINTMENT, null,
                Visit.AppointmentCategory.MANDATORY);
        service.identify(visit.id(), 1, 0);
        return service.route(visit.id(), 1).visit();
    }

    @Test
    void migrationBackfillsExistingAppointmentsAndPreservesWalkIns() {
        String schema = "step5_upgrade";
        org.flywaydb.core.Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas(schema).defaultSchema(schema).target("4").load().migrate();
        jdbc.update("""
                INSERT INTO step5_upgrade.visit (ticket, visit_type, reason, appointment_category, status, created_at)
                VALUES ('A0001', 'APPOINTMENT', NULL, 'REEMPLOYMENT', 'WAITING_STAFF', CURRENT_TIMESTAMP),
                       ('B0001', 'WALK_IN', 'OTHER', NULL, 'CHECKED_IN', CURRENT_TIMESTAMP)
                """);
        org.flywaydb.core.Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas(schema).defaultSchema(schema).load().migrate();
        assertThat(jdbc.queryForObject("SELECT appointment_type FROM step5_upgrade.visit WHERE ticket = 'A0001'", String.class))
                .isEqualTo("INITIAL");
        assertThat(jdbc.queryForObject("SELECT appointment_status FROM step5_upgrade.visit WHERE ticket = 'A0001'", String.class))
                .isEqualTo("SCHEDULED");
        assertThat(jdbc.queryForObject("SELECT appointment_type FROM step5_upgrade.visit WHERE ticket = 'B0001'", String.class))
                .isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM step5_upgrade.staff", Integer.class)).isEqualTo(10);
    }

    @Test
    void identifyDerivesInitialThenFollowupAndLeavesWalkInsUnclassified() throws Exception {
        Visit walkIn = service.checkIn(Visit.VisitType.WALK_IN, Visit.Reason.OTHER, null);
        assertThat(service.identify(walkIn.id(), 1, 0).visit().appointmentType()).isNull();
        Visit first = service.checkIn(Visit.VisitType.APPOINTMENT, null, Visit.AppointmentCategory.MANDATORY);
        assertThat(first.appointmentType()).isNull();
        mvc.perform(post("/api/visits/{id}/identify", first.id()).header("If-Match", "\"0\"")
                        .contentType("application/json").content("{\"customerId\":1}"))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.visit.appointmentType").value("INITIAL"))
                .andExpect(jsonPath("$.visit.customerId").value(1));
        service.left(first.id(), 1);
        Visit second = service.checkIn(Visit.VisitType.APPOINTMENT, null, Visit.AppointmentCategory.JOB_SEARCH_ASSISTANCE);
        mvc.perform(post("/api/visits/{id}/identify", second.id()).header("If-Match", "\"0\"")
                        .contentType("application/json").content("{\"customerId\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.visit.appointmentType").value("FOLLOWUP"));
        Visit otherCustomer = service.checkIn(Visit.VisitType.APPOINTMENT, null, Visit.AppointmentCategory.MANDATORY);
        assertThat(service.identify(otherCustomer.id(), 2, 0).visit().appointmentType()).isEqualTo(Visit.AppointmentType.INITIAL);
        mvc.perform(post("/api/visits/{id}/identify", first.id()).header("If-Match", "\"0\"")
                        .contentType("application/json").content("{\"customerId\":1}"))
                .andExpect(status().isPreconditionFailed());
        assertThat(service.detail(first.id()).visit().appointmentType()).isEqualTo(Visit.AppointmentType.INITIAL);
    }

    @Test
    void concurrentIdentificationForSameCustomerProducesOneInitialAndOneFollowup() throws Exception {
        Visit first = service.checkIn(Visit.VisitType.APPOINTMENT, null, Visit.AppointmentCategory.MANDATORY);
        Visit second = service.checkIn(Visit.VisitType.APPOINTMENT, null, Visit.AppointmentCategory.MANDATORY);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var results = executor.invokeAll(List.<Callable<Visit.AppointmentType>>of(
                    () -> service.identify(first.id(), 1, 0).visit().appointmentType(),
                    () -> service.identify(second.id(), 1, 0).visit().appointmentType()));
            assertThat(List.of(results.get(0).get(), results.get(1).get()))
                    .containsExactlyInAnyOrder(Visit.AppointmentType.INITIAL, Visit.AppointmentType.FOLLOWUP);
        }
    }

    @Test
    void failedIdentifyEventRollsBackDerivedTypeAlongWithCustomerAndVersion() {
        Visit visit = service.checkIn(Visit.VisitType.APPOINTMENT, null, Visit.AppointmentCategory.MANDATORY);
        doThrow(new IllegalStateException("Simulated identify event failure"))
                .when(repository).recordTransition(any(), any());
        assertThatThrownBy(() -> service.identify(visit.id(), 1, 0)).isInstanceOf(IllegalStateException.class);
        assertThat(service.detail(visit.id()).visit()).isEqualTo(visit);
        assertThat(service.detail(visit.id()).events()).hasSize(1);
    }

    @ParameterizedTest
    @CsvSource({"REEMPLOYMENT", "CAREER_SERVICES", "VETERANS"})
    void legacyCategoriesAreRejectedByApiAndDatabase(String category) throws Exception {
        mvc.perform(post("/api/visits").contentType("application/json")
                        .content("{\"visitType\":\"APPOINTMENT\",\"appointmentCategory\":\"" + category + "\"}"))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM visit", Integer.class)).isZero();
        Visit visit = service.checkIn(Visit.VisitType.APPOINTMENT, null, Visit.AppointmentCategory.MANDATORY);
        assertThatThrownBy(() -> jdbc.update("UPDATE visit SET appointment_category = ? WHERE id = ?", category, visit.id()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void categoryMigrationRemapsAllLegacyValuesAndAllowsUnclassifiedCheckIns() {
        String schema = "step11_upgrade";
        org.flywaydb.core.Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas(schema).defaultSchema(schema).target("8").load().migrate();
        for (String category : List.of("REEMPLOYMENT", "CAREER_SERVICES", "VETERANS")) {
            jdbc.update("""
                    INSERT INTO step11_upgrade.visit (ticket, visit_type, appointment_category, appointment_type,
                        appointment_status, status, created_at, business_date)
                    VALUES (?, 'APPOINTMENT', ?, 'INITIAL', 'SCHEDULED', 'CHECKED_IN', CURRENT_TIMESTAMP, CURRENT_DATE)
                    """, category, category);
        }
        jdbc.update("""
                INSERT INTO step11_upgrade.visit (ticket, visit_type, reason, status, created_at, business_date)
                VALUES ('B0001', 'WALK_IN', 'OTHER', 'CHECKED_IN', CURRENT_TIMESTAMP, CURRENT_DATE)
                """);
        org.flywaydb.core.Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas(schema).defaultSchema(schema).target("9").load().migrate();
        assertThat(jdbc.queryForList("SELECT appointment_category FROM step11_upgrade.visit WHERE visit_type = 'APPOINTMENT'", String.class))
                .containsExactly("MANDATORY", "MANDATORY", "MANDATORY");
        assertThat(jdbc.queryForObject("SELECT appointment_type FROM step11_upgrade.visit WHERE ticket = 'B0001'", String.class)).isNull();
        jdbc.update("UPDATE step11_upgrade.visit SET appointment_type = NULL WHERE visit_type = 'APPOINTMENT'");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM step11_upgrade.visit WHERE appointment_type IS NOT NULL", Integer.class)).isZero();
        assertThatThrownBy(() -> jdbc.update("UPDATE step11_upgrade.visit SET appointment_category = 'VETERANS' WHERE ticket = 'REEMPLOYMENT'"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

}
