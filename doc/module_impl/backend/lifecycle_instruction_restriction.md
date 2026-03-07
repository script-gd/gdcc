# Lifecycle Instruction Restriction - Engineering Notes

> Last reviewed: 2026-02-25
> Purpose: Keep only long-term valuable constraints, insights, and operational baselines after migration completion.

## 1. Completion Status

- Confirmed completed: Phase 0 / 1 / 2 / 3 / 5 / 6
- Explicitly skipped: Phase 4 (frontend/lowering not implemented yet)
- Remaining rollout gate: clear compat-mode `UNKNOWN` warnings before switching default strict mode

## 2. Stable Contract (Must Keep)

### 2.1 Controlled Lifecycle Instructions

`destruct` / `try_own_object` / `try_release_object` are controlled instructions.
They must be provenance-aware and validated before backend code generation.

### 2.2 Provenance Rules

- `AUTO_GENERATED`
  - Allowed: compiler-injected `destruct` in `__finally__`
  - Forbidden: non-`__finally__` blocks; own/release instructions
- `INTERNAL`
  - Allowed: compiler internal/temp variables (numeric IDs or `__*` IDs)
  - Forbidden: ordinary user-named variables / parameters
- `USER_EXPLICIT`
  - Allowed: frontend-lowered explicit user lifecycle intent
  - Forbidden: auto-generated blocks (`__prepare__`, `__finally__`)
- `UNKNOWN`
  - Compat mode: warn and pass
  - Strict mode: fail-fast

### 2.3 Ownership Boundary with `__finally__`

- Keep `__prepare__` / `__finally__` control-flow framework unchanged.
- Auto-destruct remains compiler-owned in `__finally__`.
- `_return_val` remains outside variable-table auto-destruct scope.

## 3. Enforcement Map in Code

- Provenance model
  - `src/main/java/dev/superice/gdcc/enums/LifecycleProvenance.java`
  - `src/main/java/dev/superice/gdcc/lir/insn/LifecycleInstruction.java`
  - `src/main/java/dev/superice/gdcc/lir/insn/DestructInsn.java`
  - `src/main/java/dev/superice/gdcc/lir/insn/TryOwnObjectInsn.java`
  - `src/main/java/dev/superice/gdcc/lir/insn/TryReleaseObjectInsn.java`
- Parser/serializer compatibility
  - `src/main/java/dev/superice/gdcc/lir/parser/ParsedLirInstruction.java`
  - `src/main/java/dev/superice/gdcc/lir/parser/SimpleLirBlockInsnSerializer.java`
- Validation and fail-fast path
  - `src/main/java/dev/superice/gdcc/lir/validation/LifecycleInstructionRestrictionValidator.java`
  - `src/main/java/dev/superice/gdcc/backend/c/gen/CCodegen.java`
  - `src/main/java/dev/superice/gdcc/backend/c/gen/insn/DestructInsnGen.java`
  - `src/main/java/dev/superice/gdcc/backend/c/gen/insn/OwnReleaseObjectInsnGen.java`
- Strict-mode switch
  - `src/main/java/dev/superice/gdcc/backend/CodegenContext.java`

## 4. Regression Baseline (Targeted)

```bash
./gradlew.bat test --tests LifecycleInstructionRestrictionValidatorTest --no-daemon --info --console=plain
./gradlew.bat test --tests LifecycleInstructionProvenanceParserTest --tests SimpleLirBlockInsnSerializerTest --tests DomLirSerializerTest --no-daemon --info --console=plain
./gradlew.bat test --tests LifecycleProvenancePropagationTest --tests CDestructInsnGenTest --tests COwnReleaseObjectInsnGenTest --no-daemon --info --console=plain
./gradlew.bat test --tests CPhaseAControlFlowAndFinallyTest --tests CBodyBuilderPhaseCTest --no-daemon --info --console=plain
./gradlew.bat classes --no-daemon --info --console=plain
```

## 5. Phase 4 Deferred Contract (Frontend Missing)

Because frontend/lowering is currently absent:

- `USER_EXPLICIT` remains a supported IR provenance category.
- Backend and validator keep accepting/rejecting it by current rules.
- Once frontend exists, only lowering-generated explicit lifecycle intent may emit `USER_EXPLICIT`.

## 6. Engineering Insights (Keep for Future Changes)

1. **Validation before generation is mandatory**
   - Lifecycle misuse must be blocked before C emission; do not rely on downstream tolerance.
2. **Generator assertions are defense-in-depth, not policy source**
   - Policy lives in validator; generators only provide local guardrails.
3. **Compat-to-strict rollout prevents migration shock**
   - Keep warning visibility until warning count reaches zero, then flip strict by default.
4. **Docs and tests must move together**
   - Any lifecycle-rule change must update low_ir/spec/backend docs and targeted tests in the same PR.
5. **Avoid external hand-written lifecycle IR assumptions**
   - Lifecycle instructions are no longer general-purpose external IR primitives.