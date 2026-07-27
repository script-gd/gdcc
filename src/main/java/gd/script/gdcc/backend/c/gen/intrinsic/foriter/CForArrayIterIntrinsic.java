package gd.script.gdcc.backend.c.gen.intrinsic.foriter;

import gd.script.gdcc.backend.c.gen.CBodyBuilder;
import gd.script.gdcc.backend.c.gen.CIntrinsicFunction;
import gd.script.gdcc.lir.LirVariable;
import gd.script.gdcc.type.GdArrayType;
import gd.script.gdcc.type.GdBoolType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdccForArrayIterType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/// Intrinsics for Array `for-in` iterator state operations.
///
/// Maps LIR intrinsic names (`gdcc.for_array_iter.*`) to C helper symbols (`gdcc_for_array_iter_*`).
/// The state type is `compiler::GdccForArrayIter` which holds a shared Array handle + cached size.
/// Get resolves each element via `operator_index_const` (no raw base-pointer cache: Array is
/// reference-semantic and may reallocate if resized during iteration).
/// The init argument accepts any `GdArrayType` regardless of element type parameter.
public final class CForArrayIterIntrinsic implements CIntrinsicFunction {
    public static final @NotNull String INIT_NAME = "gdcc.for_array_iter.init";
    public static final @NotNull String SHOULD_CONTINUE_NAME = "gdcc.for_array_iter.should_continue";
    public static final @NotNull String NEXT_NAME = "gdcc.for_array_iter.next";
    public static final @NotNull String GET_NAME = "gdcc.for_array_iter.get";

    static final @NotNull String INIT_HELPER_NAME = "gdcc_for_array_iter_from_array";
    static final @NotNull String SHOULD_CONTINUE_HELPER_NAME = "gdcc_for_array_iter_should_continue";
    static final @NotNull String NEXT_HELPER_NAME = "gdcc_for_array_iter_next";
    static final @NotNull String GET_HELPER_NAME = "gdcc_for_array_iter_get";

    private final @NotNull Spec spec;

    private CForArrayIterIntrinsic(@NotNull Spec spec) {
        this.spec = spec;
    }

    public static @NotNull CForArrayIterIntrinsic init() {
        return new CForArrayIterIntrinsic(new Spec(
                INIT_NAME,
                INIT_HELPER_NAME,
                GdccForArrayIterType.FOR_ARRAY_ITER,
                List.of(GdVariantType.VARIANT),
                true
        ));
    }

    public static @NotNull CForArrayIterIntrinsic shouldContinue() {
        return new CForArrayIterIntrinsic(new Spec(
                SHOULD_CONTINUE_NAME,
                SHOULD_CONTINUE_HELPER_NAME,
                GdBoolType.BOOL,
                List.of(GdccForArrayIterType.FOR_ARRAY_ITER),
                false
        ));
    }

    public static @NotNull CForArrayIterIntrinsic next() {
        return new CForArrayIterIntrinsic(new Spec(
                NEXT_NAME,
                NEXT_HELPER_NAME,
                GdccForArrayIterType.FOR_ARRAY_ITER,
                List.of(GdccForArrayIterType.FOR_ARRAY_ITER),
                false
        ));
    }

    public static @NotNull CForArrayIterIntrinsic get() {
        return new CForArrayIterIntrinsic(new Spec(
                GET_NAME,
                GET_HELPER_NAME,
                GdVariantType.VARIANT,
                List.of(GdccForArrayIterType.FOR_ARRAY_ITER),
                false
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
            if (spec.acceptAnyArray() && i == 0) {
                checkArrayType(bodyBuilder, argVars.get(i));
            } else {
                checkType(bodyBuilder, "argument #" + (i + 1), argVars.get(i), spec.argumentTypes().get(i));
            }
        }
    }

    private void checkArrayType(@NotNull CBodyBuilder bodyBuilder, @NotNull LirVariable variable) {
        if (!(variable.type() instanceof GdArrayType)) {
            throw bodyBuilder.invalidInsn("'" + name() + "' argument #1 variable '" + variable.id() +
                    "' must be an Array type, got '" + variable.type().getTypeName() + "'");
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
                        @NotNull List<GdType> argumentTypes,
                        boolean acceptAnyArray) {
        private Spec {
            argumentTypes = List.copyOf(argumentTypes);
        }
    }
}
