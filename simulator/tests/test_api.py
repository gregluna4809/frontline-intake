import json

import httpx
import pytest
import respx

from frontline_sim.api import API, Status


BASE = "http://frontline-backend:8080"
LOGIN = f"{BASE}/api/auth/login"
STAFF = f"{BASE}/api/staff"


def auth(token):
    return httpx.Response(200, json={"token": token, "expiresIn": 28800})


@pytest.fixture
def client():
    with httpx.Client(base_url=BASE) as client:
        yield client


@respx.mock
def test_login_reuses_token_and_refreshes_near_expiry(client):
    time = [0]
    api = API(client, clock=lambda: time[0])
    login = respx.post(LOGIN).mock(side_effect=[auth("first"), auth("second")])
    staff = respx.get(STAFF).respond(json=[])
    session = api.session("bot", "test-only")
    assert session.staff()
    assert session.staff()
    assert login.call_count == 1
    assert json.loads(login.calls[0].request.content) == {"username": "bot", "password": "test-only"}
    time[0] = 28200
    assert session.staff()
    assert [call.request.headers["Authorization"] for call in staff.calls] == [
        "Bearer first", "Bearer first", "Bearer second",
    ]


@pytest.mark.parametrize("final_code", [200, 401])
@respx.mock
def test_401_reauthenticates_once(client, final_code):
    login = respx.post(LOGIN).mock(side_effect=[auth("first"), auth("second")])
    staff = respx.get(STAFF).mock(side_effect=[
        httpx.Response(401), httpx.Response(final_code, json=[]),
    ])
    result = API(client).session("bot", "test-only").staff()
    assert bool(result) == (final_code == 200)
    assert login.call_count == 2
    assert [call.request.headers["Authorization"] for call in staff.calls] == ["Bearer first", "Bearer second"]


@pytest.mark.parametrize("code", [409, 412])
@respx.mock
def test_conflict_uses_current_etag(client, code):
    respx.post(LOGIN).mock(return_value=auth("token"))
    respx.get(f"{BASE}/api/visits/3").respond(json={"visit": {"version": 8}}, headers={"ETag": '"8"'})
    close = respx.post(f"{BASE}/api/visits/3/close").respond(code)
    api = API(client)
    result = api.session("bot", "test-only").visit_action({"id": 3, "version": 8}, "close")
    assert result.status is Status.CONFLICT
    assert close.calls.last.request.headers["If-Match"] == '"8"'
    assert api.counts["conflicts"] == 1


def test_fresh_version_cannot_overwrite_human_assignment(client, respx_mock):
    respx_mock.post(LOGIN).mock(return_value=auth("token"))
    respx_mock.get(f"{BASE}/api/visits/3").respond(json={"visit": {"version": 9, "assignedStaffId": 4}})
    result = API(client).session("bot", "test-only").visit_action(
        {"id": 3, "version": 8, "assignedStaffId": None}, "assign-staff", staffId=24,
    )
    assert result.status is Status.CONFLICT
    assert len(respx_mock.calls) == 2


@pytest.mark.parametrize("network", [False, True])
@respx.mock
def test_transient_failures_retry_then_skip(client, network):
    delays = []
    api = API(client, sleep=delays.append)
    respx.post(LOGIN).mock(return_value=auth("token"))
    route = respx.get(STAFF)
    if network:
        route.mock(side_effect=httpx.ConnectError("offline"))
    else:
        route.respond(503)
    assert api.session("bot", "test-only").staff().status is Status.ERROR
    assert route.call_count == 4
    assert delays == [0.25, 0.5, 1]
    assert api.counts["errors"] == 1


@pytest.mark.parametrize("current_version", [5, 6])
def test_help_action_uses_fresh_list_version(client, respx_mock, current_version):
    respx_mock.post(LOGIN).mock(return_value=auth("token"))
    respx_mock.get(f"{BASE}/api/help-requests", params={
        "businessDate": "2026-11-22", "open": "true",
    }).respond(json=[{"id": 8, "version": current_version}])
    if current_version == 5:
        close = respx_mock.post(f"{BASE}/api/help-requests/8/close").respond(200, json={})
    session = API(client).session("bot", "test-only")
    result = session.help_action({"id": 8, "version": 5}, "close", "2026-11-22", outcome="NEEDS_PHONE")
    if current_version == 5:
        assert result.status is Status.OK
        assert close.calls.last.request.headers["If-Match"] == '"5"'
        assert json.loads(close.calls.last.request.content) == {"outcome": "NEEDS_PHONE"}
    else:
        assert result.status is Status.CONFLICT
        assert len(respx_mock.calls) == 2
