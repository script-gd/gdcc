package gd.script.gdcc.backend.c.gen.intrinsic;

import gd.script.gdcc.backend.c.gen.CBodyBuilder;
import gd.script.gdcc.backend.c.gen.CIntrinsicFunction;
import gd.script.gdcc.lir.LirVariable;
import gd.script.gdcc.type.GdBoolType;
import gd.script.gdcc.type.GdPackedArrayType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdccForPackedArrayIterType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/// Intrinsics for Packed*Array `for-in` iterator state operations.
///
/// Maps LIR intrinsic names (`gdcc.for_packed_array_iter.*`) to C helper symbols
/// (`gdcc_for_packed_array_iter_*` / `gdcc_for_packed_*_iter_from`). Init selects a
/// type-specialized from-helper so the runtime can snapshot the typed array and cache
/// a contiguous element base pointer (godot-cpp Iterator style) without packing to Variant.
public final class CForPackedArrayIterIntrinsic implements CIntrinsicFunction {
    public static final @NotNull String INIT_NAME = "gdcc.for_packed_array_iter.init";
    public static final @NotNull String SHOULD_CONTINUE_NAME = "gdcc.for_packed_array_iter.should_continue";
    public static final @NotNull String NEXT_NAME = "gdcc.for_packed_array_iter.next";
    public static final @NotNull String GET_NAME = "gdcc.for_packed_array_iter.get";

    static final @NotNull String SHOULD_CONTINUE_HELPER_NAME = "gdcc_for_packed_array_iter_should_continue";
    static final @NotNull String NEXT_HELPER_NAME = "gdcc_for_packed_array_iter_next";
    static final @NotNull String GET_HELPER_NAME = "gdcc_for_packed_array_iter_get";

    private final @NotNull Spec spec;

    private CForPackedArrayIterIntrinsic(@NotNull Spec spec) {
        this.spec = spec;
    }

    public static @NotNull CForPackedArrayIterIntrinsic init() {
        return new CForPackedArrayIterIntrinsic(new Spec(
                INIT_NAME,
                null,
                GdccForPackedArrayIterType.FOR_PACKED_ARRAY_ITER,
                List.of(GdVariantType.VARIANT),
                true
        ));
    }

    public static @NotNull CForPackedArrayIterIntrinsic shouldContinue() {
        return new CForPackedArrayIterIntrinsic(new Spec(
                SHOULD_CONTINUE_NAME,
                SHOULD_CONTINUE_HELPER_NAME,
                GdBoolType.BOOL,
                List.of(GdccForPackedArrayIterType.FOR_PACKED_ARRAY_ITER),
                false
        ));
    }

    public static @NotNull CForPackedArrayIterIntrinsic next() {
        return new CForPackedArrayIterIntrinsic(new Spec(
                NEXT_NAME,
                NEXT_HELPER_NAME,
                GdccForPackedArrayIterType.FOR_PACKED_ARRAY_ITER,
                List.of(GdccForPackedArrayIterType.FOR_PACKED_ARRAY_ITER),
                false
        ));
    }

    public static @NotNull CForPackedArrayIterIntrinsic get() {
        return new CForPackedArrayIterIntrinsic(new Spec(
                GET_NAME,
                GET_HELPER_NAME,
                GdVariantType.VARIANT,
                List.of(GdccForPackedArrayIterType.FOR_PACKED_ARRAY_ITER),
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

        if (spec.acceptAnyPackedArray()) {
            var sourceVar = argVars.getFirst();
            var helperName = specializedFromHelperName((GdPackedArrayType) sourceVar.type());
            bodyBuilder.callAssign(
                    bodyBuilder.targetOfVar(resultVar),
                    helperName,
                    spec.resultType(),
                    List.of(bodyBuilder.valueOfVar(sourceVar))
            );
            return;
        }

        var args = argVars.stream()
                .map(bodyBuilder::valueOfVar)
                .toList();
        var helperName = spec.helperName();
        if (helperName == null) {
            throw new IllegalStateException("non-init packed array intrinsic requires a helper name");
        }
        bodyBuilder.callAssign(
                bodyBuilder.targetOfVar(resultVar),
                helperName,
                spec.resultType(),
                args
        );
    }

    private static @NotNull String specializedFromHelperName(@NotNull GdPackedArrayType packedType) {
        return switch (packedType.getGdExtensionType()) {
            case PACKED_BYTE_ARRAY -> "gdcc_for_packed_ByteArray_iter_from";
            case PACKED_INT32_ARRAY -> "gdcc_for_packed_Int32Array_iter_from";
            case PACKED_INT64_ARRAY -> "gdcc_for_packed_Int64Array_iter_from";
            case PACKED_FLOAT32_ARRAY -> "gdcc_for_packed_Float32Array_iter_from";
            case PACKED_FLOAT64_ARRAY -> "gdcc_for_packed_Float64Array_iter_from";
            case PACKED_STRING_ARRAY -> "gdcc_for_packed_StringArray_iter_from";
            case PACKED_VECTOR2_ARRAY -> "gdcc_for_packed_Vector2Array_iter_from";
            case PACKED_VECTOR3_ARRAY -> "gdcc_for_packed_Vector3Array_iter_from";
            case PACKED_VECTOR4_ARRAY -> "gdcc_for_packed_Vector4Array_iter_from";
            case PACKED_COLOR_ARRAY -> "gdcc_for_packed_ColorArray_iter_from";
            default -> throw new IllegalArgumentException(
                    "unsupported packed array type for specialized iterator: " + packedType.getTypeName()
            );
        };
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
            if (spec.acceptAnyPackedArray() && i == 0) {
                checkPackedArrayType(bodyBuilder, argVars.get(i));
            } else {
                checkType(bodyBuilder, "argument #" + (i + 1), argVars.get(i), spec.argumentTypes().get(i));
            }
        }
    }

    private void checkPackedArrayType(@NotNull CBodyBuilder bodyBuilder, @NotNull LirVariable variable) {
        if (!(variable.type() instanceof GdPackedArrayType)) {
            throw bodyBuilder.invalidInsn("'" + name() + "' argument #1 variable '" + variable.id() +
                    "' must be a Packed*Array type, got '" + variable.type().getTypeName() + "'");
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
                        @Nullable String helperName,
                        @NotNull GdType resultType,
                        @NotNull List<GdType> argumentTypes,
                        boolean acceptAnyPackedArray) {
        private Spec {
            argumentTypes = List.copyOf(argumentTypes);
            if (!acceptAnyPackedArray && (helperName == null || helperName.isBlank())) {
                throw new IllegalArgumentException("non-init packed array intrinsic requires a helper name");
            }
        }
    }
}
