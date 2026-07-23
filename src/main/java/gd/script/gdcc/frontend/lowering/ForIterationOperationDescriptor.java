package gd.script.gdcc.frontend.lowering;

import gd.script.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/// Named, signed description of one iteration operation (init / should-continue / next / get).
///
/// Carrying the intrinsic name together with its arity and signature lets CFG builder and lowering
/// processors consume the contract from `ForLoweringContractRegistry` without hardcoding intrinsic
/// names or argument layouts at each use site.
///
/// @param intrinsicName frozen LIR intrinsic name emitted by lowering (for example
///                      `gdcc.for_range_iter.init`); must match the C backend's registered copy
/// @param resultType result `GdType` of the operation; may be a compiler-only iterator state type for
///                   state-producing operations (init/next) or an ordinary type (bool/int) otherwise
/// @param argumentTypes ordered argument `GdType` signature; its size is the intrinsic arity and each
///                      entry fixes the expected operand type at that position
public record ForIterationOperationDescriptor(
        @NotNull String intrinsicName,
        @NotNull GdType resultType,
        @NotNull List<GdType> argumentTypes
) {
    public ForIterationOperationDescriptor {
        Objects.requireNonNull(intrinsicName, "intrinsicName must not be null");
        if (intrinsicName.isBlank()) {
            throw new IllegalArgumentException("intrinsicName must not be blank");
        }
        Objects.requireNonNull(resultType, "resultType must not be null");
        argumentTypes = List.copyOf(Objects.requireNonNull(argumentTypes, "argumentTypes must not be null"));
    }
}
