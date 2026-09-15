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
- My Queue automatically shows appointments for the logged-in LSR, with navigation to Resource room. Center Managers can select any LSR through a Staff member picker; their selection is retained in `/my-queue?staff=...`. Close uses the existing endpoint; only managers see Reschedule in My Queue.
- Both staff screens refresh every five seconds and provide manual refresh. Mutations use the current quoted version and trigger a reload; another action is blocked until the reload succeeds. `/appointments` redirects to `/supervisor`.

- Resource room groups Phones, Computers, and Stations, showing status and the current ticket/customer for occupied resources. Close and Left release resources atomically.
- WAITING_RESOURCE entries show the needed type and elapsed waiting time. Assign is disabled when no matching resource is free; Left remains available.
- Occupied visits are flagged after more than 45 minutes at the resource. All queues refresh every five seconds.

Steps 1–17 are implemented. Production Docker images and Compose configuration are included for the synthetic portfolio deployment. The backend requires JWT authentication and enforces roles for staff APIs; staff log in through /login and receive role-based navigation.

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

4. Open http://127.0.0.1:5173/kiosk for public check-in. For staff work, open http://127.0.0.1:5173/login and log in with a seeded account and password `demo1234`. Use `staff9` (Center Manager) to exercise the full workflow: Front desk to identify and route, Supervisor to assign an LSR, My Queue to select that LSR and close an appointment, or Resource room for walk-ins. Use `staff1` to verify the LSR experience: My Queue opens automatically with that LSR's appointments. The kiosk has no staff navigation or simulator controls.

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

The business calendar uses America/New_York, Monday through Friday, 8:30am inclusive to 4:45pm exclusive. Step 17 stores holidays in PostgreSQL. Migration V12 preserves the former 2026–2027 list; administrators can maintain future dates through the Holidays tab, including dates beyond 2027. The requested holidays follow the [2026](https://www.cs.ny.gov/attendance_leave/2026_legal_holidays.cfm) and [2027](https://www.cs.ny.gov/attendance_leave/2027_legal_holidays.cfm) observed dates: Saturday holidays stay Saturday, Sunday holidays are observed Monday. The requested day after Thanksgiving is also closed.

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

After hours, all five routes use a shared read-only snapshot rendering. Kiosk and Front desk show final visits, Supervisor shows final appointments and coverage, My Queue shows the selected staff's final appointments, and Resource room shows final walk-ins and resource occupancy. A persistent banner names the frozen date. No mutation controls or simulator controls are rendered. Staff navigation uses the logged-in identity. The manager queue picker uses staff saved in the snapshot. Each closed period loads its snapshot once; live queue, inventory, and coverage polling stop. Only business-status polling continues every five seconds so the existing live screens return automatically at opening. Status or snapshot failures never fall back to an interactive screen.

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

`POST /api/auth/login` accepts `{ "username": "staff1", "password": "demo1234" }` and returns `{ "token": "<JWT>", "tokenType": "Bearer", "expiresIn": 28800, "staffId": 1, "role": "LSR", "displayName": "Demo Staff 001" }`. Incorrect credentials return 401 with the same message for unknown users and wrong passwords; malformed input returns 400. Tokens contain `staffId`, `role`, and `displayName`, plus subject (username), issuer, issued-at, and expiry. They expire after eight hours, independently of the business calendar. Log in again after expiry; there is no refresh or server-side logout session.

The signing key comes only from `JWT_SECRET`. Startup rejects missing, invalid Base64, or keys shorter than 32 decoded bytes. The local startup example generates a random key; retain it securely between restarts if existing tokens should stay valid. `.env` is read by production Compose, not by a backend started directly from PowerShell. Test resources supply a separate test-only key.

Spring Security's [JWT resource-server support](https://docs.spring.io/spring-security/reference/6.5/servlet/oauth2/resource-server/jwt.html) validates HS256 signatures, issuer, and token timestamps (with its default clock-skew tolerance). Its bearer-token filter puts the JWT claims into the security context and exposes the role as `ROLE_<role>`. Authentication is stateless, with no form login, HTTP Basic, or session-cookie authentication. CSRF protection is disabled because clients explicitly send bearer headers.

Only these method/path pairs are public:

- `POST /api/visits`
- `GET /api/business-status`
- `GET /api/snapshot` (including its optional date query)
- `POST /api/auth/login`

Every other endpoint requires `Authorization: Bearer <token>` and returns 401 without a valid token. Step 14 established authentication; Step 15 adds the role restrictions below. Existing domain rules, such as only assigning appointments to LSRs, still apply. A supplied invalid bearer token returns 401 even on a public endpoint; omit the header for anonymous access.

Example from PowerShell with the backend running:

```powershell
$loginBody = @{ username = 'staff1'; password = 'demo1234' } | ConvertTo-Json
$login = Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/api/auth/login -ContentType 'application/json' -Body $loginBody
$authHeaders = @{ Authorization = "Bearer $($login.token)" }
Invoke-RestMethod -Uri http://127.0.0.1:8080/api/staff -Headers $authHeaders
```

Step 14 establishes backend authentication. Step 16 adds frontend login and sends bearer tokens for staff requests. Public check-in, business status, and snapshot APIs remain available anonymously.

Tests cover login and claims for all ten accounts and all five roles, security-context claims, anonymous access boundaries, malformed/tampered/expired tokens, wrong issuers, stateless requests, BCrypt seeds, uniqueness/foreign keys, and migration of additional preexisting staff. Existing API regressions now use a signed Center Manager token while retaining the security filters.

## Step 15: role-based authorization enforcement

The existing JWT `role` and `staffId` claims determine API permissions. No migration or new environment variable is needed. The following POST endpoints use Spring Security's [request authorization rules](https://docs.spring.io/spring-security/reference/6.5/servlet/authorization/authorize-http-requests.html):

| Endpoint | Allowed roles |
| --- | --- |
| `/api/visits/{id}/identify`, `/api/visits/{id}/route` | CLERK, CENTER_MANAGER |
| `/api/visits/{id}/assign` | LSR, CENTER_MANAGER |
| `/api/visits/{id}/close`, `/api/visits/{id}/left` | LSR, CENTER_MANAGER; appointment ownership rule below |
| `/api/visits/{id}/assign-staff`, `/api/visits/{id}/report-ftr`, `/api/visits/{id}/reschedule` | SUPERVISING_LSR, CENTER_MANAGER |
| `/api/call-outs` | SUPERVISING_LSR, CENTER_MANAGER |

An authenticated caller with a disallowed role receives 403 with an `application/problem+json` body whose `detail` is `Your role is not permitted to perform this action.` Administrator has no implicit override. Missing or invalid authentication still returns 401. Other endpoints retain Step 14's access rules, including the four public method/path pairs.

For close and left, an LSR can act on an APPOINTMENT only when its current `assignedStaffId` equals the JWT `staffId`. Other LSRs' appointments and unassigned appointments return 403 with `LSRs may only close or mark left appointments assigned to themselves.` Center Managers can act on any appointment, and walk-ins have no ownership requirement. Ownership is checked inside the mutation transaction under the existing business-day lock, before version/status validation; reassignment revokes the former owner's access even with a previously valid If-Match value. Rejections roll back transaction changes. Existing status and If-Match rules still apply to authorized callers.

`GET /api/visits?status=WAITING_STAFF` filters an LSR's results in SQL to their JWT staff ID. This applies to both the default business date and an explicit `businessDate`; query parameters such as `staffId`, `assignedStaffId`, or `role` cannot override the scope. Other roles see all matching visits, including unassigned appointments. Queries without that explicit status retain their previous behavior. `GET /api/rotation`, `/api/call-outs`, `/api/staff`, and `/api/resources` remain available to all authenticated roles with no added scoping; rotation and call-out reads still require their `date` parameter.

The authorization suite logs in through the real API and tests all 45 restricted endpoint/role combinations, unchanged visits after denial, own/other/unassigned appointment access, walk-in access, manager access, stale ownership and concurrent reassignment, query-parameter spoofing, business-date filtering, and shared reads for all five roles. The broader workflow tests use a signed Center Manager JWT, including concurrent requests. Step 16 adds frontend login and role-based navigation on top of these server-enforced rules.

## Step 16: frontend login and role-based navigation

`/login` accepts username/password and calls the public login API. The response now includes `staffId`, `role`, and `displayName` directly alongside the token, token type, and expiry. The frontend does not decode JWTs. Failed login stays on `/login` with an error; a successful login stores only token and identity in tab-scoped `sessionStorage` and opens the role's landing page. Reloads retain the session within that tab. The shared demo password remains **`demo1234`** for every seeded account.

| Role | Demo account | Landing | Navigation |
| --- | --- | --- | --- |
| CLERK | staff6–staff8 | Front desk | Front desk |
| LSR | staff1–staff4 | My Queue | My Queue, Resource room |
| SUPERVISING_LSR | staff5 | Supervisor board | Supervisor |
| CENTER_MANAGER | staff9 | Supervisor board | Supervisor, Front desk, My Queue, Resource room |
| ADMINISTRATOR | staff10 | Administrator console | Staff, Rotation, Holidays tabs |

The View as dropdown is removed. Staff pages show the logged-in display name and role, permitted navigation links, and Logout. Logout clears stored authentication and replaces the current route with `/login`; browser Back cannot restore protected content. Direct navigation to a disallowed or unknown staff URL redirects to the role's landing before mounting that screen. `/appointments` also resolves to the caller's landing. Without a valid stored session, staff routes redirect to login. Administrator sees the Staff, Rotation, and Holidays console without operational data requests.

The API client reads the current stored token for each authenticated request and adds `Authorization: Bearer <token>` while preserving other headers, including If-Match. A 401 clears authentication and redirects to `/login?expired=1`, displaying “Session expired, please log in again.” This applies to reads, mutations, and background requests. A login 401 instead displays the invalid-credentials error on the login page. Delayed 401 responses from a previous session cannot clear a newer login or overwrite an explicit logout. A 403 displays the server's permission error and keeps the session.

LSRs automatically load their own My Queue using the logged-in staff ID. They have no staff picker, cannot override identity using `?staff=...`, and do not see Reschedule. Center Managers have a Staff member picker for LSR queues, retain selection in the URL, and can close or reschedule those appointments. After hours, the same identity and navigation rules apply to snapshots, and managers can select staff from the frozen snapshot.

`/` and `/kiosk` remain fully public, with no staff navigation or simulator controls. The header always offers Staff login when signed out, or the current name, role, and Logout when signed in, including during closed hours. Public check-in, business-status, snapshot, and login calls omit the stored token. Login and the admin console do not depend on business hours. Operational staff screens retain the existing closed-hours behavior and simulator controls. The backend remains the authorization boundary; changing browser state does not change server permissions.

Tests cover login landings and links for all five roles, invalid credentials and incomplete responses, logout and route guards, forged staff selection, manager selection and reload, background and mutation 401s, delayed-response races, 403 handling, public kiosk isolation, and retained queue/snapshot behavior. Backend tests verify that every account's login identity fields match the signed JWT claims.

## Step 16b: persistent login access and closed-hours exemptions

Every screen uses the same header. When signed out, a small **Staff login** link opens `/login`; it is visible on the kiosk, closed-hours snapshots, and while business status or snapshot data is loading or unavailable. When signed in, that area instead shows the current display name, role, and **Logout**. These controls remain functional during closed hours. The kiosk remains public and does not show operational navigation or simulator controls.

**Closed-hours rule for Step 17 and later:** staff management is not a daily-operations screen and must work identically whether the center is open or closed. `/login` and the admin console must stay outside `BusinessScreen` and its business-hours/snapshot checks. They must not depend on business-status or snapshot requests. Only **Kiosk, Front desk, Supervisor, My Queue, and Resource room** freeze during closed hours. Authentication and role authorization still apply to admin functions.

Tests verify login-link access from open and closed kiosks, identity display on all five closed operational screens, closed-hours logout, and successful login into the admin console without consulting business hours.

## Step 17: administrator console

Log in as **staff10 / demo1234** and open `/admin`. The console has **Staff**, **Rotation**, and **Holidays** sections and remains fully functional after hours. It does not request business status or snapshots to render. Management writes preserve completed-day snapshots transactionally on the server; this does not restrict when an administrator can make changes.

Flyway **V12** adds `staff.active BOOLEAN NOT NULL DEFAULT TRUE` and `holiday(id, date UNIQUE, label)`. Existing staff remain active. The migration seeds all **25** former 2026–2027 holiday dates exactly, including the Saturday dates, July 4 and observed July 5 in 2027, and the day after Thanksgiving. The calendar reads the table for each determination; there is no static fallback or holiday cache. Weekend and opening-hour rules remain unchanged. Add actual closure dates explicitly: the console does not automatically create observed-day entries.

All `/api/admin/**` endpoints require **ADMINISTRATOR**. Other authenticated roles receive 403 with the existing clear role-denial message; unauthenticated calls receive 401. Password hashes never appear in management responses.

| Method | Path | Request / result |
| --- | --- | --- |
| GET | `/api/admin/staff` | All staff, active and inactive: `{ id, displayName, role, active, accountId, username }`; account fields are null when absent |
| POST | `/api/admin/staff` | `{ "displayName": "Demo New LSR", "role": "LSR" }`; returns 201 with the new staff row; active defaults to true |
| PUT | `/api/admin/staff/{id}` | `{ "displayName": "Demo New LSR", "role": "LSR", "active": false }`; returns the updated staff row; omitted active preserves its current value |
| GET | `/api/admin/rotation` | Active LSR base assignments: `{ staffId, displayName, dayOfWeek, dutyType }` |
| PUT | `/api/admin/rotation` | `{ "assignments": [{ "staffId": 1, "dayOfWeek": 1, "dutyType": "RESOURCE_ROOM" }] }`; atomically updates supplied cells and returns the active grid |
| GET | `/api/admin/holidays` | All `{ id, date, label }` rows ordered by date; the UI initially shows upcoming dates and offers Include past holidays |
| POST | `/api/admin/holidays` | `{ "date": "2028-01-03", "label": "Demo closure" }`; returns 201 with the holiday |
| DELETE | `/api/admin/holidays/{id}` | Removes that holiday; returns 204 with no response body |
| POST | `/api/admin/accounts` | `{ "staffId": 11, "username": "new.staff", "password": "initial123" }`; creates an account for an active staff member and returns 201 with account metadata |
| PUT | `/api/admin/accounts/{id}/reset-password` | `{ "password": "replacement123" }`; BCrypt-hashes the replacement and returns account metadata |

Display names and holiday labels must be nonblank and at most 100 characters. Roles use the existing five codes. Usernames are case-sensitive, start with an ASCII letter/digit, and contain only letters, digits, dots, underscores, or hyphens (maximum 100 characters). New passwords require 8–72 characters and no more than 72 UTF-8 bytes. Duplicate usernames, a second account for the same staff member, or a duplicate holiday date return 409. Invalid input returns 400; missing target records return 404. Staff deletion is not supported.

**Deactivation preserves history.** Inactive staff are omitted from live `/api/staff`, operational rotation, and the editable admin rotation grid. They cannot receive new visit assignments or call-outs. Existing visits, events, account rows, and saved snapshots retain their references; waiting appointments assigned to inactive staff remain visible under Other assignments on the Supervisor board for reassignment. Admin Staff still lists inactive members and offers Reactivate. Deactivation and assignment use the same transaction lock. The final active administrator with a login account cannot be disabled or demoted.

Inactive staff cannot log in. JWT validation also checks current staff activity and role, so deactivation or role changes invalidate old-role access on the next authenticated request. Reactivation restores login eligibility. Password resets take effect for the next login immediately; old passwords fail, but already-issued JWTs keep their normal lifetime unless activity or role changes. Name changes appear in login identity on the next login.

Creating a staff member does not automatically create a login. Use Create account in that row, or Reset password for an existing account. New LSRs—and staff changed to the LSR role—receive any missing Monday–Sunday rotation rows, defaulting to APPOINTMENTS. The grid uses ISO weekdays 1–7 and APPOINTMENTS / RESOURCE_ROOM duties. Inactive/non-LSR targets and duplicate staff/day entries are rejected; an invalid batch rolls back every supplied cell. Existing call-outs still override the base duty for their date. Old rotation rows are retained for possible reactivation.

Holiday additions/removals affect subsequent calendar checks immediately without restarting. Existing visit business dates and frozen snapshots are not rewritten. Saved snapshots remain readable even if their date is later marked as a holiday. New holiday dates and labels beyond the migrated years are maintained by the administrator.

Tests cover all 50 admin endpoint/role combinations, the exact migrated dates, holiday-driven open/closed changes, account creation and password reset login, deactivation/reactivation and preserved history, assignment exclusion, role-token invalidation, rotation initialization and transactional rollback, validation/conflicts, retained snapshots, and management outside business hours. Frontend tests exercise all three sections, account actions, form errors/retry, 204 deletion handling, and Supervisor visibility of inactive staff assignments.

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
