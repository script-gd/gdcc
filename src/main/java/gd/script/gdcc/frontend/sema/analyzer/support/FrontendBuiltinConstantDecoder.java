package gd.script.gdcc.frontend.sema.analyzer.support;

import gd.script.gdcc.frontend.constant.ConstCallResult;
import gd.script.gdcc.frontend.constant.GdConstantValue;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdExtensionTypeEnum;
import gd.script.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/// Decodes builtin metadata constant text into canonical `GdConstantValue`s (const plan §5.4).
///
/// The grammar is deliberately a small restricted constructor form — `TypeName(arg, ...)` with
/// flat scalar arguments — matching exactly what `VariantWriter` emits for builtin class
/// constants (`Vector2(0, 0)`, `Transform3D(1, 0, ...)`, `Color(1, 1, 1, 1)`). It is not an
/// expression evaluator: unknown shapes, nested constructors, and non-scalar tokens reject
/// instead of being interpreted. The frontend owns this decoder — it never delegates to the
/// C backend's `CBuiltinBuilder` literal parsing.
final class FrontendBuiltinConstantDecoder {
    private FrontendBuiltinConstantDecoder() {
    }

    /// Resolves `memberName` as a builtin-class constant on `ownerType`'s metadata class.
    ///
    /// `Unknown` means the metadata class exposes no such constant (builtin class enum members
    /// such as `Vector2.AXIS_X` live in the `enums` arrays, not `constants`, and stay unknown
    /// in v1). A constant that exists but decodes badly or declares a non-constant type
    /// surfaces as `NonConstant` — a claimed but unusable name, never a silent miss.
    static @NotNull LookupContext.LookupResult resolveConstant(
            @NotNull ClassRegistry classRegistry,
            @NotNull GdType ownerType,
            @NotNull String memberName
    ) {
        var lookup = classRegistry.findBuiltinClassConstantInHierarchy(
                ownerType.getTypeName(), memberName);
        if (lookup == null) {
            return new LookupContext.LookupResult.Unknown();
        }
        var constant = lookup.constant();
        var declaredType = resolveDeclaredType(classRegistry, ownerType, constant.type());
        if (declaredType == null || !GdConstantValue.isSupportedConstantType(declaredType)) {
            return new LookupContext.LookupResult.NonConstant(
                    "builtin constant '" + ownerType.getTypeName() + "." + memberName
                            + "' declares a type outside the constant value surface");
        }
        return switch (decodeConstantText(declaredType, constant.value())) {
            case ConstCallResult.Resolved resolved ->
                    new LookupContext.LookupResult.ResolvedConstant(resolved.value());
            case ConstCallResult.Rejected rejected -> new LookupContext.LookupResult.NonConstant(
                    "builtin constant '" + ownerType.getTypeName() + "." + memberName
                            + "' could not be decoded: " + rejected.reason());
        };
    }

    /// Decodes restricted constant text into the canonical value for `declaredType`.
    ///
    /// Scalar types decode a bare literal token (`-2147483648`, `inf`, `true`); controlled
    /// builtin types decode the fixed `TypeName(arg, ...)` form whose flat arity must match the
    /// canonical leaf count exactly. `String` builtin constants are not part of the surface.
    static @NotNull ConstCallResult decodeConstantText(
            @NotNull GdType declaredType,
            @NotNull String valueText
    ) {
        Objects.requireNonNull(declaredType, "declaredType must not be null");
        Objects.requireNonNull(valueText, "valueText must not be null");
        var extensionType = declaredType.getGdExtensionType();
        if (extensionType == null || !GdConstantValue.isSupportedConstantType(declaredType)) {
            return new ConstCallResult.Rejected(
                    "type '" + declaredType.getTypeName() + "' is not a constant value type");
        }
        return switch (extensionType) {
            case INT -> decodeIntText(valueText);
            case FLOAT -> decodeFloatText(valueText);
            case BOOL -> decodeBoolText(valueText);
            case STRING -> new ConstCallResult.Rejected(
                    "String builtin constants are not supported in constant metadata decoding");
            default -> decodeBuiltinCtorText(declaredType, valueText);
        };
    }

    private static @NotNull ConstCallResult decodeIntText(@NotNull String valueText) {
        var value = parseIntegerToken(valueText.trim());
        if (value == null) {
            return new ConstCallResult.Rejected(
                    "malformed int constant text '" + valueText + "'");
        }
        return new ConstCallResult.Resolved(new GdConstantValue.IntValue(value));
    }

    private static @NotNull ConstCallResult decodeFloatText(@NotNull String valueText) {
        var value = parseScalarToken(valueText.trim());
        if (value == null) {
            return new ConstCallResult.Rejected(
                    "malformed float constant text '" + valueText + "'");
        }
        return new ConstCallResult.Resolved(new GdConstantValue.FloatValue(value));
    }

    private static @NotNull ConstCallResult decodeBoolText(@NotNull String valueText) {
        return switch (valueText.trim()) {
            case "true" -> new ConstCallResult.Resolved(new GdConstantValue.BoolValue(true));
            case "false" -> new ConstCallResult.Resolved(new GdConstantValue.BoolValue(false));
            default -> new ConstCallResult.Rejected(
                    "malformed bool constant text '" + valueText + "'");
        };
    }

    /// Builtin constants decode as `TypeName(a1, ...)` whose flat scalar argument count equals
    /// the canonical leaf count — vectors at their dimension, `Rect2`/`Rect2i`/`Plane`/
    /// `Quaternion`/`Color` at 4, `AABB`/`Transform2D` at 6, `Basis` at 9, `Transform3D` at 12,
    /// and `Projection` at 16. The flat encoding is metadata-internal; user source constructors
    /// never see this grammar.
    private static @NotNull ConstCallResult decodeBuiltinCtorText(
            @NotNull GdType declaredType,
            @NotNull String valueText
    ) {
        var leafCount = GdConstantValue.BuiltinValue.leafCountOf(declaredType);
        if (leafCount < 0) {
            return new ConstCallResult.Rejected(
                    "type '" + declaredType.getTypeName()
                            + "' is not a controlled builtin constant type");
        }
        var matcher = CTOR_TEXT_PATTERN.matcher(valueText.trim());
        if (!matcher.matches()) {
            return new ConstCallResult.Rejected(
                    "malformed builtin constant text '" + valueText + "'");
        }
        var typeName = matcher.group(1);
        if (!typeName.equals(declaredType.getTypeName())) {
            return new ConstCallResult.Rejected(
                    "constant text type '" + typeName + "' does not match the declared type '"
                            + declaredType.getTypeName() + "'");
        }
        var argTexts = splitArguments(matcher.group(2));
        if (argTexts.size() != leafCount) {
            return new ConstCallResult.Rejected(
                    "constant text '" + valueText + "' has " + argTexts.size()
                            + " components but type '" + declaredType.getTypeName()
                            + "' requires " + leafCount);
        }
        return switch (Objects.requireNonNull(
                GdConstantValue.BuiltinValue.leafKindOf(declaredType))) {
            case INT -> decodeIntLeaves(declaredType, argTexts);
            case FLOAT -> decodeFloatLeaves(declaredType, argTexts);
        };
    }

    private static @NotNull ConstCallResult decodeIntLeaves(
            @NotNull GdType type,
            @NotNull List<String> argTexts
    ) {
        var leaves = new ArrayList<GdConstantValue>(argTexts.size());
        for (var argText : argTexts) {
            var value = parseIntegerToken(argText);
            if (value == null) {
                return new ConstCallResult.Rejected(
                        "component '" + argText + "' is not a valid integer literal");
            }
            // `*i`/`Rect2i` leaves are int32 — the check runs here so a bad constant surfaces
            // as a decoding rejection instead of a `BuiltinValue` programmer guard.
            if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
                return new ConstCallResult.Rejected(
                        "component " + value + " is out of the int32 component range");
            }
            leaves.add(new GdConstantValue.IntValue(value));
        }
        return new ConstCallResult.Resolved(new GdConstantValue.BuiltinValue(type, leaves));
    }

    private static @NotNull ConstCallResult decodeFloatLeaves(
            @NotNull GdType type,
            @NotNull List<String> argTexts
    ) {
        var flat = new double[argTexts.size()];
        for (var index = 0; index < argTexts.size(); index++) {
            var value = parseScalarToken(argTexts.get(index));
            if (value == null) {
                return new ConstCallResult.Rejected(
                        "component '" + argTexts.get(index) + "' is not a valid scalar literal");
            }
            flat[index] = value;
        }
        var canonical = toCanonicalOrder(type, flat);
        var leaves = new ArrayList<GdConstantValue>(canonical.length);
        for (var component : canonical) {
            leaves.add(new GdConstantValue.FloatValue(component));
        }
        return new ConstCallResult.Resolved(new GdConstantValue.BuiltinValue(type, leaves));
    }

    /// `Basis` and `Transform3D` metadata flat text stores physical row-major values while the
    /// canonical layout keeps logical axis columns — the only two types whose encoding order
    /// differs from the canonical order. Every other flat encoding already matches it.
    private static double[] toCanonicalOrder(@NotNull GdType type, double[] flat) {
        var extensionType = type.getGdExtensionType();
        if (extensionType == GdExtensionTypeEnum.BASIS) {
            var canonical = new double[9];
            for (var column = 0; column < 3; column++) {
                for (var row = 0; row < 3; row++) {
                    canonical[column * 3 + row] = flat[row * 3 + column];
                }
            }
            return canonical;
        }
        if (extensionType == GdExtensionTypeEnum.TRANSFORM3D) {
            var canonical = flat.clone();
            for (var column = 0; column < 3; column++) {
                for (var row = 0; row < 3; row++) {
                    canonical[column * 3 + row] = flat[row * 3 + column];
                }
            }
            return canonical;
        }
        return flat;
    }

    private static @NotNull List<String> splitArguments(@NotNull String inner) {
        var trimmed = inner.trim();
        if (trimmed.isEmpty()) {
            return List.of();
        }
        var parts = new ArrayList<String>();
        for (var part : trimmed.split(",", -1)) {
            parts.add(part.trim());
        }
        return parts;
    }

    /// Scalar literal grammar admitted in builtin constant text: decimal/exponent tokens plus
    /// the `inf`/`-inf`/`nan` spellings `VariantWriter` emits (`inf_neg` kept as a compat
    /// alias). Hex, octal, and expression spellings reject.
    private static @Nullable Double parseScalarToken(@NotNull String token) {
        var trimmed = token.trim();
        return switch (trimmed.toLowerCase(Locale.ROOT)) {
            case "inf", "+inf" -> Double.POSITIVE_INFINITY;
            case "-inf", "inf_neg" -> Double.NEGATIVE_INFINITY;
            case "nan", "+nan", "-nan" -> Double.NaN;
            default -> {
                if (!DECIMAL_PATTERN.matcher(trimmed).matches()) {
                    yield null;
                }
                try {
                    yield Double.parseDouble(trimmed);
                } catch (NumberFormatException ex) {
                    yield null;
                }
            }
        };
    }

    private static @Nullable Long parseIntegerToken(@NotNull String token) {
        var trimmed = token.trim();
        if (!INTEGER_PATTERN.matcher(trimmed).matches()) {
            return null;
        }
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static @Nullable GdType resolveDeclaredType(
            @NotNull ClassRegistry classRegistry,
            @NotNull GdType ownerType,
            @Nullable String typeName
    ) {
        if (typeName == null || typeName.isBlank()) {
            return ownerType;
        }
        return classRegistry.tryResolveDeclaredType(typeName);
    }

    private static final Pattern CTOR_TEXT_PATTERN =
            Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*)\\s*\\((.*)\\)$", Pattern.DOTALL);
    private static final Pattern INTEGER_PATTERN = Pattern.compile("-?\\d+");
    private static final Pattern DECIMAL_PATTERN =
            Pattern.compile("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?");
}
