package gd.script.gdcc.lir.insn;

import gd.script.gdcc.enums.GdInstruction;
import gd.script.gdcc.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

import static java.util.Objects.requireNonNull;

/// Materializes a `godot_Callable` for a compile-time known function with no instance receiver.
///
/// Utility names use an empty owner. GDCC/engine static names require a class owner.
/// The result is a destroyable builtin value created through `godot_callable_custom_create2`.
public record ConstructStandaloneCallableInsn(
        @Nullable String resultId,
        @NotNull StandaloneCallableKind kind,
        @NotNull String ownerName,
        @NotNull String callableName
) implements ConstructionInstruction {

    public ConstructStandaloneCallableInsn {
        requireNonNull(kind, "kind must not be null");
        callableName = StringUtil.requireNonBlank(callableName, "callableName");
        if (kind == StandaloneCallableKind.UTILITY) {
            if (!ownerName.isBlank()) {
                throw new IllegalArgumentException(
                        "construct_standalone_callable utility owner must be empty, got '" + ownerName + "'"
                );
            }
            ownerName = "";
        } else if (ownerName.isBlank()) {
            throw new IllegalArgumentException(
                    "construct_standalone_callable " + kind.token() + " owner must not be blank"
            );
        }
    }

    @Override
    public GdInstruction opcode() {
        return GdInstruction.CONSTRUCT_STANDALONE_CALLABLE;
    }

    @Override
    public @NotNull List<Operand> operands() {
        return List.of(
                new StringOperand(kind.token()),
                new StringOperand(ownerName),
                new StringOperand(callableName)
        );
    }
}
