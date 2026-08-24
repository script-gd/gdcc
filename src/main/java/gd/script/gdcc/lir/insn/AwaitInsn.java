package gd.script.gdcc.lir.insn;

import gd.script.gdcc.enums.GdInstruction;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/// `$result = await $operand` — suspends the enclosing coroutine until the operand produces a
/// result, then publishes that result as the instruction value (contract: `gdcc_low_ir.md`
/// §Coroutine Instructions).
///
/// Contract summary:
/// - `resultId` is REQUIRED (`ReturnKind.REQUIRED`); a missing result fails at parse time.
/// - The operand static type selects the backend dispatch path: `GdSignalType` → signal path,
///   `compiler::GdccCoroState` → coroutine state path (the operand is an OWNED single-consumer
///   reference that `await` consumes, resetting the source slot to moved-from `NULL`),
///   `GdVariantType` → dynamic path.
/// - The result type is the frontend-published await result type (callee declared return type;
///   `void` callee → `Variant` holding nil), declared on the result variable itself; it is
///   deliberately not validated at the LIR model layer and is checked by the backend generator.
/// - Await may only appear inside a function whose `isCoroutine` marker is `true`.
///
/// Await is an ordinary value-producing instruction: it never splits basic blocks and is not
/// a terminator, so `entryBlockId` / terminator integrity rules are unaffected.
public record AwaitInsn(@NotNull String resultId, @NotNull String operandId) implements CoroutineInstruction {

    public AwaitInsn {
        Objects.requireNonNull(resultId, "resultId must not be null");
        Objects.requireNonNull(operandId, "operandId must not be null");
    }

    @Override
    public GdInstruction opcode() {
        return GdInstruction.AWAIT;
    }

    @Override
    public @NotNull List<Operand> operands() {
        return List.of(new VariableOperand(operandId));
    }
}
