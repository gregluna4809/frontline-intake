# Project

This repository is a standalone career-center intake and routing prototype using synthetic data only, deployed to DigitalOcean as a portfolio piece. Production use is out of scope.

Record the reason for a visit separately from its destination. Data entered once follows the visit.

# Hard constraints

- Use no real names or IDs.
- Do not include an SSN field, even as a placeholder.
- Do not reference any real agency, program, or location.
- Do not copy visuals from internal systems.
- Use generic vocabulary only, such as Career Center, Phone Access, Benefits Assistance, and Reemployment Appointment.

# Working rules

- Propose changes before implementing them and wait for sign-off.
- Work on one scoped task per session.
- Identify the root cause before making edits.
- Deliver complete files only, never snippets.
- Deliver backend and frontend changes together.

# Stack

- Java 21 / Spring Boot
- PostgreSQL + Flyway
- React
- Docker Compose
- Maven
- Vite for the React app

# Current phase

Build a thin vertical slice: fake kiosk → front desk → resource room → close visit.

Do not build anything else until that flow works.
