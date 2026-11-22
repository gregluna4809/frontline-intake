import pytest

from frontline_sim.config import Config, parse_range


def test_config_defaults_and_overrides():
    config = Config.from_env({"SIM_BOT_PASSWORD": "test-only"})
    assert config.base_url == "http://frontline-backend:8080"
    assert config.clerk_users == ("bot-clerk-01", "bot-clerk-02")
    assert config.rr_lsr_users == tuple(f"bot-lsr-{i:02}" for i in range(1, 5))
    assert config.appt_lsr_users == tuple(f"bot-lsr-{i:02}" for i in range(5, 9))
    assert config.supervisor_user == "bot-supervisor-01"
    assert (config.tick_seconds, config.target_min, config.target_max, config.max_arrivals_per_tick) == (2, 30, 40, 3)
    assert config.session_seconds_phone == (60, 180)
    assert config.session_seconds_computer == (90, 300)
    assert config.session_seconds_station == (30, 90)
    assert config.appointment_seconds == (120, 360)
    assert (config.help_rate_ui, config.help_rate_other, config.needs_phone_rate,
            config.referred_out_rate, config.leave_early_rate) == (.4, .15, .3, .05, .05)
    assert config.claim_delay_seconds == (5, 20)
    assert config.help_work_seconds == (15, 60)
    assert config.clerk_delay_seconds == (3, 10)
    assert config.seed is None
    override = Config.from_env({"SIM_BOT_PASSWORD": "test-only", "SIM_SEED": "-6",
                                "SIM_CLAIM_DELAY_SECONDS": "0-1"})
    assert override.seed == -6
    assert override.claim_delay_seconds == (0, 1)


@pytest.mark.parametrize("value", ["5", "5-4", "-1-9", "1-anything"])
def test_invalid_range(value):
    with pytest.raises(ValueError):
        parse_range(value)


def test_missing_bot_password():
    with pytest.raises(ValueError, match="SIM_BOT_PASSWORD is required"):
        Config.from_env({})
