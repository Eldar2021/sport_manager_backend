Review all changes in the current branch compared to `develop` and produce a structured code review report.

1. Run `git fetch origin develop` to get the latest develop.
2. Run `git log origin/develop..HEAD --oneline` to list all commits.
3. Run `git diff origin/develop...HEAD` to see all changes.
4. Read each changed file in full to understand context.
5. Review the code against this project's conventions (see `CLAUDE.md` and any files under `docs/`).

Produce a structured review report with **exactly three sections**: Critical, Warning, Suggest.

---

## Summary

- Brief overview of what the branch does (1–2 sentences)

## Changes Reviewed

- List of files with a brief description

## Critical (must fix before merge)

Blocking issues — anything that would break prod, leak data, or corrupt state. For example:

- Endpoints under `/api/v1/**` that are unintentionally public, or auth bypasses
- Role checks (OWNER vs MANAGER) missing in the service layer where they are required
- `ResponseStatusException` thrown for flows that the documented multilingual error contract requires (`AppException` + entry in `GlobalExceptionHandler.MESSAGES`)
- JPA entity changes that are non-additive (renamed/dropped columns) under `ddl-auto=update` without a coordinated migration plan
- Session math that recomputes pricing from the live `Tables` instead of the snapshot fields (`tarifAmountSnapshot`, `tarifTypeSnapshot`, `totalPausedSeconds`)
- SQL injection, broken JWT validation, leaked secrets, or other security issues
- Logic bugs that produce wrong results

## Warning (should fix)

Non-blocking but real problems:

- Missing error handling, swallowed exceptions, broad `catch (Exception e)` blocks
- New error codes thrown via `AppException` but missing en/ru/ky entries in `GlobalExceptionHandler.MESSAGES`
- Missing or incorrect Swagger / OpenAPI annotations on new endpoints
- DTO / entity coupling (entities returned directly instead of via response DTOs)
- Naming inconsistencies vs the existing `controller → service → service.impl → repository → entity` layering
- Missing `@Transactional` on multi-step write flows
- N+1 query risks, unbounded queries, missing pagination

## Suggest (nice to have)

Improvements that are not required:

- Cleaner patterns, extraction of helpers, readability
- Performance refinements
- Test coverage suggestions
- Documentation updates under `docs/`

## Checklist

- [ ] New endpoints have correct route prefix (`/auth/**`, `/api/v1/**`) and are listed in `CLAUDE.md` if they introduce a new controller
- [ ] Auth and role checks enforced in the service layer (not via `SecurityConfiguration` URL patterns)
- [ ] Errors thrown as `AppException` with codes registered in `GlobalExceptionHandler.MESSAGES` (en/ru/ky)
- [ ] DTOs used for requests/responses; entities not exposed directly
- [ ] JPA changes are additive; any rename/drop is called out explicitly
- [ ] Session lifecycle changes preserve snapshot fields and pause accumulator semantics
- [ ] Swagger annotations present on new endpoints
- [ ] `application.yml` changes documented if behavior depends on them
- [ ] Tests added/updated (`./mvnw test`)
- [ ] Relevant `docs/*.md` updated if the public API changed

## Verdict

- **Approve** / **Request Changes** / **Needs Discussion** with reasoning

---
