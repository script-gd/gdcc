package gd.script.gdcc.frontend.sema.analyzer.support;

import dev.superice.gdparser.frontend.ast.ArrayExpression;
import dev.superice.gdparser.frontend.ast.DictionaryExpression;
import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.LiteralExpression;
import gd.script.gdcc.frontend.sema.FrontendContainerLiteralPlan;
import gd.script.gdcc.frontend.sema.FrontendContainerLiteralPlan.DuplicateKeyIssue;
import gd.script.gdcc.frontend.sema.FrontendContainerLiteralPlan.OperandPlan;
import gd.script.gdcc.frontend.sema.FrontendContainerLiteralPlan.OperandRole;
import gd.script.gdcc.frontend.sema.FrontendExpressionType;
import gd.script.gdcc.frontend.sema.FrontendExpressionTypeStatus;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdArrayType;
import gd.script.gdcc.type.GdContainerType;
import gd.script.gdcc.type.GdDictionaryType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/// Shared semantic helper for array / dictionary literals (Phase 1+: generic construction).
///
/// Responsibilities:
/// - resolve generic (and later contextual) construction types
/// - recursively type every child expression through the caller's nested resolver
/// - build a {@link FrontendContainerLiteralPlan} when the literal root is typing-stable
/// - fail-closed on `openEnded` pattern openings in ordinary expressions
/// - freeze directly-reducible constant duplicate-key issues into the plan
///
/// Non-responsibilities (owned elsewhere):
/// - diagnostics emission
/// - side-table / overlay writes
/// - expected-type-aware cache keys and overload preview (Phase 2)
/// - compile-gate unblock (Phase 6)
public final class FrontendContainerLiteralSemanticSupport {
    /// Pure resolution outcome: expression fact + optional plan + root-owned diagnostic flag.
    public record Resolution(
            @NotNull FrontendExpressionType expressionType,
            boolean rootOwnsOutcome,
            @Nullable FrontendContainerLiteralPlan planOrNull
    ) {
        public Resolution {
            Objects.requireNonNull(expressionType, "expressionType must not be null");
        }
    }

    private FrontendContainerLiteralSemanticSupport() {
    }

    /// Resolves an array literal without expected type → generic {@code Array}.
    public static @NotNull Resolution resolveArrayExpressionType(
            @NotNull ClassRegistry classRegistry,
            @NotNull ArrayExpression arrayExpression,
            @NotNull FrontendExpressionSemanticSupport.NestedExpressionResolver nestedResolver,
            boolean finalizeWindow
    ) {
        Objects.requireNonNull(classRegistry, "classRegistry must not be null");
        Objects.requireNonNull(arrayExpression, "arrayExpression must not be null");
        Objects.requireNonNull(nestedResolver, "nestedResolver must not be null");

        if (arrayExpression.openEnded()) {
            return rootFailed(
                    "Array pattern open-ending ('..') is not supported as an ordinary array literal"
            );
        }

        var elementTypes = new ArrayList<FrontendExpressionType>(arrayExpression.elements().size());
        for (var element : arrayExpression.elements()) {
            var elementType = nestedResolver.resolve(element, finalizeWindow);
            var dependencyIssue = firstNonResolvedDependency(elementType);
            if (dependencyIssue != null) {
                return propagated(dependencyIssue);
            }
            elementTypes.add(elementType);
        }

        var resultType = new GdArrayType(GdVariantType.VARIANT);
        var operands = buildArrayOperands(classRegistry, elementTypes, resultType);
        var plan = new FrontendContainerLiteralPlan(resultType, operands, List.of());
        return rootResolved(resultType, plan);
    }

    /// Resolves a dictionary literal without expected type → generic {@code Dictionary}.
    public static @NotNull Resolution resolveDictionaryExpressionType(
            @NotNull ClassRegistry classRegistry,
            @NotNull DictionaryExpression dictionaryExpression,
            @NotNull FrontendExpressionSemanticSupport.NestedExpressionResolver nestedResolver,
            boolean finalizeWindow
    ) {
        Objects.requireNonNull(classRegistry, "classRegistry must not be null");
        Objects.requireNonNull(dictionaryExpression, "dictionaryExpression must not be null");
        Objects.requireNonNull(nestedResolver, "nestedResolver must not be null");

        if (dictionaryExpression.openEnded()) {
            return rootFailed(
                    "Dictionary pattern open-ending ('..') is not supported as an ordinary dictionary literal"
            );
        }

        var keyTypes = new ArrayList<FrontendExpressionType>(dictionaryExpression.entries().size());
        var valueTypes = new ArrayList<FrontendExpressionType>(dictionaryExpression.entries().size());
        for (var entry : dictionaryExpression.entries()) {
            var keyType = nestedResolver.resolve(entry.key(), finalizeWindow);
            var keyIssue = firstNonResolvedDependency(keyType);
            if (keyIssue != null) {
                return propagated(keyIssue);
            }
            var valueType = nestedResolver.resolve(entry.value(), finalizeWindow);
            var valueIssue = firstNonResolvedDependency(valueType);
            if (valueIssue != null) {
                return propagated(valueIssue);
            }
            keyTypes.add(keyType);
            valueTypes.add(valueType);
        }

        var resultType = new GdDictionaryType(GdVariantType.VARIANT, GdVariantType.VARIANT);
        var operands = buildDictionaryOperands(classRegistry, keyTypes, valueTypes, resultType);
        var duplicateKeyIssues = collectDuplicateKeyIssues(dictionaryExpression);
        var plan = new FrontendContainerLiteralPlan(resultType, operands, duplicateKeyIssues);
        return rootResolved(resultType, plan);
    }

    private static @NotNull List<OperandPlan> buildArrayOperands(
            @NotNull ClassRegistry classRegistry,
            @NotNull List<FrontendExpressionType> elementTypes,
            @NotNull GdArrayType resultType
    ) {
        var targetType = resultType.getValueType();
        var operands = new ArrayList<OperandPlan>(elementTypes.size());
        for (var i = 0; i < elementTypes.size(); i++) {
            var sourceType = requirePublishedType(elementTypes.get(i), "array element");
            operands.add(new OperandPlan(
                    i,
                    OperandRole.ARRAY_ELEMENT,
                    sourceType,
                    targetType,
                    FrontendVariantBoundaryCompatibility.determineFrontendBoundaryDecision(
                            classRegistry,
                            sourceType,
                            targetType
                    )
            ));
        }
        return List.copyOf(operands);
    }

    private static @NotNull List<OperandPlan> buildDictionaryOperands(
            @NotNull ClassRegistry classRegistry,
            @NotNull List<FrontendExpressionType> keyTypes,
            @NotNull List<FrontendExpressionType> valueTypes,
            @NotNull GdDictionaryType resultType
    ) {
        var keyTarget = resultType.getKeyType();
        var valueTarget = resultType.getValueType();
        var operands = new ArrayList<OperandPlan>(keyTypes.size() * 2);
        for (var i = 0; i < keyTypes.size(); i++) {
            var sourceKey = requirePublishedType(keyTypes.get(i), "dictionary key");
            var sourceValue = requirePublishedType(valueTypes.get(i), "dictionary value");
            operands.add(new OperandPlan(
                    i,
                    OperandRole.DICTIONARY_KEY,
                    sourceKey,
                    keyTarget,
                    FrontendVariantBoundaryCompatibility.determineFrontendBoundaryDecision(
                            classRegistry,
                            sourceKey,
                            keyTarget
                    )
            ));
            operands.add(new OperandPlan(
                    i,
                    OperandRole.DICTIONARY_VALUE,
                    sourceValue,
                    valueTarget,
                    FrontendVariantBoundaryCompatibility.determineFrontendBoundaryDecision(
                            classRegistry,
                            sourceValue,
                            valueTarget
                    )
            ));
        }
        return List.copyOf(operands);
    }

    /// Freezes only directly-reducible constant keys (null/bool/int/float/String/StringName/NodePath).
    /// String and StringName share one equivalence class; int and float stay distinct.
    private static @NotNull List<DuplicateKeyIssue> collectDuplicateKeyIssues(
            @NotNull DictionaryExpression dictionaryExpression
    ) {
        var firstIndexByKey = new LinkedHashMap<ConstantKey, Integer>();
        var firstDisplayByKey = new LinkedHashMap<ConstantKey, String>();
        var issues = new ArrayList<DuplicateKeyIssue>();
        var entries = dictionaryExpression.entries();
        for (var i = 0; i < entries.size(); i++) {
            var constantKey = tryConstantKey(entries.get(i).key());
            if (constantKey == null) {
                continue;
            }
            var existing = firstIndexByKey.get(constantKey);
            if (existing == null) {
                firstIndexByKey.put(constantKey, i);
                firstDisplayByKey.put(constantKey, constantKey.display());
                continue;
            }
            issues.add(new DuplicateKeyIssue(existing, i, firstDisplayByKey.get(constantKey)));
        }
        return List.copyOf(issues);
    }

    private static @Nullable ConstantKey tryConstantKey(@NotNull Expression keyExpression) {
        if (!(keyExpression instanceof LiteralExpression literal)) {
            return null;
        }
        // Total reduction: unsupported/malformed lexemes return null (not reducible), never throw.
        try {
            return switch (literal.kind()) {
                case "null" -> ConstantKey.nullKey();
                case "true" -> ConstantKey.boolKey(true);
                case "false" -> ConstantKey.boolKey(false);
                case "integer", "number" -> {
                    if (literal.kind().equals("number") && literal.sourceText().contains(".")) {
                        yield ConstantKey.floatKey(parseFloatLiteral(literal.sourceText()));
                    }
                    yield ConstantKey.intKey(parseIntLiteral(literal.sourceText()));
                }
                case "float" -> ConstantKey.floatKey(parseFloatLiteral(literal.sourceText()));
                case "string", "string_name" -> ConstantKey.stringLikeKey(
                        StringUtil.decodeGdStringLexeme(literal.sourceText())
                );
                case "node_path" -> {
                    var decoded = tryDecodeNodePathLexeme(literal.sourceText());
                    yield decoded == null ? null : ConstantKey.nodePathKey(decoded);
                }
                default -> null;
            };
        } catch (RuntimeException _) {
            return null;
        }
    }

    /// Parses gdparser integer lexemes including {@code 0x}/{@code 0b}/{@code 0o} prefixes and {@code _}.
    private static long parseIntLiteral(@NotNull String sourceText) {
        var normalized = sourceText.replace("_", "").trim();
        if (normalized.startsWith("0x") || normalized.startsWith("0X")) {
            return Long.parseLong(normalized.substring(2), 16);
        }
        if (normalized.startsWith("0b") || normalized.startsWith("0B")) {
            return Long.parseLong(normalized.substring(2), 2);
        }
        if (normalized.startsWith("0o") || normalized.startsWith("0O")) {
            return Long.parseLong(normalized.substring(2), 8);
        }
        return Long.parseLong(normalized);
    }

    private static double parseFloatLiteral(@NotNull String sourceText) {
        return Double.parseDouble(sourceText.replace("_", "").trim());
    }

    /// Decodes {@code ^"..."} / {@code "..."} NodePath lexemes via shared string unescape rules.
    private static @Nullable String tryDecodeNodePathLexeme(@NotNull String sourceText) {
        var text = sourceText.trim();
        if (text.startsWith("^")) {
            text = text.substring(1).trim();
        }
        if (text.startsWith("\"")) {
            return StringUtil.decodeGdStringLexeme(text);
        }
        return null;
    }

    private static @NotNull GdType requirePublishedType(
            @NotNull FrontendExpressionType expressionType,
            @NotNull String role
    ) {
        var published = expressionType.publishedType();
        if (published == null) {
            throw new IllegalStateException(
                    "Container literal " + role + " is typing-stable but has no published type"
            );
        }
        return published;
    }

    private static @Nullable FrontendExpressionType firstNonResolvedDependency(
            @Nullable FrontendExpressionType dependency
    ) {
        if (dependency == null
                || dependency.status() == FrontendExpressionTypeStatus.RESOLVED
                || dependency.status() == FrontendExpressionTypeStatus.DYNAMIC) {
            return null;
        }
        return dependency;
    }

    private static @NotNull Resolution propagated(@NotNull FrontendExpressionType expressionType) {
        return new Resolution(expressionType, false, null);
    }

    private static @NotNull Resolution rootFailed(@NotNull String detailReason) {
        return new Resolution(FrontendExpressionType.failed(detailReason), true, null);
    }

    private static @NotNull Resolution rootResolved(
            @NotNull GdContainerType resultType,
            @NotNull FrontendContainerLiteralPlan plan
    ) {
        return new Resolution(FrontendExpressionType.resolved(resultType), true, plan);
    }

    /// Compile-time constant key identity for duplicate detection only.
    private sealed interface ConstantKey {
        @NotNull String display();

        static @NotNull ConstantKey nullKey() {
            return NullKey.INSTANCE;
        }

        static @NotNull ConstantKey boolKey(boolean value) {
            return new BoolKey(value);
        }

        static @NotNull ConstantKey intKey(long value) {
            return new IntKey(value);
        }

        static @NotNull ConstantKey floatKey(double value) {
            return new FloatKey(value);
        }

        static @NotNull ConstantKey stringLikeKey(@NotNull String content) {
            return new StringLikeKey(content);
        }

        static @NotNull ConstantKey nodePathKey(@NotNull String content) {
            return new NodePathKey(content);
        }

        enum NullKey implements ConstantKey {
            INSTANCE;

            @Override
            public @NotNull String display() {
                return "null";
            }
        }

        record BoolKey(boolean value) implements ConstantKey {
            @Override
            public @NotNull String display() {
                return Boolean.toString(value);
            }
        }

        record IntKey(long value) implements ConstantKey {
            @Override
            public @NotNull String display() {
                return Long.toString(value);
            }
        }

        /// Distinct from {@link IntKey}: Godot keeps int {@code 1} and float {@code 1.0} as different keys.
        record FloatKey(double value) implements ConstantKey {
            @Override
            public boolean equals(Object other) {
                return other instanceof FloatKey(var fv)
                        && Double.doubleToLongBits(value) == Double.doubleToLongBits(fv);
            }

            @Override
            public int hashCode() {
                return Long.hashCode(Double.doubleToLongBits(value));
            }

            @Override
            public @NotNull String display() {
                return Double.toString(value);
            }
        }

        /// String and StringName share one equivalence class (Godot string-like key rule).
        record StringLikeKey(@NotNull String content) implements ConstantKey {
            public StringLikeKey {
                Objects.requireNonNull(content, "content must not be null");
            }

            @Override
            public @NotNull String display() {
                return "\"" + content + "\"";
            }
        }

        record NodePathKey(@NotNull String content) implements ConstantKey {
            public NodePathKey {
                Objects.requireNonNull(content, "content must not be null");
            }

            @Override
            public @NotNull String display() {
                return "^\"" + content + "\"";
            }
        }
    }
}
