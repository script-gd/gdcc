## Summary
<!--
1-3 sentences.
State the main feature/fix and subsystem (frontend / HIR / LIR / backend).
-->

## What changed
<!--
List concrete changes grouped by responsibility.
Prefer specific components/classes/files.
-->
- 

## Why
<!--
Explain root problem or missing capability.
Include design rationale when trade-offs exist.
-->
- 

## Affected packages/files
<!--
List impacted packages and key files.
For large PRs, keep this review-relevant.
-->
- 

## Validation
<!--
Provide exact commands that were run.
Prefer targeted tests during iteration.
Use required flags: --no-daemon --info --console=plain
-->
- `./gradlew test --tests <TestClassOrMethod> --no-daemon --info --console=plain`
- `./gradlew classes --no-daemon --info --console=plain`

Result: `BUILD SUCCESSFUL`

## Risks / Notes
<!--
List intentional limitations/non-goals and follow-up work.
If behavior contracts changed (IR validation, ownership lifecycle, type compatibility, codegen semantics), state explicitly.
-->
- 

## Key behaviors covered (Optional)
<!--
For compiler/codegen semantics, e.g. dispatch rules, type checks, lifecycle handling, vararg/default argument contracts.
-->
- 

## Diff stats (Optional)
<!--
Useful for large PRs.
Example: 12 files changed, 520 insertions, 74 deletions.
-->
- 

## Breaking changes (Optional)
<!--
Required when API/behavior compatibility changes.
-->
- None

## Related docs (Optional)
<!--
Link updated docs under doc/.
-->
- `doc/pr_message_guideline.md`

---

## Checklist
- [ ] PR title follows Conventional Commits format: `<type>(<scope>): <imperative summary>`
- [ ] PR body is in English
- [ ] Required sections are complete (`Summary`, `What changed`, `Why`, `Affected packages/files`, `Validation`, `Risks / Notes`)
- [ ] Validation commands are exact and reproducible
- [ ] Semantics/ownership/type-contract impacts are clearly described when applicable
- [ ] Scope and non-goals are explicit
