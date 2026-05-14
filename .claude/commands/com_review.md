Review all changes in the current branch compared to main and produce a structured code review report:

1. Run `git fetch origin main` to get the latest main.
2. Run `git log origin/main..HEAD --oneline` to list all commits.
3. Run `git diff origin/main...HEAD` to see all changes.
4. Read each changed file in full to understand context.
5. Review the code against the project's code rules (see `docs/code-rules.md`).

Produce a structured review report:

---

## Summary
- Brief overview of what the branch does (1-2 sentences)

## Changes Reviewed
- List of files with brief description

## Issues Found

### Critical (must fix before merge)
- List blocking issues: bugs, broken logic, security concerns

### Warnings (should fix)
- List non-blocking issues: naming violations, missing error handling, code smells

### Suggestions (nice to have)
- List improvements: better patterns, performance, readability

## Checklist
- [ ] Follows naming conventions (see docs/code-rules.md)
- [ ] State management uses RequestStatus pattern correctly
- [ ] Models use @JsonSerializable, @immutable, Equatable
- [ ] Interfaces defined with `abstract interface class`
- [ ] New routes registered in AppRouter
- [ ] DI modules registered correctly
- [ ] Code formatted (120 char line length)
- [ ] Generated files up to date (.g.dart)
- [ ] Translations added for all 3 languages (en, ru, ky)
- [ ] Unit tests added/updated

## Verdict
- **Approve** / **Request Changes** / **Needs Discussion** with reasoning

---
