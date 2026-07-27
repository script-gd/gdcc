package gd.script.gdcc.backend.c.gen.intrinsic.foriter.packed;

import gd.script.gdcc.backend.c.gen.CBodyBuilder;
import gd.script.gdcc.backend.c.gen.CIntrinsicFunction;
import gd.script.gdcc.lir.LirVariable;
import gd.script.gdcc.type.GdBoolType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdccForPackedArrayIterType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/// Per-family Packed*Array `for-in` iterator intrinsics.
///
/// One LIR intrinsic set exists for each Packed*Array family; helpers map 1:1 to typed C symbols
/// so `get`/`copy`/`destroy` need no runtime family switch.
public final class CForPackedArrayIterIntrinsic implements CIntrinsicFunction {
    private final @NotNull Spec spec;

    private CForPackedArrayIterIntrinsic(@NotNull Spec spec) {
        this.spec = spec;
    }

    public static @NotNull List<CForPackedArrayIterIntrinsic> allOperations() {
        var ops = new ArrayList<CForPackedArrayIterIntrinsic>();
        for (var family : GdccForPackedArrayIterType.all()) {
            ops.add(init(family));
            ops.add(shouldContinue(family));
            ops.add(next(family));
            ops.add(get(family));
        }
        return List.copyOf(ops);
    }

    public static @NotNull CForPackedArrayIterIntrinsic init(@NotNull GdccForPackedArrayIterType family) {
        return new CForPackedArrayIterIntrinsic(new Spec(
                family.getInitIntrinsicName(),
                family.getCFromHelperName(),
                family,
                List.of(family.sourceType())
        ));
    }

    public static @NotNull CForPackedArrayIterIntrinsic shouldContinue(@NotNull GdccForPackedArrayIterType family) {
        return new CForPackedArrayIterIntrinsic(new Spec(
                family.getShouldContinueIntrinsicName(),
                family.getCShouldContinueHelperName(),
                GdBoolType.BOOL,
                List.of(family)
        ));
    }

    public static @NotNull CForPackedArrayIterIntrinsic next(@NotNull GdccForPackedArrayIterType family) {
        return new CForPackedArrayIterIntrinsic(new Spec(
                family.getNextIntrinsicName(),
                family.getCNextHelperName(),
                family,
                List.of(family)
        ));
    }

    public static @NotNull CForPackedArrayIterIntrinsic get(@NotNull GdccForPackedArrayIterType family) {
        return new CForPackedArrayIterIntrinsic(new Spec(
                family.getGetIntrinsicName(),
                family.getCGetHelperName(),
                family.elementType(),
                List.of(family)
        ));
    }

    @Override
    public @NotNull String name() {
        return spec.name();
    }

    @Override
    public void generateCCode(@NotNull CBodyBuilder bodyBuilder,
                              @Nullable LirVariable resultVar,
                              @NotNull List<LirVariable> argVars) {
        checkResult(bodyBuilder, resultVar);
        checkArgs(bodyBuilder, argVars);

        var args = argVars.stream()
                .map(bodyBuilder::valueOfVar)
                .toList();
        bodyBuilder.callAssign(
                bodyBuilder.targetOfVar(resultVar),
                spec.helperName(),
                spec.resultType(),
                args
        );
    }

    private void checkResult(@NotNull CBodyBuilder bodyBuilder,
                             @Nullable LirVariable resultVar) {
        if (resultVar == null) {
            throw bodyBuilder.invalidInsn("'" + name() + "' requires a result variable");
        }
        if (resultVar.ref()) {
            throw bodyBuilder.invalidInsn("'" + name() + "' result variable '" + resultVar.id() + "' cannot be a reference");
        }
        checkType(bodyBuilder, "result", resultVar, spec.resultType());
    }

    private void checkArgs(@NotNull CBodyBuilder bodyBuilder,
                           @NotNull List<LirVariable> argVars) {
        if (argVars.size() != spec.argumentTypes().size()) {
            throw bodyBuilder.invalidInsn("'" + name() + "' requires exactly " + spec.argumentTypes().size() +
                    " argument" + (spec.argumentTypes().size() == 1 ? "" : "s") + ", got " + argVars.size());
        }
        for (var i = 0; i < argVars.size(); i++) {
            checkType(bodyBuilder, "argument #" + (i + 1), argVars.get(i), spec.argumentTypes().get(i));
        }
    }

    private void checkType(@NotNull CBodyBuilder bodyBuilder,
                           @NotNull String role,
                           @NotNull LirVariable variable,
                           @NotNull GdType expectedType) {
        if (!variable.type().equals(expectedType)) {
            throw bodyBuilder.invalidInsn("'" + name() + "' " + role + " variable '" + variable.id() +
                    "' must be " + expectedType.getTypeName() + ", got '" + variable.type().getTypeName() + "'");
        }
    }

    private record Spec(@NotNull String name,
                        @NotNull String helperName,
                        @NotNull GdType resultType,
                        @NotNull List<GdType> argumentTypes) {
        private Spec {
            argumentTypes = List.copyOf(argumentTypes);
        }
    }
}
