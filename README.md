# Frontline

Standalone Career Center intake and routing prototype. Synthetic data only; production use is out of scope.

## Steps 1 and 2 implemented

- Java 21 / Spring Boot 3.5.16, Maven wrapper 3.3.4 with Maven 3.9.11.
- PostgreSQL 17 in Docker Compose; Flyway creates Customer, Resource, Visit, and VisitEvent tables.
- 50 synthetic customers, 6 phones, 8 computers, and 2 stations.
- React 19 / Vite 8 kiosk and front-desk routes.
- Kiosk check-in creates a CHECKED_IN visit and its VisitEvent in one transaction.
- Front desk lists CHECKED_IN visits in arrival order and refreshes every 5 seconds.
- Select a ticket, search and attach a customer, then route the visit. The panel shows the attached name/ID and routing result.
- Identify and route use optimistic version checks and write FRONT_DESK events in the same transaction as the visit and resource updates.

Resource-room actions, close, and left belong to step 3. The resource-room option in the role switcher is disabled until then. No login, app Dockerfile, or deployment is included.

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

4. Open http://127.0.0.1:5173/kiosk. Choose a reason and check in. Open http://127.0.0.1:5173/front-desk in another tab to select the ticket, search for Demo Customer 001, attach that customer, and route the visit.

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
| POST | /api/visits | Body: `{"reason":"PHONE_ACCESS"}`; returns 201 with visit and Location header |
| GET | /api/visits?status=CHECKED_IN | Visits matching the status, oldest first |
| GET | /api/visits | All visits, oldest first |
| GET | /api/visits/{id} | Object with `visit`, `events`, `customer`, `resource`, and `requiredResourceType`; includes an ETag |
| GET | /api/customers?q= | Case-insensitive literal display-name search; at most 20 results, ordered by name and ID |
| POST | /api/visits/{id}/identify | Body: `{"customerId":1}`; CHECKED_IN → IDENTIFIED |
| POST | /api/visits/{id}/route | No body; IDENTIFIED → AT_RESOURCE, WAITING_RESOURCE, or WAITING_STAFF |

Allowed reasons: PHONE_ACCESS, COMPUTER_ACCESS, DOCUMENT_SERVICES, BENEFITS_ASSISTANCE, JOB_SEARCH, OTHER. Invalid request values return 400; an unknown visit returns 404. Unknown JSON fields are rejected. Identify and route require an `If-Match` header containing the quoted version, such as `"0"`. Their response includes the updated detail and ETag. Missing versions return 428, malformed versions 400, stale versions 412, and invalid transitions 409. No mutation occurs on rejection.

Customer search trims whitespace and treats wildcard characters literally. An empty search returns the first 20 customers. The front-desk search waits for typed input.

### Routing

| Visit / reason | Destination |
| --- | --- |
| APPOINTMENT | WAITING_STAFF, with no resource allocation |
| WALK_IN / PHONE_ACCESS | PHONE |
| WALK_IN / COMPUTER_ACCESS or JOB_SEARCH | COMPUTER |
| WALK_IN / DOCUMENT_SERVICES, BENEFITS_ASSISTANCE, or OTHER | STATION |

A walk-in receives the lowest-labeled FREE resource of the required type. The resource becomes OCCUPIED and the visit becomes AT_RESOURCE. If none is free, the visit becomes WAITING_RESOURCE. The small inventory of that type is row-locked in label order during assignment so concurrent callers wait for a committed result. Resource reservation, the guarded version update, and the event commit or roll back together.

## Simplest choices where the spec is silent

- Spring JDBC and parameterized SQL; Flyway owns the schema. SQL names use snake_case; Java and JSON retain the specified camelCase names.
- Database-generated numeric IDs; ticket sequence starts at B0001, grows beyond four digits, and does not reset daily. PostgreSQL sequences can have gaps after failed transactions.
- Creation writes an event with fromStatus null, toStatus CHECKED_IN, and actor KIOSK. Visit version starts at 0.
- UTC timestamp storage; the front desk displays local browser time.
- Customer names are Demo Customer 001 through Demo Customer 050. Languages cycle EN/ES/OTHER; every fifth record has veteran=true. These are synthetic values.
- All resources start FREE and use labels Phone 01, Computer 01, and Station 01, numbered within each type.
- Plain React with JavaScript, system fonts, npm, and a Vite API proxy. The root URL shows the kiosk.
- Step 2 adds APPOINTMENT and WAITING_STAFF through migration V3 without rewriting V1/V2. A nullable `appointmentCategory` field holds generic category text (up to 100 characters). Appointments require a nonblank category and may omit reason; walk-ins still require reason.
- Kiosk creation remains WALK_IN-only. Appointment routing supports existing database records; there is no appointment creation endpoint or UI in this step. Tests use Reemployment Appointment as a synthetic category.
- A selected visit is retained in the `/front-desk?visit=...` URL so identification can be resumed after a refresh. Identifying removes the visit from CHECKED_IN immediately; its panel remains available for routing.
- Mutation failures require reloading the selected visit before retrying, including ambiguous network failures.

## Verification

Backend integration tests use a separate disposable PostgreSQL container through Testcontainers, not the demo database. Docker Desktop must be running.

```powershell
Set-Location C:\Projects\frontline\backend
.\mvnw.cmd test
```

The backend tests cover exact seeds, check-in/list/detail, request validation, customer search, identify, all six routing mappings, exhausted resources, appointment routing, version preconditions, event/optimistic-update rollback, and concurrent assignment. Ticket creation and growth past B9999 remain covered.

```powershell
Set-Location C:\Projects\frontline\frontend
npm test
npm run build
```

Frontend tests cover check-in, queue loading, customer attachment, all routing outcomes, stale-version recovery, resuming an identified visit, and ignoring obsolete search responses. Full workflow end-to-end verification remains step 4.
