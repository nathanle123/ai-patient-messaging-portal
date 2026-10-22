# AI Patient Portal Messaging

CMPE 272 project for patient messaging with AI-assisted processing.
Built with React, Spring Boot, Python, Kafka, and PostgreSQL.

To run locally, install Docker and copy the environment template:

```bash
cp .env.example .env
```

Fill in the values in `.env`, then start the app:

```bash
docker compose up -d --build
```

Open http://localhost:3000. Sign in as `admin`, `dr.alvarez`, or
`maria.lopez` using the `DEMO_USER_PASSWORD` set in `.env`.
