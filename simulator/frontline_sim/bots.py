from itertools import cycle


class Bots:
    def __init__(self, api, config):
        def sessions(names):
            return [api.session(name, config.password) for name in names]

        self.clerks = sessions(config.clerk_users)
        self.rr = sessions(config.rr_lsr_users)
        self.appointments = sessions(config.appt_lsr_users)
        self.supervisor, = sessions([config.supervisor_user])
        self.roles = [
            (self.clerks, "CLERK"), (self.rr, "LSR"),
            (self.appointments, "LSR"), ([self.supervisor], "SUPERVISING_LSR"),
        ]
        self.clerk_cycle = cycle(self.clerks)
        self.rr_cycle = cycle(self.rr)

    def connect(self):
        ready = True
        for sessions, role in self.roles:
            for session in sessions:
                if session.identity is None and not session.login():
                    ready = False
                    continue
                staff = session.identity
                if staff["role"] != role or not staff["displayName"].startswith("Bot "):
                    raise ValueError(f"{session.username} must be a Bot staff account with role {role}")
        return ready


def staff_id(session):
    return session.identity["staffId"]
