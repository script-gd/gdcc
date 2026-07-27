package gd.script.gdcc.backend.c.gen.intrinsic.foriter;

import gd.script.gdcc.backend.c.gen.CBodyBuilder;
import gd.script.gdcc.backend.c.gen.CIntrinsicFunction;
import gd.script.gdcc.lir.LirVariable;
import gd.script.gdcc.type.GdBoolType;
import gd.script.gdcc.type.GdFloatType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdccForFloatIterType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/// Intrinsics for float shorthand `for-in` iterator state operations.
///
/// Maps LIR intrinsic names (`gdcc.for_float_iter.*`) to C helper symbols (`gdcc_for_float_iter_*`).
/// The state type is `compiler::GdccForFloatIter` which iterates `0.0, 1.0, ...` while
/// `current < end`, matching Godot FLOAT `Variant::iter_*` semantics.
public final class CForFloatIterIntrinsic implements CIntrinsicFunction {
    public static final @NotNull String INIT_NAME = "gdcc.for_float_iter.init";
    public static final @NotNull String SHOULD_CONTINUE_NAME = "gdcc.for_float_iter.should_continue";
    public static final @NotNull String NEXT_NAME = "gdcc.for_float_iter.next";
    public static final @NotNull String GET_NAME = "gdcc.for_float_iter.get";

    static final @NotNull String INIT_HELPER_NAME = "gdcc_for_float_iter_from_end";
    static final @NotNull String SHOULD_CONTINUE_HELPER_NAME = "gdcc_for_float_iter_should_continue";
    static final @NotNull String NEXT_HELPER_NAME = "gdcc_for_float_iter_next";
    static final @NotNull String GET_HELPER_NAME = "gdcc_for_float_iter_get";

    private final @NotNull Spec spec;

    private CForFloatIterIntrinsic(@NotNull Spec spec) {
        this.spec = spec;
    }

    public static @NotNull CForFloatIterIntrinsic init() {
        return new CForFloatIterIntrinsic(new Spec(
                INIT_NAME,
                INIT_HELPER_NAME,
                GdccForFloatIterType.FOR_FLOAT_ITER,
                List.of(GdFloatType.FLOAT)
        ));
    }

    public static @NotNull CForFloatIterIntrinsic shouldContinue() {
        return new CForFloatIterIntrinsic(new Spec(
                SHOULD_CONTINUE_NAME,
                SHOULD_CONTINUE_HELPER_NAME,
                GdBoolType.BOOL,
                List.of(GdccForFloatIterType.FOR_FLOAT_ITER)
        ));
    }

    public static @NotNull CForFloatIterIntrinsic next() {
        return new CForFloatIterIntrinsic(new Spec(
                NEXT_NAME,
                NEXT_HELPER_NAME,
                GdccForFloatIterType.FOR_FLOAT_ITER,
                List.of(GdccForFloatIterType.FOR_FLOAT_ITER)
        ));
    }

    public static @NotNull CForFloatIterIntrinsic get() {
        return new CForFloatIterIntrinsic(new Spec(
                GET_NAME,
                GET_HELPER_NAME,
                GdFloatType.FLOAT,
                List.of(GdccForFloatIterType.FOR_FLOAT_ITER)
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
