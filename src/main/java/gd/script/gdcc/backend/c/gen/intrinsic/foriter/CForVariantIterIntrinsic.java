package gd.script.gdcc.backend.c.gen.intrinsic.foriter;

import gd.script.gdcc.backend.c.gen.CBodyBuilder;
import gd.script.gdcc.backend.c.gen.CIntrinsicFunction;
import gd.script.gdcc.lir.LirVariable;
import gd.script.gdcc.type.GdBoolType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdccForVariantIterType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/// Intrinsics for generic Variant `for-in` iterator state operations.
///
/// Maps LIR intrinsic names (`gdcc.for_variant_iter.*`) to C helper symbols (`gdcc_for_variant_iter_*`).
/// The state type is `compiler::GdccForVariantIter` which wraps GDExtension Variant iteration API.
public final class CForVariantIterIntrinsic implements CIntrinsicFunction {
    public static final @NotNull String INIT_NAME = "gdcc.for_variant_iter.init";
    public static final @NotNull String SHOULD_CONTINUE_NAME = "gdcc.for_variant_iter.should_continue";
    public static final @NotNull String NEXT_NAME = "gdcc.for_variant_iter.next";
    public static final @NotNull String GET_NAME = "gdcc.for_variant_iter.get";

    static final @NotNull String INIT_HELPER_NAME = "gdcc_for_variant_iter_from_variant";
    static final @NotNull String SHOULD_CONTINUE_HELPER_NAME = "gdcc_for_variant_iter_should_continue";
    static final @NotNull String NEXT_HELPER_NAME = "gdcc_for_variant_iter_next";
    static final @NotNull String GET_HELPER_NAME = "gdcc_for_variant_iter_get";

    private final @NotNull Spec spec;

    private CForVariantIterIntrinsic(@NotNull Spec spec) {
        this.spec = spec;
    }

    public static @NotNull CForVariantIterIntrinsic init() {
        return new CForVariantIterIntrinsic(new Spec(
                INIT_NAME,
                INIT_HELPER_NAME,
                GdccForVariantIterType.FOR_VARIANT_ITER,
                List.of(GdVariantType.VARIANT)
        ));
    }

    public static @NotNull CForVariantIterIntrinsic shouldContinue() {
        return new CForVariantIterIntrinsic(new Spec(
                SHOULD_CONTINUE_NAME,
                SHOULD_CONTINUE_HELPER_NAME,
                GdBoolType.BOOL,
                List.of(GdccForVariantIterType.FOR_VARIANT_ITER)
        ));
    }

    public static @NotNull CForVariantIterIntrinsic next() {
        return new CForVariantIterIntrinsic(new Spec(
                NEXT_NAME,
                NEXT_HELPER_NAME,
                GdccForVariantIterType.FOR_VARIANT_ITER,
                List.of(GdccForVariantIterType.FOR_VARIANT_ITER)
        ));
    }

    public static @NotNull CForVariantIterIntrinsic get() {
        return new CForVariantIterIntrinsic(new Spec(
                GET_NAME,
                GET_HELPER_NAME,
                GdVariantType.VARIANT,
                List.of(GdccForVariantIterType.FOR_VARIANT_ITER)
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
