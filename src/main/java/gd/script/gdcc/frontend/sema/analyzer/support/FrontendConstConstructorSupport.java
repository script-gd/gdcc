package gd.script.gdcc.frontend.sema.analyzer.support;

import gd.script.gdcc.frontend.constant.ConstCallResult;
import gd.script.gdcc.frontend.constant.GdConstantValue;
import gd.script.gdcc.frontend.sema.FrontendCallResolutionStatus;
import gd.script.gdcc.gdextension.ExtensionBuiltinClass;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.ParameterDef;
import gd.script.gdcc.scope.ScopeTypeMeta;
import gd.script.gdcc.scope.ScopeTypeMetaKind;
import gd.script.gdcc.type.GdAABBType;
import gd.script.gdcc.type.GdBasisType;
import gd.script.gdcc.type.GdBoolType;
import gd.script.gdcc.type.GdColorType;
import gd.script.gdcc.type.GdExtensionTypeEnum;
import gd.script.gdcc.type.GdFloatType;
import gd.script.gdcc.type.GdFloatVectorType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdIntVectorType;
import gd.script.gdcc.type.GdPlaneType;
import gd.script.gdcc.type.GdProjectionType;
import gd.script.gdcc.type.GdQuaternionType;
import gd.script.gdcc.type.GdRect2Type;
import gd.script.gdcc.type.GdRect2iType;
import gd.script.gdcc.type.GdStringType;
import gd.script.gdcc.type.GdTransform2DType;
import gd.script.gdcc.type.GdTransform3DType;
import gd.script.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

/// The §5.3 constant-constructor whitelist (const plan §5.3).
///
/// Selection reuses the ordinary `FrontendConstructorResolutionSupport` overload machinery —
/// the same metadata-driven applicability and ranking a regular `Vector2(...)` call would get —
/// then admits only signatures declared in this explicit whitelist. A `is_const`-looking or
/// otherwise legal overload that is not listed here rejects instead of being treated as pure:
/// axis-angle constructors, `Basis(Quaternion)`, `Quaternion(Basis)`, `Color(String)`,
/// `Transform3D(Projection)`, `Plane` point/normal variants, scalar string parsing, and every
/// other API constructor shape stay outside the constant surface.
final class FrontendConstConstructorSupport {
    private FrontendConstConstructorSupport() {
    }

    /// One whitelisted constructor signature: exact declared parameter types plus the frozen
    /// construction recipe applied to already-normalized argument values.
    ///
    /// @param parameterTypes declared parameter types in signature order (empty for `T()`)
    /// @param evaluator      pure recipe producing the constant value or a domain rejection
    record WhitelistedConstructor(
            @NotNull List<GdType> parameterTypes,
            @NotNull ConstructorEvaluator evaluator
    ) {
    }

    /// Pure recipe for one whitelisted signature. Arguments arrive normalized to the declared
    /// parameter types (identity, `int -> float`, or same-dimension `Vector*i -> Vector*` —
    /// the same boundary conversions the evaluator already applies to member-call arguments).
    @FunctionalInterface
    interface ConstructorEvaluator {
        @NotNull ConstCallResult construct(@NotNull List<GdConstantValue> arguments);
    }

    /// Outcome of matching a constant call against the constructor whitelist.
    sealed interface Selection {
        /// A whitelisted signature matched — ready for value normalization and construction.
        record Selected(@NotNull WhitelistedConstructor constructor) implements Selection {
        }

        /// Target type, argument surface, overload resolution, or the whitelist itself failed.
        record Rejected(@NotNull String reason) implements Selection {
        }
    }

    /// Selects the constant-evaluatable constructor for `targetType` called with
    /// `argumentTypes`: ordinary metadata overload resolution first, then the whitelist check
    /// on the selected signature's declared parameter types.
    static @NotNull Selection selectConstructor(
            @NotNull ClassRegistry classRegistry,
            @NotNull GdType targetType,
            @NotNull List<GdType> argumentTypes
    ) {
        var candidates = WHITELIST.get(targetType.getGdExtensionType());
        if (candidates == null) {
            return new Selection.Rejected(
                    "type '" + targetType.getTypeName() + "' is not a constant-constructible type");
        }
        for (var index = 0; index < argumentTypes.size(); index++) {
            var argumentType = argumentTypes.get(index);
            if (!GdConstantValue.isSupportedConstantType(argumentType)) {
                return new Selection.Rejected(
                        "argument #" + (index + 1) + " of type '" + argumentType.getTypeName()
                                + "' is not a supported constant type");
            }
        }
        var receiverMeta = new ScopeTypeMeta(
                targetType.getTypeName(),
                targetType.getTypeName(),
                targetType,
                ScopeTypeMetaKind.BUILTIN,
                null,
                false
        );
        var resolution = FrontendConstructorResolutionSupport.resolveConstructor(
                classRegistry, receiverMeta, argumentTypes);
        if (resolution.status() != FrontendCallResolutionStatus.RESOLVED) {
            return new Selection.Rejected(resolution.detailReason());
        }
        if (!(resolution.declarationSite() instanceof ExtensionBuiltinClass.ConstructorInfo constructor)) {
            return new Selection.Rejected(
                    "constructor selection for '" + targetType.getTypeName()
                            + "' did not resolve to a builtin constructor");
        }
        var parameterTypes = constructor.getParameters().stream().map(ParameterDef::getType).toList();
        for (var candidate : candidates) {
            if (sameParameterTypes(candidate.parameterTypes(), parameterTypes)) {
                return new Selection.Selected(candidate);
            }
        }
        return new Selection.Rejected(
                "builtin constructor '" + targetType.getTypeName() + "("
                        + renderParameterTypes(parameterTypes)
                        + ")' is not in the constant constructor whitelist");
    }

    private static boolean sameParameterTypes(
            @NotNull List<GdType> whitelistTypes,
            @NotNull List<GdType> declaredTypes
    ) {
        if (whitelistTypes.size() != declaredTypes.size()) {
            return false;
        }
        for (var index = 0; index < whitelistTypes.size(); index++) {
            if (!FrontendConstCallSelectionSupport.sameExactType(
                    whitelistTypes.get(index), declaredTypes.get(index))) {
                return false;
            }
        }
        return true;
    }

    private static @NotNull String renderParameterTypes(@NotNull List<GdType> parameterTypes) {
        var joiner = new StringJoiner(", ");
        for (var parameterType : parameterTypes) {
            joiner.add(parameterType.getTypeName());
        }
        return joiner.toString();
    }

    // ---------------------------------------------------------------------
    // Construction recipes — pure value transforms on normalized arguments
    // ---------------------------------------------------------------------

    /// `T(T)` copy — the canonical argument value passes through unchanged.
    private static @NotNull ConstCallResult copyArgument(@NotNull List<GdConstantValue> args) {
        return new ConstCallResult.Resolved(args.getFirst());
    }

    /// `int(float)` — toward-zero truncation inside the int64 range; non-finite and
    /// out-of-range values reject instead of saturating.
    private static @NotNull ConstCallResult intFromFloat(@NotNull List<GdConstantValue> args) {
        var value = ((GdConstantValue.FloatValue) args.getFirst()).value();
        if (!Double.isFinite(value) || value < -0x1.0p63 || value >= 0x1.0p63) {
            return new ConstCallResult.Rejected(
                    "float value " + value + " is not convertible to int in a constant expression");
        }
        return new ConstCallResult.Resolved(new GdConstantValue.IntValue((long) value));
    }

    private static @NotNull ConstCallResult intFromBool(@NotNull List<GdConstantValue> args) {
        return new ConstCallResult.Resolved(
                new GdConstantValue.IntValue(((GdConstantValue.BoolValue) args.getFirst()).value() ? 1 : 0));
    }

    private static @NotNull ConstCallResult floatFromInt(@NotNull List<GdConstantValue> args) {
        return new ConstCallResult.Resolved(
                new GdConstantValue.FloatValue(((GdConstantValue.IntValue) args.getFirst()).value()));
    }

    private static @NotNull ConstCallResult floatFromBool(@NotNull List<GdConstantValue> args) {
        return new ConstCallResult.Resolved(
                new GdConstantValue.FloatValue(
                        ((GdConstantValue.BoolValue) args.getFirst()).value() ? 1.0 : 0.0));
    }

    private static @NotNull ConstCallResult boolFromInt(@NotNull List<GdConstantValue> args) {
        return new ConstCallResult.Resolved(
                new GdConstantValue.BoolValue(((GdConstantValue.IntValue) args.getFirst()).value() != 0));
    }

    private static @NotNull ConstCallResult boolFromFloat(@NotNull List<GdConstantValue> args) {
        return new ConstCallResult.Resolved(
                new GdConstantValue.BoolValue(
                        ((GdConstantValue.FloatValue) args.getFirst()).value() != 0.0));
    }

    /// `VectorN(VectorNi)` / `Rect2(Rect2i)` — int leaves widen to float exactly.
    private static @NotNull ConstCallResult widenIntLeavesToFloat(
            @NotNull GdType targetType,
            @NotNull List<GdConstantValue> args
    ) {
        var source = (GdConstantValue.BuiltinValue) args.getFirst();
        var leaves = new ArrayList<GdConstantValue>(source.components().size());
        for (var leaf : source.components()) {
            leaves.add(new GdConstantValue.FloatValue(
                    ((GdConstantValue.IntValue) leaf).value()));
        }
        return new ConstCallResult.Resolved(new GdConstantValue.BuiltinValue(targetType, leaves));
    }

    /// `VectorNi(VectorN)` / `Rect2i(Rect2)` — toward-zero truncation into the int32
    /// component range; non-finite or out-of-range leaves reject.
    private static @NotNull ConstCallResult truncateFloatLeavesToInt(
            @NotNull GdType targetType,
            @NotNull List<GdConstantValue> args
    ) {
        var source = (GdConstantValue.BuiltinValue) args.getFirst();
        var leaves = new ArrayList<GdConstantValue>(source.components().size());
        for (var leaf : source.components()) {
            var value = ((GdConstantValue.FloatValue) leaf).value();
            var truncated = truncateToInt32(value);
            if (truncated == null) {
                return new ConstCallResult.Rejected(
                        "component " + value + " is not finite or outside the int32 component range");
            }
            leaves.add(new GdConstantValue.IntValue(truncated));
        }
        return new ConstCallResult.Resolved(new GdConstantValue.BuiltinValue(targetType, leaves));
    }

    private static @Nullable Integer truncateToInt32(double value) {
        if (!Double.isFinite(value)) {
            return null;
        }
        // Saturating for out-of-long-range inputs — the int32 bound rejects them anyway.
        var truncated = (long) value;
        return truncated >= Integer.MIN_VALUE && truncated <= Integer.MAX_VALUE
                ? (int) truncated
                : null;
    }

    /// Scalar-leaf constructors `T(float, ...)` — collect the float arguments verbatim; the
    /// `BuiltinValue` constructor applies the float32 narrowing itself.
    private static @NotNull ConstCallResult fromFloatArgs(
            @NotNull GdType targetType,
            @NotNull List<GdConstantValue> args
    ) {
        var leaves = new ArrayList<GdConstantValue>(args.size());
        for (var arg : args) {
            leaves.add(new GdConstantValue.FloatValue(
                    ((GdConstantValue.FloatValue) arg).value()));
        }
        return new ConstCallResult.Resolved(new GdConstantValue.BuiltinValue(targetType, leaves));
    }

    /// Scalar-leaf constructors `T(int, ...)` — int arguments are int64, so each must be
    /// range-checked into int32 before `BuiltinValue` admits it.
    private static @NotNull ConstCallResult fromIntArgs(
            @NotNull GdType targetType,
            @NotNull List<GdConstantValue> args
    ) {
        var leaves = new ArrayList<GdConstantValue>(args.size());
        for (var arg : args) {
            var value = ((GdConstantValue.IntValue) arg).value();
            if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
                return new ConstCallResult.Rejected(
                        "int component " + value + " is outside the int32 component range");
            }
            leaves.add(new GdConstantValue.IntValue(value));
        }
        return new ConstCallResult.Resolved(new GdConstantValue.BuiltinValue(targetType, leaves));
    }

    /// Composite constructors — `Rect2(Vector2, Vector2)`, `Plane(Vector3, float)`,
    /// `Transform3D(Basis, Vector3)`, the axis-column forms — where concatenating each
    /// argument's flat leaves in signature order already produces the canonical layout.
    private static @NotNull ConstCallResult concatLeaves(
            @NotNull GdType targetType,
            @NotNull List<GdConstantValue> args
    ) {
        var leaves = new ArrayList<GdConstantValue>();
        for (var arg : args) {
            if (arg instanceof GdConstantValue.BuiltinValue builtin) {
                leaves.addAll(builtin.components());
            } else {
                leaves.add(arg);
            }
        }
        return new ConstCallResult.Resolved(new GdConstantValue.BuiltinValue(targetType, leaves));
    }

    /// `Color(float, float, float)` — alpha defaults to 1.
    private static @NotNull ConstCallResult colorRgb(@NotNull List<GdConstantValue> args) {
        var leaves = new ArrayList<GdConstantValue>(args.size() + 1);
        leaves.addAll(args);
        leaves.add(new GdConstantValue.FloatValue(1.0));
        return new ConstCallResult.Resolved(new GdConstantValue.BuiltinValue(GdColorType.COLOR, leaves));
    }

    /// `Color(Color, float alpha)` — keep the source color's rgb leaves, replace alpha.
    private static @NotNull ConstCallResult colorWithAlpha(@NotNull List<GdConstantValue> args) {
        var color = (GdConstantValue.BuiltinValue) args.getFirst();
        var leaves = new ArrayList<>(color.components().subList(0, 3));
        leaves.add(args.get(1));
        return new ConstCallResult.Resolved(new GdConstantValue.BuiltinValue(GdColorType.COLOR, leaves));
    }

    // ---------------------------------------------------------------------
    // Whitelist and default-value tables
    // ---------------------------------------------------------------------

    private static @NotNull WhitelistedConstructor ctor(
            @NotNull ConstructorEvaluator evaluator,
            @NotNull GdType... parameterTypes
    ) {
        return new WhitelistedConstructor(List.of(parameterTypes), evaluator);
    }

    private static @NotNull WhitelistedConstructor defaultCtor(@NotNull GdType type) {
        return new WhitelistedConstructor(List.of(), _ -> new ConstCallResult.Resolved(
                Objects.requireNonNull(
                        DEFAULT_VALUES.get(type.getGdExtensionType()),
                        "missing default value for '" + type.getTypeName() + "'")));
    }

    private static @NotNull Map<GdExtensionTypeEnum, List<WhitelistedConstructor>> buildWhitelist() {
        var table = new EnumMap<GdExtensionTypeEnum, List<WhitelistedConstructor>>(GdExtensionTypeEnum.class);
        table.put(GdExtensionTypeEnum.INT, List.of(
                defaultCtor(GdIntType.INT),
                ctor(FrontendConstConstructorSupport::copyArgument, GdIntType.INT),
                ctor(FrontendConstConstructorSupport::intFromFloat, GdFloatType.FLOAT),
                ctor(FrontendConstConstructorSupport::intFromBool, GdBoolType.BOOL)));
        table.put(GdExtensionTypeEnum.FLOAT, List.of(
                defaultCtor(GdFloatType.FLOAT),
                ctor(FrontendConstConstructorSupport::copyArgument, GdFloatType.FLOAT),
                ctor(FrontendConstConstructorSupport::floatFromInt, GdIntType.INT),
                ctor(FrontendConstConstructorSupport::floatFromBool, GdBoolType.BOOL)));
        table.put(GdExtensionTypeEnum.BOOL, List.of(
                defaultCtor(GdBoolType.BOOL),
                ctor(FrontendConstConstructorSupport::copyArgument, GdBoolType.BOOL),
                ctor(FrontendConstConstructorSupport::boolFromInt, GdIntType.INT),
                ctor(FrontendConstConstructorSupport::boolFromFloat, GdFloatType.FLOAT)));
        table.put(GdExtensionTypeEnum.STRING, List.of(
                defaultCtor(GdStringType.STRING),
                ctor(FrontendConstConstructorSupport::copyArgument, GdStringType.STRING)));
        putFloatVector(table, GdFloatVectorType.VECTOR2, GdIntVectorType.VECTOR2I, 2);
        putFloatVector(table, GdFloatVectorType.VECTOR3, GdIntVectorType.VECTOR3I, 3);
        putFloatVector(table, GdFloatVectorType.VECTOR4, GdIntVectorType.VECTOR4I, 4);
        putIntVector(table, GdIntVectorType.VECTOR2I, GdFloatVectorType.VECTOR2, 2);
        putIntVector(table, GdIntVectorType.VECTOR3I, GdFloatVectorType.VECTOR3, 3);
        putIntVector(table, GdIntVectorType.VECTOR4I, GdFloatVectorType.VECTOR4, 4);
        table.put(GdExtensionTypeEnum.RECT2, List.of(
                defaultCtor(GdRect2Type.RECT2),
                ctor(FrontendConstConstructorSupport::copyArgument, GdRect2Type.RECT2),
                ctor(args -> widenIntLeavesToFloat(GdRect2Type.RECT2, args), GdRect2iType.RECT2I),
                ctor(args -> concatLeaves(GdRect2Type.RECT2, args),
                        GdFloatVectorType.VECTOR2, GdFloatVectorType.VECTOR2),
                ctor(args -> fromFloatArgs(GdRect2Type.RECT2, args),
                        GdFloatType.FLOAT, GdFloatType.FLOAT, GdFloatType.FLOAT, GdFloatType.FLOAT)));
        table.put(GdExtensionTypeEnum.RECT2I, List.of(
                defaultCtor(GdRect2iType.RECT2I),
                ctor(FrontendConstConstructorSupport::copyArgument, GdRect2iType.RECT2I),
                ctor(args -> truncateFloatLeavesToInt(GdRect2iType.RECT2I, args), GdRect2Type.RECT2),
                ctor(args -> concatLeaves(GdRect2iType.RECT2I, args),
                        GdIntVectorType.VECTOR2I, GdIntVectorType.VECTOR2I),
                ctor(args -> fromIntArgs(GdRect2iType.RECT2I, args),
                        GdIntType.INT, GdIntType.INT, GdIntType.INT, GdIntType.INT)));
        table.put(GdExtensionTypeEnum.PLANE, List.of(
                defaultCtor(GdPlaneType.PLANE),
                ctor(FrontendConstConstructorSupport::copyArgument, GdPlaneType.PLANE),
                ctor(args -> concatLeaves(GdPlaneType.PLANE, args), GdFloatVectorType.VECTOR3, GdFloatType.FLOAT),
                ctor(args -> fromFloatArgs(GdPlaneType.PLANE, args),
                        GdFloatType.FLOAT, GdFloatType.FLOAT, GdFloatType.FLOAT, GdFloatType.FLOAT)));
        table.put(GdExtensionTypeEnum.QUATERNION, List.of(
                defaultCtor(GdQuaternionType.QUATERNION),
                ctor(FrontendConstConstructorSupport::copyArgument, GdQuaternionType.QUATERNION),
                ctor(args -> fromFloatArgs(GdQuaternionType.QUATERNION, args),
                        GdFloatType.FLOAT, GdFloatType.FLOAT, GdFloatType.FLOAT, GdFloatType.FLOAT)));
        table.put(GdExtensionTypeEnum.AABB, List.of(
                defaultCtor(GdAABBType.AABB),
                ctor(FrontendConstConstructorSupport::copyArgument, GdAABBType.AABB),
                ctor(args -> concatLeaves(GdAABBType.AABB, args),
                        GdFloatVectorType.VECTOR3, GdFloatVectorType.VECTOR3)));
        table.put(GdExtensionTypeEnum.BASIS, List.of(
                defaultCtor(GdBasisType.BASIS),
                ctor(FrontendConstConstructorSupport::copyArgument, GdBasisType.BASIS),
                ctor(args -> concatLeaves(GdBasisType.BASIS, args),
                        GdFloatVectorType.VECTOR3, GdFloatVectorType.VECTOR3, GdFloatVectorType.VECTOR3)));
        table.put(GdExtensionTypeEnum.TRANSFORM2D, List.of(
                defaultCtor(GdTransform2DType.TRANSFORM2D),
                ctor(FrontendConstConstructorSupport::copyArgument, GdTransform2DType.TRANSFORM2D),
                ctor(args -> concatLeaves(GdTransform2DType.TRANSFORM2D, args),
                        GdFloatVectorType.VECTOR2, GdFloatVectorType.VECTOR2, GdFloatVectorType.VECTOR2)));
        table.put(GdExtensionTypeEnum.TRANSFORM3D, List.of(
                defaultCtor(GdTransform3DType.TRANSFORM3D),
                ctor(FrontendConstConstructorSupport::copyArgument, GdTransform3DType.TRANSFORM3D),
                ctor(args -> concatLeaves(GdTransform3DType.TRANSFORM3D, args),
                        GdBasisType.BASIS, GdFloatVectorType.VECTOR3),
                ctor(args -> concatLeaves(GdTransform3DType.TRANSFORM3D, args),
                        GdFloatVectorType.VECTOR3, GdFloatVectorType.VECTOR3,
                        GdFloatVectorType.VECTOR3, GdFloatVectorType.VECTOR3)));
        table.put(GdExtensionTypeEnum.PROJECTION, List.of(
                defaultCtor(GdProjectionType.PROJECTION),
                ctor(FrontendConstConstructorSupport::copyArgument, GdProjectionType.PROJECTION),
                ctor(args -> concatLeaves(GdProjectionType.PROJECTION, args),
                        GdFloatVectorType.VECTOR4, GdFloatVectorType.VECTOR4,
                        GdFloatVectorType.VECTOR4, GdFloatVectorType.VECTOR4)));
        table.put(GdExtensionTypeEnum.COLOR, List.of(
                defaultCtor(GdColorType.COLOR),
                ctor(FrontendConstConstructorSupport::copyArgument, GdColorType.COLOR),
                ctor(FrontendConstConstructorSupport::colorWithAlpha, GdColorType.COLOR, GdFloatType.FLOAT),
                ctor(FrontendConstConstructorSupport::colorRgb,
                        GdFloatType.FLOAT, GdFloatType.FLOAT, GdFloatType.FLOAT),
                ctor(args -> fromFloatArgs(GdColorType.COLOR, args),
                        GdFloatType.FLOAT, GdFloatType.FLOAT, GdFloatType.FLOAT, GdFloatType.FLOAT)));
        return Collections.unmodifiableMap(table);
    }

    /// `VectorN` signatures: `T()`, `T(T)`, `T(VectorNi)` widening, `T(float ×N)`.
    private static void putFloatVector(
            @NotNull Map<GdExtensionTypeEnum, List<WhitelistedConstructor>> table,
            @NotNull GdType vectorType,
            @NotNull GdType intVectorType,
            int dimension
    ) {
        var floatParams = Collections.nCopies(dimension, GdFloatType.FLOAT);
        table.put(vectorType.getGdExtensionType(), List.of(
                defaultCtor(vectorType),
                ctor(FrontendConstConstructorSupport::copyArgument, vectorType),
                ctor(args -> widenIntLeavesToFloat(vectorType, args), intVectorType),
                ctor(args -> fromFloatArgs(vectorType, args), floatParams.toArray(new GdType[0]))));
    }

    /// `VectorNi` signatures: `T()`, `T(T)`, `T(VectorN)` truncation (finite + int32), `T(int ×N)`.
    private static void putIntVector(
            @NotNull Map<GdExtensionTypeEnum, List<WhitelistedConstructor>> table,
            @NotNull GdType intVectorType,
            @NotNull GdType floatVectorType,
            int dimension
    ) {
        var intParams = Collections.nCopies(dimension, GdIntType.INT);
        table.put(intVectorType.getGdExtensionType(), List.of(
                defaultCtor(intVectorType),
                ctor(FrontendConstConstructorSupport::copyArgument, intVectorType),
                ctor(args -> truncateFloatLeavesToInt(intVectorType, args), floatVectorType),
                ctor(args -> fromIntArgs(intVectorType, args), intParams.toArray(new GdType[0]))));
    }

    private static @NotNull Map<GdExtensionTypeEnum, GdConstantValue> buildDefaults() {
        var defaults = new EnumMap<GdExtensionTypeEnum, GdConstantValue>(GdExtensionTypeEnum.class);
        defaults.put(GdExtensionTypeEnum.INT, new GdConstantValue.IntValue(0));
        defaults.put(GdExtensionTypeEnum.FLOAT, new GdConstantValue.FloatValue(0.0));
        defaults.put(GdExtensionTypeEnum.BOOL, new GdConstantValue.BoolValue(false));
        defaults.put(GdExtensionTypeEnum.STRING, new GdConstantValue.StringValue(""));
        putZeroFloats(defaults, GdFloatVectorType.VECTOR2);
        putZeroFloats(defaults, GdFloatVectorType.VECTOR3);
        putZeroFloats(defaults, GdFloatVectorType.VECTOR4);
        putZeroInts(defaults, GdIntVectorType.VECTOR2I);
        putZeroInts(defaults, GdIntVectorType.VECTOR3I);
        putZeroInts(defaults, GdIntVectorType.VECTOR4I);
        putZeroFloats(defaults, GdRect2Type.RECT2);
        putZeroInts(defaults, GdRect2iType.RECT2I);
        putZeroFloats(defaults, GdPlaneType.PLANE);
        putZeroFloats(defaults, GdAABBType.AABB);
        // Non-zero defaults: identity quaternions/transforms and opaque color.
        defaults.put(GdExtensionTypeEnum.QUATERNION,
                GdConstantValue.BuiltinValue.ofFloats(GdQuaternionType.QUATERNION, 0, 0, 0, 1));
        defaults.put(GdExtensionTypeEnum.BASIS,
                GdConstantValue.BuiltinValue.ofFloats(GdBasisType.BASIS, 1, 0, 0, 0, 1, 0, 0, 0, 1));
        defaults.put(GdExtensionTypeEnum.TRANSFORM2D,
                GdConstantValue.BuiltinValue.ofFloats(GdTransform2DType.TRANSFORM2D, 1, 0, 0, 1, 0, 0));
        defaults.put(GdExtensionTypeEnum.TRANSFORM3D,
                GdConstantValue.BuiltinValue.ofFloats(GdTransform3DType.TRANSFORM3D,
                        1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0));
        defaults.put(GdExtensionTypeEnum.PROJECTION,
                GdConstantValue.BuiltinValue.ofFloats(GdProjectionType.PROJECTION,
                        1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1));
        defaults.put(GdExtensionTypeEnum.COLOR,
                GdConstantValue.BuiltinValue.ofFloats(GdColorType.COLOR, 0, 0, 0, 1));
        return Collections.unmodifiableMap(defaults);
    }

    private static void putZeroFloats(
            @NotNull Map<GdExtensionTypeEnum, GdConstantValue> defaults,
            @NotNull GdType type
    ) {
        var leaves = new GdConstantValue[GdConstantValue.BuiltinValue.leafCountOf(type)];
        for (var index = 0; index < leaves.length; index++) {
            leaves[index] = new GdConstantValue.FloatValue(0.0);
        }
        defaults.put(type.getGdExtensionType(),
                new GdConstantValue.BuiltinValue(type, List.of(leaves)));
    }

    private static void putZeroInts(
            @NotNull Map<GdExtensionTypeEnum, GdConstantValue> defaults,
            @NotNull GdType type
    ) {
        var leaves = new GdConstantValue[GdConstantValue.BuiltinValue.leafCountOf(type)];
        for (var index = 0; index < leaves.length; index++) {
            leaves[index] = new GdConstantValue.IntValue(0);
        }
        defaults.put(type.getGdExtensionType(),
                new GdConstantValue.BuiltinValue(type, List.of(leaves)));
    }

    private static final Map<GdExtensionTypeEnum, GdConstantValue> DEFAULT_VALUES = buildDefaults();
    private static final Map<GdExtensionTypeEnum, List<WhitelistedConstructor>> WHITELIST = buildWhitelist();
}
