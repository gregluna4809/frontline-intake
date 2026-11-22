# Frontline v1 inventory

Audited source: `cb1850d` (main). Verification: November 21, 2026, America/New_York. This inventory describes committed code, not a running deployment. Paths below are relative to the repository root. Backend Java packages live under `backend/src/main/java/com/frontline`; migrations under `backend/src/main/resources/db/migration`.

## 1. Entities

There are **no JPA entities** and no JPA dependency or persistence annotations. The backend uses Java 21, Spring Boot 3.5.16, Spring JDBC `JdbcClient`, PostgreSQL, and Flyway. The following are the actual persisted models and schema after V12. Nullable refers to SQL nullability, not whether a Java reference can technically hold null. Camel-case Java names map to snake-case SQL columns.

| Record / table | Fields: Java type / SQL type / nullable |
| --- | --- |
| `Customer` / `customer` | `id`: long / BIGINT identity / no; `displayName`: String / VARCHAR(100) / no; `language`: Language / VARCHAR(5) / no; `veteran`: boolean / BOOLEAN / no |
| `Resource` / `resource` | `id`: long / BIGINT identity / no; `type`: Type / VARCHAR(10) / no; `label`: String / VARCHAR(50), unique / no; `status`: Status / VARCHAR(10) / no; SQL-only `active_visit_id`: BIGINT / yes |
| `Staff` / `staff` | `id`: long / BIGINT identity / no; `displayName`: String / VARCHAR(100) / no; `role`: Role / VARCHAR(30) / no; `active`: boolean / BOOLEAN, default true / no |
| `Visit` / `visit` | `id`: long / BIGINT identity / no; `ticket`: String / VARCHAR(32) / no; `visitType`: VisitType / VARCHAR(20) / no; `reason`: Reason / VARCHAR(30) / yes; `appointmentCategory`: String / VARCHAR(100) / yes; `appointmentType`: AppointmentType / TEXT / yes; `appointmentStatus`: AppointmentStatus / TEXT / yes; `assignedStaffId`: Long / BIGINT / yes; `status`: Status / VARCHAR(20) / no; `customerId`: Long / BIGINT / yes; `resourceId`: Long / BIGINT / yes; `createdAt`: Instant / TIMESTAMPTZ / no; `closedAt`: Instant / TIMESTAMPTZ / yes; `version`: long / BIGINT, default 0 / no; `previousVisitId`: Long / BIGINT, unique / yes; `businessDate`: LocalDate / DATE / no |
| `VisitEvent` / `visit_event` | `id`: long / BIGINT identity / no; `visitId`: long / BIGINT / no; `fromStatus`: Visit.Status / VARCHAR(20) / yes; `toStatus`: Visit.Status / VARCHAR(20) / no; `actor`: String / VARCHAR(30) / no; `createdAt`: Instant / TIMESTAMPTZ / no; `note`: String / TEXT / yes |
| `CallOut` / `call_out` | `id`: long / BIGINT identity / no; `staffId`: long / BIGINT / no; `date`: LocalDate / DATE / no; `reason`: String / TEXT / yes; `createdAt`: Instant / TIMESTAMPTZ, default current timestamp / no |
| `StaffDuty` projection / `staff_rotation` | Stored: `staff_id`: BIGINT / no; `day_of_week`: INTEGER (1–7) / no; `duty_type`: VARCHAR(20) / no. Composite primary key `(staff_id, day_of_week)`. Projection: `staffId` long, `displayName` String (joined staff), `dutyType` DutyType. `AdminRepository.RotationRow` also exposes `dayOfWeek` int. |
| `UserAccountRepository.Account`, `AdminRepository.Account` projections / `user_account` | Stored: `id`: BIGINT identity / no; `staff_id`: BIGINT, unique / no; `username`: VARCHAR(100), unique / no; `password_hash`: VARCHAR(60) / no; `created_at`: TIMESTAMPTZ, default now / no. Authentication projection has `username` String, `passwordHash` String, `staff` Staff; admin projection has `id` long, `staffId` long, `username` String, `createdAt` Instant. |
| `AdminRepository.Holiday` / `holiday` | `id`: long / BIGINT identity / no; `date`: LocalDate / DATE, unique / no; `label`: String / VARCHAR(100) / no |
| No entity record / `business_day_snapshot` | `business_date`: DATE primary key / no; `captured_at`: TIMESTAMPTZ, default current timestamp / no; `payload`: JSONB / no. Read as Jackson `JsonNode`. |
| No entity record / `business_day_rollover` | `business_date`: DATE primary key / no |

Relationships are SQL foreign keys, not ORM associations: visits optionally reference customer, resource, assigned staff, and a previous visit. A previous visit has at most one replacement, and cannot reference itself. Events belong to one visit. A resource optionally owns one active visit; a partial unique index prevents duplicate non-null active visit IDs. Staff have rotation rows, call-outs, and at most one account. Call-outs are unique per staff/date. Snapshots and rollover markers have no foreign keys. Tickets are unique per `(business_date, ticket)`. Flyway also maintains its own schema-history table.

Walk-ins require a reason and null category; appointments require a nonblank category. SQL permits nullable appointment classification; walk-ins cannot have an appointment type. The service sets the initial appointment status and derives classification at identification.

Enums in `model/*.java`:

| Enum | Values |
| --- | --- |
| `Customer.Language` | EN, ES, OTHER |
| `Resource.Type` | PHONE, COMPUTER, STATION |
| `Resource.Status` | FREE, OCCUPIED |
| `Staff.Role` | LSR, SUPERVISING_LSR, CLERK, CENTER_MANAGER, ADMINISTRATOR |
| `StaffDuty.DutyType` | APPOINTMENTS, RESOURCE_ROOM |
| `Visit.VisitType` | WALK_IN, APPOINTMENT |
| `Visit.AppointmentType` | INITIAL, FOLLOWUP |
| `Visit.AppointmentStatus` | SCHEDULED, RESCHEDULED, NOT_ATTENDED_FTR, FTR_REPORTED |
| `Visit.AppointmentCategory` | MANDATORY, JOB_SEARCH_ASSISTANCE (the Visit record stores a String; request validation uses the enum) |
| `Visit.Reason` | PHONE_ACCESS, COMPUTER_ACCESS, DOCUMENT_SERVICES, BENEFITS_ASSISTANCE, JOB_SEARCH, OTHER |
| `Visit.Status` | CHECKED_IN, IDENTIFIED, AT_RESOURCE, WAITING_RESOURCE, WAITING_STAFF, CLOSED, LEFT |

## 2. REST endpoints

These are all application controller mappings; query parameters are shown where relevant.

| HTTP method | Path | Controller.method | Purpose |
| --- | --- | --- | --- |
| POST | `/api/auth/login` | AuthController.login | Validate credentials and issue an eight-hour bearer JWT. |
| POST | `/api/visits` | VisitController.create | Check in a walk-in or appointment and issue a ticket. |
| GET | `/api/visits?status=&businessDate=` | VisitController.list | List date-scoped visits, optionally by status; WAITING_STAFF is scoped to an LSR caller. |
| GET | `/api/visits/{id}` | VisitController.detail | Return visit, events, customer, resource, and missed-appointment context. |
| POST | `/api/visits/{id}/identify` | VisitController.identify | Attach a customer and derive appointment classification. |
| POST | `/api/visits/{id}/route` | VisitController.route | Route to a matching resource, resource wait, or staff wait. |
| POST | `/api/visits/{id}/assign` | VisitController.assign | Assign a free matching resource to a waiting visit. |
| POST | `/api/visits/{id}/assign-staff` | VisitController.assignStaff | Assign or reassign a waiting appointment to an active LSR. |
| POST | `/api/visits/{id}/close` | VisitController.close | Complete a resource or staff visit and release owned resources. |
| POST | `/api/visits/{id}/left` | VisitController.left | Mark an active visit left and release owned resources. |
| POST | `/api/visits/{id}/reschedule` | VisitController.reschedule | Close a waiting appointment and create its linked replacement. |
| POST | `/api/visits/{id}/report-ftr` | VisitController.reportFtr | Record contact against an appointment marked NOT_ATTENDED_FTR. |
| GET | `/api/customers?q=` | CustomerController.search | Search synthetic customer names. |
| GET | `/api/resources?businessDate=` | ResourceController.inventory | Read inventory and date-scoped current occupant metadata. |
| GET | `/api/staff` | StaffController.list | List active staff. |
| GET | `/api/rotation?date=` | CoverageController.rotation | Read effective LSR duties for a required date. |
| GET | `/api/call-outs?date=` | CoverageController.callOuts | Read call-outs for a required date. |
| POST | `/api/call-outs` | CoverageController.callOut | Upsert a staff/date call-out. |
| GET | `/api/business-status` | BusinessController.status | Return open state, effective date, and last completed date. |
| GET | `/api/snapshot?date=` | SnapshotController.snapshot | Read or lazily capture a completed-day snapshot. |
| POST | `/api/simulator/start` | SimulatorController.start | Start arrival generation. |
| POST | `/api/simulator/stop` | SimulatorController.stop | Stop arrival generation. |
| GET | `/api/simulator/status` | SimulatorController.status | Read running state, rate, and count since start. |
| GET | `/api/admin/staff` | AdminController.staff | List staff with account metadata, including inactive staff. |
| POST | `/api/admin/staff` | AdminController.createStaff | Create staff and initialize LSR rotation when applicable. |
| PUT | `/api/admin/staff/{id}` | AdminController.editStaff | Edit name, role, and active state. |
| GET | `/api/admin/rotation` | AdminController.rotation | Read active LSR weekly rotation cells. |
| PUT | `/api/admin/rotation` | AdminController.editRotation | Atomically update supplied rotation cells. |
| GET | `/api/admin/holidays` | AdminController.holidays | List stored closure dates. |
| POST | `/api/admin/holidays` | AdminController.addHoliday | Add a closure date and label. |
| DELETE | `/api/admin/holidays/{id}` | AdminController.removeHoliday | Remove a closure date. |
| POST | `/api/admin/accounts` | AdminController.createAccount | Create a BCrypt-backed staff login. |
| PUT | `/api/admin/accounts/{id}/reset-password` | AdminController.resetPassword | Replace an account password. |
| GET | `/api/reports/summary?from=&to=` | ReportController.summary | Aggregate business-day metrics across a required range of at most 90 calendar days inclusive. |

Visit mutations by ID require a quoted numeric `If-Match` version; missing is 428, malformed is 400, stale is 412. Read/create responses expose ETags where implemented. Security rules in `auth/SecurityConfiguration.java`: anonymous check-in, login, business status, and snapshot; admin endpoints require ADMINISTRATOR; reports require CENTER_MANAGER or ADMINISTRATOR; identify/route require CLERK or CENTER_MANAGER; assign/close/left require LSR or CENTER_MANAGER; assign-staff/report-ftr/reschedule/call-out require SUPERVISING_LSR or CENTER_MANAGER. Other mappings require authentication. LSR appointment close/left requires ownership. There is no controller logout endpoint.

## 3. Frontend

React 19.2.8, JavaScript, Vite 8.2.2, npm. `App.jsx` selects screens from browser pathname and `popstate`; there is no React Router dependency. Authentication is stored in sessionStorage. Unauthorized paths redirect to login or the role landing page.

| Path / screen | Component | Endpoints called | Live updates |
| --- | --- | --- | --- |
| `/`, `/kiosk` | `App.jsx` / Kiosk | POST `/api/visits`; shared business view below | No visit polling; business-status polling below. |
| `/login` | Login | POST `/api/auth/login` | None. |
| `/front-desk`, optional `?visit={id}` | FrontDesk | GET `/api/visits?status=CHECKED_IN`, `?status=IDENTIFIED`, `/api/visits/{id}`, `/api/customers?q=`; POST visit `identify`, `route` | Queue polls every 5 seconds; selection reloads on demand; debounced customer search. |
| `/supervisor` | Supervisor, CoveragePanel, useAppointmentQueue | GET `/api/business-status`, `/api/visits?status=WAITING_STAFF&businessDate=`, `/api/staff`, `/api/rotation?date=`; POST visit `assign-staff`, `reschedule`; POST `/api/call-outs` | Polls every 5 seconds and refreshes after mutations; wait display updates each second. |
| `/my-queue`, manager optional `?staff={id}` | MyQueue, StaffQueueSelector, useAppointmentQueue | GET `/api/visits?status=WAITING_STAFF`, `/api/staff`; POST visit `close`, manager-only `reschedule` | Polls every 5 seconds and refreshes after mutations; wait display updates each second. |
| `/resource-room` | ResourceRoom | GET `/api/resources`, `/api/visits?status=WAITING_RESOURCE`; POST visit `assign`, `close`, `left` | Polls every 5 seconds and refreshes after mutations; elapsed display updates each second. |
| `/admin` / Staff tab | AdminConsole, StaffAdmin, useAdminData | GET/POST `/api/admin/staff`; PUT `/api/admin/staff/{id}`; POST `/api/admin/accounts`; PUT `/api/admin/accounts/{id}/reset-password` | None; load on mount, manual refresh, reload after saves. |
| `/admin` / Rotation tab | RotationAdmin, useAdminData | GET/PUT `/api/admin/rotation` | None; load on mount, manual refresh, reload after saves. |
| `/admin` / Holidays tab | HolidaysAdmin, useAdminData | GET/POST `/api/admin/holidays`; DELETE `/api/admin/holidays/{id}` | None; load on mount, manual refresh, reload after saves. |
| Operational paths while closed | BusinessScreen, useBusinessView, SnapshotView | GET `/api/business-status`; GET `/api/snapshot?date=` | Status polls every 5 seconds; snapshot loads once per closed date/state, then renders frozen data. |
| Shared open authenticated operational screens | SimulatorControl | GET `/api/simulator/status`, `/api/business-status`; POST simulator `start`/`stop` | Polls every 5 seconds. |

Every operational screen also uses the shared business-status polling while open. Login and admin remain outside the closed-hours wrapper. SnapshotView has no requests or mutation controls. No SSE or WebSocket code exists. There is no reports screen. `/appointments` has no screen in current App; an authenticated user reaching it follows the generic role-landing redirect. Admin tabs do not have separate URL routes.

Role paths: CLERK front desk; LSR My Queue and Resource Room; SUPERVISING_LSR supervisor; CENTER_MANAGER all four staff operational screens; ADMINISTRATOR admin. Kiosk and login are public, though an authenticated login visit redirects to the role landing page. LSR My Queue identity comes from login, and any `staff` query is removed.

## 4. Flyway migrations

Exactly 12 SQL migration files exist.

| Version | File name | Summary |
| --- | --- | --- |
| 1 | V1__create_schema.sql | Create customer, resource, visit, visit_event, ticket sequence, constraints and indexes. |
| 2 | V2__seed_synthetic_data.sql | Seed 50 customers, six phones, eight computers, two stations. |
| 3 | V3__identify_and_route.sql | Add appointment intake/category and WAITING_STAFF support. |
| 4 | V4__appointment_tickets.sql | Add an independent appointment ticket sequence. |
| 5 | V5__staff_and_appointment_classification.sql | Create/seed staff, add appointment type/status and assignment, add event notes. |
| 6 | V6__staff_rotation_call_outs_and_rescheduling.sql | Add weekly rotation, dated call-outs, and previous-visit linkage. |
| 7 | V7__visit_business_dates.sql | Backfill Eastern business dates and make ticket uniqueness date-scoped. |
| 8 | V8__business_day_snapshots.sql | Add snapshot and rollover tables plus current resource ownership. |
| 9 | V9__derive_appointment_classification.sql | Allow unclassified appointments and constrain/remap categories to current values. |
| 10 | V10__seed_completed_business_day.sql | Seed 60 historical visits/events and a September 11, 2026 snapshot. |
| 11 | V11__authentication_infrastructure.sql | Extend staff roles, seed five more staff, create and seed user accounts. |
| 12 | V12__administrator_management.sql | Add staff active flag and seed the holiday table. |

## 5. Simulator

Implementation: `simulator/TrafficSimulator.java`, `SimulatorProperties.java`, `SimulatorController.java`. Configuration lives in `backend/src/main/resources/application.yaml`: `enabled=false`, `arrivalsPerMinute=4`; weights PHONE_ACCESS 70, APPOINTMENT 20, COMPUTER_ACCESS 3, JOB_SEARCH 2, DOCUMENT_SERVICES 2, BENEFITS_ASSISTANCE 2, OTHER 1; appointment category weights MANDATORY 95 and JOB_SEARCH_ASSISTANCE 5. `backend/src/test/resources/application.properties` disables business scheduling for tests. There are no dedicated simulator config files or named day profiles in the checked-in source.

`tick()` runs after an initial 60 seconds and every 60 seconds after completion. When running, it makes four independent weighted draws at the default rate, calls `VisitService.checkIn`, and records actor SIMULATOR. It generates CHECKED_IN visits, ticket numbers, appointment categories and SCHEDULED appointment status. Appointment type starts null. It does not generate customer identification, staff assignment, resource occupation/release, completed service, or call-outs. Arrival failures are logged individually; successful arrivals increment the in-memory count. Restarting a stopped simulator resets that count.

`BusinessSchedules.reconcileSimulator()` runs at second zero each minute in America/New_York, freezes completed days, starts when open, and stops when closed. Thus the next reconciliation can override a manual stop/start. `business.scheduling.enabled=false` disables these calendar tasks; `simulator.enabled` controls initial running state only. No distinct daily traffic patterns are implemented. V10's one-time historical data generation is separate from this live simulator.

## 6. Resources

`resource` has type, label, status, and nullable `active_visit_id`; `visit.resource_id` retains the historical resource reference. V2 seeds **6 PHONE**, **8 COMPUTER**, **2 STATION**, all FREE. No fax type/table/count exists. Document Services, Benefits Assistance, and Other use STATION; Phone Access uses PHONE; Computer Access and Job Search use COMPUTER. Appointments require no physical resource.

`VisitService.route()` calls `ResourceRepository.assignLowestFree()` and `setOccupant()`. The repository locks matching resource rows in label/id order and selects the lowest FREE label, setting OCCUPIED and ownership transactionally. Exhaustion sets visit status WAITING_RESOURCE. `VisitService.assign()` explicitly retries a waiting visit; freeing a resource does not automatically promote the waiting queue.

`VisitService.finish()` (close/left) calls `ResourceRepository.release()`, which frees only when `active_visit_id` still matches that visit. Historical close/left cannot free a new occupant. Opening rollover clears every resource's status/ownership once per business date. FREE/OCCUPIED are resource statuses; WAITING_RESOURCE/AT_RESOURCE are visit statuses. Resource Room shows type groups, occupants, waiting visits, actions, and a 45-minute elapsed threshold. Date-filtered inventory is current availability with scoped occupant metadata, not historical inventory; saved snapshots provide frozen occupancy.

## 7. Staff, roles, rotation, call-outs

`staff` stores display name, role, active flag. V5 seeds four LSRs and one supervising LSR; V11 adds three clerks, one center manager, and one administrator: **10 seeded staff total**, all active after V12. One seeded login account exists per staff row. Roles are LSR, SUPERVISING_LSR, CLERK, CENTER_MANAGER, ADMINISTRATOR. Active filtering applies to operational staff, rotation, assignment, and call-out eligibility. Administrator management retains inactive staff/history and protects the final active administrator with an account.

V6 creates seven `staff_rotation` rows per seeded LSR, ISO Monday=1 through Sunday=7. Staff in ID order receive one RESOURCE_ROOM day each, Monday through Thursday respectively; every other day is APPOINTMENTS, including weekends. This is a recurring weekly template, evaluated for any requested date; there is no generated dated roster or finite look-ahead horizon. New staff created/changed to LSR get missing seven-day rows defaulted to APPOINTMENTS by `AdminRepository.ensureRotation()`. Administrators edit the base weekly cells atomically.

`CoverageService.callOut()` requires a date not before the current Eastern calendar date and an active LSR, freezes completed days, and upserts `(staff_id,date)` with an optional trimmed reason. A call-out **overrides that LSR's effective duty to RESOURCE_ROOM**, rather than representing absence. Rotation reads combine base rows and dated call-outs. It neither closes nor reassigns appointments. Supervisor flags assigned waiting appointments whose LSR now has Resource Room duty as orphaned; reassignment/rescheduling is manual. UI call-outs are restricted to the displayed current calendar date; API supports future dates. There is no delete/cancel call-out endpoint.

## 8. Appointments

Appointments are visits, with no separate appointment table or scheduled-time field. Intake categories: MANDATORY and JOB_SEARCH_ASSISTANCE. Type: INITIAL or FOLLOWUP, derived during identification from whether any other appointment exists for that customer; check-in starts unclassified. Appointment statuses: SCHEDULED, RESCHEDULED, NOT_ATTENDED_FTR, FTR_REPORTED. Visit status is a separate lifecycle.

Identification attaches the customer; route moves the appointment to WAITING_STAFF without using a resource. Supervisor manually assigns/reassigns `assigned_staff_id` through `VisitService.assignStaff()`. Server eligibility is an active LSR; base Resource Room duty or call-out does not prevent assignment. Assignment is version-checked, audited with event notes, and preserves the original waiting timestamp.

There is **no ATTENDED result enum or separate result column**. A served appointment is closed via visit status CLOSED; closing does not change SCHEDULED to an Attended value. UI labels in `api.js` are Scheduled, Rescheduled, Not attended / FTR, and FTR reported. LEFT is a visit outcome. At opening, `VisitRepository.closeMissedAppointments()` closes earlier-date SCHEDULED appointments still in a nonterminal visit state, sets NOT_ATTENDED_FTR, increments version, and records SYSTEM events. It excludes already CLOSED/LEFT and RESCHEDULED replacements. Contact reporting changes NOT_ATTENDED_FTR to FTR_REPORTED while retaining the visit's lifecycle state. Front desk detail warns about the customer's latest other appointment if it is NOT_ATTENDED_FTR; the front desk UI has no report-ftr mutation control.

Rescheduling closes the WAITING_STAFF original and creates a new unassigned WAITING_STAFF appointment on the current effective business date, with a new A ticket, copied customer/category/type, status RESCHEDULED, and `previousVisitId` linking the original. It does not select a future appointment time/date.

Supervisor groups waiting appointments by Unassigned, active LSR, and Other assignments, shows type/status/wait time, and supports assign/reassign/reschedule and coverage call-outs. My Queue filters to the logged-in LSR (server also scopes WAITING_STAFF), allows closing, and gives center managers a staff selector and reschedule action. Both refresh every five seconds and after actions. Neither screen displays a full completed-appointment result ledger.

## 9. Day mechanics

`business/BusinessCalendar.java` uses America/New_York, Monday–Friday, **08:30 inclusive to 16:45 exclusive**. `HolidayRepository` reads the holiday table each determination. V12 seeds 25 dates across 2026–2027: New Year, MLK Day, Presidents Day, Memorial Day, Juneteenth, Independence Day, Labor Day, Columbus Day, Veterans Day, Thanksgiving, day after Thanksgiving, Christmas in each year, plus July 5, 2027 observed. Saturday dates remain Saturday; July 4, 2027 and its observed Monday are both stored. No observed dates are automatically generated; admin edits the table.

When open, currentBusinessDate is today. Before opening, weekends, and holidays use the previous completed business day; after weekday closing use that day. Visit lists and occupant metadata default to this effective date. V7 backfilled Eastern dates from created timestamps.

`TicketSequenceService` uses independent PostgreSQL sequences: B0001 walk-ins, A0001 appointments, growing beyond four digits. The weekday 08:30 Eastern task resets to 1 only when open and no tickets already exist for that day. First creation catches up after a missed opening when older visits exist. Creation/reset share transaction advisory lock 74008001. Tickets remain unique per business date; sequences can have gaps after rollback.

`BusinessDayService` first freezes completed days, inserts a once-per-day `business_day_rollover` marker at opening, detects overdue scheduled appointments, then resets resource availability/ownership. Ticket reset follows. Resource reset preserves historical visit references/statuses; FTR processing separately changes eligible appointments. Calls before visit/coverage/admin changes preserve completed snapshots; the minute reconciliation also captures completed days, including after downtime. There is no separate exact 16:45 snapshot cron: the minute task performs capture at/after closing. Snapshot GET also lazily captures completed dates.

`business_day_snapshot` stores JSON containing businessDate, visits with customer/queue metadata, resources with occupants, staff, coverage, callOuts. Existing payloads are returned without recapture. Saved snapshots survive subsequent operational/admin changes, rollover, and holiday edits. FTR detection deliberately follows capture, so the frozen payload can retain the preceding state while live historical visits later show FTR. Snapshot requests default to lastCompletedBusinessDate; unsaved future/incomplete or nonbusiness dates are rejected. Empty completed days can be captured. Uncaptured older dates are assembled from records available at capture time; the code has no historical staff/rotation version table.

Frontend BusinessScreen unmounts live daily screens while closed, displays the completed-date banner and read-only SnapshotView, and resumes daily screens when open/date changes. SnapshotView renders the corresponding kiosk, front-desk, supervisor, My Queue, or Resource Room frozen view. Login and administrator console stay available after hours. **Step 9 is complete** for the implemented rollover + frozen snapshot behavior: migration, service, scheduling integration, endpoint, closed-hours UI, and tests all exist. No missing implementation of that recorded step was identified. Closed-hours freezing is a UI presentation rule; the backend does not generally reject operational writes simply because the calendar is closed.

## 10. Build steps map

The requested numbered outline differs from actual commit numbering. No commit is titled Step 1 or Vertical slice. The first application commit is Step 2 and contains the initial slice; Step 3 finishes Resource Room/close/left. The table preserves Git's actual labels. Java paths below omit the common package root.

| Committed step / commit | Main files added or changed and behavior |
| --- | --- |
| Initial application / Step 2 `c59acc1` | Added `FrontlineApplication`, model Customer/Resource/Visit/VisitEvent, customer/resource/visit repositories, VisitService/Controller, CustomerController, ApiExceptionHandler, V1–V3, application.yaml, pom/wrapper, compose.yaml, React App/FrontDesk/api/main/styles, and slice tests: kiosk, identify, route. |
| Kiosk appointments `9597099` | Added Appointments screen/test, VisitQueueItem, appointment intake UI and independent appointment tickets via V4; changed Visit and intake paths. |
| Step 3 `4c3931f` | Added ResourceController, ResourceInventoryItem, ResourceRoom/test and time.js; changed Appointments, VisitService/Repository/Controller and resource repository for close, left, waiting assignment. Appointments was later replaced. |
| Step 4: traffic simulator `4ddea52` | Added TrafficSimulator, SimulatorProperties/Controller, TrafficSimulatorTest, SimulatorControl/test; changed config and check-in actor handling. This is requested outline item 2. |
| Step 5: staff and appointment classification `ad6192e` | Added Staff, StaffRepository/Controller, V5; changed Visit/Event, service/repository/controller, simulator and intake UI/tests. Requested item 3. |
| Step 6: supervisor board and My Queue `781eb04` | Added Supervisor, MyQueue, useAppointmentQueue, RoleSelector and tests/helpers; removed Appointments. RoleSelector was later replaced by authenticated navigation. Requested item 4. |
| Step 7: staff rotation and call-outs `4aecc80` | Added coverage Controller/Repository/Service, CallOut, StaffDuty, V6, CoverageApiTest, CoveragePanel; changed appointment code/UI for rescheduling. This answers requested items 5 and 7; they refer to the same actual step. |
| Step 8: calendar and day scoping `470fd28` | Added BusinessCalendar/Configuration/Controller/Schedules, TicketSequenceService, V7, BusinessApi/Calendar/Schedules tests; changed visit/resource scoping, simulator scheduling, ticket reset, coverage UI. This answers requested items 6 and 8. |
| Step 9: rollover and snapshot `b0a55b8` | Added BusinessDayService, SnapshotController, V8, useBusinessView, SnapshotView/test; changed scheduling, ticket/resource ownership logic, App closed view and tests. |
| Step 10: automatic FTR `8123dec` | Added FtrApiTest; changed rollover/VisitRepository/Service/Controller and front-desk warning UI for detection/contact. |
| Step 11: derived classification `c5e7d53` | Added V9 and simulator/CategoryDrawTest; changed identification, categories, simulator and tests. |
| Step 12: completed-day seed `cb7b246` | Added V10 and SeedDayMigrationTest. |
| Step 13: deployment `74730e9` | Added backend/frontend Dockerfiles, frontend/nginx.conf, docker-compose.prod.yml and Docker ignore files; changed setup documentation. |
| Step 14: authentication `dcb4bf4` | Added auth SecurityConfiguration, UserAccountRepository, AuthController, V11, AuthApiTest, SecurityConfigurationTest and test application.properties; changed pom/config. |
| Step 15: authorization `b38cc71` | Added AuthorizationApiTest and AuthenticatedApiTestConfiguration; changed security rules, JWT owner-scoped queues and visit ownership enforcement. |
| Step 16: login/navigation `56ba587` | Added Login, auth.js, StaffNavigation, StaffQueueSelector, AuthNavigation/api tests; replaced role-selector navigation and updated App/MyQueue/API client. |
| Step 16b `41a25c6` | Changed App/navigation/tests so login and admin remain outside the closed-hours operational freeze. |
| Step 17: admin console `73dbeed` | Added admin Controller/Repository/Service, V12, HolidayRepository, AdminApiTest, AdminConsole, StaffAdmin, RotationAdmin, HolidaysAdmin, useAdminData and frontend tests; changed active filtering/calendar/auth. |
| Step 18: reporting `04f4e63` | Added report Controller/Service/Repository/Summary and ReportApiTest; changed security rules and README. |

Other history: `0753fc5` fixes identified-visit visibility/staff-role validation; `cb1850d` restores rollover markers in resource-occupancy test fixtures. `2f2f261` added AGENTS.md, `8fce5af` added .gitattributes, and `1bc507a` removed AGENTS.md. There is no current checked-in AGENTS.md; this task follows the supplied instructions and existing descriptive commit style.

## 11. Tests

Ran **`mvn test` in `backend`** on November 21, 2026. **BUILD SUCCESS: 316 tests, 0 failures, 0 errors, 0 skipped; 49.258 seconds**, finished 20:35:24 Eastern. Integration tests use disposable Testcontainers PostgreSQL 17 databases. All 12 migrations applied during the run. No application/test/config/migration source was changed.

Backend test files are under `backend/src/test/java/com/frontline`:

| Test class | Executed cases | Coverage |
| --- | --- | --- |
| AdminApiTest | 58 | Admin role matrix, holiday CRUD/calendar, staff activation/history, accounts/passwords, rotation atomicity, validation, role-token invalidation, last admin, frozen snapshots, closed hours. |
| AuthApiTest | 8 | Seeded logins/JWT claims, bad credentials/tokens, public route boundaries, BCrypt account migration/upgrades. |
| AuthorizationApiTest | 62 | Endpoint role matrix, LSR ownership, queue JWT scoping, shared reads, concurrent reassignment. |
| BusinessApiTest | 19 | Effective dates, day scoping, migration backfill, ticket reset/races, resource reset/ownership, immutable snapshots, missed schedule catch-up and validation. |
| BusinessCalendarTest | 38 | Opening/closing boundaries, weekends, holiday dates, Saturday behavior, Eastern daylight saving. |
| BusinessSchedulesTest | 2 | Minute simulator start/stop and opening reset exclusions. |
| CoverageApiTest | 7 | Seeded rotation, date validation, call-out upsert/concurrency, past/invalid staff rejection, duty override without visit changes. |
| FtrApiTest | 12 | Overdue eligibility, snapshot preservation, transactional rollback, report-contact validation/version/concurrency, latest missed appointment context. |
| ReportApiTest | 16 | Role access, date/range validation, category/FTR/wait/duration/workload calculations, day attribution, call-outs/reschedules, empty days/holidays. |
| SeedDayMigrationTest | 2 | Historical distribution, terminal visits/events/snapshot, upgrade preservation of live state and counters. |
| TrafficSimulatorTest | 10 | Every arrival choice/category, SIMULATOR events, toggles/defaults, enabled config and invalid weights. |
| VisitApiTest | 79 | Seeds, intake/list/detail/search, identification/classification, routing, inventory exhaustion, close/left/reuse, optimistic versions, rollback/concurrency, staff assignment/rescheduling, migration upgrades/categories. |
| auth.SecurityConfigurationTest | 2 | Reject invalid signing secrets and decode valid secret bytes. |
| simulator.CategoryDrawTest | 1 | Exact 95/5 weighted category draw boundaries. |

`AuthenticatedApiTestConfiguration` and `TestHolidays` are test helpers, not test suites.

Frontend test files in `frontend/src` (inventoried, not executed in this audit): `App.test.jsx` (kiosk/intake and business-screen switching), `FrontDesk.test.jsx` (queue, identification/search, routing, stale/reload behavior), `ResourceRoom.test.jsx` (inventory/waiting/actions/polling/elapsed threshold), `Supervisor.test.jsx` (groups, assignments, coverage/call-outs, reschedule), `MyQueue.test.jsx` (staff filtering, closing/reschedule), `SimulatorControl.test.jsx` (status/toggles/business state), `SnapshotView.test.jsx` (frozen role views), `AuthNavigation.test.jsx` (login/session/role routes), `AdminConsole.test.jsx` (staff/accounts/rotation/holidays/actions/errors), `api.test.js` (bearer/public requests, expiry/errors/204 handling), `time.test.js` (elapsed/wait/calendar formatting). `testQueues.js` supplies fixtures. Frontend script is `npm test` (Vitest); it is separate from Maven.

## 12. Questions

- The request says JPA entities, but the code uses JDBC records and SQL schemas. Is JPA terminology historical? No JPA mappings exist to inventory.
- The requested step numbering compresses actual Steps 4–8. Should future references use Git's numbering? Section 10 reports both correspondences without inventing commits.
- The request mentions every named day profile; none exist. Is a profile definition located outside this repository?
- The request mentions fax, but only PHONE/COMPUTER/STATION exist. Does STATION represent fax outside the code? No such association is defined in the resource mapping.
- The request mentions Attended results; no ATTENDED status/result is stored. Is CLOSED plus appointment status intended to communicate attendance? Current closure preserves SCHEDULED or RESCHEDULED.
- Call-out currently means Resource Room duty, not absence, and cannot be canceled through an endpoint. Is that terminology still intended? The UI and repository explicitly implement that override.
- RESCHEDULED replacements are same-effective-day waiting visits without future slots and are excluded from automatic SCHEDULED-only FTR detection. Is that the intended meaning of rescheduling?
- Operational writes can occur after hours and use the last completed effective date while the UI stays frozen. Is that intended for direct API callers? The code implements presentation freezing, not a closed-hours write prohibition.
- An uncaptured older day's snapshot uses current staff/rotation information and resource ownership still available at capture; there is no historical configuration archive. What historical fidelity is expected after extended downtime or retrospective date requests? Existing saved payloads do remain frozen.
- There is a protected report-ftr endpoint, but no frontend action calling it. Is contact reporting intended to be API-only at this stage?
