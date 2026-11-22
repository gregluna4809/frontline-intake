from collections import Counter
from dataclasses import replace
from itertools import cycle
from random import Random
from types import SimpleNamespace
from unittest.mock import Mock

import pytest

from frontline_sim.api import Result, Status
from frontline_sim.config import Config
from frontline_sim.engine import Engine, arrival_count, help_outcome


@pytest.fixture
def config():
    return Config.from_env({"SIM_BOT_PASSWORD": "test-only", "SIM_SEED": "0"})


@pytest.mark.parametrize(("served", "pipeline", "expected"), [
    (20, 30, 3), (20, 34, 1), (29, 35, 0), (30, 45, 1), (40, 45, 1), (41, 41, 0),
])
def test_arrival_count_uses_served_band_and_pipeline(config, served, pipeline, expected):
    assert arrival_count(config, Random(0), served, pipeline) == expected
    assert arrival_count(config, Random(1), 35, 35) == 0


def test_help_outcome_distribution(config):
    config = replace(config, needs_phone_rate=0.47)
    rng = Random(32)
    outcomes = Counter(help_outcome(config, rng, "UI_ACCOUNT") for _ in range(10_000))
    assert abs(outcomes["NEEDS_PHONE"] / 10_000 - config.needs_phone_rate) <= 0.02
    for reason in ("JOB_SEARCH", "COMPUTER_ACCESS"):
        assert all(help_outcome(config, rng, reason) != "NEEDS_PHONE" for _ in range(10_000))


def ok(data):
    return Result(Status.OK, data)


def make_engine(config):
    clerk = Mock(identity={"staffId": 10})
    rr = Mock(identity={"staffId": 11})
    appt = Mock(identity={"staffId": 12})
    supervisor = Mock(identity={"staffId": 13})
    bots = SimpleNamespace(supervisor=supervisor, rr=[rr], appointments=[appt],
                           rr_cycle=cycle([rr]), clerk_cycle=cycle([clerk]))
    engine = Engine(config, Mock(), bots, clock=lambda: 0)
    engine.day = "2026-11-22"
    return engine, clerk, rr, appt


def test_non_bot_claims_and_assignments_are_protected(config):
    engine, clerk, rr, appt = make_engine(config)
    computer = {"id": 1, "status": "AT_RESOURCE", "reason": "UI_ACCOUNT",
                "requiredResourceType": "COMPUTER", "resourceId": 40}
    request = {"id": 2, "visitId": 1, "claimedByStaffId": 99}
    appointment = {"id": 3, "status": "WAITING_STAFF", "assignedStaffId": 98,
                   "waitingSince": "2026-11-22T12:00:00Z"}
    for now in (0, 1000):
        engine.resource_work([computer], [request], [request], now)
        engine.help_work([computer], [request], {10, 11, 12, 13}, {98, 99}, now)
        engine.appointment_work([appointment], {10, 11, 12, 13}, {98, 99}, now)
    for bot in (clerk, rr, appt, engine.reader):
        bot.visit_action.assert_not_called()
        bot.help_action.assert_not_called()


def test_restart_rebuilds_timers_without_duplicating_help_or_close(config):
    config = replace(config, session_seconds_computer=(90, 90), leave_early_rate=0, help_rate_ui=1)
    engine, clerk, rr, _ = make_engine(config)
    computer = {"id": 1, "status": "AT_RESOURCE", "reason": "UI_ACCOUNT",
                "requiredResourceType": "COMPUTER", "resourceId": 40}
    closed_help = {"id": 2, "visitId": 1}
    engine.reader.help_requests.return_value = ok([])
    engine.resource_work([computer], [closed_help], [], 100)
    assert engine.sessions[1].end == 190
    engine.resource_work([computer], [closed_help], [], 160)
    rr.visit_action.assert_not_called()
    engine.resource_work([computer], [closed_help], [], 190)
    engine.resource_work([], [closed_help], [], 192)
    clerk.visit_action.assert_not_called()
    rr.visit_action.assert_called_once_with(computer, "close")
    assert not engine.sessions
