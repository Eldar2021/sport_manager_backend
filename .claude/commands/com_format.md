Analyze the Spring Boot / Maven project for warnings and errors first, then format the code. Fixing issues takes priority over formatting.

If a specific path is provided as argument ($ARGUMENTS), scope the analysis to that path (e.g. a package or single file). Otherwise, run on the entire project.

Steps:

1. Run `./mvnw -q -DskipTests compile` to compile the project and surface any errors or warnings from `javac`. If `$ARGUMENTS` is a single file, you may instead inspect that file directly with the LSP/diagnostics rather than recompiling the whole project.
2. If compilation **fails** (errors): read each affected file, understand the root cause, and fix the errors. Do not silence them with broad catches or by deleting code. Re-run step 1 until it succeeds.
3. If compilation succeeds but emits **warnings** (unchecked, deprecation, unused, raw types, etc.): fix them where reasonable. Suppress with `@SuppressWarnings` only when the warning is genuinely unavoidable, and explain why in the commit context (not in a code comment unless the reason is non-obvious).
4. Run `./mvnw -q test-compile` to make sure test sources also compile cleanly, and apply the same fix-first policy to any warnings/errors there.
5. Once analysis is clean, format the code:
   - If the project's `pom.xml` declares a formatter plugin (e.g. `spotless-maven-plugin`, `fmt-maven-plugin`, `formatter-maven-plugin`), run that plugin's apply/format goal (e.g. `./mvnw spotless:apply`).
   - If no formatter plugin is configured, do **not** auto-rewrite files. Instead, manually tidy only the files you touched in steps 2–3: organize imports, remove unused imports, ensure trailing newline, and keep indentation consistent with the surrounding code. Report this clearly so the user knows no project-wide formatter ran.
6. Report results: how many files were modified, what warnings/errors were fixed, and whether a formatter plugin ran or only manual tidy-up was applied.
