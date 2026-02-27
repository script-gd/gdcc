# PR Message Guideline

## Mandatory Rules

1. PR title must use Conventional Commits style:
   - `<type>(<scope>): <imperative summary>`
   - Example: `feat(backend): implement CALL_METHOD C codegen`
2. PR body must be written in English.
3. Do not describe only "what"; include "why" and runtime/semantic impact.
4. If behavior contracts changed, explicitly state them.

## Required Sections

Use this section order unless there is a strong reason not to:

1. `## Summary`
2. `## What changed`
3. `## Why`
4. `## Affected packages/files`
5. `## Validation`
6. `## Risks / Notes`

### Section Details

`## Summary`
- 1-3 sentences.
- State feature/fix and subsystem (frontend / HIR / LIR / backend).

`## What changed`
- Bullet list grouped by responsibility.
- Prefer concrete component names (for example `CBodyBuilder`, `CallMethodInsnGen`, `ExtensionApiLoader`).

`## Why`
- Explain root problem or missing capability.
- Mention why this design is chosen over alternatives when trade-offs exist.

`## Affected packages/files`
- List package names and key files.
- For large PRs, keep this to review-relevant paths only.

`## Validation`
- Provide exact commands that were run.
- Include result summary (`BUILD SUCCESSFUL` or failure + follow-up).
- Prefer targeted tests over full suite during iteration.

`## Risks / Notes`
- List intentional limitations/non-goals.
- Mention follow-up work if the current PR is intentionally scoped.

## 4. Recommended Optional Sections

Use when applicable:

- `## Key behaviors covered`
  - For compiler semantics (dispatch rules, type checks, lifecycle handling, vararg/default argument contracts).
- `## Breaking changes`
  - Required when API/behavior compatibility changes.
- `## Related docs`
  - Link updated docs under `doc/`.

## 5. Project-Specific Expectations (Compiler + C Backend)

For backend/codegen PRs, prefer explicitly documenting:

1. Instruction semantics and validation boundary
   - What is validated in InsnGen vs builder/helper layers.
2. Ownership/lifecycle implications
   - `try_own_object`, `try_release_object`, destroy/copy behavior changes.
3. Type compatibility contract
   - How assignability is checked and enforced.
4. Default value / vararg behavior
   - Especially for utility calls and generated C argument conventions.
5. Fail-fast behavior
   - Which paths now throw `InvalidInsnException` and why.

## 6. PR Body Template

```md
## Summary
<One short paragraph describing the main change and subsystem.>

## What changed
- <change item 1>
- <change item 2>
- <change item 3>

## Why
- <problem statement>
- <design rationale>

## Affected packages/files
- `dev.superice.gdcc.backend.c.gen`
- `dev.superice.gdcc.backend.c.gen.insn`
- `src/main/c/codegen/template_451/...`
- `src/test/java/dev/superice/gdcc/backend/c/gen/...`
- `doc/module_impl/...`

## Validation
- `./gradlew test --tests <TestClassOrMethod> --no-daemon --info --console=plain`
- `./gradlew classes --no-daemon --info --console=plain`

Result: `BUILD SUCCESSFUL`

## Risks / Notes
- <intentional limitation>
- <follow-up item>
```

## 7. Anti-Patterns to Avoid

1. Only a high-level description without concrete changed components.
2. Listing changes but omitting `Why`.
3. No reproducible validation commands.
4. Mixing too many unrelated topics in one PR message.
5. Using vague statements like "refactor" without semantic impact description.

## 8. Quick Checklist (Before Opening PR)

- [ ] Title follows Conventional Commits format.
- [ ] Body is in English.
- [ ] Required sections are complete.
- [ ] Validation commands are exact and reproducible.
- [ ] Semantics/ownership/type-contract impacts are clearly described.
- [ ] Scope and non-goals are explicit.
