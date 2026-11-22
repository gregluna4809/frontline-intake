import logging
import time
from collections import Counter
from dataclasses import dataclass
from datetime import datetime
from random import Random


LOG = logging.getLogger(__name__)
REASON_WEIGHTS = {
    "UI_ACCOUNT": 70, "JOB_SEARCH": 5, "COMPUTER_ACCESS": 3, "DOCUMENT_SERVICES": 2,
    "BENEFITS_ASSISTANCE": 2, "OTHER": 1, "PHONE_ACCESS": 70,
}
RESOURCE_BY_REASON = {
    "PHONE_ACCESS": "PHONE",
    "UI_ACCOUNT": "COMPUTER", "COMPUTER_ACCESS": "COMPUTER", "JOB_SEARCH": "COMPUTER",
    "DOCUMENT_SERVICES": "STATION", "BENEFITS_ASSISTANCE": "STATION", "OTHER": "STATION",
}
IN_TRANSIT = {"CHECKED_IN", "IDENTIFIED"}


def arrival_count(config, rng, served, pipeline):
    if served < config.target_min:
        midpoint = (config.target_min + config.target_max) // 2
        return min(max(0, midpoint - pipeline), config.max_arrivals_per_tick)
    if served <= config.target_max:
        return rng.randrange(2)
    return 0


def help_outcome(config, rng, reason):
    draw = rng.random()
    if reason != "UI_ACCOUNT":
        return "REFERRED_OUT" if draw < 0.10 else "RESOLVED_ONLINE"
    if draw < config.needs_phone_rate:
        return "NEEDS_PHONE"
    if draw < config.needs_phone_rate + config.referred_out_rate:
        return "REFERRED_OUT"
    return "RESOLVED_ONLINE"


def in_appointment(visit):
    return visit["status"] == "WAITING_STAFF" and visit["assignedStaffId"] is not None


@dataclass
class QueueState:
    served: int
    in_transit: int
    resources: Counter
    appointments_waiting: int
    appointments_in_queue: int

    @property
    def pipeline(self):
        return self.served + self.in_transit

    @property
    def waiting(self):
        return self.in_transit + self.resources.total() + self.appointments_waiting

    @classmethod
    def from_visits(cls, visits):
        served = sum(v["status"] == "AT_RESOURCE" or in_appointment(v) for v in visits)
        in_transit = sum(v["status"] in IN_TRANSIT for v in visits)
        resources = Counter(v["requiredResourceType"] for v in visits if v["status"] == "WAITING_RESOURCE")
        appointments = [v for v in visits if v["status"] == "WAITING_STAFF"]
        unassigned = sum(v["assignedStaffId"] is None for v in appointments)
        return cls(served, in_transit, resources, unassigned, len(appointments))


def timestamp(value):
    return datetime.fromisoformat(value).timestamp()


def retain(mapping, ids):
    for key in mapping.keys() - ids:
        del mapping[key]


@dataclass
class ResourceTimer:
    resource_id: int
    end: float
    leave_early: bool
    raise_at: float | None


class Engine:
    def __init__(self, config, api, bots, clock=time.monotonic):
        self.config = config
        self.api = api
        self.bots = bots
        self.reader = bots.supervisor
        self.clock = clock
        self.rng = Random(config.seed)
        self.day = None
        self.last_summary = clock()
        self.clerk_due = {}
        self.customers = {}
        self.sessions = {}
        self.claim_due = {}
        self.work_due = {}
        self.appointments = {}

    def draw(self, bounds):
        return self.rng.randint(*bounds)

    def tick(self):
        now = self.clock()
        try:
            self.run_tick(now)
        finally:
            if now - self.last_summary >= 60:
                self.summary(now)

    def run_tick(self, now):
        business = self.api.public("GET", "/api/business-status")
        if not business or not business.data["isOpen"]:
            return
        date = business.data["currentBusinessDate"]
        visits = self.reader.visits(date)
        resources = self.reader.resources(date)
        history = self.reader.help_requests(date, open_only=False)
        staff = self.reader.staff()
        reasons = self.api.public("GET", "/api/visit-reasons", params={"date": date})
        if not all((visits, resources, history, staff, reasons)):
            return
        if self.day != date:
            for timers in (self.clerk_due, self.customers, self.sessions, self.claim_due,
                           self.work_due, self.appointments):
                timers.clear()
            self.day = date

        visits = sorted(visits.data, key=lambda v: (v["createdAt"], v["id"]))
        open_help = [row for row in history.data if row["closedAt"] is None]
        bot_ids = {person["id"] for person in staff.data if person["displayName"].startswith("Bot ")}
        queues = QueueState.from_visits(visits)
        self.arrive(reasons.data, arrival_count(self.config, self.rng, queues.served, queues.pipeline), queues)
        self.waiting_work(visits, resources.data, timestamp(business.data["serverNow"]))
        self.clerk_work(visits, now)
        busy = {row["claimedByStaffId"] for row in open_help if row["claimedByStaffId"] is not None}
        busy.update(v["assignedStaffId"] for v in visits if in_appointment(v))
        self.resource_work(visits, history.data, open_help, now)
        self.help_work(visits, open_help, bot_ids, busy, now)
        self.appointment_work(visits, bot_ids, busy, now)

    def summary(self, now):
        # Read after mutations so the summary reports the state at the sample time.
        visits = self.reader.visits(self.day) if self.day else None
        help_rows = self.reader.help_requests(self.day) if self.day else None
        queues = QueueState.from_visits(visits.data) if visits else None
        open_help = len(help_rows.data) if help_rows else None
        elapsed = (now - self.last_summary) / 60
        counts = self.api.counts
        LOG.info("beingServed=%s waiting=%s waitingPhone=%s waitingComputer=%s waitingStation=%s "
                 "waitingAppointments=%s openHelp=%s arrivals/min=%.1f actions/min=%.1f "
                 "conflicts/min=%.1f errors/min=%.1f",
                 queues.served if queues else None, queues.waiting if queues else None,
                 queues.resources["PHONE"] if queues else None, queues.resources["COMPUTER"] if queues else None,
                 queues.resources["STATION"] if queues else None, queues.appointments_waiting if queues else None,
                 open_help, counts["arrivals"] / elapsed, counts["actions"] / elapsed,
                 counts["conflicts"] / elapsed, counts["errors"] / elapsed)
        self.last_summary = now
        counts.clear()

    def arrival_reason(self, weights, queues):
        eligible = weights.copy()
        # Draw once, then redraw up to five times without selecting a capped type again.
        for _ in range(6):
            if not eligible:
                return None
            reason, = self.rng.choices(tuple(eligible), weights=tuple(eligible.values()))
            if reason == "APPOINTMENT":
                return reason
            resource_type = RESOURCE_BY_REASON[reason]
            if queues.resources[resource_type] < self.config.max_waiting_per_resource:
                return reason
            eligible = {
                candidate: weight for candidate, weight in eligible.items()
                if candidate != "APPOINTMENT" and RESOURCE_BY_REASON[candidate] != resource_type
            }
        return None

    def arrive(self, reasons, count, queues):
        active = {row["code"] for row in reasons}
        weights = {reason: weight for reason, weight in REASON_WEIGHTS.items() if reason in active}
        if queues.appointments_in_queue < self.config.max_waiting_appointments:
            weights["APPOINTMENT"] = 20
        for _ in range(count):
            reason = self.arrival_reason(weights, queues)
            if reason is None:
                break
            if reason == "APPOINTMENT":
                category, = self.rng.choices(("MANDATORY", "JOB_SEARCH_ASSISTANCE"), (95, 5))
                body = {"visitType": "APPOINTMENT", "appointmentCategory": category}
            else:
                body = {"visitType": "WALK_IN", "reason": reason}
            if self.api.public("POST", "/api/visits", json=body):
                self.api.counts["arrivals"] += 1

    def clerk_work(self, visits, now):
        checked_in = {v["id"] for v in visits if v["status"] == "CHECKED_IN"}
        retain(self.clerk_due, checked_in)
        retain(self.customers, checked_in)
        for visit in visits:
            status, visit_id = visit["status"], visit["id"]
            if status not in ("CHECKED_IN", "IDENTIFIED"):
                continue
            if status == "CHECKED_IN":
                if visit_id not in self.clerk_due:
                    self.clerk_due[visit_id] = now + self.draw(self.config.clerk_delay_seconds)
                if now < self.clerk_due[visit_id]:
                    continue
            clerk = next(self.bots.clerk_cycle)
            if status == "CHECKED_IN":
                if visit_id not in self.customers:
                    language, = self.rng.choices(("EN", "ES", "OTHER"), (85, 12, 3))
                    customer = clerk.create_customer(
                        displayName=f"Sim Customer {visit_id % 1_000_000:06}",
                        language=language, veteran=self.rng.random() < 0.05,
                    )
                    if not customer:
                        continue
                    self.customers[visit_id] = customer.data["id"]
                identified = clerk.visit_action(visit, "identify", customerId=self.customers[visit_id])
                if not identified:
                    continue
                visit = identified.data["visit"]
                del self.customers[visit_id]
            clerk.visit_action(visit, "route")

    def waiting_work(self, visits, resources, business_now):
        free = Counter(row["type"] for row in resources if row["status"] == "FREE")
        waiting = [v for v in visits if v["status"] == "WAITING_RESOURCE"]
        for visit in sorted(waiting, key=lambda v: (v["waitingSince"], v["id"])):
            rr = next(self.bots.rr_cycle)
            if business_now - timestamp(visit["waitingSince"]) > 600:
                rr.visit_action(visit, "left")
                continue
            resource_type = visit["requiredResourceType"]
            if free[resource_type] > 0 and rr.visit_action(visit, "assign"):
                free[resource_type] -= 1

    def resource_timer(self, visit, now, has_help_history):
        resource_type = visit["requiredResourceType"]
        duration = self.draw(getattr(self.config, f"session_seconds_{resource_type.lower()}"))
        leave = self.rng.random() < self.config.leave_early_rate
        end = now + duration * (self.rng.uniform(0.3, 0.8) if leave else 1)
        raise_at = None
        if resource_type == "COMPUTER" and not has_help_history:
            rates = {"UI_ACCOUNT": self.config.help_rate_ui, "COMPUTER_ACCESS": self.config.help_rate_other,
                     "JOB_SEARCH": self.config.help_rate_other}
            if self.rng.random() < rates.get(visit["reason"], 0):
                raise_at = now + (end - now) * self.rng.uniform(0.25, 0.65)
        return ResourceTimer(visit["resourceId"], end, leave, raise_at)

    def resource_work(self, visits, history, open_help, now):
        active = [v for v in visits if v["status"] == "AT_RESOURCE"]
        retain(self.sessions, {v["id"] for v in active})
        had_help = {row["visitId"] for row in history}
        has_help = {row["visitId"] for row in open_help}
        for visit in active:
            visit_id = visit["id"]
            timer = self.sessions.get(visit_id)
            if timer is None or timer.resource_id != visit["resourceId"]:
                timer = self.resource_timer(visit, now, visit_id in had_help)
                self.sessions[visit_id] = timer
            if visit_id in has_help:
                continue
            if timer.raise_at is not None and now >= timer.raise_at and visit_id not in had_help:
                if next(self.bots.clerk_cycle).visit_action(visit, "help-requests"):
                    timer.raise_at = None
                continue
            if now >= timer.end:
                # Help mutations have their own version, independent of the visit ETag.
                current = self.reader.help_requests(self.day)
                if current and not any(row["visitId"] == visit_id for row in current.data):
                    next(self.bots.rr_cycle).visit_action(visit, "left" if timer.leave_early else "close")

    def help_work(self, visits, requests, bot_ids, busy, now):
        retain(self.claim_due, {r["id"] for r in requests if r["claimedByStaffId"] is None})
        retain(self.work_due, {r["id"] for r in requests})
        rr_by_id = {bot.identity["staffId"]: bot for bot in self.bots.rr}
        visit_by_id = {visit["id"]: visit for visit in visits}
        for request in requests:
            request_id, owner = request["id"], request["claimedByStaffId"]
            visit = visit_by_id.get(request["visitId"])
            if visit is None or visit["status"] != "AT_RESOURCE":
                continue
            if owner is None:
                if request_id not in self.claim_due:
                    self.claim_due[request_id] = now + self.draw(self.config.claim_delay_seconds)
                if now < self.claim_due[request_id]:
                    continue
                rr = next((bot for sid, bot in rr_by_id.items() if sid in bot_ids and sid not in busy), None)
                if rr is None:
                    continue
                claimed = rr.help_action(request, "claim", self.day)
                if claimed:
                    busy.add(claimed.data["claimedByStaffId"])
                    self.work_due[request_id] = now + self.draw(self.config.help_work_seconds)
                continue
            if owner not in bot_ids or owner not in rr_by_id:
                continue
            if request_id not in self.work_due:
                self.work_due[request_id] = now + self.draw(self.config.help_work_seconds)
            if now >= self.work_due[request_id]:
                outcome = help_outcome(self.config, self.rng, visit["reason"])
                if rr_by_id[owner].help_action(request, "close", self.day, outcome=outcome):
                    timer = self.sessions.get(visit["id"])
                    if timer and outcome == "REFERRED_OUT":
                        timer.end = now + self.config.tick_seconds
                        timer.leave_early = False

    def appointment_work(self, visits, bot_ids, busy, now):
        waiting = [v for v in visits if v["status"] == "WAITING_STAFF"]
        retain(self.appointments, {v["id"] for v in waiting})
        appt_by_id = {bot.identity["staffId"]: bot for bot in self.bots.appointments}
        for visit in sorted(waiting, key=lambda v: (v["waitingSince"], v["id"])):
            visit_id, owner = visit["id"], visit["assignedStaffId"]
            if owner is None:
                free = next((sid for sid in appt_by_id if sid in bot_ids and sid not in busy), None)
                if free is not None and self.reader.visit_action(visit, "assign-staff", staffId=free):
                    self.appointments[visit_id] = (free, now + self.draw(self.config.appointment_seconds))
                    busy.add(free)
                continue
            if owner not in bot_ids or owner not in appt_by_id:
                continue
            timer_owner, due = self.appointments.get(visit_id, (None, None))
            if timer_owner != owner:
                due = now + self.draw(self.config.appointment_seconds)
                self.appointments[visit_id] = (owner, due)
            if now >= due:
                appt_by_id[owner].visit_action(visit, "close")
