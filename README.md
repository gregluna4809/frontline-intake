# Frontline

Standalone Career Center intake and routing prototype. Synthetic data only; production use is out of scope.

## Kiosk, front desk, resource room, and staff queues

- Java 21 / Spring Boot 3.5.16, Maven wrapper 3.3.4 with Maven 3.9.11.
- PostgreSQL 17 in Docker Compose; Flyway creates Customer, Resource, Visit, and VisitEvent tables.
- 50 synthetic customers, 6 phones, 8 computers, and 2 stations.
- React 19 / Vite 8 kiosk, front-desk, resource-room, supervisor, and my-queue routes.
- Kiosk starts with Walk-In or Appointment. Walk-ins choose a reason; appointments choose REEMPLOYMENT, CAREER_SERVICES, or VETERANS, plus Initial or Follow-up.
- Check-in creates a CHECKED_IN visit and its VisitEvent in one transaction. Walk-ins use B tickets; appointments use A tickets with an independent sequence.
- Front desk lists CHECKED_IN visits in arrival order and refreshes every 5 seconds.
- Select a ticket, search and attach a customer, then route the visit. The panel shows the attached name/ID and routing result.
- Identify and route use optimistic version checks and write FRONT_DESK events in the same transaction as the visit and resource updates.
- Supervisor groups WAITING_STAFF visits into Unassigned and one group per LSR. Each visit shows ticket, type, appointment status, waiting time, and an Assign/Reassign control. Supervising LSRs are not assignable targets.
- My Queue filters WAITING_STAFF visits to the LSR selected through View as. The selected staff ID is retained in `/my-queue?staff=...`. Close uses the existing endpoint; resource-room data and its role entry are absent from this view.
- Both staff screens refresh every five seconds and provide manual refresh. Mutations use the current quoted version and trigger a reload; another action is blocked until the reload succeeds. `/appointments` redirects to `/supervisor`.

- Resource room groups Phones, Computers, and Stations, showing status and the current ticket/customer for occupied resources. Close and Left release resources atomically.
- WAITING_RESOURCE entries show the needed type and elapsed waiting time. Assign is disabled when no matching resource is free; Left remains available.
- Occupied visits are flagged after more than 45 minutes at the resource. All queues refresh every five seconds.

Steps 1–6 are implemented. No login, app Dockerfile, or deployment configuration is included.

## Run on Windows PowerShell

Prerequisites: Java 21, Node.js 22.12+ (Node 24 is supported), and Docker Desktop running Linux containers. Maven does not need to be installed globally. First runs require internet access for dependencies and images.

1. Start PostgreSQL from the repository root:

   ```powershell
   Set-Location C:\Projects\frontline
   docker compose up -d --wait
   ```

2. In a second terminal, start the backend. Flyway runs automatically on startup:

   ```powershell
   Set-Location C:\Projects\frontline\backend
   .\mvnw.cmd spring-boot:run
   ```

3. In a third terminal, start the frontend:

   ```powershell
   Set-Location C:\Projects\frontline\frontend
   npm ci
   npm run dev
   ```

4. Open http://127.0.0.1:5173/kiosk. Choose Walk-In and a reason, or Appointment, a category, and Initial or Follow-up, then check in. Open http://127.0.0.1:5173/front-desk in another tab to select the ticket, search for Demo Customer 001, attach that customer, and route the visit. Routed appointments appear at http://127.0.0.1:5173/supervisor. Assign an LSR there, then choose that person's My Queue entry in View as to view and close their appointments. Walk-ins appear at http://127.0.0.1:5173/resource-room; occupied tiles have Close/Left, and waiting entries have Assign/Left.

Stop the backend and frontend with Ctrl+C in their terminals. Stop this project's database with `docker compose stop` from the root. The named volume retains data.

## Configuration

| Variable | Default | Used by |
| --- | --- | --- |
| DB_PASSWORD | frontline_demo | Compose and backend |
| DB_PORT | 55432 | Compose host port |
| DB_URL | jdbc:postgresql://localhost:55432/frontline | Backend |
| DB_USER | frontline | Backend |
| SERVER_PORT | 8080 | Backend |
| API_PROXY_TARGET | http://127.0.0.1:8080 | Vite |

The default database credential is for this synthetic local demo. PostgreSQL is bound to localhost. Set PowerShell environment variables in the relevant terminals to override defaults. If DB_PORT changes, update DB_URL too; if SERVER_PORT changes, update API_PROXY_TARGET in the frontend terminal. The backend uses environment variables directly; it does not load a root .env file.

## Implemented API

| Method | Path | Result |
| --- | --- | --- |
| POST | /api/visits | Body: `{"visitType":"WALK_IN","reason":"PHONE_ACCESS"}` or `{"visitType":"APPOINTMENT","appointmentCategory":"REEMPLOYMENT","appointmentType":"INITIAL"}`; returns 201 with visit and Location header |
| GET | /api/visits?status=CHECKED_IN | Visits matching the status, oldest first; also supports WAITING_STAFF and WAITING_RESOURCE |
| GET | /api/visits | All visits, oldest first |
| GET | /api/visits/{id} | Object with `visit`, `events`, `customer`, `resource`, and `requiredResourceType`; includes an ETag |
| GET | /api/customers?q= | Case-insensitive literal display-name search; at most 20 results, ordered by name and ID |
| POST | /api/visits/{id}/identify | Body: `{"customerId":1}`; CHECKED_IN → IDENTIFIED |
| POST | /api/visits/{id}/route | No body; IDENTIFIED → AT_RESOURCE, WAITING_RESOURCE, or WAITING_STAFF |
| POST | /api/visits/{id}/close | No body; AT_RESOURCE or WAITING_STAFF → CLOSED; stamps closedAt and frees the resource if present |
| POST | /api/visits/{id}/left | No body; any status except CLOSED/LEFT → LEFT; stamps closedAt and frees the resource if present |
| POST | /api/visits/{id}/assign | No body; WAITING_RESOURCE → AT_RESOURCE; 409 if no matching resource is free |
| GET | /api/resources | Inventory with current visitId, ticket, customer, visitVersion, and assignedAt when occupied |
| GET | /api/staff | All staff ordered by role then display name; screens and role selector filter assignable staff to LSR |
| POST | /api/visits/{id}/assign-staff | Body: `{"staffId":1}`; requires If-Match; assigns or reassigns a WAITING_STAFF visit, increments version, and records an event note |

Allowed reasons: PHONE_ACCESS, COMPUTER_ACCESS, DOCUMENT_SERVICES, BENEFITS_ASSISTANCE, JOB_SEARCH, OTHER. Invalid request values return 400; an unknown visit returns 404. Unknown JSON fields are rejected. Identify, route, assign, assign-staff, close, and left require an `If-Match` header containing the quoted version, such as `"0"`. Their response includes the updated detail and ETag. Missing versions return 428, malformed versions 400, stale versions 412, and invalid transitions 409. No mutation occurs on rejection.

Creation requires visitType. WALK_IN requires reason and no appointmentCategory or appointmentType; APPOINTMENT requires appointmentCategory and appointmentType, with no reason. Appointment types are INITIAL and FOLLOWUP. Appointment status starts SCHEDULED and is not accepted from the client. Missing required fields, mixed non-null fields, and unknown category codes return 400. Optional null fields are treated as absent. Categories are REEMPLOYMENT, CAREER_SERVICES, and VETERANS.

Queue items preserve the visit fields, including appointmentType, appointmentStatus, and assignedStaffId, at the top level and add customer, waitingSince, and requiredResourceType. waitingSince is the most recent event entering the current WAITING_STAFF or WAITING_RESOURCE status, so waiting time excludes time spent at the kiosk/front desk. Timers update every second; queues reload every five seconds.

Inventory keeps id/type/label/status at the top level and adds the current visit's metadata. Only AT_RESOURCE visits are joined, so retained resource IDs on completed visits cannot show an old occupant. assignedAt is the latest AT_RESOURCE event. The UI flag threshold is RESOURCE_ALERT_MINUTES = 45 in frontend/src/ResourceRoom.jsx; exactly 45 minutes is not flagged.

Customer search trims whitespace and treats wildcard characters literally. An empty search returns the first 20 customers. The front-desk search waits for typed input.

### Routing

| Visit / reason | Destination |
| --- | --- |
| APPOINTMENT | WAITING_STAFF, with no resource allocation |
| WALK_IN / PHONE_ACCESS | PHONE |
| WALK_IN / COMPUTER_ACCESS or JOB_SEARCH | COMPUTER |
| WALK_IN / DOCUMENT_SERVICES, BENEFITS_ASSISTANCE, or OTHER | STATION |

A walk-in receives the lowest-labeled FREE resource of the required type. The resource becomes OCCUPIED and the visit becomes AT_RESOURCE. If none is free, the visit becomes WAITING_RESOURCE. The small inventory of that type is row-locked in label order during assignment so concurrent callers wait for a committed result. Resource reservation or release, the guarded version update, and the event commit or roll back together. Resource rows are locked before visit updates consistently. Close/Left retain the visit's resourceId as history while setting the resource FREE; the inventory only reports its current occupant.

## Simplest choices where the spec is silent

- Spring JDBC and parameterized SQL; Flyway owns the schema. SQL names use snake_case; Java and JSON retain the specified camelCase names.
- Database-generated numeric IDs; separate ticket sequences start at B0001 (walk-ins) and A0001 (appointments), grow beyond four digits, and do not reset daily. PostgreSQL sequences can have gaps after failed transactions.
- Creation writes an event with fromStatus null, toStatus CHECKED_IN, and actor KIOSK. Visit version starts at 0.
- UTC timestamp storage; the front desk displays local browser time.
- Customer names are Demo Customer 001 through Demo Customer 050. Languages cycle EN/ES/OTHER; every fifth record has veteran=true. These are synthetic values.
- All resources start FREE and use labels Phone 01, Computer 01, and Station 01, numbered within each type.
- Plain React with JavaScript, system fonts, npm, and a Vite API proxy. The root URL shows the kiosk.
- Migration V3 introduced appointment fields and statuses; V4 adds the independent appointment ticket sequence. Prior migrations and existing visit records are preserved. New appointment creation accepts only the three category codes above; legacy category labels remain readable.
- A selected visit is retained in the `/front-desk?visit=...` URL so identification can be resumed after a refresh. Identifying removes the visit from CHECKED_IN immediately; its panel remains available for routing.
- Front-desk mutation failures require reloading the selected visit before retrying. The supervisor, my-queue, and resource-room screens refresh after mutations and failures. Resource-room actions stay disabled until both inventory and waiting-list reloads succeed.
- Resource-room events use actor RESOURCE_ROOM, appointment events APPOINTMENTS, and front-desk events FRONT_DESK. No schema migration is needed for step 3.

## Verification

Backend integration tests use a separate disposable PostgreSQL container through Testcontainers, not the demo database. Docker Desktop must be running.

```powershell
Set-Location C:\Projects\frontline\backend
.\mvnw.cmd test
```

The backend tests cover exact seeds, check-in/list/detail, request validation, customer search, identify, all six routing mappings, exhausted resources, appointment routing, version preconditions, event/optimistic-update rollback, and concurrent assignment. Appointment creation for each category, independent ticket sequences, mixed-input rejection, appointment closing, staff waiting timestamps, and close rollback are also covered. Resource close/left, all allowed Left source statuses, terminal rejection, release rollback, waiting assignment, resource reuse, and competing close/left/assign calls are covered. Ticket growth past B9999 remains covered.

```powershell
Set-Location C:\Projects\frontline\frontend
npm test
npm run build
```

Frontend tests cover both kiosk types, clearing incompatible choices, queue loading, customer attachment, routing outcomes, supervisor grouping and assignment/reassignment, My Queue filtering and closing, role navigation and the legacy redirect, stale-version recovery, resuming an identified visit, and ignoring obsolete search responses. Resource-room tests cover grouping and occupant details, the 45-minute boundary, availability-based Assign disabling, close/left/assign, conflicting assignment, and five-second polling.
