import logging
import time
from collections import Counter
from dataclasses import dataclass
from enum import Enum

import httpx


LOG = logging.getLogger(__name__)


class Status(Enum):
    OK = "ok"
    CONFLICT = "conflict"
    ERROR = "error"


@dataclass(frozen=True)
class Result:
    status: Status
    data: object = None
    etag: str | None = None

    def __bool__(self):
        return self.status is Status.OK


CONFLICT = Result(Status.CONFLICT)
ERROR = Result(Status.ERROR)


class API:
    def __init__(self, client, sleep=time.sleep, clock=time.monotonic):
        self.client = client
        self.sleep = sleep
        self.clock = clock
        self.counts = Counter()

    def send(self, method, path, **kwargs):
        # Retry transient failures up to three times after the original attempt.
        for attempt in range(4):
            try:
                response = self.client.request(method, path, **kwargs)
                if response.status_code < 500:
                    return response
            except httpx.RequestError:
                pass
            if attempt < 3:
                self.sleep(0.25 * 2**attempt)
        return None

    def result(self, response, method, path):
        if response is not None:
            if response.status_code in (409, 412):
                self.counts["conflicts"] += 1
                return CONFLICT
            if response.is_success:
                try:
                    data = response.json() if response.content else None
                except ValueError:
                    pass
                else:
                    if method != "GET" and path != "/api/auth/login":
                        self.counts["actions"] += 1
                    return Result(Status.OK, data, response.headers.get("ETag"))
        self.counts["errors"] += 1
        LOG.error("%s %s failed (%s)", method, path,
                  response.status_code if response is not None else "transient retries exhausted")
        return ERROR

    def public(self, method, path, **kwargs):
        return self.result(self.send(method, path, **kwargs), method, path)

    def session(self, username, password):
        return Session(self, username, password)


class Session:
    def __init__(self, api, username, password):
        self.api = api
        self.username = username
        self.password = password
        self.identity = None
        self.expires = 0

    def login(self):
        result = self.api.public("POST", "/api/auth/login", json={
            "username": self.username, "password": self.password,
        })
        if not result:
            self.identity = None
            return False
        self.identity = result.data
        self.expires = self.api.clock() + self.identity["expiresIn"]
        return True

    def request(self, method, path, headers=None, **kwargs):
        if (self.identity is None or self.expires - self.api.clock() <= 600) and not self.login():
            return ERROR
        headers = dict(headers or {})
        for attempt in range(2):
            headers["Authorization"] = f'Bearer {self.identity["token"]}'
            response = self.api.send(method, path, headers=headers, **kwargs)
            if response is None or response.status_code != 401 or attempt == 1:
                return self.api.result(response, method, path)
            if not self.login():
                return ERROR

    def staff(self):
        return self.request("GET", "/api/staff")

    def visits(self, date):
        return self.request("GET", "/api/visits", params={"businessDate": date})

    def resources(self, date):
        return self.request("GET", "/api/resources", params={"businessDate": date})

    def help_requests(self, date, open_only=True):
        return self.request("GET", "/api/help-requests", params={"businessDate": date, "open": open_only})

    def create_customer(self, **data):
        return self.request("POST", "/api/customers", json=data)

    def visit_action(self, visit, action, **data):
        path = f'/api/visits/{visit["id"]}'
        current = self.request("GET", path)
        if not current:
            return current
        if current.data["visit"]["version"] != visit["version"]:
            self.api.counts["conflicts"] += 1
            return CONFLICT
        body = {"json": data} if data else {}
        return self.request("POST", f"{path}/{action}", headers={"If-Match": current.etag}, **body)

    def help_action(self, request, action, date, **data):
        # The list endpoint supplies versions. There is no help detail GET.
        current = self.help_requests(date)
        if not current:
            return current
        version = request["version"]
        if not any(row["id"] == request["id"] and row["version"] == version for row in current.data):
            self.api.counts["conflicts"] += 1
            return CONFLICT
        body = {"json": data} if data else {}
        path = f'/api/help-requests/{request["id"]}/{action}'
        return self.request("POST", path, headers={"If-Match": f'"{version}"'}, **body)

    def admin_staff(self):
        return self.request("GET", "/api/admin/staff")

    def create_account(self, staff_id, username, password):
        return self.request("POST", "/api/admin/accounts", json={
            "staffId": staff_id, "username": username, "password": password,
        })

    def reset_password(self, account_id, password):
        return self.request("PUT", f"/api/admin/accounts/{account_id}/reset-password",
                            json={"password": password})
