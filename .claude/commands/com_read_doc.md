Read all AI context documentation files in this Spring Boot project and summarize the key points:

1. Read `CLAUDE.md` (root) if it exists.
2. Read `README.md` if it exists.
3. Read all Markdown files in the `docs/` directory:
   - `docs/auth-api.md`
   - `docs/home_page_api.md`
   - `docs/managers-api.md`
   - `docs/reports-api.md`
   - `docs/session_api.md`
   - `docs/subscription-api.md`
   - Also read any other `*.md` files under `docs/` (including `docs/ru/` if present).
4. Read `pom.xml` to understand dependencies, Java version, Spring Boot version, and build plugins.
5. Read `src/main/resources/application.yml` (and any `application-*.yml` profile files) to learn runtime configuration: database, JWT, JPA, server port.
6. Read `Dockerfile` to understand the runtime image / packaging.
7. Glance at the package layout under `src/main/java/kg/sportmanager/` (controller, service, service.impl, repository, entity, dto, mapper, security, exception, configuration) so you know where each layer lives.

After reading, provide a concise summary of:

- Project purpose and domain (sport venue / table session billing).
- Tech stack and Java/Spring Boot versions.
- Architecture layers and the `controller → service → repository → entity` flow.
- Auth model (JWT, OWNER/MANAGER roles, invite codes, public vs protected routes).
- Error handling model (`AppException` + `GlobalExceptionHandler` vs `ResponseStatusException` — note the inconsistency).
- Session lifecycle and snapshot fields (`tarifAmountSnapshot`, `tarifTypeSnapshot`, `totalPausedSeconds`).
- i18n model (`Accept-Language: en|ru|ky`, translations hard-coded in `GlobalExceptionHandler.MESSAGES`).
- Any conventions, gotchas, or footguns called out in `CLAUDE.md` that subsequent tasks must respect.

This ensures full context before making any changes.
