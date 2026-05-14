Deep analyze all changes in the current branch compared to the main branch:

1. Run `git fetch origin main` to get the latest main.
2. Run `git log origin/main..HEAD --oneline` to list all commits.
3. Run `git diff origin/main...HEAD --stat` to see a summary of changed files.
4. Run `git diff origin/main...HEAD` to see the full diff.
5. For each changed file, read the full file to understand the context around the changes.
6. Produce a structured analysis:
   - **Branch name** and number of commits
   - **Files changed**: list with brief description of what changed in each
   - **New features**: any new functionality added
   - **Bug fixes**: any bugs that were fixed
   - **Refactoring**: any code restructuring
   - **Dependencies**: any dependency changes
   - **Potential issues**: anything that looks risky or needs attention
   - **Missing items**: tests, translations, or generated files that may need updating
