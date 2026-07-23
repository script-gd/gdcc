package gd.script.gdcc.frontend.lowering;

import gd.script.gdcc.type.GdCompilerType;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/// Lowering contract for one compile-ready `for-in` iteration route.
///
/// This is the single lowering source of truth for the hidden iterator state type and the four
/// iteration operations. It is queried from `ForLoweringContractRegistry` by the compile gate and CFG
/// builder; it is never published into `FrontendAnalysisData` and never carries source-facing facts.
/// The compiler-only `iteratorStateType` may only appear in lowering-internal storage (hidden-slot
/// metadata, LIR function local, intrinsic operand/result and backend C storage).
///
/// @param iteratorStateType compiler-only storage type of the loop-carried hidden iterator state (for
///                          example `GdccForRangeIterType.FOR_RANGE_ITER`); restricted to hidden-slot
///                          metadata, LIR local, intrinsic operand/result and backend C storage
/// @param init operation that builds the initial iterator state from the source operands; result is the
///             hidden state, arguments are the normalized source values
/// @param shouldContinue operation that reads the hidden state and produces the ordinary `bool` loop
///                       condition consumed by the condition branch
/// @param next operation that reads the hidden state and returns the next state as a new value (not an
///             in-place mutation), committed via a distinct temp plus `AssignInsn`
/// @param get operation that reads the hidden state and produces the ordinary raw element value, later
///            committed (with optional conversion) to the source-facing iterator local
public record FrontendForLoweringContract(
        @NotNull GdCompilerType iteratorStateType,
        @NotNull ForIterationOperationDescriptor init,
        @NotNull ForIterationOperationDescriptor shouldContinue,
        @NotNull ForIterationOperationDescriptor next,
        @NotNull ForIterationOperationDescriptor get
) {
    public FrontendForLoweringContract {
        Objects.requireNonNull(iteratorStateType, "iteratorStateType must not be null");
        Objects.requireNonNull(init, "init must not be null");
        Objects.requireNonNull(shouldContinue, "shouldContinue must not be null");
        Objects.requireNonNull(next, "next must not be null");
        Objects.requireNonNull(get, "get must not be null");
    }
}
