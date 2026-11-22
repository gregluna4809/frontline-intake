import json

import httpx
import respx

from frontline_sim.api import API
from scripts.create_bot_accounts import ACCOUNTS, provision


@respx.mock
def test_provision_creates_missing_and_resets_existing_then_only_resets():
    base = "http://frontline-backend:8080"
    respx.post(f"{base}/api/auth/login").respond(json={"token": "token", "expiresIn": 28800})
    staff = [
        {"id": i, "displayName": name, "accountId": 100 if i == 0 else None,
         "username": username if i == 0 else None}
        for i, (name, username) in enumerate(ACCOUNTS.items())
    ]

    def create(request):
        data = json.loads(request.content)
        row = staff[data["staffId"]]
        assert data == {"staffId": row["id"], "username": ACCOUNTS[row["displayName"]],
                        "password": "test-only"}
        assert row["accountId"] is None
        row["accountId"] = 100 + row["id"]
        row["username"] = data["username"]
        return httpx.Response(201, json={"id": row["accountId"]})

    def reset(request):
        assert json.loads(request.content) == {"password": "test-only"}
        return httpx.Response(200, json={})

    respx.get(f"{base}/api/admin/staff").mock(side_effect=lambda _: httpx.Response(200, json=staff))
    creates = respx.post(f"{base}/api/admin/accounts").mock(side_effect=create)
    resets = [
        respx.put(f"{base}/api/admin/accounts/{100 + i}/reset-password").mock(side_effect=reset)
        for i in range(len(staff))
    ]
    with httpx.Client(base_url=base) as client:
        admin = API(client).session("admin", "local-only")
        assert provision(admin, "test-only") == (10, 1, 0)
        assert provision(admin, "test-only") == (0, 11, 0)
    assert creates.call_count == 10
    assert [route.call_count for route in resets] == [2] + [1] * 10
