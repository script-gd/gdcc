package dev.superice.gdcc.backend.c.gen;

import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.type.GdArrayType;
import dev.superice.gdcc.type.GdBasisType;
import dev.superice.gdcc.type.GdBoolType;
import dev.superice.gdcc.type.GdDictionaryType;
import dev.superice.gdcc.type.GdFloatType;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdNodePathType;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdProjectionType;
import dev.superice.gdcc.type.GdStringNameType;
import dev.superice.gdcc.type.GdStringType;
import dev.superice.gdcc.type.GdTransform2DType;
import dev.superice.gdcc.type.GdTransform3DType;
import dev.superice.gdcc.type.GdType;
import dev.superice.gdcc.type.GdVariantType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

import static dev.superice.gdcc.util.StringUtil.escapeStringLiteral;

/// Helper for built-in type constructor symbol generation and constructor metadata lookup.
///
/// This helper is intentionally scoped to built-in constructor naming and metadata validation.
public final class CBuiltinBuilder {
    private static final @NotNull Pattern NUMERIC_LITERAL_PATTERN =
            Pattern.compile("[+-]?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?");
    private static final @NotNull Pattern INTEGER_LITERAL_PATTERN = Pattern.compile("[+-]?\\d+");

    private final @NotNull CGenHelper helper;

    public CBuiltinBuilder(@NotNull CGenHelper helper) {
        this.helper = Objects.requireNonNull(helper);
    }

    /// Render `godot_new_<BuiltinType>` constructor base symbol.
    public @NotNull String renderConstructorBaseName(@NotNull GdType type) {
        return "godot_new_" + helper.renderGdTypeName(type);
    }

    /// Render constructor symbol `godot_new_<BuiltinType>[_with_<argType>...]`.
    ///
    /// `argTypeSuffixes` should match gdextension-lite constructor suffix tokens, e.g.
    /// `float`, `int`, `Vector2`, `utf8_chars`.
    public @NotNull String renderConstructorFunctionName(@NotNull GdType type,
                                                         @NotNull List<String> argTypeSuffixes) {
        var ctorName = renderConstructorBaseName(type);
        if (argTypeSuffixes.isEmpty()) {
            return ctorName;
        }
        var normalizedSuffixes = new ArrayList<String>(argTypeSuffixes.size());
        for (var suffix : argTypeSuffixes) {
            if (suffix == null || suffix.isBlank()) {
                throw new IllegalArgumentException("Constructor argument suffix must not be blank");
            }
            normalizedSuffixes.add(suffix);
        }
        return ctorName + "_with_" + String.join("_", normalizedSuffixes);
    }

    /// Render constructor symbol using GD type names as suffixes.
    public @NotNull String renderConstructorFunctionNameByTypes(@NotNull GdType type,
                                                                @NotNull List<GdType> argTypes) {
        var suffixes = new ArrayList<String>(argTypes.size());
        for (var argType : argTypes) {
            suffixes.add(helper.renderGdTypeName(argType));
        }
        return renderConstructorFunctionName(type, suffixes);
    }

    /// Checks whether ExtensionBuiltinClass metadata contains a constructor with the exact argument type list.
    ///
    /// Matching uses normalized GD type names (`CGenHelper.renderGdTypeName`) to avoid
    /// instance-based equality pitfalls.
    public boolean hasConstructor(@NotNull GdType type, @NotNull List<GdType> argTypes) {
        var builtinClass = helper.context().classRegistry().findBuiltinClass(helper.renderGdTypeName(type));
        if (builtinClass == null) {
            return false;
        }
        var expectedTypeNames = new ArrayList<String>(argTypes.size());
        for (var argType : argTypes) {
            expectedTypeNames.add(helper.renderGdTypeName(argType));
        }
        for (var ctor : builtinClass.constructors()) {
            if (ctor.arguments().size() != expectedTypeNames.size()) {
                continue;
            }
            var matches = true;
            for (var i = 0; i < ctor.arguments().size(); i++) {
                var parsedType = ClassRegistry.tryParseTextType(ctor.arguments().get(i).type());
                if (parsedType == null || !helper.renderGdTypeName(parsedType).equals(expectedTypeNames.get(i))) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return true;
            }
        }
        return false;
    }

    /// Construct a builtin value into `target`.
    ///
    /// This API is shared for construct_builtin and container constructions:
    /// - Array / Dictionary follow dedicated typed-constructor path.
    /// - Other builtin types resolve constructor metadata by exact argument type match.
    /// - If API metadata has no exact constructor, fallback to gdcc helper shims:
    ///   Transform2D, Transform3D, Basis, Projection.
    public void constructBuiltin(@NotNull CBodyBuilder bodyBuilder,
                                 @NotNull CBodyBuilder.TargetRef target,
                                 @NotNull List<CBodyBuilder.ValueRef> args) {
        Objects.requireNonNull(bodyBuilder);
        Objects.requireNonNull(target);
        Objects.requireNonNull(args);
        var targetType = target.type();
        switch (targetType) {
            case GdArrayType arrayType -> constructArray(bodyBuilder, target, arrayType, args);
            case GdDictionaryType dictionaryType -> constructDictionary(bodyBuilder, target, dictionaryType, args);
            default -> constructRegularBuiltin(bodyBuilder, target, targetType, args);
        }
    }

    /// Materializes one utility default literal into the given writable target.
    /// The caller controls target lifetime (for example, temp var declaration/destruction).
    public void materializeUtilityDefaultValue(@NotNull CBodyBuilder bodyBuilder,
                                               @NotNull CBodyBuilder.TargetRef target,
                                               @NotNull String defaultLiteral,
                                               @NotNull String utilityName,
                                               int parameterIndexBaseOne) {
        var errorReporter = new LiteralErrorReporter() {
            @Override
            public RuntimeException invalid(@NotNull String detail) {
                return bodyBuilder.invalidInsn(
                        "Utility '" + utilityName + "' parameter #" + parameterIndexBaseOne + " " + detail
                );
            }

            @Override
            public RuntimeException malformed(@NotNull String literal, @NotNull IllegalArgumentException ex) {
                return bodyBuilder.invalidInsn(
                        "Utility '" + utilityName + "' parameter #" + parameterIndexBaseOne +
                                " default literal '" + literal + "' is malformed: " + ex.getMessage()
                );
            }
        };
        materializeLiteralValue(bodyBuilder, target, defaultLiteral, errorReporter);
    }

    /// Materializes one builtin static constant literal into the given writable target.
    public void materializeStaticLiteralValue(@NotNull CBodyBuilder bodyBuilder,
                                              @NotNull CBodyBuilder.TargetRef target,
                                              @NotNull String literalValue,
                                              @NotNull String className,
                                              @NotNull String constantName) {
        var errorReporter = new LiteralErrorReporter() {
            @Override
            public RuntimeException invalid(@NotNull String detail) {
                return bodyBuilder.invalidInsn(
                        "Builtin constant '" + constantName + "' in class '" + className + "' " + detail
                );
            }

            @Override
            public RuntimeException malformed(@NotNull String literal, @NotNull IllegalArgumentException ex) {
                return bodyBuilder.invalidInsn(
                        "Builtin constant '" + constantName + "' in class '" + className +
                                "' literal '" + literal + "' is malformed: " + ex.getMessage()
                );
            }
        };
        materializeLiteralValue(bodyBuilder, target, literalValue, errorReporter);
    }

    private void materializeLiteralValue(@NotNull CBodyBuilder bodyBuilder,
                                         @NotNull CBodyBuilder.TargetRef target,
                                         @NotNull String rawLiteral,
                                         @NotNull LiteralErrorReporter errorReporter) {
        var expectedType = target.type();
        var literal = rawLiteral.trim();
        if (literal.isEmpty()) {
            throw errorReporter.invalid("has empty literal value");
        }

        if ("null".equals(literal)) {
            if (expectedType instanceof GdVariantType) {
                bodyBuilder.callAssign(target, "godot_new_Variant_nil", GdVariantType.VARIANT, List.of());
                return;
            }
            if (expectedType instanceof GdObjectType) {
                bodyBuilder.assignExpr(target, "NULL", expectedType, CBodyBuilder.PtrKind.GODOT_PTR);
                return;
            }
            throw errorReporter.invalid("literal 'null' is not assignable to '" + expectedType.getTypeName() + "'");
        }

        try {
            switch (expectedType) {
                case GdBoolType _ -> {
                    if (!"true".equals(literal) && !"false".equals(literal)) {
                        throw errorReporter.invalid("expects bool literal, got '" + literal + "'");
                    }
                    bodyBuilder.assignExpr(target, literal, GdBoolType.BOOL);
                    return;
                }
                case GdIntType _ -> {
                    bodyBuilder.assignExpr(target, literal, GdIntType.INT);
                    return;
                }
                case GdFloatType _ -> {
                    bodyBuilder.assignExpr(target, normalizeFloatLiteral(literal), GdFloatType.FLOAT);
                    return;
                }
                default -> {
                }
            }

            if (isQuotedStringNameLiteral(literal)) {
                var value = unescapeQuoted(literal.substring(2, literal.length() - 1));
                bodyBuilder.assignVar(target, bodyBuilder.valueOfStringNamePtrLiteral(value));
                return;
            }
            if (isQuotedStringLiteral(literal)) {
                var value = unescapeQuoted(literal.substring(1, literal.length() - 1));
                if (expectedType instanceof GdNodePathType) {
                    bodyBuilder.assignExpr(
                            target,
                            "godot_new_NodePath_with_utf8_chars(u8\"" + escapeStringLiteral(value) + "\")",
                            GdNodePathType.NODE_PATH
                    );
                    return;
                }
                bodyBuilder.assignVar(target, bodyBuilder.valueOfStringPtrLiteral(value));
                return;
            }
            if (isQuotedNodePathLiteral(literal)) {
                var value = unescapeQuoted(literal.substring(2, literal.length() - 1));
                bodyBuilder.assignExpr(
                        target,
                        "godot_new_NodePath_with_utf8_chars(u8\"" + escapeStringLiteral(value) + "\")",
                        GdNodePathType.NODE_PATH
                );
                return;
            }

            if ("[]".equals(literal)) {
                if (expectedType instanceof GdArrayType) {
                    constructBuiltin(bodyBuilder, target, List.of());
                    return;
                }
                throw errorReporter.invalid("literal '[]' is not assignable to '" + expectedType.getTypeName() + "'");
            }
            if ("{}".equals(literal)) {
                if (expectedType instanceof GdDictionaryType) {
                    constructBuiltin(bodyBuilder, target, List.of());
                    return;
                }
                throw errorReporter.invalid("literal '{}' is not assignable to '" + expectedType.getTypeName() + "'");
            }

            if (materializeConstructorDefault(bodyBuilder, target, literal, errorReporter)) {
                return;
            }
        } catch (IllegalArgumentException ex) {
            throw errorReporter.malformed(literal, ex);
        }

        throw errorReporter.invalid("has unsupported literal '" + literal + "'");
    }

    /// Validates constructor availability against ExtensionBuiltinClass metadata.
    /// Helper shim constructors may skip this check.
    public void validateConstructor(@NotNull GdType type,
                                    @NotNull List<GdType> argTypes,
                                    boolean skipApiValidation) {
        if (skipApiValidation) {
            return;
        }
        if (!hasConstructor(type, argTypes)) {
            var argTypeNames = new ArrayList<String>(argTypes.size());
            for (var argType : argTypes) {
                argTypeNames.add(helper.renderGdTypeName(argType));
            }
            throw new IllegalArgumentException("Builtin constructor validation failed: '" +
                    helper.renderGdTypeName(type) + "' with args [" +
                    String.join(", ", argTypeNames) + "] is not defined in ExtensionBuiltinClass");
        }
    }

    private @Nullable List<GdType> resolveHelperShimCtorArgTypes(@NotNull GdType type, int argCount) {
        return switch (type) {
            case GdTransform2DType _ when argCount == 6 -> repeatedCtorArgTypes(GdFloatType.FLOAT, 6);
            case GdTransform3DType _ when argCount == 12 -> repeatedCtorArgTypes(GdFloatType.FLOAT, 12);
            case GdBasisType _ when argCount == 9 -> repeatedCtorArgTypes(GdFloatType.FLOAT, 9);
            case GdProjectionType _ when argCount == 16 -> repeatedCtorArgTypes(GdFloatType.FLOAT, 16);
            default -> null;
        };
    }

    private @NotNull List<GdType> repeatedCtorArgTypes(@NotNull GdType argType, int count) {
        var suffixes = new ArrayList<GdType>(count);
        for (var i = 0; i < count; i++) {
            suffixes.add(argType);
        }
        return suffixes;
    }

    private void constructRegularBuiltin(@NotNull CBodyBuilder bodyBuilder,
                                         @NotNull CBodyBuilder.TargetRef target,
                                         @NotNull GdType targetType,
                                         @NotNull List<CBodyBuilder.ValueRef> args) {
        var ctorArgTypes = new ArrayList<GdType>(args.size());
        for (var arg : args) {
            ctorArgTypes.add(arg.type());
        }
        if (hasConstructor(targetType, ctorArgTypes)) {
            var ctorFunc = renderConstructorFunctionNameByTypes(targetType, ctorArgTypes);
            bodyBuilder.callAssign(target, ctorFunc, targetType, args);
            return;
        }
        var helperCtorArgTypes = resolveHelperShimCtorArgTypes(targetType, ctorArgTypes.size());
        if (helperCtorArgTypes != null && checkExactTypeNames(helperCtorArgTypes, ctorArgTypes)) {
            var ctorFunc = renderConstructorFunctionNameByTypes(targetType, helperCtorArgTypes);
            bodyBuilder.callAssign(target, ctorFunc, targetType, args);
            return;
        }
        var argTypeNames = new ArrayList<String>(ctorArgTypes.size());
        for (var argType : ctorArgTypes) {
            argTypeNames.add(helper.renderGdTypeName(argType));
        }
        throw new IllegalArgumentException("Builtin constructor validation failed: '" +
                helper.renderGdTypeName(targetType) + "' with args [" +
                String.join(", ", argTypeNames) + "] is not defined in ExtensionBuiltinClass");
    }

    private void constructArray(@NotNull CBodyBuilder bodyBuilder,
                                @NotNull CBodyBuilder.TargetRef target,
                                @NotNull GdArrayType arrayType,
                                @NotNull List<CBodyBuilder.ValueRef> args) {
        var elementType = arrayType.getValueType();
        if (elementType instanceof GdVariantType) {
            if (args.isEmpty()) {
                bodyBuilder.callAssign(target, renderConstructorBaseName(arrayType), arrayType, List.of());
                return;
            }
            constructRegularBuiltin(bodyBuilder, target, arrayType, args);
            return;
        }
        if (args.size() > 1) {
            throw new IllegalArgumentException("Typed Array construction expects at most one runtime argument");
        }

        var baseType = new GdArrayType(GdVariantType.VARIANT);
        CBodyBuilder.ValueRef baseValue;
        CBodyBuilder.TempVar baseTemp = null;
        if (args.isEmpty()) {
            baseTemp = bodyBuilder.newTempVariable("array_base", baseType, "godot_new_Array()");
            bodyBuilder.declareTempVar(baseTemp);
            baseValue = bodyBuilder.valueOfExpr(baseTemp.name(), baseType);
        } else {
            var providedBase = args.getFirst();
            if (!(providedBase.type() instanceof GdArrayType) ||
                    !bodyBuilder.classRegistry().checkAssignable(providedBase.type(), baseType)) {
                throw new IllegalArgumentException("Typed Array construction expects one Array argument");
            }
            baseValue = providedBase;
        }

        var ctorArgs = List.of(
                baseValue,
                bodyBuilder.valueOfExpr(renderGdExtensionVariantTypeIntLiteral(elementType), GdIntType.INT),
                bodyBuilder.valueOfStringNamePtrLiteral(resolveTypedContainerClassName(elementType)),
                bodyBuilder.valueOfExpr("NULL", GdObjectType.OBJECT, CBodyBuilder.PtrKind.GODOT_PTR)
        );
        bodyBuilder.callAssign(
                target,
                "godot_new_Array_with_Array_int_StringName_Variant",
                arrayType,
                ctorArgs
        );
        if (baseTemp != null) {
            bodyBuilder.destroyTempVar(baseTemp);
        }
    }

    private void constructDictionary(@NotNull CBodyBuilder bodyBuilder,
                                     @NotNull CBodyBuilder.TargetRef target,
                                     @NotNull GdDictionaryType dictionaryType,
                                     @NotNull List<CBodyBuilder.ValueRef> args) {
        var keyType = dictionaryType.getKeyType();
        var valueType = dictionaryType.getValueType();
        if (keyType instanceof GdVariantType && valueType instanceof GdVariantType) {
            if (args.isEmpty()) {
                bodyBuilder.callAssign(target, renderConstructorBaseName(dictionaryType), dictionaryType, List.of());
                return;
            }
            constructRegularBuiltin(bodyBuilder, target, dictionaryType, args);
            return;
        }
        if (args.size() > 1) {
            throw new IllegalArgumentException("Typed Dictionary construction expects at most one runtime argument");
        }

        var baseType = new GdDictionaryType(GdVariantType.VARIANT, GdVariantType.VARIANT);
        CBodyBuilder.ValueRef baseValue;
        CBodyBuilder.TempVar baseTemp = null;
        if (args.isEmpty()) {
            baseTemp = bodyBuilder.newTempVariable("dict_base", baseType, "godot_new_Dictionary()");
            bodyBuilder.declareTempVar(baseTemp);
            baseValue = bodyBuilder.valueOfExpr(baseTemp.name(), baseType);
        } else {
            var providedBase = args.getFirst();
            if (!(providedBase.type() instanceof GdDictionaryType) ||
                    !bodyBuilder.classRegistry().checkAssignable(providedBase.type(), baseType)) {
                throw new IllegalArgumentException("Typed Dictionary construction expects one Dictionary argument");
            }
            baseValue = providedBase;
        }

        var ctorArgs = List.of(
                baseValue,
                bodyBuilder.valueOfExpr(renderGdExtensionVariantTypeIntLiteral(keyType), GdIntType.INT),
                bodyBuilder.valueOfStringNamePtrLiteral(resolveTypedContainerClassName(keyType)),
                bodyBuilder.valueOfExpr("NULL", GdObjectType.OBJECT, CBodyBuilder.PtrKind.GODOT_PTR),
                bodyBuilder.valueOfExpr(renderGdExtensionVariantTypeIntLiteral(valueType), GdIntType.INT),
                bodyBuilder.valueOfStringNamePtrLiteral(resolveTypedContainerClassName(valueType)),
                bodyBuilder.valueOfExpr("NULL", GdObjectType.OBJECT, CBodyBuilder.PtrKind.GODOT_PTR)
        );
        bodyBuilder.callAssign(
                target,
                "godot_new_Dictionary_with_Dictionary_int_StringName_Variant_int_StringName_Variant",
                dictionaryType,
                ctorArgs
        );
        if (baseTemp != null) {
            bodyBuilder.destroyTempVar(baseTemp);
        }
    }

    private boolean checkExactTypeNames(@NotNull List<GdType> expected,
                                        @NotNull List<GdType> actual) {
        if (expected.size() != actual.size()) {
            return false;
        }
        for (var i = 0; i < expected.size(); i++) {
            if (!helper.renderGdTypeName(expected.get(i)).equals(helper.renderGdTypeName(actual.get(i)))) {
                return false;
            }
        }
        return true;
    }

    private @NotNull String resolveTypedContainerClassName(@NotNull GdType type) {
        if (type instanceof GdObjectType objectType) {
            return objectType.getTypeName();
        }
        return "";
    }

    private @NotNull String renderGdExtensionVariantTypeIntLiteral(@NotNull GdType type) {
        var gdType = type.getGdExtensionType();
        if (gdType == null) {
            throw new IllegalArgumentException("Type '" + type.getTypeName() +
                    "' has no GDExtension variant type");
        }
        return "(godot_int)GDEXTENSION_VARIANT_TYPE_" + gdType.name();
    }

    private boolean materializeConstructorDefault(@NotNull CBodyBuilder bodyBuilder,
                                                  @NotNull CBodyBuilder.TargetRef target,
                                                  @NotNull String literal,
                                                  @NotNull LiteralErrorReporter errorReporter) {
        var leftParen = literal.indexOf('(');
        if (leftParen <= 0 || !literal.endsWith(")")) {
            return false;
        }
        var expectedType = target.type();
        var ctorTypeName = literal.substring(0, leftParen).trim();
        var ctorType = ClassRegistry.tryParseTextType(ctorTypeName);
        if (ctorType == null) {
            throw errorReporter.invalid("constructor type '" + ctorTypeName + "' is unknown");
        }
        if (!helper.context().classRegistry().checkAssignable(ctorType, expectedType)) {
            throw errorReporter.invalid(
                    "constructor type '" + ctorTypeName + "' is not assignable to '" +
                            expectedType.getTypeName() + "'"
            );
        }

        var rawArgs = splitCtorArguments(literal.substring(leftParen + 1, literal.length() - 1));
        var ctorArgTypes = resolveCtorArgTypes(ctorType, rawArgs, errorReporter, literal);
        var ctorArgs = new ArrayList<CBodyBuilder.ValueRef>(rawArgs.size());
        for (var i = 0; i < rawArgs.size(); i++) {
            var argLiteral = rawArgs.get(i).trim();
            if (argLiteral.isEmpty()) {
                throw errorReporter.invalid("constructor literal '" + literal + "' has empty argument at index " + (i + 1));
            }
            var argValue = parseCtorArgumentValueRef(bodyBuilder, argLiteral, ctorArgTypes.get(i));
            ctorArgs.add(argValue);
        }

        if (helper.renderGdTypeName(expectedType).equals(helper.renderGdTypeName(ctorType))) {
            constructBuiltin(bodyBuilder, target, ctorArgs);
            return true;
        }

        var ctorTemp = bodyBuilder.newTempVariable("default_ctor_arg", ctorType);
        bodyBuilder.declareTempVar(ctorTemp);
        constructBuiltin(bodyBuilder, ctorTemp, ctorArgs);
        bodyBuilder.assignVar(target, ctorTemp);
        bodyBuilder.destroyTempVar(ctorTemp);
        return true;
    }

    private @NotNull List<GdType> resolveCtorArgTypes(@NotNull GdType ctorType,
                                                       @NotNull List<String> rawArgs,
                                                       @NotNull LiteralErrorReporter errorReporter,
                                                       @NotNull String literal) {
        var ctorArgCount = rawArgs.size();
        var candidates = new ArrayList<List<GdType>>();

        var builtinClass = helper.context().classRegistry().findBuiltinClass(helper.renderGdTypeName(ctorType));
        if (builtinClass != null) {
            for (var ctor : builtinClass.constructors()) {
                if (ctor.arguments().size() != ctorArgCount) {
                    continue;
                }
                var argTypes = new ArrayList<GdType>(ctorArgCount);
                var malformedMetadata = false;
                for (var ctorArg : ctor.arguments()) {
                    var parsedType = ClassRegistry.tryParseTextType(ctorArg.type());
                    if (parsedType == null) {
                        malformedMetadata = true;
                        break;
                    }
                    argTypes.add(parsedType);
                }
                if (!malformedMetadata) {
                    candidates.add(argTypes);
                }
            }
        }

        var helperShimArgTypes = resolveHelperShimCtorArgTypes(ctorType, ctorArgCount);
        if (helperShimArgTypes != null) {
            candidates.add(helperShimArgTypes);
        }

        for (var candidate : candidates) {
            var allMatched = true;
            for (var i = 0; i < ctorArgCount; i++) {
                if (!canMaterializeCtorArg(rawArgs.get(i).trim(), candidate.get(i))) {
                    allMatched = false;
                    break;
                }
            }
            if (allMatched) {
                return candidate;
            }
        }

        var inferredTypes = new ArrayList<GdType>(ctorArgCount);
        for (var rawArg : rawArgs) {
            var inferredType = inferCtorArgType(rawArg.trim());
            if (inferredType == null) {
                throw errorReporter.invalid(
                        "constructor literal '" + literal + "' has unsupported argument '" + rawArg.trim() + "'"
                );
            }
            inferredTypes.add(inferredType);
        }
        return inferredTypes;
    }

    private @NotNull List<String> splitCtorArguments(@NotNull String argsLiteral) {
        if (argsLiteral.isBlank()) {
            return List.of();
        }
        var args = new ArrayList<String>();
        var current = new StringBuilder();
        var depth = 0;
        var inString = false;
        var escaped = false;
        for (var i = 0; i < argsLiteral.length(); i++) {
            var ch = argsLiteral.charAt(i);
            if (inString) {
                current.append(ch);
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == '"') {
                    inString = false;
                }
                continue;
            }
            if (ch == '"') {
                inString = true;
                current.append(ch);
                continue;
            }
            if (ch == '(') {
                depth++;
                current.append(ch);
                continue;
            }
            if (ch == ')') {
                depth--;
                if (depth < 0) {
                    throw new IllegalArgumentException("Unbalanced constructor argument literal: " + argsLiteral);
                }
                current.append(ch);
                continue;
            }
            if (ch == ',' && depth == 0) {
                args.add(current.toString().trim());
                current.setLength(0);
                continue;
            }
            current.append(ch);
        }
        if (inString || depth != 0) {
            throw new IllegalArgumentException("Malformed constructor argument literal: " + argsLiteral);
        }
        var tail = current.toString().trim();
        if (!tail.isEmpty()) {
            args.add(tail);
        }
        return args;
    }

    private @Nullable GdType inferCtorArgType(@NotNull String argLiteral) {
        if ("true".equals(argLiteral) || "false".equals(argLiteral)) {
            return GdBoolType.BOOL;
        }
        if (isInfinityLiteral(argLiteral)) {
            return GdFloatType.FLOAT;
        }
        if (isNumericLiteral(argLiteral)) {
            return argLiteral.contains(".") || argLiteral.contains("e") || argLiteral.contains("E")
                    ? GdFloatType.FLOAT
                    : GdIntType.INT;
        }
        if (isQuotedStringLiteral(argLiteral)) {
            return GdStringType.STRING;
        }
        if (isQuotedStringNameLiteral(argLiteral)) {
            return GdStringNameType.STRING_NAME;
        }
        if ("[]".equals(argLiteral)) {
            return new GdArrayType(GdVariantType.VARIANT);
        }
        if ("{}".equals(argLiteral)) {
            return new GdDictionaryType(GdVariantType.VARIANT, GdVariantType.VARIANT);
        }
        return null;
    }

    private @NotNull CBodyBuilder.ValueRef parseCtorArgumentValueRef(@NotNull CBodyBuilder bodyBuilder,
                                                                       @NotNull String argLiteral,
                                                                       @NotNull GdType argType) {
        switch (argType) {
            case GdStringNameType _ -> {
                var value = unescapeQuoted(argLiteral.substring(2, argLiteral.length() - 1));
                return bodyBuilder.valueOfStringNamePtrLiteral(value);
            }
            case GdStringType _ -> {
                var value = unescapeQuoted(argLiteral.substring(1, argLiteral.length() - 1));
                return bodyBuilder.valueOfStringPtrLiteral(value);
            }
            case GdArrayType _ -> {
                return bodyBuilder.valueOfExpr("godot_new_Array()", argType);
            }
            case GdDictionaryType _ -> {
                return bodyBuilder.valueOfExpr("godot_new_Dictionary()", argType);
            }
            case GdFloatType _ -> {
                return bodyBuilder.valueOfExpr(normalizeFloatLiteral(argLiteral), argType);
            }
            default -> {
                return bodyBuilder.valueOfExpr(argLiteral, argType);
            }
        }
    }

    private boolean canMaterializeCtorArg(@NotNull String argLiteral, @NotNull GdType expectedType) {
        return switch (expectedType) {
            case GdBoolType _ -> "true".equals(argLiteral) || "false".equals(argLiteral);
            case GdIntType _ -> isIntegerLiteral(argLiteral);
            case GdFloatType _ -> isNumericLiteral(argLiteral) || isInfinityLiteral(argLiteral);
            case GdStringType _ -> isQuotedStringLiteral(argLiteral);
            case GdStringNameType _ -> isQuotedStringNameLiteral(argLiteral);
            case GdArrayType _ -> "[]".equals(argLiteral);
            case GdDictionaryType _ -> "{}".equals(argLiteral);
            default -> inferCtorArgType(argLiteral) != null;
        };
    }

    private @NotNull String normalizeFloatLiteral(@NotNull String literal) {
        var mappedInfinity = mapInfinityLiteral(literal);
        if (mappedInfinity != null) {
            return mappedInfinity;
        }
        return literal;
    }

    private boolean isInfinityLiteral(@NotNull String literal) {
        return mapInfinityLiteral(literal) != null;
    }

    private @Nullable String mapInfinityLiteral(@NotNull String literal) {
        return switch (literal.trim().toLowerCase(Locale.ROOT)) {
            case "inf", "+inf" -> "godot_inf";
            case "-inf" -> "-godot_inf";
            default -> null;
        };
    }

    private boolean isNumericLiteral(@NotNull String value) {
        return NUMERIC_LITERAL_PATTERN.matcher(value).matches();
    }

    private boolean isIntegerLiteral(@NotNull String value) {
        return INTEGER_LITERAL_PATTERN.matcher(value).matches();
    }

    private boolean isQuotedStringLiteral(@NotNull String literal) {
        return literal.length() >= 2 && literal.startsWith("\"") && literal.endsWith("\"");
    }

    private boolean isQuotedStringNameLiteral(@NotNull String literal) {
        return literal.length() >= 3 && literal.startsWith("&\"") && literal.endsWith("\"");
    }

    private boolean isQuotedNodePathLiteral(@NotNull String literal) {
        return literal.length() >= 3 && literal.startsWith("$\"") && literal.endsWith("\"");
    }

    private @NotNull String unescapeQuoted(@NotNull String content) {
        var out = new StringBuilder();
        for (var i = 0; i < content.length(); i++) {
            var ch = content.charAt(i);
            if (ch != '\\') {
                out.append(ch);
                continue;
            }
            if (i + 1 >= content.length()) {
                out.append('\\');
                break;
            }
            var next = content.charAt(++i);
            switch (next) {
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case '\\' -> out.append('\\');
                case '"' -> out.append('"');
                case 'u' -> {
                    if (i + 4 >= content.length()) {
                        throw new IllegalArgumentException("Invalid unicode escape in literal: \\" + next);
                    }
                    var hex = content.substring(i + 1, i + 5);
                    out.append((char) Integer.parseInt(hex, 16));
                    i += 4;
                }
                case 'U' -> {
                    if (i + 8 >= content.length()) {
                        throw new IllegalArgumentException("Invalid unicode escape in literal: \\" + next);
                    }
                    var hex = content.substring(i + 1, i + 9);
                    out.appendCodePoint(Integer.parseInt(hex, 16));
                    i += 8;
                }
                default -> out.append(next);
            }
        }
        return out.toString();
    }

    @FunctionalInterface
    private interface LiteralErrorReporter {
        RuntimeException invalid(@NotNull String detail);

        default RuntimeException malformed(@NotNull String literal, @NotNull IllegalArgumentException ex) {
            return invalid("literal '" + literal + "' is malformed: " + ex.getMessage());
        }
    }
}
