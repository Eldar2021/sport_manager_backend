Deep analyze all changes in the current branch compared to the `develop` branch (this project's main integration branch):

1. Run `git fetch origin develop` to get the latest develop.
2. Run `git log origin/develop..HEAD --oneline` to list all commits on the current branch.
3. Run `git diff origin/develop...HEAD --stat` to see a summary of changed files.
4. Run `git diff origin/develop...HEAD` to see the full diff.
5. For each changed file, read the full file to understand the context around the changes (not just the diff hunks). Pay special attention to:
   - `controller/` — new or modified endpoints, route prefixes, auth expectations
   - `service/` and `service.impl/` — business logic, role checks (OWNER vs MANAGER) enforced here, not in `SecurityConfiguration`
   - `entity/` — JPA changes; remember `ddl-auto=update` means non-additive changes risk data loss
   - `exception/` — new error codes must be registered in `GlobalExceptionHandler.MESSAGES` (en/ru/ky) and thrown as `AppException`, not `ResponseStatusException`
   - `security/` and `configuration/` — anything affecting JWT, public routes, or filter chain
   - `dto/`, `mapper/` — request/response shape changes
6. Produce a structured analysis:
   - **Branch name** and number of commits
   - **Files changed**: list with a one-line description of what changed in each
   - **New features / endpoints**: any new functionality added (with route prefix and auth requirement)
   - **Bug fixes**: any bugs that were fixed
   - **Refactoring**: any code restructuring
   - **Schema impact**: JPA entity / column changes and whether they are additive or risky under `ddl-auto=update`
   - **Dependencies**: changes to `pom.xml`
   - **Configuration**: changes to `application.yml` or other config
   - **Potential issues**: anything risky — missing auth checks, missing role enforcement in service layer, `ResponseStatusException` where `AppException` should be used, missing i18n entries in `GlobalExceptionHandler.MESSAGES`, session math that bypasses snapshot fields, etc.
   - **Missing items**: tests, docs under `docs/`, translation entries, Swagger annotations, or migrations that may need updating
