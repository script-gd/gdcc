package gd.script.gdcc.backend.c.gen.intrinsic;

import gd.script.gdcc.backend.c.gen.CBodyBuilder;
import gd.script.gdcc.backend.c.gen.CIntrinsicFunction;
import gd.script.gdcc.lir.LirVariable;
import gd.script.gdcc.type.GdFloatVectorType;
import gd.script.gdcc.type.GdIntVectorType;
import gd.script.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/// Materializes frontend ordinary-boundary `Vector*i -> Vector*` widening casts.
///
/// These conversions are Godot builtin constructor conversions, not C casts. The intrinsic keeps
/// the fixed 2/3/4D mapping local and leaves argument pointer shape plus slot writes to
/// `CBodyBuilder.callAssign(...)`.
public final class CVectorIToVectorIntrinsic implements CIntrinsicFunction {
    public static final @NotNull String VECTOR2I_TO_VECTOR2_NAME = "c_vector2i_to_vector2";
    public static final @NotNull String VECTOR3I_TO_VECTOR3_NAME = "c_vector3i_to_vector3";
    public static final @NotNull String VECTOR4I_TO_VECTOR4_NAME = "c_vector4i_to_vector4";

    private final @NotNull Spec spec;

    private CVectorIToVectorIntrinsic(@NotNull Spec spec) {
        this.spec = spec;
    }

    public static @NotNull CVectorIToVectorIntrinsic vector2() {
        return new CVectorIToVectorIntrinsic(new Spec(
                VECTOR2I_TO_VECTOR2_NAME,
                GdIntVectorType.VECTOR2I,
                GdFloatVectorType.VECTOR2
        ));
    }

    public static @NotNull CVectorIToVectorIntrinsic vector3() {
        return new CVectorIToVectorIntrinsic(new Spec(
                VECTOR3I_TO_VECTOR3_NAME,
                GdIntVectorType.VECTOR3I,
                GdFloatVectorType.VECTOR3
        ));
    }

    public static @NotNull CVectorIToVectorIntrinsic vector4() {
        return new CVectorIToVectorIntrinsic(new Spec(
                VECTOR4I_TO_VECTOR4_NAME,
                GdIntVectorType.VECTOR4I,
                GdFloatVectorType.VECTOR4
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
        if (resultVar == null) {
            throw bodyBuilder.invalidInsn("'" + name() + "' requires a result variable");
        }
        if (resultVar.ref()) {
            throw bodyBuilder.invalidInsn("'" + name() + "' result variable '" + resultVar.id() + "' cannot be a reference");
        }
        checkType(bodyBuilder, "result", resultVar, spec.targetType());
        if (argVars.size() != 1) {
            throw bodyBuilder.invalidInsn("'" + name() + "' requires exactly one argument, got " + argVars.size());
        }

        var sourceVar = argVars.getFirst();
        checkType(bodyBuilder, "argument", sourceVar, spec.sourceType());

        var builtinBuilder = bodyBuilder.helper().builtinBuilder();
        var ctorArgTypes = List.<GdType>of(spec.sourceType());
        try {
            builtinBuilder.validateConstructor(spec.targetType(), ctorArgTypes, false);
        } catch (IllegalArgumentException ex) {
            throw bodyBuilder.invalidInsn("'" + name() + "' " + ex.getMessage());
        }
        var ctorName = builtinBuilder.renderConstructorFunctionNameByTypes(spec.targetType(), ctorArgTypes);
        bodyBuilder.callAssign(
                bodyBuilder.targetOfVar(resultVar),
                ctorName,
                spec.targetType(),
                List.of(bodyBuilder.valueOfVar(sourceVar))
        );
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
                        @NotNull GdIntVectorType sourceType,
                        @NotNull GdFloatVectorType targetType) {
    }
}
