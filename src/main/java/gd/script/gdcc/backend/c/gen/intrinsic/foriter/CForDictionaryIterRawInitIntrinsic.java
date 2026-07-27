package gd.script.gdcc.backend.c.gen.intrinsic.foriter;

import gd.script.gdcc.backend.c.gen.CBodyBuilder;
import gd.script.gdcc.backend.c.gen.CIntrinsicFunction;
import gd.script.gdcc.lir.LirVariable;
import gd.script.gdcc.type.GdccForDictionaryIterType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/// Prepare-block intrinsic for default Dictionary key iterator storage initialization.
public final class CForDictionaryIterRawInitIntrinsic implements CIntrinsicFunction {
    public static final @NotNull String NAME = GdccForDictionaryIterType.C_INIT_HELPER_NAME;

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
        if (!(resultVar.type() instanceof GdccForDictionaryIterType)) {
            throw bodyBuilder.invalidInsn("'" + NAME + "' result variable '" + resultVar.id() +
                    "' must be compiler-only dictionary iterator storage, got '" + resultVar.type().getTypeName() + "'");
        }
        if (!argVars.isEmpty()) {
            throw bodyBuilder.invalidInsn("'" + NAME + "' requires no arguments, got " + argVars.size());
        }
        bodyBuilder.callAssign(bodyBuilder.targetOfVar(resultVar), NAME, GdccForDictionaryIterType.FOR_DICTIONARY_ITER, List.of());
    }
}
