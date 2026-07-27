package gd.script.gdcc.backend.c.gen.intrinsic.foriter;

import gd.script.gdcc.backend.c.gen.CBodyBuilder;
import gd.script.gdcc.backend.c.gen.CIntrinsicFunction;
import gd.script.gdcc.lir.LirVariable;
import gd.script.gdcc.type.GdccForFloatIterType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/// Prepare-block intrinsic for default float shorthand iterator storage initialization.
public final class CForFloatIterRawInitIntrinsic implements CIntrinsicFunction {
    public static final @NotNull String NAME = GdccForFloatIterType.C_INIT_HELPER_NAME;

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
        if (!(resultVar.type() instanceof GdccForFloatIterType)) {
            throw bodyBuilder.invalidInsn("'" + NAME + "' result variable '" + resultVar.id() +
                    "' must be compiler-only float iterator storage, got '" +
                    resultVar.type().getTypeName() + "'");
        }
        if (!argVars.isEmpty()) {
            throw bodyBuilder.invalidInsn("'" + NAME + "' requires no arguments, got " + argVars.size());
        }
        bodyBuilder.callAssign(
                bodyBuilder.targetOfVar(resultVar),
                NAME,
                GdccForFloatIterType.FOR_FLOAT_ITER,
                List.of()
        );
    }
}
