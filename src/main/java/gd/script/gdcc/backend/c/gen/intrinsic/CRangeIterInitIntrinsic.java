package gd.script.gdcc.backend.c.gen.intrinsic;

import gd.script.gdcc.backend.c.gen.CBodyBuilder;
import gd.script.gdcc.backend.c.gen.CIntrinsicFunction;
import gd.script.gdcc.lir.LirVariable;
import gd.script.gdcc.type.GdccForRangeIterType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/// Skeleton intrinsic for compiler-only range iterator storage initialization.
///
/// The phase-6 scaffold only wires the intrinsic shape into backend codegen and tests.
/// It intentionally avoids implementing any concrete runtime iterator semantics.
public final class CRangeIterInitIntrinsic implements CIntrinsicFunction {
    public static final @NotNull String NAME = GdccForRangeIterType.C_INIT_HELPER_NAME;

    @Override
    public @NotNull String name() {
        return NAME;
    }

    @Override
    public void generateCCode(@NotNull CBodyBuilder bodyBuilder,
                              @Nullable LirVariable resultVar,
                              @NotNull List<LirVariable> argVars) {
        if (resultVar != null) {
            throw bodyBuilder.invalidInsn("'" + NAME + "' does not produce a result variable");
        }
        if (argVars.size() != 1) {
            throw bodyBuilder.invalidInsn("'" + NAME + "' requires exactly one argument, got " + argVars.size());
        }
        var targetVar = argVars.getFirst();
        if (!(targetVar.type() instanceof GdccForRangeIterType)) {
            throw bodyBuilder.invalidInsn("'" + NAME + "' argument variable '" + targetVar.id() +
                    "' must be compiler-only range iterator storage, got '" + targetVar.type().getTypeName() + "'");
        }
        bodyBuilder.callVoid(NAME, List.of(bodyBuilder.valueOfVar(targetVar)));
    }
}
