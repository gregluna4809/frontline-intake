import math
import os
import re
from dataclasses import dataclass, field
from urllib.parse import urlsplit


DEFAULT_BASE = "http://frontline-backend:8080"
DEFAULTS = {
    "CLERK_USERS": ("bot-clerk-01,bot-clerk-02", "users"),
    "RR_LSR_USERS": (",".join(f"bot-lsr-{i:02}" for i in range(1, 5)), "users"),
    "APPT_LSR_USERS": (",".join(f"bot-lsr-{i:02}" for i in range(5, 9)), "users"),
    "SUPERVISOR_USER": ("bot-supervisor-01", "user"),
    "TICK_SECONDS": ("2", "positive"),
    "TARGET_MIN": ("30", "count"),
    "TARGET_MAX": ("40", "count"),
    "MAX_ARRIVALS_PER_TICK": ("3", "count"),
    "MAX_WAITING_PER_RESOURCE": ("5", "count"),
    "SESSION_SECONDS_PHONE": ("60-180", "range"),
    "SESSION_SECONDS_COMPUTER": ("90-300", "range"),
    "SESSION_SECONDS_STATION": ("30-90", "range"),
    "APPOINTMENT_SECONDS": ("120-360", "range"),
    "HELP_RATE_UI": ("0.40", "rate"),
    "HELP_RATE_OTHER": ("0.15", "rate"),
    "NEEDS_PHONE_RATE": ("0.30", "rate"),
    "REFERRED_OUT_RATE": ("0.05", "rate"),
    "LEAVE_EARLY_RATE": ("0.05", "rate"),
    "CLAIM_DELAY_SECONDS": ("5-20", "range"),
    "HELP_WORK_SECONDS": ("15-60", "range"),
    "CLERK_DELAY_SECONDS": ("3-10", "range"),
}


def required(env, name):
    value = env.get(name, "")
    if not value.strip():
        raise ValueError(f"{name} is required")
    return value


def api_base(env):
    value = env.get("FRONTLINE_API_BASE", DEFAULT_BASE)
    parsed = urlsplit(value)
    if (
        parsed.scheme not in ("http", "https")
        or not parsed.hostname
        or parsed.username is not None
        or parsed.path not in ("", "/")
        or parsed.query
        or parsed.fragment
    ):
        raise ValueError("FRONTLINE_API_BASE must be an HTTP(S) origin")
    try:
        parsed.port
    except ValueError as error:
        raise ValueError("FRONTLINE_API_BASE has an invalid port") from error
    return value.rstrip("/")


def parse_range(value):
    if not re.fullmatch(r"[0-9]+-[0-9]+", value):
        raise ValueError("expected a min-max range in seconds")
    low, high = map(int, value.split("-"))
    if low > high:
        raise ValueError("range minimum exceeds maximum")
    return low, high


def parse_value(value, kind):
    match kind:
        case "range":
            return parse_range(value)
        case "users" | "user":
            users = tuple(part.strip() for part in value.split(","))
            if not all(users) or any(len(user) > 100 for user in users):
                raise ValueError("expected nonempty usernames of at most 100 characters")
            if kind == "users":
                return users
            if len(users) != 1:
                raise ValueError("expected one username")
            return users[0]
        case "count":
            result = int(value)
            if result >= 0:
                return result
            raise ValueError("expected a nonnegative integer")
        case "positive":
            result = float(value)
            if math.isfinite(result) and result > 0:
                return result
            raise ValueError("expected a positive number")
        case "rate":
            result = float(value)
            if 0 <= result <= 1:
                return result
            raise ValueError("expected a rate from 0 to 1")


@dataclass(frozen=True)
class Config:
    base_url: str
    password: str = field(repr=False)
    clerk_users: tuple[str, ...]
    rr_lsr_users: tuple[str, ...]
    appt_lsr_users: tuple[str, ...]
    supervisor_user: str
    tick_seconds: float
    target_min: int
    target_max: int
    max_arrivals_per_tick: int
    max_waiting_per_resource: int
    max_waiting_appointments: int
    session_seconds_phone: tuple[int, int]
    session_seconds_computer: tuple[int, int]
    session_seconds_station: tuple[int, int]
    appointment_seconds: tuple[int, int]
    help_rate_ui: float
    help_rate_other: float
    needs_phone_rate: float
    referred_out_rate: float
    leave_early_rate: float
    claim_delay_seconds: tuple[int, int]
    help_work_seconds: tuple[int, int]
    clerk_delay_seconds: tuple[int, int]
    seed: int | None

    @classmethod
    def from_env(cls, env=None):
        env = os.environ if env is None else env
        password = required(env, "SIM_BOT_PASSWORD")
        if not 8 <= len(password.encode()) <= 72:
            raise ValueError("SIM_BOT_PASSWORD must be 8–72 bytes")
        values = {}
        for name, (default, kind) in DEFAULTS.items():
            try:
                values[name.lower()] = parse_value(env.get(f"SIM_{name}", default), kind)
            except ValueError as error:
                raise ValueError(f"SIM_{name}: {error}") from None
        try:
            values["max_waiting_appointments"] = parse_value(
                env.get("SIM_MAX_WAITING_APPOINTMENTS", str(2 * len(values["appt_lsr_users"]))),
                "count",
            )
        except ValueError as error:
            raise ValueError(f"SIM_MAX_WAITING_APPOINTMENTS: {error}") from None
        try:
            seed = int(env["SIM_SEED"]) if env.get("SIM_SEED") else None
        except ValueError:
            raise ValueError("SIM_SEED must be an integer") from None
        config = cls(api_base(env), password, **values, seed=seed)
        if config.target_min > config.target_max:
            raise ValueError("SIM_TARGET_MIN cannot exceed SIM_TARGET_MAX")
        if config.needs_phone_rate + config.referred_out_rate > 1:
            raise ValueError("SIM_NEEDS_PHONE_RATE and SIM_REFERRED_OUT_RATE must sum to at most 1")
        users = (*config.clerk_users, *config.rr_lsr_users, *config.appt_lsr_users, config.supervisor_user)
        if len(set(users)) != len(users):
            raise ValueError("SIM bot usernames must be distinct")
        return config
