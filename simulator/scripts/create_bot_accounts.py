import os
import sys

import httpx

from frontline_sim.api import API
from frontline_sim.config import api_base, required


ACCOUNTS = {
    **{f"Bot Clerk {i:02}": f"bot-clerk-{i:02}" for i in range(1, 3)},
    **{f"Bot LSR {i:02}": f"bot-lsr-{i:02}" for i in range(1, 9)},
    "Bot Supervising LSR 01": "bot-supervisor-01",
}


def provision(admin, password):
    staff = admin.admin_staff()
    if not staff:
        raise ValueError("Could not list staff as administrator")
    by_name = {row["displayName"]: row for row in staff.data}
    for name in ACCOUNTS:
        if name not in by_name:
            raise ValueError(f"Missing staff record: {name}")
    created = reset = failed = 0
    for name, username in ACCOUNTS.items():
        row = by_name[name]
        if row["accountId"] is None:
            result = admin.create_account(row["id"], username, password)
            created += bool(result)
        else:
            result = admin.reset_password(row["accountId"], password)
            reset += bool(result)
        failed += not result
    return created, reset, failed


def main():
    try:
        username = required(os.environ, "ADMIN_USERNAME")
        password = required(os.environ, "ADMIN_PASSWORD")
        bot_password = required(os.environ, "SIM_BOT_PASSWORD")
        if not 8 <= len(bot_password.encode()) <= 72:
            raise ValueError("SIM_BOT_PASSWORD must be 8–72 bytes")
        with httpx.Client(base_url=api_base(os.environ), timeout=10, trust_env=False) as client:
            created, reset, failed = provision(API(client).session(username, password), bot_password)
    except ValueError as error:
        print(error, file=sys.stderr)
        return 1
    print(f"Bot accounts: {created} created, {reset} reset, {failed} failed")
    return int(bool(failed))


if __name__ == "__main__":
    sys.exit(main())
