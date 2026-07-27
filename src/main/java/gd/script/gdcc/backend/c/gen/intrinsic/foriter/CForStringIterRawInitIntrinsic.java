package gd.script.gdcc.backend.c.gen.intrinsic.foriter;

import gd.script.gdcc.backend.c.gen.CBodyBuilder;
import gd.script.gdcc.backend.c.gen.CIntrinsicFunction;
import gd.script.gdcc.lir.LirVariable;
import gd.script.gdcc.type.GdccForStringIterType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/// Prepare-block intrinsic for default String iterator storage initialization.
///
/// Initializes compiler-only storage before ordinary control flow starts. Separate from
/// `gdcc.for_string_iter.init`, which builds a runtime state from a source String.
public final class CForStringIterRawInitIntrinsic implements CIntrinsicFunction {
    public static final @NotNull String NAME = GdccForStringIterType.C_INIT_HELPER_NAME;

    @Override
    public @NotNull String name() {
        return NAME;
    }

    @Override
    public void generateCCode(@NotNull CBodyBuilder bodyBuilder,
                              @Nullable LirVariable resultVar,
                              @NotNull List<LirVariable> argVars) {
        if (resultVar == null) {
            throw bodyBuilder.invalidInsn("'" + NAME + "' requires a result variable");
        }
        if (resultVar.ref()) {
            throw bodyBuilder.invalidInsn("'" + NAME + "' result variable '" + resultVar.id() + "' cannot be a reference");
        }
        if (!(resultVar.type() instanceof GdccForStringIterType)) {
            throw bodyBuilder.invalidInsn("'" + NAME + "' result variable '" + resultVar.id() +
                    "' must be compiler-only string iterator storage, got '" + resultVar.type().getTypeName() + "'");
        }
        if (!argVars.isEmpty()) {
            throw bodyBuilder.invalidInsn("'" + NAME + "' requires no arguments, got " + argVars.size());
        }
        bodyBuilder.callAssign(bodyBuilder.targetOfVar(resultVar), NAME, GdccForStringIterType.FOR_STRING_ITER, List.of());
    }
}
