package gd.script.gdcc.frontend.sema.analyzer.support;

import dev.superice.gdparser.frontend.ast.AttributeCallStep;
import dev.superice.gdparser.frontend.ast.AttributeExpression;
import dev.superice.gdparser.frontend.ast.AttributePropertyStep;
import dev.superice.gdparser.frontend.ast.AttributeStep;
import dev.superice.gdparser.frontend.ast.AttributeSubscriptStep;
import dev.superice.gdparser.frontend.ast.BinaryExpression;
import dev.superice.gdparser.frontend.ast.ConditionalExpression;
import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.LiteralExpression;
import dev.superice.gdparser.frontend.ast.MissingAttributeStep;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.UnaryExpression;
import dev.superice.gdparser.frontend.ast.UnknownAttributeStep;
import gd.script.gdcc.enums.GodotOperator;
import gd.script.gdcc.frontend.constant.ConstCallResult;
import gd.script.gdcc.frontend.constant.Evaluation;
import gd.script.gdcc.frontend.constant.GdConstantValue;
import gd.script.gdcc.type.GdBoolType;
import gd.script.gdcc.type.GdFloatType;
import gd.script.gdcc.type.GdFloatVectorType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdIntVectorType;
import gd.script.gdcc.type.GdStringType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// Pure compile-time constant evaluator (const plan §5.1–§5.5).
///
/// Evaluation runs in two passes that share one whitelist:
/// - the validation pass walks the whole tree exactly once and answers only types — whitelist
///   shape, name binding, operator legality, and registered-call signatures are verified
///   without computing any values, so a dead branch can never hide an unbound name or an
///   unregistered call, and no handler runs before a later sibling's validation failure
/// - the evaluation pass computes values along the taken control-flow edges only: `and`/`or`
///   and conditional expressions evaluate just the deciding operand and (when needed) the
///   surviving side, so dead-branch domain errors — division by zero, negative integer
///   powers — never fire
///
/// The evaluator never touches the Godot runtime, never mutates the AST, never publishes side
/// tables, and never emits diagnostics; every rejection is a structured `Evaluation.Rejected`
/// that the owning semantic phase maps to diagnostics. Registered member calls go through the
/// context's ordinary signature selection and exact-key registry lookup — never a name-only or
/// runtime fallback.
public final class FrontendConstantEvaluator {
    private FrontendConstantEvaluator() {
    }

    /// Evaluates `expression` to a frozen `GdConstantValue`, or rejects with a human-readable
    /// reason plus the offending AST node.
    public static @NotNull Evaluation evaluate(
            @NotNull Expression expression,
            @NotNull LookupContext context,
            @NotNull EvaluationPolicy policy
    ) {
        var env = new EvaluationEnvironment(context, policy);
        var node = Objects.requireNonNull(expression, "expression must not be null");
        // Validation proves the whole tree admissible before a single value is computed:
        // nothing evaluates — and no registered handler runs — when any node would reject.
        return switch (checkExpression(node, env)) {
            case Checked.Failed<GdType> failed -> failed.rejection();
            case Checked.Namespace<GdType> _ -> new Evaluation.Rejected(
                    "the name denotes a constant namespace, not a value", node);
            case Checked.Payload<GdType> _ -> evaluateExpression(node, env);
        };
    }

    /// Per-evaluation immutable inputs — the scope-facing lookup surface and the capability
    /// gate, threaded unchanged through every recursion.
    private record EvaluationEnvironment(
            @NotNull LookupContext lookup,
            @NotNull EvaluationPolicy policy
    ) {
        private EvaluationEnvironment {
            Objects.requireNonNull(lookup, "lookup must not be null");
            Objects.requireNonNull(policy, "policy must not be null");
        }

        private boolean permits(@NotNull EvaluationPolicy.Capability capability) {
            return policy.permits(capability);
        }
    }

    /// Internal tri-state: a computed payload, an opaque namespace token that only the issuing
    /// `LookupContext` can descend further, or a rejection.
    ///
    /// The validation pass instantiates it as `Checked<GdType>` (shape proven, value unknown);
    /// the evaluation pass instantiates `Checked<GdConstantValue>` (frozen value known).
    /// `Namespace` never leaves an attribute chain: consuming one as a value is a rejection.
    private sealed interface Checked<T> {
        record Payload<T>(@NotNull T payload) implements Checked<T> {
            public Payload {
                Objects.requireNonNull(payload, "payload must not be null");
            }
        }

        record Namespace<T>(@NotNull Object identity) implements Checked<T> {
            public Namespace {
                Objects.requireNonNull(identity, "identity must not be null");
            }
        }

        record Failed<T>(@NotNull Evaluation.Rejected rejection) implements Checked<T> {
            public Failed {
                Objects.requireNonNull(rejection, "rejection must not be null");
            }
        }
    }

    /// Outcome of matching one member-call step against selection and the frozen registry:
    /// either the exact registered entry to dispatch, or the rejection to surface.
    private sealed interface CallStepSelection {
        record Registered(
                @NotNull ConstCallableKey key,
                @NotNull FrontendConstCallableRegistry.Entry entry
        ) implements CallStepSelection {
            public Registered {
                Objects.requireNonNull(key, "key must not be null");
                Objects.requireNonNull(entry, "entry must not be null");
            }
        }

        record Failed(@NotNull Evaluation.Rejected rejection) implements CallStepSelection {
            public Failed {
                Objects.requireNonNull(rejection, "rejection must not be null");
            }
        }
    }

    // ---------------------------------------------------------------------
    // Evaluation pass — computes frozen values along taken control-flow edges
    // ---------------------------------------------------------------------

    private static @NotNull Evaluation evaluateExpression(
            @NotNull Expression expression,
            @NotNull EvaluationEnvironment env
    ) {
        return switch (expression) {
            case LiteralExpression literal -> evaluateLiteral(literal, env);
            case IdentifierExpression identifier -> toEvaluation(
                    resolveConstantName(identifier.name(), identifier, env),
                    identifier
            );
            case UnaryExpression unary -> evaluateUnary(unary, env);
            case BinaryExpression binary -> evaluateBinary(binary, env);
            case ConditionalExpression conditional -> evaluateConditional(conditional, env);
            case AttributeExpression attribute -> evaluateAttributeChain(attribute, env);
            default -> rejectedShape(expression, env);
        };
    }

    private static @NotNull Evaluation evaluateLiteral(
            @NotNull LiteralExpression literal,
            @NotNull EvaluationEnvironment env
    ) {
        switch (literal.kind()) {
            case "integer" -> {
                if (!env.permits(EvaluationPolicy.Capability.INT_ARITHMETIC)) {
                    return rejectedLiteral(literal, env);
                }
                var value = StringUtil.parseGdIntegerLexeme(literal.sourceText());
                if (value == null) {
                    return new Evaluation.Rejected(
                            "integer literal '" + literal.sourceText()
                                    + "' is malformed or overflows 64-bit int",
                            literal
                    );
                }
                return new Evaluation.Resolved(new GdConstantValue.IntValue(value));
            }
            case "float" -> {
                if (!env.permits(EvaluationPolicy.Capability.FLOAT_ARITHMETIC)) {
                    return rejectedLiteral(literal, env);
                }
                var value = StringUtil.parseGdFloatLexeme(literal.sourceText());
                if (value == null) {
                    return new Evaluation.Rejected(
                            "float literal '" + literal.sourceText() + "' is malformed",
                            literal
                    );
                }
                return new Evaluation.Resolved(new GdConstantValue.FloatValue(value));
            }
            case "string" -> {
                if (!env.permits(EvaluationPolicy.Capability.STRING_OPERATORS)) {
                    return rejectedLiteral(literal, env);
                }
                try {
                    return new Evaluation.Resolved(
                            new GdConstantValue.StringValue(
                                    StringUtil.decodeGdStringLexeme(literal.sourceText()))
                    );
                } catch (IllegalArgumentException ex) {
                    return new Evaluation.Rejected(
                            "string literal is malformed: " + ex.getMessage(), literal);
                }
            }
            case "true", "false" -> {
                if (!env.permits(EvaluationPolicy.Capability.BOOL_OPERATORS)) {
                    return rejectedLiteral(literal, env);
                }
                return new Evaluation.Resolved(
                        new GdConstantValue.BoolValue("true".equals(literal.kind())));
            }
            default -> {
                return rejectedLiteral(literal, env);
            }
        }
    }

    private static @NotNull Evaluation evaluateUnary(
            @NotNull UnaryExpression unary,
            @NotNull EvaluationEnvironment env
    ) {
        // Signed numbers are `Unary(-) + literal` in the AST, so the long-min edge must be
        // intercepted before operand evaluation: the positive magnitude of Long.MIN_VALUE does
        // not itself parse into a long literal. The edge still requires INT_ARITHMETIC —
        // LONG_MIN_LITERAL only widens that one magnitude, it does not own a domain.
        if ("-".equals(unary.operator())
                && unary.operand() instanceof LiteralExpression operandLiteral
                && "integer".equals(operandLiteral.kind())
                && env.permits(EvaluationPolicy.Capability.INT_ARITHMETIC)
                && env.permits(EvaluationPolicy.Capability.LONG_MIN_LITERAL)
                && isMinValueMagnitudeLexeme(operandLiteral.sourceText())) {
            return new Evaluation.Resolved(new GdConstantValue.IntValue(Long.MIN_VALUE));
        }
        var operandEvaluation = evaluateExpression(unary.operand(), env);
        if (operandEvaluation instanceof Evaluation.Rejected rejected) {
            return rejected;
        }
        var operand = ((Evaluation.Resolved) operandEvaluation).value();
        var operator = parseUnaryOperator(unary.operator());
        if (operator == null) {
            return new Evaluation.Rejected(
                    "unary operator '" + unary.operator() + "' is not supported in "
                            + unsupportedSuffix(env),
                    unary
            );
        }
        return switch (requireUnaryOperandType(operator, operand.type(), unary, env)) {
            case Checked.Payload<GdType> _ -> computeUnary(operator, operand, unary);
            case Checked.Failed<GdType> failed -> failed.rejection();
            case Checked.Namespace<GdType> _ -> throw new IllegalStateException(
                    "operand type admission never produces a namespace token"
            );
        };
    }

    /// `and`/`or` evaluate only the taken edges: whole-tree validation already proved both
    /// operands are bool, so this pass just computes the deciding operand and — when it does
    /// not decide — the surviving one.
    private static @NotNull Evaluation evaluateLogical(
            @NotNull GodotOperator operator,
            @NotNull BinaryExpression binary,
            @NotNull EvaluationEnvironment env
    ) {
        var denied = requireCapability(
                env,
                EvaluationPolicy.Capability.BOOL_OPERATORS,
                "binary operator '" + binary.operator() + "'",
                binary
        );
        if (denied != null) {
            return denied;
        }
        var leftEvaluation = evaluateExpression(binary.left(), env);
        if (leftEvaluation instanceof Evaluation.Rejected rejected) {
            return rejected;
        }
        var leftValue = ((Evaluation.Resolved) leftEvaluation).value();
        if (!(leftValue instanceof GdConstantValue.BoolValue leftBool)) {
            throw new IllegalStateException(
                    "bool-admitted logical operand evaluated to '" + leftValue.type().getTypeName() + "'");
        }
        if (operator == GodotOperator.AND && !leftBool.value()
                || operator == GodotOperator.OR && leftBool.value()) {
            return new Evaluation.Resolved(leftBool);
        }
        var rightEvaluation = evaluateExpression(binary.right(), env);
        if (rightEvaluation instanceof Evaluation.Rejected rejected) {
            return rejected;
        }
        var rightValue = ((Evaluation.Resolved) rightEvaluation).value();
        if (!(rightValue instanceof GdConstantValue.BoolValue rightBool)) {
            throw new IllegalStateException(
                    "bool-admitted logical operand evaluated to '" + rightValue.type().getTypeName() + "'");
        }
        return new Evaluation.Resolved(rightBool);
    }

    private static @NotNull Evaluation evaluateBinary(
            @NotNull BinaryExpression binary,
            @NotNull EvaluationEnvironment env
    ) {
        var operator = parseBinaryOperator(binary.operator());
        if (operator == null) {
            return new Evaluation.Rejected(
                    "binary operator '" + binary.operator() + "' is not supported in "
                            + unsupportedSuffix(env),
                    binary
            );
        }
        if (operator == GodotOperator.AND || operator == GodotOperator.OR) {
            return evaluateLogical(operator, binary, env);
        }
        var leftEvaluation = evaluateExpression(binary.left(), env);
        if (leftEvaluation instanceof Evaluation.Rejected rejected) {
            return rejected;
        }
        var rightEvaluation = evaluateExpression(binary.right(), env);
        if (rightEvaluation instanceof Evaluation.Rejected rejected) {
            return rejected;
        }
        var leftValue = ((Evaluation.Resolved) leftEvaluation).value();
        var rightValue = ((Evaluation.Resolved) rightEvaluation).value();
        return switch (requireBinaryOperandTypes(operator, leftValue.type(), rightValue.type(), binary, env)) {
            case Checked.Payload<GdType> _ -> computeBinary(operator, leftValue, rightValue, binary, env);
            case Checked.Failed<GdType> failed -> failed.rejection();
            case Checked.Namespace<GdType> _ -> throw new IllegalStateException(
                    "operand type admission never produces a namespace token"
            );
        };
    }

    /// Conditional expressions evaluate the condition and only the selected arm; the
    /// whole-tree check already proved the condition is bool and both arms share one
    /// exact type.
    private static @NotNull Evaluation evaluateConditional(
            @NotNull ConditionalExpression conditional,
            @NotNull EvaluationEnvironment env
    ) {
        if (!env.permits(EvaluationPolicy.Capability.TERNARY)) {
            return rejectedShape(conditional, env);
        }
        var conditionEvaluation = evaluateExpression(conditional.condition(), env);
        if (conditionEvaluation instanceof Evaluation.Rejected rejected) {
            return rejected;
        }
        var conditionValue = ((Evaluation.Resolved) conditionEvaluation).value();
        if (!(conditionValue instanceof GdConstantValue.BoolValue conditionBool)) {
            throw new IllegalStateException(
                    "bool-admitted condition evaluated to '" + conditionValue.type().getTypeName() + "'");
        }
        return evaluateExpression(
                conditionBool.value() ? conditional.left() : conditional.right(), env);
    }

    /// Attribute chains resolve through values and namespace tokens: property steps descend
    /// namespaces via `resolveMember`, call steps dispatch registered member handlers, and any
    /// other step — projections, subscripts, malformed steps — rejects.
    private static @NotNull Evaluation evaluateAttributeChain(
            @NotNull AttributeExpression attribute,
            @NotNull EvaluationEnvironment env
    ) {
        if (!env.permits(EvaluationPolicy.Capability.ATTRIBUTE_ACCESS)) {
            return rejectedShape(attribute, env);
        }
        var receiver = evalChainOperand(attribute.base(), env);
        for (var step : attribute.steps()) {
            if (receiver instanceof Checked.Failed<GdConstantValue> failed) {
                return failed.rejection();
            }
            receiver = applyEvaluatedStep(receiver, step, env);
        }
        return toEvaluation(receiver, attribute);
    }

    /// Resolves a chain-head operand into a value or namespace token — identifiers may name
    /// either, nested chains keep descending, and every other expression evaluates to a value.
    private static @NotNull Checked<GdConstantValue> evalChainOperand(
            @NotNull Expression base,
            @NotNull EvaluationEnvironment env
    ) {
        if (base instanceof IdentifierExpression identifier) {
            return resolveConstantName(identifier.name(), identifier, env);
        }
        if (base instanceof AttributeExpression nested) {
            if (!env.permits(EvaluationPolicy.Capability.ATTRIBUTE_ACCESS)) {
                return new Checked.Failed<>(rejectedShape(nested, env));
            }
            var receiver = evalChainOperand(nested.base(), env);
            for (var step : nested.steps()) {
                if (receiver instanceof Checked.Failed<GdConstantValue> failed) {
                    return failed;
                }
                receiver = applyEvaluatedStep(receiver, step, env);
            }
            return receiver;
        }
        return switch (evaluateExpression(base, env)) {
            case Evaluation.Resolved resolved -> new Checked.Payload<>(resolved.value());
            case Evaluation.Rejected rejected -> new Checked.Failed<>(rejected);
        };
    }

    private static @NotNull Checked<GdConstantValue> applyEvaluatedStep(
            @NotNull Checked<GdConstantValue> receiver,
            @NotNull AttributeStep step,
            @NotNull EvaluationEnvironment env
    ) {
        return switch (step) {
            case AttributePropertyStep property -> {
                if (receiver instanceof Checked.Namespace<GdConstantValue> namespace) {
                    yield switch (env.lookup().resolveMember(namespace.identity(), property.name())) {
                        case LookupContext.LookupResult.ResolvedConstant constant ->
                                new Checked.Payload<GdConstantValue>(constant.value());
                        case LookupContext.LookupResult.ResolvedNamespace nested ->
                                new Checked.Namespace<GdConstantValue>(nested.namespaceIdentity());
                        case LookupContext.LookupResult.NonConstant nonConstant ->
                                new Checked.Failed<GdConstantValue>(
                                        new Evaluation.Rejected(nonConstant.reason(), property));
                        case LookupContext.LookupResult.Blocked blocked ->
                                new Checked.Failed<GdConstantValue>(
                                        new Evaluation.Rejected(blocked.reason(), property));
                        case LookupContext.LookupResult.Unknown _ ->
                                new Checked.Failed<GdConstantValue>(new Evaluation.Rejected(
                                        "unknown member '" + property.name() + "' in constant expression",
                                        property
                                ));
                    };
                }
                // A property step on a value is a §5.4 member projection, handled by the
                // projection stage — the capability gate decides admission here.
                var denied = requireCapability(
                        env,
                        EvaluationPolicy.Capability.MEMBER_PROJECTION,
                        "member projection '" + property.name() + "'",
                        property
                );
                yield new Checked.Failed<GdConstantValue>(Objects.requireNonNullElseGet(
                        denied,
                        () -> new Evaluation.Rejected(
                                "member projections are not supported in this constant evaluation",
                                property
                        )
                ));
            }
            case AttributeCallStep call -> applyCallStep(receiver, call, env);
            case AttributeSubscriptStep _, MissingAttributeStep _, UnknownAttributeStep _ ->
                    new Checked.Failed<GdConstantValue>(new Evaluation.Rejected(
                            "attribute step '" + step.getClass().getSimpleName()
                                    + "' is not supported in constant expressions",
                            step
                    ));
        };
    }

    /// Member-call dispatch: evaluate frozen arguments, select the exact signature through the
    /// context, look the key up in the frozen registry, normalize payloads to the declared
    /// parameter types, and invoke only the pure handler. An unregistered or unselectable call
    /// is always a rejection — there is no runtime fallback.
    private static @NotNull Checked<GdConstantValue> applyCallStep(
            @NotNull Checked<GdConstantValue> receiver,
            @NotNull AttributeCallStep call,
            @NotNull EvaluationEnvironment env
    ) {
        if (receiver instanceof Checked.Namespace<GdConstantValue>) {
            return new Checked.Failed<>(new Evaluation.Rejected(
                    "member calls on constant namespaces are not supported", call));
        }
        var denied = requireCapability(
                env,
                EvaluationPolicy.Capability.REGISTERED_MEMBER_CALL,
                "member calls",
                call
        );
        if (denied != null) {
            return new Checked.Failed<>(denied);
        }
        var receiverValue = ((Checked.Payload<GdConstantValue>) receiver).payload();
        var arguments = new ArrayList<GdConstantValue>(call.arguments().size());
        for (var argumentExpression : call.arguments()) {
            var argumentEvaluation = evaluateExpression(argumentExpression, env);
            if (argumentEvaluation instanceof Evaluation.Rejected rejected) {
                return new Checked.Failed<>(rejected);
            }
            arguments.add(((Evaluation.Resolved) argumentEvaluation).value());
        }
        var argumentTypes = arguments.stream().map(GdConstantValue::type).toList();
        var selection = resolveCallStep(receiverValue.type(), call.name(), argumentTypes, call, env);
        if (selection instanceof CallStepSelection.Failed failed) {
            return new Checked.Failed<>(failed.rejection());
        }
        var registered = (CallStepSelection.Registered) selection;
        var normalizedArguments = new ArrayList<GdConstantValue>(arguments.size());
        for (var index = 0; index < arguments.size(); index++) {
            var normalized = normalizeArgument(
                    arguments.get(index), registered.key().parameterTypes().get(index));
            if (normalized == null) {
                return new Checked.Failed<>(new Evaluation.Rejected(
                        "argument #" + (index + 1) + " of type '"
                                + arguments.get(index).type().getTypeName()
                                + "' cannot be converted to declared parameter type '"
                                + registered.key().parameterTypes().get(index).getTypeName() + "'",
                        call
                ));
            }
            normalizedArguments.add(normalized);
        }
        var callResult = registered.entry().evaluator().evaluate(
                receiverValue, List.copyOf(normalizedArguments));
        return switch (callResult) {
            case ConstCallResult.Rejected rejected ->
                    new Checked.Failed<GdConstantValue>(
                            new Evaluation.Rejected(rejected.reason(), call));
            case ConstCallResult.Resolved resolved -> {
                // A wrong-typed handler result is a programmer invariant violation (§5.5), not
                // a source error — it surfaces as a guard, never disguised as `Rejected`.
                if (!FrontendConstCallSelectionSupport.sameExactType(
                        resolved.value().type(), registered.entry().returnType())) {
                    throw new IllegalStateException(
                            "registered handler for " + registered.key()
                                    + " returned '" + resolved.value().type().getTypeName()
                                    + "', expected '" + registered.entry().returnType().getTypeName() + "'");
                }
                yield new Checked.Payload<GdConstantValue>(resolved.value());
            }
        };
    }

    // ---------------------------------------------------------------------
    // Validation pass — proves whitelist/type/name/call legality without values
    // ---------------------------------------------------------------------

    private static @NotNull Checked<GdType> checkExpression(
            @NotNull Expression expression,
            @NotNull EvaluationEnvironment env
    ) {
        return switch (expression) {
            case LiteralExpression literal -> checkLiteral(literal, env);
            case IdentifierExpression identifier -> checkIdentifier(identifier, env);
            case UnaryExpression unary -> checkUnary(unary, env);
            case BinaryExpression binary -> checkBinary(binary, env);
            case ConditionalExpression conditional -> checkConditional(conditional, env);
            case AttributeExpression attribute -> checkAttributeChain(attribute, env);
            default -> new Checked.Failed<>(rejectedShape(expression, env));
        };
    }

    /// Lexemes are parsed during validation too: a malformed literal in a dead branch is a
    /// whitelist violation, not a domain error, so it still rejects.
    private static @NotNull Checked<GdType> checkLiteral(
            @NotNull LiteralExpression literal,
            @NotNull EvaluationEnvironment env
    ) {
        switch (literal.kind()) {
            case "integer" -> {
                if (!env.permits(EvaluationPolicy.Capability.INT_ARITHMETIC)) {
                    return new Checked.Failed<>(rejectedLiteral(literal, env));
                }
                if (StringUtil.parseGdIntegerLexeme(literal.sourceText()) == null) {
                    return new Checked.Failed<>(new Evaluation.Rejected(
                            "integer literal '" + literal.sourceText()
                                    + "' is malformed or overflows 64-bit int",
                            literal
                    ));
                }
                return new Checked.Payload<>(GdIntType.INT);
            }
            case "float" -> {
                if (!env.permits(EvaluationPolicy.Capability.FLOAT_ARITHMETIC)) {
                    return new Checked.Failed<>(rejectedLiteral(literal, env));
                }
                if (StringUtil.parseGdFloatLexeme(literal.sourceText()) == null) {
                    return new Checked.Failed<>(new Evaluation.Rejected(
                            "float literal '" + literal.sourceText() + "' is malformed", literal));
                }
                return new Checked.Payload<>(GdFloatType.FLOAT);
            }
            case "string" -> {
                if (!env.permits(EvaluationPolicy.Capability.STRING_OPERATORS)) {
                    return new Checked.Failed<>(rejectedLiteral(literal, env));
                }
                try {
                    StringUtil.decodeGdStringLexeme(literal.sourceText());
                } catch (IllegalArgumentException ex) {
                    return new Checked.Failed<>(new Evaluation.Rejected(
                            "string literal is malformed: " + ex.getMessage(), literal));
                }
                return new Checked.Payload<>(GdStringType.STRING);
            }
            case "true", "false" -> {
                if (!env.permits(EvaluationPolicy.Capability.BOOL_OPERATORS)) {
                    return new Checked.Failed<>(rejectedLiteral(literal, env));
                }
                return new Checked.Payload<>(GdBoolType.BOOL);
            }
            default -> {
                return new Checked.Failed<>(rejectedLiteral(literal, env));
            }
        }
    }

    private static @NotNull Checked<GdType> checkIdentifier(
            @NotNull IdentifierExpression identifier,
            @NotNull EvaluationEnvironment env
    ) {
        return switch (resolveConstantName(identifier.name(), identifier, env)) {
            case Checked.Payload<GdConstantValue> payload ->
                    new Checked.Payload<GdType>(payload.payload().type());
            case Checked.Namespace<GdConstantValue> namespace ->
                    new Checked.Namespace<GdType>(namespace.identity());
            case Checked.Failed<GdConstantValue> failed -> new Checked.Failed<>(failed.rejection());
        };
    }

    private static @NotNull Checked<GdType> checkUnary(
            @NotNull UnaryExpression unary,
            @NotNull EvaluationEnvironment env
    ) {
        if ("-".equals(unary.operator())
                && unary.operand() instanceof LiteralExpression operandLiteral
                && "integer".equals(operandLiteral.kind())
                && env.permits(EvaluationPolicy.Capability.INT_ARITHMETIC)
                && env.permits(EvaluationPolicy.Capability.LONG_MIN_LITERAL)
                && isMinValueMagnitudeLexeme(operandLiteral.sourceText())) {
            return new Checked.Payload<>(GdIntType.INT);
        }
        var operand = checkExpression(unary.operand(), env);
        if (operand instanceof Checked.Failed<GdType> failed) {
            return failed;
        }
        if (!(operand instanceof Checked.Payload<GdType> payload)) {
            return new Checked.Failed<>(new Evaluation.Rejected(
                    "unary operand must be a constant value", unary));
        }
        var operator = parseUnaryOperator(unary.operator());
        if (operator == null) {
            return new Checked.Failed<>(new Evaluation.Rejected(
                    "unary operator '" + unary.operator() + "' is not supported in "
                            + unsupportedSuffix(env),
                    unary
            ));
        }
        return requireUnaryOperandType(operator, payload.payload(), unary, env);
    }

    private static @NotNull Checked<GdType> checkBinary(
            @NotNull BinaryExpression binary,
            @NotNull EvaluationEnvironment env
    ) {
        var operator = parseBinaryOperator(binary.operator());
        if (operator == null) {
            return new Checked.Failed<>(new Evaluation.Rejected(
                    "binary operator '" + binary.operator() + "' is not supported in "
                            + unsupportedSuffix(env),
                    binary
            ));
        }
        if (operator == GodotOperator.AND || operator == GodotOperator.OR) {
            var denied = requireCapability(
                    env,
                    EvaluationPolicy.Capability.BOOL_OPERATORS,
                    "binary operator '" + binary.operator() + "'",
                    binary
            );
            if (denied != null) {
                return new Checked.Failed<>(denied);
            }
            var leftCheck = checkExpression(binary.left(), env);
            if (leftCheck instanceof Checked.Failed<GdType> failed) {
                return failed;
            }
            var rightCheck = checkExpression(binary.right(), env);
            if (rightCheck instanceof Checked.Failed<GdType> failed) {
                return failed;
            }
            if (!(leftCheck instanceof Checked.Payload<GdType> leftTyped)
                    || !(leftTyped.payload() instanceof GdBoolType)
                    || !(rightCheck instanceof Checked.Payload<GdType> rightTyped)
                    || !(rightTyped.payload() instanceof GdBoolType)) {
                return new Checked.Failed<>(new Evaluation.Rejected(
                        "operands of '" + binary.operator() + "' must be 'bool'", binary));
            }
            return new Checked.Payload<>(GdBoolType.BOOL);
        }
        var leftCheck = checkExpression(binary.left(), env);
        if (leftCheck instanceof Checked.Failed<GdType> failed) {
            return failed;
        }
        var rightCheck = checkExpression(binary.right(), env);
        if (rightCheck instanceof Checked.Failed<GdType> failed) {
            return failed;
        }
        if (!(leftCheck instanceof Checked.Payload<GdType> leftTyped)
                || !(rightCheck instanceof Checked.Payload<GdType> rightTyped)) {
            return new Checked.Failed<>(new Evaluation.Rejected(
                    "binary operands must be constant values", binary));
        }
        return requireBinaryOperandTypes(operator, leftTyped.payload(), rightTyped.payload(), binary, env);
    }

    private static @NotNull Checked<GdType> checkConditional(
            @NotNull ConditionalExpression conditional,
            @NotNull EvaluationEnvironment env
    ) {
        if (!env.permits(EvaluationPolicy.Capability.TERNARY)) {
            return new Checked.Failed<>(rejectedShape(conditional, env));
        }
        var conditionCheck = checkExpression(conditional.condition(), env);
        if (conditionCheck instanceof Checked.Failed<GdType> failed) {
            return failed;
        }
        if (!(conditionCheck instanceof Checked.Payload<GdType> conditionTyped)
                || !(conditionTyped.payload() instanceof GdBoolType)) {
            return new Checked.Failed<>(new Evaluation.Rejected(
                    "conditional expression condition must be 'bool'", conditional));
        }
        var leftCheck = checkExpression(conditional.left(), env);
        if (leftCheck instanceof Checked.Failed<GdType> failed) {
            return failed;
        }
        var rightCheck = checkExpression(conditional.right(), env);
        if (rightCheck instanceof Checked.Failed<GdType> failed) {
            return failed;
        }
        if (!(leftCheck instanceof Checked.Payload<GdType> leftTyped)
                || !(rightCheck instanceof Checked.Payload<GdType> rightTyped)) {
            return new Checked.Failed<>(new Evaluation.Rejected(
                    "conditional expression branches must be constant values", conditional));
        }
        if (!FrontendConstCallSelectionSupport.sameExactType(leftTyped.payload(), rightTyped.payload())) {
            return new Checked.Failed<>(new Evaluation.Rejected(
                    "conditional expression branches must have the same exact type, got '"
                            + leftTyped.payload().getTypeName() + "' and '"
                            + rightTyped.payload().getTypeName() + "'",
                    conditional
            ));
        }
        return leftTyped;
    }

    private static @NotNull Checked<GdType> checkAttributeChain(
            @NotNull AttributeExpression attribute,
            @NotNull EvaluationEnvironment env
    ) {
        if (!env.permits(EvaluationPolicy.Capability.ATTRIBUTE_ACCESS)) {
            return new Checked.Failed<>(rejectedShape(attribute, env));
        }
        var receiver = checkChainOperand(attribute.base(), env);
        for (var step : attribute.steps()) {
            if (receiver instanceof Checked.Failed<GdType> failed) {
                return failed;
            }
            receiver = applyCheckedStep(receiver, step, env);
        }
        return receiver;
    }

    private static @NotNull Checked<GdType> checkChainOperand(
            @NotNull Expression base,
            @NotNull EvaluationEnvironment env
    ) {
        if (base instanceof IdentifierExpression identifier) {
            return checkIdentifier(identifier, env);
        }
        if (base instanceof AttributeExpression nested) {
            return checkAttributeChain(nested, env);
        }
        return checkExpression(base, env);
    }

    private static @NotNull Checked<GdType> applyCheckedStep(
            @NotNull Checked<GdType> receiver,
            @NotNull AttributeStep step,
            @NotNull EvaluationEnvironment env
    ) {
        return switch (step) {
            case AttributePropertyStep property -> {
                if (receiver instanceof Checked.Namespace<GdType> namespace) {
                    yield switch (env.lookup().resolveMember(namespace.identity(), property.name())) {
                        case LookupContext.LookupResult.ResolvedConstant constant ->
                                new Checked.Payload<GdType>(constant.value().type());
                        case LookupContext.LookupResult.ResolvedNamespace nested ->
                                new Checked.Namespace<GdType>(nested.namespaceIdentity());
                        case LookupContext.LookupResult.NonConstant nonConstant ->
                                new Checked.Failed<GdType>(
                                        new Evaluation.Rejected(nonConstant.reason(), property));
                        case LookupContext.LookupResult.Blocked blocked ->
                                new Checked.Failed<GdType>(
                                        new Evaluation.Rejected(blocked.reason(), property));
                        case LookupContext.LookupResult.Unknown _ ->
                                new Checked.Failed<GdType>(new Evaluation.Rejected(
                                        "unknown member '" + property.name() + "' in constant expression",
                                        property
                                ));
                    };
                }
                var denied = requireCapability(
                        env,
                        EvaluationPolicy.Capability.MEMBER_PROJECTION,
                        "member projection '" + property.name() + "'",
                        property
                );
                yield new Checked.Failed<GdType>(Objects.requireNonNullElseGet(
                        denied,
                        () -> new Evaluation.Rejected(
                                "member projections are not supported in this constant evaluation",
                                property
                        )
                ));
            }
            case AttributeCallStep call -> {
                if (receiver instanceof Checked.Namespace<GdType>) {
                    yield new Checked.Failed<GdType>(new Evaluation.Rejected(
                            "member calls on constant namespaces are not supported", call));
                }
                var denied = requireCapability(
                        env,
                        EvaluationPolicy.Capability.REGISTERED_MEMBER_CALL,
                        "member calls",
                        call
                );
                if (denied != null) {
                    yield new Checked.Failed<GdType>(denied);
                }
                var receiverType = ((Checked.Payload<GdType>) receiver).payload();
                var argumentTypes = new ArrayList<GdType>(call.arguments().size());
                for (var argumentExpression : call.arguments()) {
                    var argumentCheck = checkExpression(argumentExpression, env);
                    if (argumentCheck instanceof Checked.Failed<GdType> failed) {
                        yield failed;
                    }
                    if (!(argumentCheck instanceof Checked.Payload<GdType> payload)) {
                        yield new Checked.Failed<GdType>(new Evaluation.Rejected(
                                "call arguments must be constant values", call));
                    }
                    argumentTypes.add(payload.payload());
                }
                var selection = resolveCallStep(receiverType, call.name(), argumentTypes, call, env);
                if (selection instanceof CallStepSelection.Failed failed) {
                    yield new Checked.Failed<GdType>(failed.rejection());
                }
                var registered = (CallStepSelection.Registered) selection;
                yield new Checked.Payload<GdType>(registered.entry().returnType());
            }
            case AttributeSubscriptStep _, MissingAttributeStep _, UnknownAttributeStep _ ->
                    new Checked.Failed<GdType>(new Evaluation.Rejected(
                            "attribute step '" + step.getClass().getSimpleName()
                                    + "' is not supported in constant expressions",
                            step
                    ));
        };
    }

    // ---------------------------------------------------------------------
    // Shared name resolution and call-step selection
    // ---------------------------------------------------------------------

    private static @NotNull Checked<GdConstantValue> resolveConstantName(
            @NotNull String name,
            @NotNull Node anchor,
            @NotNull EvaluationEnvironment env
    ) {
        return switch (env.lookup().resolve(name)) {
            case LookupContext.LookupResult.ResolvedConstant constant ->
                    new Checked.Payload<>(constant.value());
            case LookupContext.LookupResult.ResolvedNamespace namespace ->
                    new Checked.Namespace<>(namespace.namespaceIdentity());
            case LookupContext.LookupResult.NonConstant nonConstant ->
                    new Checked.Failed<>(new Evaluation.Rejected(nonConstant.reason(), anchor));
            case LookupContext.LookupResult.Blocked blocked ->
                    new Checked.Failed<>(new Evaluation.Rejected(blocked.reason(), anchor));
            case LookupContext.LookupResult.Unknown _ ->
                    new Checked.Failed<>(new Evaluation.Rejected(
                            "unknown identifier '" + name + "' in " + expressionSuffix(env), anchor));
        };
    }

    /// Runs selection through the context and resolves the selected exact key in the frozen
    /// registry. A registry entry whose return type disagrees with the selected signature is a
    /// registration-time programmer invariant, so it throws rather than pretending a source
    /// failure.
    private static @NotNull CallStepSelection resolveCallStep(
            @NotNull GdType receiverType,
            @NotNull String methodName,
            @NotNull List<GdType> argumentTypes,
            @NotNull Node anchor,
            @NotNull EvaluationEnvironment env
    ) {
        return switch (env.lookup().selectCall(receiverType, methodName, argumentTypes)) {
            case LookupContext.SelectionResult.Rejected rejected ->
                    new CallStepSelection.Failed(new Evaluation.Rejected(rejected.reason(), anchor));
            case LookupContext.SelectionResult.Selected selected -> {
                var entry = env.lookup().callableRegistry().find(selected.key());
                if (entry == null) {
                    yield new CallStepSelection.Failed(new Evaluation.Rejected(
                            "method '" + methodName + "' on type '" + receiverType.getTypeName()
                                    + "' is not a registered constant callable",
                            anchor
                    ));
                }
                if (!FrontendConstCallSelectionSupport.sameExactType(
                        entry.returnType(), selected.returnType())) {
                    throw new IllegalStateException(
                            "registered return type '" + entry.returnType().getTypeName()
                                    + "' does not match the selected signature's return type '"
                                    + selected.returnType().getTypeName() + "' for " + selected.key());
                }
                yield new CallStepSelection.Registered(selected.key(), entry);
            }
        };
    }

    /// Converts a frozen argument value to the selected declared parameter type using the
    /// ordinary implicit conversion rules only: identity, `int -> float`, and same-dimension
    /// `Vector*i -> Vector*`. Anything else cannot arise from a legal selection and rejects.
    private static @Nullable GdConstantValue normalizeArgument(
            @NotNull GdConstantValue argument,
            @NotNull GdType declaredType
    ) {
        if (FrontendConstCallSelectionSupport.sameExactType(argument.type(), declaredType)) {
            return argument;
        }
        if (argument instanceof GdConstantValue.IntValue intValue
                && declaredType instanceof GdFloatType) {
            return new GdConstantValue.FloatValue(intValue.value());
        }
        if (argument instanceof GdConstantValue.BuiltinValue builtinValue
                && builtinValue.type() instanceof GdIntVectorType sourceVector
                && declaredType instanceof GdFloatVectorType targetVector
                && sourceVector.getDimension() == targetVector.getDimension()) {
            var leaves = new ArrayList<GdConstantValue>(builtinValue.components().size());
            for (var leaf : builtinValue.components()) {
                if (!(leaf instanceof GdConstantValue.IntValue intLeaf)) {
                    return null;
                }
                leaves.add(new GdConstantValue.FloatValue(intLeaf.value()));
            }
            return new GdConstantValue.BuiltinValue(declaredType, leaves);
        }
        return null;
    }

    // ---------------------------------------------------------------------
    // Operator admission table shared by both passes
    // ---------------------------------------------------------------------

    /// Admits a unary operator on a resolved operand type; the returned payload is the result
    /// type. Capability denials and out-of-whitelist pairs are both rejections, phrased the same
    /// way the enum policy phrases them.
    private static @NotNull Checked<GdType> requireUnaryOperandType(
            @NotNull GodotOperator operator,
            @NotNull GdType operandType,
            @NotNull UnaryExpression unary,
            @NotNull EvaluationEnvironment env
    ) {
        var description = "unary operator '" + unary.operator() + "'";
        switch (operator) {
            case NEGATE, POSITIVE -> {
                if (operandType instanceof GdIntType) {
                    return admit(env, EvaluationPolicy.Capability.INT_ARITHMETIC,
                            description, GdIntType.INT, unary);
                }
                if (operandType instanceof GdFloatType) {
                    return admit(env, EvaluationPolicy.Capability.FLOAT_ARITHMETIC,
                            description, GdFloatType.FLOAT, unary);
                }
                if (isBuiltinConstantType(operandType)) {
                    var denied = requireCapability(
                            env, EvaluationPolicy.Capability.BUILTIN_OPERATORS, description, unary);
                    return new Checked.Failed<>(denied != null
                            ? denied
                            : unsupportedPair(description, operandType, unary, env));
                }
                return new Checked.Failed<>(unsupportedPair(description, operandType, unary, env));
            }
            case BIT_NOT -> {
                var denied = requireCapability(
                        env, EvaluationPolicy.Capability.INT_ARITHMETIC, description, unary);
                if (denied != null) {
                    return new Checked.Failed<>(denied);
                }
                return operandType instanceof GdIntType
                        ? new Checked.Payload<>(GdIntType.INT)
                        : new Checked.Failed<>(new Evaluation.Rejected(
                        "unary operator '~' requires an 'int' operand, got '"
                                + operandType.getTypeName() + "'", unary));
            }
            case NOT -> {
                var denied = requireCapability(
                        env, EvaluationPolicy.Capability.BOOL_OPERATORS, description, unary);
                if (denied != null) {
                    return new Checked.Failed<>(denied);
                }
                return operandType instanceof GdBoolType
                        ? new Checked.Payload<>(GdBoolType.BOOL)
                        : new Checked.Failed<>(new Evaluation.Rejected(
                        "unary operator '" + unary.operator() + "' requires a 'bool' operand, got '"
                                + operandType.getTypeName() + "'", unary));
            }
            default -> {
                return new Checked.Failed<>(new Evaluation.Rejected(
                        description + " is not supported in " + unsupportedSuffix(env), unary));
            }
        }
    }

    /// Admits a binary operator on a resolved operand pair; the returned payload is the result
    /// type. The whitelist is deliberately narrower than ordinary builtin metadata — strict bool
    /// `and`/`or`, same-exact-type scalar pairs, no `in`, no string comparisons or formatting —
    /// so pairs are decided by the constant surface, not by metadata lookup.
    private static @NotNull Checked<GdType> requireBinaryOperandTypes(
            @NotNull GodotOperator operator,
            @NotNull GdType leftType,
            @NotNull GdType rightType,
            @NotNull BinaryExpression binary,
            @NotNull EvaluationEnvironment env
    ) {
        var description = "binary operator '" + binary.operator() + "'";
        // Mixed static int/float stays fail-closed at this boundary — the same rule ordinary
        // typing applies, now through the extracted shared helper.
        if (FrontendBuiltinOperatorSupport.isMixedIntFloatScalarPair(leftType, rightType)) {
            return new Checked.Failed<>(unsupportedPair(description, leftType, rightType, binary, env));
        }
        if (leftType instanceof GdIntType && rightType instanceof GdIntType) {
            return switch (operator) {
                case ADD, SUBTRACT, MULTIPLY, DIVIDE, MODULE, SHIFT_LEFT, SHIFT_RIGHT,
                     BIT_AND, BIT_OR, BIT_XOR -> admit(env, EvaluationPolicy.Capability.INT_ARITHMETIC,
                        description, GdIntType.INT, binary);
                case POWER -> admit(env, EvaluationPolicy.Capability.INT_POWER,
                        description, GdIntType.INT, binary);
                case EQUAL, NOT_EQUAL, LESS, LESS_EQUAL, GREATER, GREATER_EQUAL ->
                        admit(env, EvaluationPolicy.Capability.COMPARISON,
                                description, GdBoolType.BOOL, binary);
                default -> new Checked.Failed<>(unsupportedPair(description, leftType, rightType, binary, env));
            };
        }
        if (leftType instanceof GdFloatType && rightType instanceof GdFloatType) {
            return switch (operator) {
                case ADD, SUBTRACT, MULTIPLY, DIVIDE, POWER ->
                        admit(env, EvaluationPolicy.Capability.FLOAT_ARITHMETIC,
                                description, GdFloatType.FLOAT, binary);
                case EQUAL, NOT_EQUAL, LESS, LESS_EQUAL, GREATER, GREATER_EQUAL ->
                        admit(env, EvaluationPolicy.Capability.COMPARISON,
                                description, GdBoolType.BOOL, binary);
                default -> new Checked.Failed<>(unsupportedPair(description, leftType, rightType, binary, env));
            };
        }
        if (leftType instanceof GdBoolType && rightType instanceof GdBoolType) {
            return switch (operator) {
                case EQUAL, NOT_EQUAL, AND, OR ->
                        admit(env, EvaluationPolicy.Capability.BOOL_OPERATORS,
                                description, GdBoolType.BOOL, binary);
                default -> new Checked.Failed<>(unsupportedPair(description, leftType, rightType, binary, env));
            };
        }
        if (leftType instanceof GdStringType && rightType instanceof GdStringType) {
            return switch (operator) {
                case ADD -> admit(env, EvaluationPolicy.Capability.STRING_OPERATORS,
                        description, GdStringType.STRING, binary);
                case EQUAL, NOT_EQUAL -> admit(env, EvaluationPolicy.Capability.STRING_OPERATORS,
                        description, GdBoolType.BOOL, binary);
                default -> new Checked.Failed<>(unsupportedPair(description, leftType, rightType, binary, env));
            };
        }
        // Controlled-builtin pairs belong to the §5.1 vector/Color surface handled by the
        // builtin-operator stage; while the gate denies them every pair rejects with the
        // capability reason, and even an opened gate keeps out-of-whitelist pairs rejected.
        if (isBuiltinConstantType(leftType) || isBuiltinConstantType(rightType)) {
            var denied = requireCapability(
                    env, EvaluationPolicy.Capability.BUILTIN_OPERATORS, description, binary);
            return new Checked.Failed<>(denied != null
                    ? denied
                    : unsupportedPair(description, leftType, rightType, binary, env));
        }
        return new Checked.Failed<>(unsupportedPair(description, leftType, rightType, binary, env));
    }

    private static @NotNull Checked<GdType> admit(
            @NotNull EvaluationEnvironment env,
            @NotNull EvaluationPolicy.Capability capability,
            @NotNull String description,
            @NotNull GdType resultType,
            @NotNull Node anchor
    ) {
        var denied = requireCapability(env, capability, description, anchor);
        return denied != null
                ? new Checked.Failed<>(denied)
                : new Checked.Payload<>(resultType);
    }

    // ---------------------------------------------------------------------
    // Value computation — runs only after operand admission succeeded
    // ---------------------------------------------------------------------

    private static @NotNull Evaluation computeUnary(
            @NotNull GodotOperator operator,
            @NotNull GdConstantValue operand,
            @NotNull UnaryExpression unary
    ) {
        if (operand instanceof GdConstantValue.IntValue intValue) {
            return switch (operator) {
                // Java long semantics are the int64 wrap rule for unary minus and bitwise not.
                case NEGATE -> new Evaluation.Resolved(new GdConstantValue.IntValue(-intValue.value()));
                case POSITIVE -> new Evaluation.Resolved(operand);
                case BIT_NOT -> new Evaluation.Resolved(new GdConstantValue.IntValue(~intValue.value()));
                default -> throw new IllegalStateException(
                        "admitted unary operator '" + operator + "' is not computable on int");
            };
        }
        if (operand instanceof GdConstantValue.FloatValue floatValue) {
            return switch (operator) {
                case NEGATE -> new Evaluation.Resolved(new GdConstantValue.FloatValue(-floatValue.value()));
                case POSITIVE -> new Evaluation.Resolved(operand);
                default -> throw new IllegalStateException(
                        "admitted unary operator '" + operator + "' is not computable on float");
            };
        }
        if (operand instanceof GdConstantValue.BoolValue boolValue && operator == GodotOperator.NOT) {
            return new Evaluation.Resolved(new GdConstantValue.BoolValue(!boolValue.value()));
        }
        throw new IllegalStateException(
                "admitted unary operator '" + operator + "' is not computable on '"
                        + operand.type().getTypeName() + "'");
    }

    /// Integer arithmetic keeps Java `long` semantics throughout: overflow wraps, division
    /// truncates toward zero (`MIN / -1` wraps to `MIN` instead of throwing), shift counts are
    /// range-checked rather than masked, and `**` runs repeated squaring — never `Math.pow`.
    private static @NotNull Evaluation computeBinary(
            @NotNull GodotOperator operator,
            @NotNull GdConstantValue leftValue,
            @NotNull GdConstantValue rightValue,
            @NotNull BinaryExpression binary,
            @NotNull EvaluationEnvironment env
    ) {
        if (leftValue instanceof GdConstantValue.IntValue leftInt
                && rightValue instanceof GdConstantValue.IntValue rightInt) {
            var left = leftInt.value();
            var right = rightInt.value();
            return switch (operator) {
                case ADD -> resolvedInt(left + right);
                case SUBTRACT -> resolvedInt(left - right);
                case MULTIPLY -> resolvedInt(left * right);
                case DIVIDE -> {
                    if (right == 0) {
                        yield new Evaluation.Rejected(
                                "division by zero in " + expressionSuffix(env), binary);
                    }
                    yield resolvedInt(divideInts(left, right));
                }
                case MODULE -> {
                    if (right == 0) {
                        yield new Evaluation.Rejected(
                                "remainder by zero in " + expressionSuffix(env), binary);
                    }
                    yield resolvedInt(left % right);
                }
                case SHIFT_LEFT, SHIFT_RIGHT -> {
                    if (right < 0 || right > 63) {
                        yield new Evaluation.Rejected(
                                "shift count " + right + " is out of the 0..63 range in "
                                        + expressionSuffix(env),
                                binary
                        );
                    }
                    yield resolvedInt(operator == GodotOperator.SHIFT_LEFT ? left << right : left >> right);
                }
                case BIT_AND -> resolvedInt(left & right);
                case BIT_OR -> resolvedInt(left | right);
                case BIT_XOR -> resolvedInt(left ^ right);
                case POWER -> {
                    if (right < 0) {
                        yield new Evaluation.Rejected(
                                "negative exponent for integer '**' is not supported in "
                                        + expressionSuffix(env),
                                binary
                        );
                    }
                    yield resolvedInt(intPower(left, right));
                }
                case EQUAL -> resolvedBool(left == right);
                case NOT_EQUAL -> resolvedBool(left != right);
                case LESS -> resolvedBool(left < right);
                case LESS_EQUAL -> resolvedBool(left <= right);
                case GREATER -> resolvedBool(left > right);
                case GREATER_EQUAL -> resolvedBool(left >= right);
                default -> throw new IllegalStateException(
                        "admitted binary operator '" + operator + "' is not computable on int pair");
            };
        }
        if (leftValue instanceof GdConstantValue.FloatValue leftFloat
                && rightValue instanceof GdConstantValue.FloatValue rightFloat) {
            var left = leftFloat.value();
            var right = rightFloat.value();
            return switch (operator) {
                case ADD -> resolvedFloat(left + right);
                case SUBTRACT -> resolvedFloat(left - right);
                case MULTIPLY -> resolvedFloat(left * right);
                case DIVIDE -> {
                    if (right == 0.0) {
                        yield new Evaluation.Rejected(
                                "division by zero in " + expressionSuffix(env), binary);
                    }
                    yield resolvedFloat(left / right);
                }
                case POWER -> resolvedFloat(Math.pow(left, right));
                case EQUAL -> resolvedBool(left == right);
                case NOT_EQUAL -> resolvedBool(left != right);
                case LESS -> resolvedBool(left < right);
                case LESS_EQUAL -> resolvedBool(left <= right);
                case GREATER -> resolvedBool(left > right);
                case GREATER_EQUAL -> resolvedBool(left >= right);
                default -> throw new IllegalStateException(
                        "admitted binary operator '" + operator + "' is not computable on float pair");
            };
        }
        if (leftValue instanceof GdConstantValue.BoolValue leftBool
                && rightValue instanceof GdConstantValue.BoolValue rightBool) {
            return switch (operator) {
                case EQUAL -> resolvedBool(leftBool.value() == rightBool.value());
                case NOT_EQUAL -> resolvedBool(leftBool.value() != rightBool.value());
                default -> throw new IllegalStateException(
                        "admitted binary operator '" + operator + "' is not computable on bool pair");
            };
        }
        if (leftValue instanceof GdConstantValue.StringValue leftString
                && rightValue instanceof GdConstantValue.StringValue rightString) {
            return switch (operator) {
                case ADD -> new Evaluation.Resolved(
                        new GdConstantValue.StringValue(leftString.value() + rightString.value()));
                case EQUAL -> resolvedBool(leftString.value().equals(rightString.value()));
                case NOT_EQUAL -> resolvedBool(!leftString.value().equals(rightString.value()));
                default -> throw new IllegalStateException(
                        "admitted binary operator '" + operator + "' is not computable on String pair");
            };
        }
        throw new IllegalStateException(
                "admitted operand pair '" + leftValue.type().getTypeName() + "' and '"
                        + rightValue.type().getTypeName() + "' has no computation rule");
    }

    /// `MIN / -1` is the only `long` division that throws in Java; the int64 wrap rule yields
    /// `MIN` instead, matching the constant model's overflow contract.
    private static long divideInts(long left, long right) {
        return right == -1 ? -left : left / right;
    }

    /// Integer `**` by squaring-and-multiply on `long`: `0 ** 0` is `1`, overflow wraps like the
    /// rest of int64 arithmetic, and the negative-exponent case is rejected by the caller.
    private static long intPower(long base, long exponent) {
        var result = 1L;
        var factor = base;
        var remaining = exponent;
        while (remaining > 0) {
            if ((remaining & 1) != 0) {
                result *= factor;
            }
            remaining >>>= 1;
            if (remaining != 0) {
                factor *= factor;
            }
        }
        return result;
    }

    /// Detects the `Long.MIN_VALUE` magnitude edge in any radix the integer-lexeme grammar
    /// admits. The unsigned parse shares the ordinary lexeme validation — prefix, radix
    /// digits, and between-two-digits underscores — and accepts exactly 2^63; every other
    /// malformed or overflowing lexeme stays a normal literal rejection so nothing else
    /// widens silently.
    private static boolean isMinValueMagnitudeLexeme(@NotNull String lexeme) {
        var magnitude = StringUtil.parseGdUnsignedIntegerLexeme(lexeme);
        return magnitude != null && magnitude == Long.MIN_VALUE;
    }

    private static @Nullable GodotOperator parseUnaryOperator(@NotNull String lexeme) {
        try {
            return GodotOperator.fromSourceLexeme(lexeme, GodotOperator.OperatorArity.UNARY);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static @Nullable GodotOperator parseBinaryOperator(@NotNull String lexeme) {
        try {
            return GodotOperator.fromSourceLexeme(lexeme, GodotOperator.OperatorArity.BINARY);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static @NotNull Evaluation.Resolved resolvedInt(long value) {
        return new Evaluation.Resolved(new GdConstantValue.IntValue(value));
    }

    private static @NotNull Evaluation.Resolved resolvedFloat(double value) {
        return new Evaluation.Resolved(new GdConstantValue.FloatValue(value));
    }

    private static @NotNull Evaluation.Resolved resolvedBool(boolean value) {
        return new Evaluation.Resolved(new GdConstantValue.BoolValue(value));
    }

    // ---------------------------------------------------------------------
    // Rejection helpers — the enum policy keeps its legacy int-only wording
    // ---------------------------------------------------------------------

    private static @Nullable Evaluation.Rejected requireCapability(
            @NotNull EvaluationEnvironment env,
            @NotNull EvaluationPolicy.Capability capability,
            @NotNull String description,
            @NotNull Node anchor
    ) {
        if (env.permits(capability)) {
            return null;
        }
        return new Evaluation.Rejected(
                description + " is not supported in " + unsupportedSuffix(env), anchor);
    }

    /// Rejection-wording bridges: the enum policy keeps its legacy int-only phrasing exactly —
    /// capability/operator denials read "in enum constants" and domain errors read
    /// "in enum constant expression", matching the evaluator this domain replaces.
    private static @NotNull String unsupportedSuffix(@NotNull EvaluationEnvironment env) {
        return env.policy().domain() == EvaluationPolicy.EvaluationDomain.ENUM
                ? "enum constants"
                : "this constant evaluation";
    }

    private static @NotNull String expressionSuffix(@NotNull EvaluationEnvironment env) {
        return env.policy().domain() == EvaluationPolicy.EvaluationDomain.ENUM
                ? "enum constant expression"
                : "constant expression";
    }

    private static @NotNull Evaluation.Rejected rejectedLiteral(
            @NotNull LiteralExpression literal,
            @NotNull EvaluationEnvironment env
    ) {
        return new Evaluation.Rejected(
                env.policy().domain() == EvaluationPolicy.EvaluationDomain.ENUM
                        ? "literal '" + literal.sourceText() + "' is not an int constant"
                        : "literal '" + literal.sourceText() + "' is not a supported constant value",
                literal
        );
    }

    private static @NotNull Evaluation.Rejected rejectedShape(
            @NotNull Expression expression,
            @NotNull EvaluationEnvironment env
    ) {
        var kindName = expression.getClass().getSimpleName();
        return new Evaluation.Rejected(
                env.policy().domain() == EvaluationPolicy.EvaluationDomain.ENUM
                        ? "expression of kind '" + kindName + "' is not a supported int constant expression"
                        : "expression of kind '" + kindName + "' is not a supported constant expression",
                expression
        );
    }

    /// Illegal operand pairs phrase like the legacy enum evaluator in the enum domain —
    /// "binary operator 'in' is not supported in enum constants" — and like the general
    /// constant surface elsewhere.
    private static @NotNull Evaluation.Rejected unsupportedPair(
            @NotNull String description,
            @NotNull GdType leftType,
            @NotNull GdType rightType,
            @NotNull BinaryExpression binary,
            @NotNull EvaluationEnvironment env
    ) {
        return new Evaluation.Rejected(
                env.policy().domain() == EvaluationPolicy.EvaluationDomain.ENUM
                        ? description + " is not supported in enum constants"
                        : description + " is not supported for operand types '" + leftType.getTypeName()
                        + "' and '" + rightType.getTypeName() + "'",
                binary
        );
    }

    private static @NotNull Evaluation.Rejected unsupportedPair(
            @NotNull String description,
            @NotNull GdType operandType,
            @NotNull UnaryExpression unary,
            @NotNull EvaluationEnvironment env
    ) {
        return new Evaluation.Rejected(
                env.policy().domain() == EvaluationPolicy.EvaluationDomain.ENUM
                        ? description + " is not supported in enum constants"
                        : description + " is not supported for operand type '" + operandType.getTypeName() + "'",
                unary
        );
    }

    private static @NotNull Evaluation toEvaluation(
            @NotNull Checked<GdConstantValue> checked,
            @NotNull Node anchor
    ) {
        return switch (checked) {
            case Checked.Payload<GdConstantValue> payload ->
                    new Evaluation.Resolved(payload.payload());
            case Checked.Failed<GdConstantValue> failed -> failed.rejection();
            case Checked.Namespace<GdConstantValue> _ -> new Evaluation.Rejected(
                    "the name denotes a constant namespace, not a value", anchor);
        };
    }

    private static boolean isBuiltinConstantType(@NotNull GdType type) {
        return GdConstantValue.isSupportedConstantType(type)
                && !(type instanceof GdBoolType)
                && !(type instanceof GdIntType)
                && !(type instanceof GdFloatType)
                && !(type instanceof GdStringType);
    }
}
