# Frontline

Standalone Career Center intake and routing prototype. Synthetic data only; production use is out of scope.

## Kiosk, front desk, resource room, and staff queues

- Java 21 / Spring Boot 3.5.16, Maven wrapper 3.3.4 with Maven 3.9.11.
- PostgreSQL 17 in Docker Compose; Flyway creates Customer, Resource, Visit, and VisitEvent tables.
- 50 synthetic customers, 6 phones, 8 computers, and 2 stations.
- React 19 / Vite 8 kiosk, front-desk, resource-room, supervisor, and my-queue routes.
- Kiosk starts with Walk-In or Appointment. Walk-ins choose a reason; appointments choose Mandatory or Job Search Assistance. Initial/Follow-up is derived when the front desk identifies the customer.
- Check-in creates a CHECKED_IN visit and its VisitEvent in one transaction. Walk-ins use B tickets; appointments use A tickets with an independent sequence.
- Front desk lists CHECKED_IN and IDENTIFIED visits in arrival order and refreshes every 5 seconds.
- Select a ticket, search and attach a customer, then route the visit. The panel shows the attached name/ID and routing result.
- Identify and route use optimistic version checks and write FRONT_DESK events in the same transaction as the visit and resource updates.
- Supervisor groups WAITING_STAFF visits into Unassigned and one group per LSR. Each visit shows ticket, type, appointment status, waiting time, and an Assign/Reassign control. Supervising LSRs are not assignable targets.
- My Queue filters WAITING_STAFF visits to the LSR selected through View as. The selected staff ID is retained in `/my-queue?staff=...`. Close uses the existing endpoint; resource-room data and its role entry are absent from this view.
- Both staff screens refresh every five seconds and provide manual refresh. Mutations use the current quoted version and trigger a reload; another action is blocked until the reload succeeds. `/appointments` redirects to `/supervisor`.

- Resource room groups Phones, Computers, and Stations, showing status and the current ticket/customer for occupied resources. Close and Left release resources atomically.
- WAITING_RESOURCE entries show the needed type and elapsed waiting time. Assign is disabled when no matching resource is free; Left remains available.
- Occupied visits are flagged after more than 45 minutes at the resource. All queues refresh every five seconds.

Steps 1–14 are implemented. Production Docker images and Compose configuration are included for the synthetic portfolio deployment. The backend now requires JWT authentication for staff APIs; frontend login integration is not included in Step 14.

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
   $jwtBytes = New-Object byte[] 32
   $jwtRng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
   $jwtRng.GetBytes($jwtBytes)
   $jwtRng.Dispose()
   $env:JWT_SECRET = [Convert]::ToBase64String($jwtBytes)
   .\mvnw.cmd spring-boot:run
   ```

3. In a third terminal, start the frontend:

   ```powershell
   Set-Location C:\Projects\frontline\frontend
   npm ci
   npm run dev
   ```

4. Open http://127.0.0.1:5173/kiosk. Choose Walk-In and a reason, or Appointment and a category, then check in. The staff-screen workflow below requires frontend login integration after Step 14; use the authenticated API example below for now. Open http://127.0.0.1:5173/front-desk in another tab to select the ticket, search for Demo Customer 001, attach that customer, and route the visit. Routed appointments appear at http://127.0.0.1:5173/supervisor. Assign an LSR there, then choose that person's My Queue entry in View as to view and close their appointments. Walk-ins appear at http://127.0.0.1:5173/resource-room; occupied tiles have Close/Left, and waiting entries have Assign/Left.

Stop the backend and frontend with Ctrl+C in their terminals. Stop this project's database with `docker compose stop` from the root. The named volume retains data.

## Production Docker deployment

`docker-compose.prod.yml` is separate from the local development `compose.yaml` and uses the project name `frontline-prod`, with its own persistent PostgreSQL volume. Copy `.env.example` to an untracked `.env` and supply DB_USER, DB_PASSWORD, and JWT_SECRET; DB_NAME defaults to frontline and is used consistently by PostgreSQL and the backend JDBC URL. Missing or empty credentials or JWT_SECRET fail Compose validation. Generate JWT_SECRET using the PowerShell commands above, then put that Base64 value in .env. Keep the value stable across backend restarts; replacing it invalidates existing tokens.

The Dockerfiles build with Maven/Java 21 and Node 22, then run with the Java 21 JRE and nginx. Backend image builds skip tests. nginx serves the Vite output with an index.html fallback for client-side routes. Build contexts exclude local dependencies, build output, logs, and environment files.

The external Docker network `edge` must already exist. Caddy on that network must preserve and route `/api/*` to `frontline-backend:8080`, and all other requests to `frontline-frontend:80`. This file does not configure or start Caddy. PostgreSQL joins only the internal default network; the backend joins default and edge; the frontend joins only edge. No service publishes host ports.

After configuring `.env` and Caddy, build and start using the explicit production file:

```powershell
docker compose -f docker-compose.prod.yml config --quiet
docker compose -f docker-compose.prod.yml up -d --build
```

Starting the backend applies pending Flyway migrations, including the one-time historical seed. The backend waits for PostgreSQL's health check before starting. All services use the unless-stopped restart policy; the database volume persists across container replacements.

## Configuration

| Variable | Default | Used by |
| --- | --- | --- |
| DB_PASSWORD | frontline_demo | Compose and backend |
| DB_PORT | 55432 | Compose host port |
| DB_URL | jdbc:postgresql://localhost:55432/frontline | Backend |
| DB_USER | frontline | Backend |
| SERVER_PORT | 8080 | Backend |
| JWT_SECRET | Required; no default | Backend; Base64-encoded random key of at least 32 bytes |
| API_PROXY_TARGET | http://127.0.0.1:8080 | Vite |

The default database credential is for this synthetic local demo. PostgreSQL is bound to localhost. Set PowerShell environment variables in the relevant terminals to override defaults. If DB_PORT changes, update DB_URL too; if SERVER_PORT changes, update API_PROXY_TARGET in the frontend terminal. The backend uses environment variables directly; it does not load a root .env file.

## Implemented API

| Method | Path | Result |
| --- | --- | --- |
| POST | /api/visits | Body: `{"visitType":"WALK_IN","reason":"PHONE_ACCESS"}` or `{"visitType":"APPOINTMENT","appointmentCategory":"MANDATORY"}`; returns 201 with visit and Location header |
| GET | /api/visits?status=CHECKED_IN | Current business-day visits matching the status, oldest first; also supports IDENTIFIED, WAITING_STAFF, and WAITING_RESOURCE |
| GET | /api/visits | Current business-day visits, oldest first; optional `businessDate=YYYY-MM-DD` selects another day |
| GET | /api/visits/{id} | Object with `visit`, `events`, `customer`, `resource`, and `requiredResourceType`; includes an ETag |
| GET | /api/customers?q= | Case-insensitive literal display-name search; at most 20 results, ordered by name and ID |
| POST | /api/visits/{id}/identify | Body: `{"customerId":1}`; CHECKED_IN → IDENTIFIED |
| POST | /api/visits/{id}/route | No body; IDENTIFIED → AT_RESOURCE, WAITING_RESOURCE, or WAITING_STAFF |
| POST | /api/visits/{id}/close | No body; AT_RESOURCE or WAITING_STAFF → CLOSED; stamps closedAt and frees the resource if present |
| POST | /api/visits/{id}/left | No body; any status except CLOSED/LEFT → LEFT; stamps closedAt and frees the resource if present |
| POST | /api/visits/{id}/assign | No body; WAITING_RESOURCE → AT_RESOURCE; 409 if no matching resource is free |
| GET | /api/resources | Current inventory with occupied visit metadata scoped to the business day; optional `businessDate=YYYY-MM-DD` |
| GET | /api/staff | All staff ordered by role then display name; screens and role selector filter assignable staff to LSR |
| POST | /api/visits/{id}/assign-staff | Body: `{"staffId":1}`; requires If-Match; assigns or reassigns a WAITING_STAFF visit, increments version, and records an event note |

Allowed reasons: PHONE_ACCESS, COMPUTER_ACCESS, DOCUMENT_SERVICES, BENEFITS_ASSISTANCE, JOB_SEARCH, OTHER. Invalid request values return 400; an unknown visit returns 404. Unknown JSON fields are rejected. Identify, route, assign, assign-staff, close, and left require an `If-Match` header containing the quoted version, such as `"0"`. Their response includes the updated detail and ETag. Missing versions return 428, malformed versions 400, stale versions 412, and invalid transitions 409. No mutation occurs on rejection.

Creation requires visitType. WALK_IN requires reason and no appointmentCategory; APPOINTMENT requires appointmentCategory and no reason. Only MANDATORY and JOB_SEARCH_ASSISTANCE category codes are accepted. New visits start with appointmentType null; appointment status starts SCHEDULED for appointments. Both appointmentType (including explicit null) and appointmentStatus are rejected as unknown request fields. Missing required fields, mixed non-null fields, and unknown category codes return 400. Optional null reason/category values are treated as absent. At identify, an appointment is classified INITIAL when the attached customer has no other appointment visit, otherwise FOLLOWUP; walk-ins remain unclassified.

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

## Step 7: staff rotation, call-outs, and rescheduling

Migration V6 adds `staff_rotation`, `call_out`, and the nullable `visit.previous_visit_id` self-reference. ISO weekdays are stored as integers, Monday = 1 through Sunday = 7. Each of the four synthetic LSRs has seven rotation rows: one Resource Room day, staggered Monday through Thursday in staff-ID order, and Appointments on every other day (including weekends).

| Method | Path | Result |
| --- | --- | --- |
| GET | /api/rotation?date=YYYY-MM-DD | Each LSR's `{ staffId, displayName, dutyType }`; a call-out overrides the base duty with RESOURCE_ROOM for that date only |
| POST | /api/call-outs | Body: `{ "staffId": 1, "date": "YYYY-MM-DD", "reason": "Optional synthetic note" }`; returns 200 with `{ id, staffId, date, reason, createdAt }` |
| GET | /api/call-outs?date=YYYY-MM-DD | Call-outs for that date, ordered by staff ID |
| POST | /api/visits/{id}/reschedule | No body; requires the current quoted If-Match version; returns 201 with `{ closedVisit, replacementVisit }` visit-detail objects and a Location header for the replacement |

Call-outs upsert on `(staff_id, date)`: later requests replace the reason while retaining the row ID and original creation timestamp. Omitted or blank reasons become null. Past dates are rejected with 400 using the America/New_York calendar date; unknown staff return 404 and non-LSR targets return 409. Missing or malformed dates return 400. GET endpoints also support historical dates.

The supervisor board's Business-day coverage panel uses `/api/business-status` to load rotation and waiting appointments for the same business date. It displays every LSR's effective duty and provides a small Mark called out form when that date is the current America/New_York calendar date. When showing an earlier business day, call-out controls are disabled because past dates cannot be changed. For this prototype, a call-out means the selected LSR covers Resource Room, rather than an absence from the center. Assigned waiting appointments are flagged whenever their LSR's effective duty is RESOURCE_ROOM, whether from the base rotation or a call-out. Assignment remains a supervisor decision; call-outs do not silently reassign or close visits. Coverage and queues refresh together every five seconds and after mutations; actions stay disabled until that refresh succeeds.

Reschedule is available for every waiting appointment on the supervisor board and alongside Close in My Queue. It uses the normal CLOSED transition and closedAt timestamp, with an explicit rescheduling note in the old visit's event log. In the same transaction it creates a fresh A ticket with the same customer, appointment type, and category, appointmentStatus RESCHEDULED, status WAITING_STAFF, version 0, and no staff or resource assignment. The replacement's previousVisitId links back to the old visit; a unique foreign key prevents multiple direct replacements. Its initial event records entry to WAITING_STAFF and starts a fresh waiting timer. Failures roll back the closure, replacement, and both event writes together. Repeated or competing requests are guarded by the existing version and status checks.

Resource-room visits are not assigned to staff, so that screen remains unchanged. The new tests cover rotation seeds and date isolation, call-out upserts and validation, orphan flagging and recovery, rescheduling from both staff screens, preserved customer/classification, history links, rollback, and concurrency.

## Step 8: business calendar and day scoping

The business calendar uses America/New_York, Monday through Friday, 8:30am inclusive to 4:45pm exclusive. Its static holiday list covers 2026 and 2027 and must be extended before 2028. The requested holidays follow the [2026](https://www.cs.ny.gov/attendance_leave/2026_legal_holidays.cfm) and [2027](https://www.cs.ny.gov/attendance_leave/2027_legal_holidays.cfm) observed dates: Saturday holidays stay Saturday, Sunday holidays are observed Monday. The requested day after Thanksgiving is also closed.

`GET /api/business-status` returns `{ isOpen, currentBusinessDate, lastCompletedBusinessDate }`. While open, currentBusinessDate is today. Before opening, on weekends, and on holidays it is the most recently completed business day; after closing on a business day it is that same day. During open hours the simulator control displays the effective business date; after hours the persistent closed banner identifies the frozen date.

Migration V7 adds non-null `visit.business_date`, backfills existing rows from created_at in America/New_York, and changes ticket uniqueness to `(business_date, ticket)`. Check-in, simulator creation, and rescheduling all stamp the business calendar's currentBusinessDate. Direct API creation outside hours still receives the most recently completed business date; the frontend offers no after-hours check-in, and a saved snapshot is never changed by later API activity.

Visit queues default to this date. `GET /api/visits?businessDate=YYYY-MM-DD` selects a specific day and can be combined with status. Detail/history reads by visit ID remain available across dates. `/api/resources` scopes occupant metadata the same way and accepts the same optional date. Resource availability remains its actual current state and is reset to FREE at the next business opening. Historical visit records are unchanged by rollover. Use `/api/snapshot` for preserved end-of-day occupancy; `/api/resources?businessDate=...` only filters current occupant metadata and is not historical inventory.

Every minute, a scheduled task starts or stops the simulator using its existing methods according to business hours. Manual start/stop still works, but the next scheduled check restores the calendar's state. A weekday 8:30am Eastern task resets both ticket sequences to 1, skipping holidays. Creation and reset share a PostgreSQL transaction advisory lock; the first creation also catches up after a missed opening when earlier-day visits exist. Reset never rewinds tickets already issued for the current business day, including when an arrival races the scheduled task. Historical tickets remain valid because uniqueness is per business date.

Tests disable the live business schedules with `business.scheduling.enabled=false` and exercise the calendar and schedule methods directly under controlled clocks. Tests cover hour boundaries, weekends, holidays and observance, daylight-saving offsets, status responses, all creation paths, default/explicit date filtering, migration backfill, sequence resets and concurrent arrivals. Frontend tests cover business status display, business-date coverage alignment, and disabled past-date call-outs.

## Step 9: day rollover and after-hours snapshot

Migration V8 adds `business_day_snapshot` (immutable JSON payload per date), `business_day_rollover` (once-per-day reset marker), and `resource.active_visit_id` (current ownership). It backfills ownership for existing occupied resources and replaces the old unique index on AT_RESOURCE visits, since historical visits now retain that status even after their resource is reused. Flyway applies this migration on backend startup; tests apply it only to disposable containers.

The existing 8:30am Eastern business-day ticket-reset trigger also clears every resource to FREE and removes current ownership. The resource reset itself does not alter old visit records; Step 10 separately closes eligible missed appointments before resources are cleared. Repeated or competing resets cannot clear resources already assigned after that opening. The first visit mutation can catch up if opening was missed while the backend was stopped. Closing or marking an old visit Left releases a resource only if that visit still owns it, so a new occupant is protected.

`GET /api/snapshot?date=YYYY-MM-DD` returns `{ businessDate, visits, resources, staff, coverage, callOuts }`. Visits include all final statuses and customer attachments; resources include occupancy and occupant metadata; coverage resolves the rotation and call-outs for that date. Omitting date selects lastCompletedBusinessDate. Invalid dates, weekends/holidays, and days that have not completed return 400. A completed day without visits has an empty visits array.

The minute schedule saves completed activity, including at 4:45pm Eastern. Capture also runs before visit/coverage mutations and before opening resets, using the same transaction advisory lock as ticket creation. This preserves the closing state even when the close schedule was missed, and prevents a later API change from rewriting an already saved snapshot. Existing data from before Step 9 is captured from the state available at first capture; exact pre-installation closing history cannot be recovered where it was never recorded. Direct database edits bypass application capture.

After hours, all five routes use a shared read-only snapshot rendering. Kiosk and Front desk show final visits, Supervisor shows final appointments and coverage, My Queue shows the selected staff's final appointments, and Resource room shows final walk-ins and resource occupancy. A persistent banner names the frozen date. No mutation controls or simulator controls are rendered. Role navigation uses staff saved in the snapshot. Each closed period loads its snapshot once; live queue, inventory, and coverage polling stop. Only business-status polling continues every five seconds so the existing live screens return automatically at opening. Status or snapshot failures never fall back to an interactive screen.

Regression tests cover rollover without visit changes, migration ownership backfill, old-visit closing after resource reuse, repeated/concurrent resets, default and explicit snapshot dates, immutable snapshots after subsequent changes, missed schedules, all five closed routes, stopped live polling, and return to interactive mode.

## Step 10: automatic FTR detection

At business-day opening, rollover runs in this order: preserve completed-day snapshots, detect missed appointments, clear resource ownership and availability, then reset ticket sequences. It uses the existing 8:30am Eastern business-day trigger and once-per-day rollover marker, including the existing catch-up path when the first mutation follows a missed opening. Holidays and weekends do not trigger FTR processing.

Eligible visits are APPOINTMENT visits with appointmentStatus SCHEDULED, businessDate earlier than the opening business date, and status other than CLOSED or LEFT. Each becomes NOT_ATTENDED_FTR and CLOSED, receives closedAt, increments version once, and records a SYSTEM event with the previous visit status and an automatic-FTR note. Customer, classification, and assignment history are retained. RESCHEDULED appointments, completed visits, walk-ins, and current/future business dates are untouched. The updates, events, and rollover marker commit together; a failure rolls everything back for retry. Previously saved snapshots continue to show the actual closing state before next-day FTR processing.

Visit detail and identify responses now include `missedAppointment`: the customer's most recent other appointment if its status is NOT_ATTENDED_FTR, otherwise null. Recency uses createdAt descending, then ID descending. The current visit is excluded so identifying a returning appointment does not hide its own prior history; subsequent walk-ins do not supersede appointment history. A newer appointment, including one already FTR_REPORTED, supersedes an older FTR. Unattached or different customers do not inherit the notice. The front-desk panel displays "Customer has a missed appointment (FTR)" with the missed ticket and business date, including when a selected visit is resumed or reloaded.

`POST /api/visits/{id}/report-ftr` takes no body and requires the current quoted If-Match version. It changes NOT_ATTENDED_FTR to FTR_REPORTED, increments version, and records an APPOINTMENTS event noting customer contact. It preserves the visit's status, closedAt, and assignments. The response is the updated visit detail with its ETag. Wrong starting appointment status returns 409; missing, malformed, and stale versions return 428, 400, and 412 respectively; an unknown visit returns 404. Rejections and event failures leave visit state and events unchanged. Reporting contact uses this endpoint; the front-desk addition is a notice, with no automatic reporting when a customer is identified.

No migration is required: the existing appointment-status enum and database check already include both FTR values. Tests cover eligibility exclusions, one-time rollover and snapshot preservation, FTR/contact event rollback, request preconditions, concurrent reports, customer-history selection, and front-desk notice visibility and reload behavior.

## Step 11: derive appointment classification and correct categories

Check-in accepts only visitType, reason (walk-ins), and appointmentCategory (appointments). The kiosk no longer asks for Initial/Follow-up, and neither kiosk nor simulator supplies appointmentType. Any appointmentType field in a check-in request, including null, returns 400. This is a breaking request-contract change for older clients.

Customer identification derives appointmentType in the same guarded database update as customer attachment: INITIAL if no other APPOINTMENT visit belongs to that customer, FOLLOWUP otherwise. History spans every business date and status and excludes the current visit. Walk-in history does not count, and walk-ins keep appointmentType null. The existing transaction lock serializes competing identifications, so two first appointments identified concurrently for the same customer produce one INITIAL and one FOLLOWUP. An event failure rolls back classification, customer attachment, and version together. Rescheduling continues to preserve the original appointment classification.

Migration V9 permits unclassified appointments while retaining the restriction that walk-ins cannot have appointmentType. Existing classification values are preserved; identifying any still-unidentified appointment recalculates its type. All existing synthetic appointment categories, including REEMPLOYMENT, CAREER_SERVICES, VETERANS, and older text labels, become MANDATORY. A new database check permits only MANDATORY and JOB_SEARCH_ASSISTANCE. Saved snapshots remain immutable historical records. Flyway applies V9 on backend startup; validation used disposable test databases.

The kiosk offers Mandatory and Job Search Assistance. Simulator category weights are MANDATORY 95 and JOB_SEARCH_ASSISTANCE 5; the obsolete appointmentTypeWeights configuration is removed. Simulator visits remain unclassified until identification. Tests verify both accepted categories, legacy-category rejection, null classification at check-in, rejected client classification, first/repeat appointment history, concurrent identification, transactional rollback, migration remapping, and all 100 weighted category draw positions (95/5) without statistical flakiness.

## Step 14: authentication infrastructure

Flyway V11 retains the existing four LSRs and one Supervising LSR and adds three Clerks, one Center Manager, and one Administrator with synthetic names. It creates `user_account` with a unique, required staff foreign key, unique username, BCrypt password hash, and creation timestamp. Every staff row present when V11 runs receives exactly one account, including any extra staff created before the migration. Later staff creation does not automatically provision accounts. Existing snapshots remain unchanged and can still contain the original five staff.

**Synthetic demo credentials: every seeded account uses password `demo1234`. These shared, documented credentials are not a real security boundary.** Usernames are `staff` followed by the staff ID. On an unmodified seed:

| Username | Display name | Role |
| --- | --- | --- |
| staff1–staff4 | Demo Staff 001–004 | LSR |
| staff5 | Demo Supervisor 001 | SUPERVISING_LSR |
| staff6–staff8 | Demo Clerk 001–003 | CLERK |
| staff9 | Demo Center Manager 001 | CENTER_MANAGER |
| staff10 | Demo Administrator 001 | ADMINISTRATOR |

`POST /api/auth/login` accepts `{ "username": "staff1", "password": "demo1234" }` and returns `{ "token": "<JWT>", "tokenType": "Bearer", "expiresIn": 28800 }`. Incorrect credentials return 401 with the same message for unknown users and wrong passwords; malformed input returns 400. Tokens contain `staffId`, `role`, and `displayName`, plus subject (username), issuer, issued-at, and expiry. They expire after eight hours, independently of the business calendar. Log in again after expiry; there is no refresh or server-side logout session.

The signing key comes only from `JWT_SECRET`. Startup rejects missing, invalid Base64, or keys shorter than 32 decoded bytes. The local startup example generates a random key; retain it securely between restarts if existing tokens should stay valid. `.env` is read by production Compose, not by a backend started directly from PowerShell. Test resources supply a separate test-only key.

Spring Security's [JWT resource-server support](https://docs.spring.io/spring-security/reference/6.5/servlet/oauth2/resource-server/jwt.html) validates HS256 signatures, issuer, and token timestamps (with its default clock-skew tolerance). Its bearer-token filter puts the JWT claims into the security context and exposes the role as `ROLE_<role>`. Authentication is stateless, with no form login, HTTP Basic, or session-cookie authentication. CSRF protection is disabled because clients explicitly send bearer headers.

Only these method/path pairs are public:

- `POST /api/visits`
- `GET /api/business-status`
- `GET /api/snapshot` (including its optional date query)
- `POST /api/auth/login`

Every other endpoint requires `Authorization: Bearer <token>` and returns 401 without a valid token. All five roles have the same access in this step; role enforcement is Step 15. Existing domain rules, such as only assigning appointments to LSRs, still apply. A supplied invalid bearer token returns 401 even on a public endpoint; omit the header for anonymous access.

Example from PowerShell with the backend running:

```powershell
$loginBody = @{ username = 'staff1'; password = 'demo1234' } | ConvertTo-Json
$login = Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/api/auth/login -ContentType 'application/json' -Body $loginBody
$authHeaders = @{ Authorization = "Bearer $($login.token)" }
Invoke-RestMethod -Uri http://127.0.0.1:8080/api/staff -Headers $authHeaders
```

Step 14 implements backend infrastructure. The current frontend has no login/token handling, so live staff screens receive 401 until that integration is added. Public check-in, business status, and snapshot APIs remain available anonymously.

Tests cover login and claims for all ten accounts and all five roles, security-context claims, anonymous access boundaries, malformed/tampered/expired tokens, wrong issuers, stateless requests, BCrypt seeds, uniqueness/foreign keys, and migration of additional preexisting staff. Existing API regressions use an authenticated test user while retaining the security filters.

## Simplest choices where the spec is silent

- Spring JDBC and parameterized SQL; Flyway owns the schema. SQL names use snake_case; Java and JSON retain the specified camelCase names.
- Database-generated numeric IDs; separate ticket sequences start at B0001 (walk-ins) and A0001 (appointments), grow beyond four digits, and reset at business-day opening. PostgreSQL sequences can have gaps after failed transactions.
- Creation writes an event with fromStatus null, toStatus CHECKED_IN, and actor KIOSK. Visit version starts at 0.
- UTC timestamp storage; the front desk displays local browser time.
- Customer names are Demo Customer 001 through Demo Customer 050. Languages cycle EN/ES/OTHER; every fifth record has veteran=true. These are synthetic values.
- All resources start FREE and use labels Phone 01, Computer 01, and Station 01, numbered within each type.
- Plain React with JavaScript, system fonts, npm, and a Vite API proxy. The root URL shows the kiosk.
- Migration V3 introduced appointment fields and statuses; V4 adds the independent appointment ticket sequence. Prior migrations and existing visit records are preserved. Migration V9 replaces the legacy categories with the two codes above. Frozen snapshots retain their previously captured values.
- A selected visit is retained in the `/front-desk?visit=...` URL so identification can be resumed after a refresh. Identifying changes the row label from Needs ID to Ready to route; the visit remains in the queue and can be resumed.
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
