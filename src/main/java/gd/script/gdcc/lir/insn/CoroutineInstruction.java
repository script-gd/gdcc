package gd.script.gdcc.lir.insn;

import gd.script.gdcc.lir.LirInstruction;

/// Grouping marker for coroutine instructions (`gdcc_low_ir.md` §Coroutine Instructions).
/// Members are ordinary value instructions — never control-flow terminators.
/// The marker itself carries no validation: operand type dispatch, single-consumer ownership,
/// and the `isCoroutine` placement rule are enforced by the backend generator / validators,
/// not by this interface.
public interface CoroutineInstruction extends LirInstruction {
}
