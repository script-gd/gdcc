package gd.script.gdcc.backend.c.gen.intrinsic;

import gd.script.gdcc.backend.c.gen.CBodyBuilder;
import gd.script.gdcc.backend.c.gen.CIntrinsicFunction;
import gd.script.gdcc.lir.LirVariable;
import gd.script.gdcc.type.GdFloatType;
import gd.script.gdcc.type.GdIntType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/// Materializes the frontend ordinary-boundary `int -> float` primitive cast.
///
/// The cast expression is only a producer; `assignVar(...)` remains the single write path so target
/// validation, initialization state, and future non-primitive lifecycle rules stay centralized.
public final class CIntToFloatIntrinsic implements CIntrinsicFunction {
    public static final @NotNull String NAME = "c_int_to_float";

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
        if (!(resultVar.type() instanceof GdFloatType)) {
            throw bodyBuilder.invalidInsn("'" + NAME + "' result variable '" + resultVar.id() +
                    "' must be float, got '" + resultVar.type().getTypeName() + "'");
        }
        if (argVars.size() != 1) {
            throw bodyBuilder.invalidInsn("'" + NAME + "' requires exactly one argument, got " + argVars.size());
        }

        var sourceVar = argVars.getFirst();
        if (!(sourceVar.type() instanceof GdIntType)) {
            throw bodyBuilder.invalidInsn("'" + NAME + "' argument variable '" + sourceVar.id() +
                    "' must be int, got '" + sourceVar.type().getTypeName() + "'");
        }

        bodyBuilder.assignVar(
                bodyBuilder.targetOfVar(resultVar),
                bodyBuilder.valueOfCastedVar(sourceVar, GdFloatType.FLOAT)
        );
    }
}
