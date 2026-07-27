package gd.script.gdcc.backend.c.gen.intrinsic.foriter.packed;

import gd.script.gdcc.backend.c.gen.CBodyBuilder;
import gd.script.gdcc.backend.c.gen.CIntrinsicFunction;
import gd.script.gdcc.lir.LirVariable;
import gd.script.gdcc.type.GdccForPackedArrayIterType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/// Prepare-block raw init for one specialized Packed*Array iterator storage type.
public final class CForPackedArrayIterRawInitIntrinsic implements CIntrinsicFunction {
    private final @NotNull GdccForPackedArrayIterType family;

    private CForPackedArrayIterRawInitIntrinsic(@NotNull GdccForPackedArrayIterType family) {
        this.family = family;
    }

    public static @NotNull List<CForPackedArrayIterRawInitIntrinsic> all() {
        var inits = new ArrayList<CForPackedArrayIterRawInitIntrinsic>();
        for (var family : GdccForPackedArrayIterType.all()) {
            inits.add(new CForPackedArrayIterRawInitIntrinsic(family));
        }
        return List.copyOf(inits);
    }

    @Override
    public @NotNull String name() {
        return family.getCInitHelperName();
    }

    @Override
    public void generateCCode(@NotNull CBodyBuilder bodyBuilder,
                              @Nullable LirVariable resultVar,
                              @NotNull List<LirVariable> argVars) {
        if (resultVar == null) {
            throw bodyBuilder.invalidInsn("'" + name() + "' requires a result variable");
        }
        if (resultVar.ref()) {
            throw bodyBuilder.invalidInsn("'" + name() + "' result variable '" + resultVar.id() + "' cannot be a reference");
        }
        if (!resultVar.type().equals(family)) {
            throw bodyBuilder.invalidInsn("'" + name() + "' result variable '" + resultVar.id() +
                    "' must be " + family.getTypeName() + ", got '" + resultVar.type().getTypeName() + "'");
        }
        if (!argVars.isEmpty()) {
            throw bodyBuilder.invalidInsn("'" + name() + "' requires no arguments, got " + argVars.size());
        }
        bodyBuilder.callAssign(
                bodyBuilder.targetOfVar(resultVar),
                name(),
                family,
                List.of()
        );
    }
}
