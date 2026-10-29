package gd.script.gdcc.frontend.sema.analyzer.support;

import dev.superice.gdparser.frontend.ast.AttributeCallStep;
import dev.superice.gdparser.frontend.ast.AttributeExpression;
import dev.superice.gdparser.frontend.ast.AttributePropertyStep;
import dev.superice.gdparser.frontend.ast.AttributeSubscriptStep;
import dev.superice.gdparser.frontend.ast.BinaryExpression;
import dev.superice.gdparser.frontend.ast.CallExpression;
import dev.superice.gdparser.frontend.ast.ConditionalExpression;
import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.LiteralExpression;
import dev.superice.gdparser.frontend.ast.MissingAttributeStep;
import dev.superice.gdparser.frontend.ast.Point;
import dev.superice.gdparser.frontend.ast.Range;
import dev.superice.gdparser.frontend.ast.SubscriptExpression;
import dev.superice.gdparser.frontend.ast.UnaryExpression;
import gd.script.gdcc.frontend.constant.ConstCallResult;
import gd.script.gdcc.frontend.constant.Evaluation;
import gd.script.gdcc.frontend.constant.GdConstantValue;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Behavior contracts for `FrontendConstantEvaluator`: every accepted shape reduces to a frozen
/// `GdConstantValue` and every rejected shape carries a human-readable reason — anchored to the
/// documented §5.1/§5.2/§5.5 semantic rules on both the positive and the negative side.
class FrontendConstantEvaluatorTest {
    private static final @NotNull Range TINY = new Range(0, 1, new Point(0, 0), new Point(0, 1));
    private static final @NotNull ClassRegistry REGISTRY = loadRegistry();

    // ------------------------------------------------------------------
    // Scalar literals and arithmetic
    // ------------------------------------------------------------------

    @Test
    void evaluatesIntegerLiteralsAndOperators() {
        assertAll(
                () -> assertResolvedInt(42, evaluate(lit("integer", "42"))),
                () -> assertResolvedInt(0xF0, evaluate(lit("integer", "0xF0"))),
                () -> assertResolvedInt(0b101, evaluate(lit("integer", "0b101"))),
                () -> assertResolvedInt(15, evaluate(lit("integer", "0o17"))),
                () -> assertResolvedInt(1_000_000, evaluate(lit("integer", "1_000_000"))),
                () -> assertResolvedInt(-1, evaluate(unary("-", intLit(1)))),
                () -> assertResolvedInt(1, evaluate(unary("+", intLit(1)))),
                () -> assertResolvedInt(~0, evaluate(unary("~", intLit(0)))),
                () -> assertResolvedInt(7, evaluate(binary("+", intLit(3), intLit(4)))),
                () -> assertResolvedInt(-1, evaluate(binary("-", intLit(3), intLit(4)))),
                () -> assertResolvedInt(12, evaluate(binary("*", intLit(3), intLit(4)))),
                // Integer division truncates toward zero, matching int64 semantics.
                () -> assertResolvedInt(3, evaluate(binary("/", intLit(7), intLit(2)))),
                () -> assertResolvedInt(-3, evaluate(binary("/", unary("-", intLit(7)), intLit(2)))),
                () -> assertResolvedInt(1, evaluate(binary("%", intLit(7), intLit(3)))),
                () -> assertResolvedInt(8, evaluate(binary("<<", intLit(1), intLit(3)))),
                () -> assertResolvedInt(2, evaluate(binary(">>", intLit(16), intLit(3)))),
                () -> assertResolvedInt(0b1010, evaluate(binary("&", intLit(0b1110), intLit(0b1011)))),
                () -> assertResolvedInt(0b1111, evaluate(binary("|", intLit(0b1100), intLit(0b0111)))),
                () -> assertResolvedInt(0b0110, evaluate(binary("^", intLit(0b1100), intLit(0b1010))))
        );
    }

    @Test
    void integerArithmeticWrapsAndPreservesLongMinEdge() {
        assertAll(
                // int64 wrap on overflow: MAX_VALUE + 1 wraps to MIN_VALUE.
                () -> assertResolvedInt(
                        Long.MIN_VALUE,
                        evaluate(binary("+", lit("integer", "9223372036854775807"), intLit(1)))
                ),
                // `Unary(-) + literal` carries the long-min edge even though the bare magnitude
                // overflows the literal parser.
                () -> assertResolvedInt(
                        Long.MIN_VALUE,
                        evaluate(unary("-", lit("integer", "9223372036854775808")))
                ),
                () -> assertResolvedInt(
                        Long.MIN_VALUE,
                        evaluate(unary("-", lit("integer", "0x8000000000000000")))
                ),
                // `(1 << 63) / -1` wraps to MIN instead of raising like Java `/`.
                () -> assertResolvedInt(
                        Long.MIN_VALUE,
                        evaluate(binary("/", binary("<<", intLit(1), intLit(63)), unary("-", intLit(1))))
                ),
                // A bare overflowing literal still rejects when the unary-minus edge does not apply.
                () -> assertRejected(
                        evaluate(lit("integer", "9223372036854775808")),
                        "malformed or overflows"
                ),
                // The magnitude edge shares the ordinary lexeme grammar: malformed underscore
                // placements around 2^63 are literal rejections, not the Long.MIN_VALUE edge.
                () -> assertRejected(
                        evaluate(unary("-", lit("integer", "9223372036854775808_"))),
                        "malformed or overflows"
                ),
                () -> assertRejected(
                        evaluate(unary("-", lit("integer", "0x_8000000000000000"))),
                        "malformed or overflows"
                ),
                () -> assertRejected(
                        evaluate(unary("-", lit("integer", "9__223372036854775808"))),
                        "malformed or overflows"
                ),
                // The same malformed magnitude inside a dead conditional arm still rejects.
                () -> assertRejected(
                        evaluate(ternary(
                                lit("true", "true"), intLit(1),
                                unary("-", lit("integer", "0x_8000000000000000")))),
                        "malformed or overflows"
                ),
                // A well-formed separated magnitude still takes the edge.
                () -> assertResolvedInt(
                        Long.MIN_VALUE,
                        evaluate(unary("-", lit("integer", "9_223_372_036_854_775_808")))
                )
        );
    }

    @Test
    void integerPowerUsesRepeatedSquaring() {
        assertAll(
                () -> assertResolvedInt(1024, evaluate(binary("**", intLit(2), intLit(10)))),
                () -> assertResolvedInt(1, evaluate(binary("**", intLit(0), intLit(0)))),
                // 2^63 wraps to MIN_VALUE — `Math.pow` would lose this exact value.
                () -> assertResolvedInt(
                        Long.MIN_VALUE,
                        evaluate(binary("**", intLit(2), intLit(63)))
                ),
                () -> assertRejected(
                        evaluate(binary("**", intLit(2), unary("-", intLit(1)))),
                        "negative exponent"
                )
        );
    }

    @Test
    void rejectsIntegerDomainErrors() {
        assertAll(
                () -> assertRejected(evaluate(binary("/", intLit(1), intLit(0))), "division by zero"),
                () -> assertRejected(evaluate(binary("%", intLit(1), intLit(0))), "remainder by zero"),
                () -> assertRejected(
                        evaluate(binary("<<", intLit(1), unary("-", intLit(1)))), "shift count -1"),
                () -> assertRejected(evaluate(binary("<<", intLit(1), intLit(64))), "shift count 64"),
                () -> assertRejected(evaluate(lit("integer", "0xGG")), "malformed")
        );
    }

    @Test
    void evaluatesFloatLiteralsAndOperators() {
        assertAll(
                () -> assertResolvedFloat(4.0, evaluate(binary("+", floatLit("1.5"), floatLit("2.5")))),
                () -> assertResolvedFloat(6.0, evaluate(binary("*", floatLit("1.5"), floatLit("4.0")))),
                () -> assertResolvedFloat(-1.5, evaluate(unary("-", floatLit("1.5")))),
                () -> assertResolvedFloat(1024.0, evaluate(binary("**", floatLit("2.0"), floatLit("10.0")))),
                () -> assertResolvedBool(true, evaluate(binary("<", floatLit("1.5"), floatLit("2.0")))),
                () -> assertResolvedBool(true, evaluate(binary("==", floatLit("1.5"), floatLit("1.5")))),
                () -> assertResolvedBool(true, evaluate(binary("!=", floatLit("1.5"), floatLit("2.5")))),
                () -> assertRejected(
                        evaluate(binary("/", floatLit("1.5"), floatLit("0.0"))),
                        "division by zero"
                ),
                () -> assertRejected(evaluate(lit("float", "1.5x")), "malformed")
        );
    }

    @Test
    void mixedIntFloatOperandsStayRejected() {
        // Ordinary typing applies the same mixed-scalar guard; constant evaluation shares it.
        assertAll(
                () -> assertRejected(evaluate(binary("+", intLit(1), floatLit("1.5"))), "not supported for operand types"),
                () -> assertRejected(evaluate(binary("==", intLit(1), floatLit("1.0"))), "not supported for operand types"),
                () -> assertRejected(evaluate(binary("<", floatLit("1.5"), intLit(2))), "not supported for operand types")
        );
    }

    @Test
    void evaluatesBoolLiteralsAndOperators() {
        assertAll(
                () -> assertResolvedBool(false, evaluate(unary("not", lit("true", "true")))),
                () -> assertResolvedBool(true, evaluate(unary("!", lit("false", "false")))),
                () -> assertResolvedBool(false, evaluate(binary("and", lit("true", "true"), lit("false", "false")))),
                () -> assertResolvedBool(true, evaluate(binary("or", lit("false", "false"), lit("true", "true")))),
                () -> assertResolvedBool(true, evaluate(binary("&&", lit("true", "true"), lit("true", "true")))),
                () -> assertResolvedBool(true, evaluate(binary("||", lit("false", "false"), lit("true", "true")))),
                () -> assertResolvedBool(true, evaluate(binary("==", lit("true", "true"), lit("true", "true")))),
                () -> assertResolvedBool(true, evaluate(binary("!=", lit("true", "true"), lit("false", "false"))))
        );
    }

    @Test
    void boolOperatorsRequireStrictBoolOperands() {
        assertAll(
                () -> assertRejected(evaluate(binary("and", intLit(1), lit("true", "true"))), "must be 'bool'"),
                () -> assertRejected(evaluate(binary("or", lit("true", "true"), intLit(0))), "must be 'bool'"),
                () -> assertRejected(evaluate(unary("not", intLit(1))), "'bool' operand"),
                () -> assertRejected(
                        evaluate(binary("<", lit("true", "true"), lit("false", "false"))),
                        "not supported for operand types"
                )
        );
    }

    @Test
    void evaluatesDecodedStrings() {
        assertAll(
                () -> assertResolvedString("ab", evaluate(binary("+", strLit("a"), strLit("b")))),
                () -> assertResolvedString("a\n", evaluate(lit("string", "\"a\\n\""))),
                () -> assertResolvedBool(true, evaluate(binary("==", strLit("a"), strLit("a")))),
                () -> assertResolvedBool(true, evaluate(binary("!=", strLit("a"), strLit("b")))),
                // The const String whitelist is narrower than metadata: `<` and `%` are rejected.
                () -> assertRejected(evaluate(binary("<", strLit("a"), strLit("b"))), "not supported for operand types"),
                () -> assertRejected(evaluate(binary("-", strLit("a"), strLit("b"))), "not supported for operand types"),
                () -> assertRejected(evaluate(binary("%", strLit("a"), intLit(1))), "not supported for operand types")
        );
    }

    @Test
    void evaluatesSameTypeScalarComparisons() {
        assertAll(
                () -> assertResolvedBool(true, evaluate(binary("<", intLit(1), intLit(2)))),
                () -> assertResolvedBool(true, evaluate(binary("<=", intLit(2), intLit(2)))),
                () -> assertResolvedBool(true, evaluate(binary(">", intLit(3), intLit(2)))),
                () -> assertResolvedBool(true, evaluate(binary(">=", intLit(3), intLit(3)))),
                () -> assertResolvedBool(true, evaluate(binary("!=", intLit(1), intLit(2)))),
                () -> assertResolvedBool(true, evaluate(binary(">", floatLit("3.0"), floatLit("2.0"))))
        );
    }

    // ------------------------------------------------------------------
    // Short-circuit and conditional expressions — whole-tree validation
    // ------------------------------------------------------------------

    @Test
    void shortCircuitsWithoutEvaluatingDeadBranches() {
        assertAll(
                // Division by zero in the dead branch is never executed.
                () -> assertResolvedBool(
                        false,
                        evaluate(binary("and", lit("false", "false"),
                                binary("==", binary("/", intLit(1), intLit(0)), intLit(0))))
                ),
                () -> assertResolvedBool(
                        true,
                        evaluate(binary("or", lit("true", "true"),
                                binary("==", binary("/", intLit(1), intLit(0)), intLit(0))))
                ),
                // The deciding operand is evaluated: division by zero on the left still rejects.
                () -> assertRejected(
                        evaluate(binary("and",
                                binary("==", binary("/", intLit(1), intLit(0)), intLit(0)),
                                lit("true", "true"))),
                        "division by zero"
                )
        );
    }

    @Test
    void deadBranchesCannotHideValidationFailures() {
        // Whole-tree validation runs before short-circuiting, so unknown names, illegal operand
        // pairs, and unregistered calls in the untaken branch still reject.
        assertAll(
                () -> assertRejected(
                        evaluate(binary("and", lit("false", "false"), identifier("MISSING"))),
                        "unknown identifier"
                ),
                () -> assertRejected(
                        evaluate(binary("or", lit("true", "true"), identifier("MISSING"))),
                        "unknown identifier"
                ),
                () -> assertRejected(
                        evaluate(binary("and", lit("false", "false"),
                                binary("/", intLit(1), strLit("s")))),
                        "not supported"
                ),
                () -> assertRejected(
                        evaluate(
                                binary("or", lit("true", "true"),
                                        attrCall(identifier("S"), "contains", strLit("x"))),
                                context(
                                        Map.of("S", constOf(new GdConstantValue.StringValue("abc"))),
                                        Map.of(),
                                        FrontendConstCallableRegistry.empty(),
                                        null
                                )
                        ),
                        "not a registered constant callable"
                )
        );
    }

    @Test
    void evaluatesConditionalExpressions() {
        assertAll(
                () -> assertResolvedInt(1, evaluate(ternary(lit("true", "true"), intLit(1), intLit(2)))),
                () -> assertResolvedInt(2, evaluate(ternary(lit("false", "false"), intLit(1), intLit(2)))),
                () -> assertResolvedString("y", evaluate(ternary(lit("false", "false"), strLit("x"), strLit("y")))),
                // The dead arm computes nothing: its division by zero never fires.
                () -> assertResolvedInt(
                        1,
                        evaluate(ternary(lit("true", "true"), intLit(1), binary("/", intLit(1), intLit(0))))
                )
        );
    }

    @Test
    void rejectsIllegalConditionalShapes() {
        assertAll(
                () -> assertRejected(
                        evaluate(ternary(intLit(1), intLit(1), intLit(2))),
                        "condition must be 'bool'"
                ),
                () -> assertRejected(
                        evaluate(ternary(lit("true", "true"), intLit(1), floatLit("2.0"))),
                        "same exact type"
                ),
                // Dead branches are validated: an unknown name in the untaken arm still rejects.
                () -> assertRejected(
                        evaluate(ternary(lit("true", "true"), intLit(1), identifier("MISSING"))),
                        "unknown identifier"
                )
        );
    }

    // ------------------------------------------------------------------
    // Identifier and namespace lookup
    // ------------------------------------------------------------------

    @Test
    void resolvesIdentifiersThroughLookupStates() {
        var context = context(
                Map.of(
                        "ANSWER", constOf(new GdConstantValue.IntValue(42)),
                        "RATIO", constOf(new GdConstantValue.FloatValue(1.5)),
                        "FLAG", constOf(new GdConstantValue.BoolValue(true)),
                        "NAME", constOf(new GdConstantValue.StringValue("gdcc")),
                        "VAR", new LookupContext.LookupResult.NonConstant("'VAR' is mutable"),
                        "SHADOW", new LookupContext.LookupResult.Blocked("'SHADOW' is a forward reference")
                ),
                Map.of(),
                FrontendConstCallableRegistry.empty(),
                null
        );
        assertAll(
                () -> assertResolvedInt(43, evaluate(binary("+", identifier("ANSWER"), intLit(1)), context)),
                () -> assertResolvedFloat(3.0, evaluate(binary("*", identifier("RATIO"), floatLit("2.0")), context)),
                () -> assertResolvedBool(true, evaluate(identifier("FLAG"), context)),
                () -> assertResolvedString("gdcc", evaluate(identifier("NAME"), context)),
                () -> assertRejected(evaluate(identifier("VAR"), context), "mutable"),
                () -> assertRejected(evaluate(identifier("SHADOW"), context), "forward reference"),
                () -> assertRejected(evaluate(identifier("MISSING"), context), "unknown identifier")
        );
    }

    @Test
    void descendsNamespaceMemberChains() {
        var context = context(
                Map.of(
                        "State", new LookupContext.LookupResult.ResolvedNamespace("State"),
                        "Other", new LookupContext.LookupResult.ResolvedNamespace("Other")
                ),
                Map.of(
                        "State.IDLE", constOf(new GdConstantValue.IntValue(0)),
                        "State.RUN", constOf(new GdConstantValue.IntValue(1)),
                        "Other.Inner", new LookupContext.LookupResult.ResolvedNamespace("Inner"),
                        "Inner.CONST", constOf(new GdConstantValue.IntValue(7))
                ),
                FrontendConstCallableRegistry.empty(),
                null
        );
        assertAll(
                () -> assertResolvedInt(0, evaluate(attrProperty(identifier("State"), "IDLE"), context)),
                () -> assertResolvedInt(
                        1,
                        evaluate(binary("+", attrProperty(identifier("State"), "RUN"), intLit(0)), context)
                ),
                () -> assertResolvedInt(
                        7,
                        evaluate(
                                new AttributeExpression(
                                        attrProperty(identifier("Other"), "Inner"),
                                        List.of(new AttributePropertyStep("CONST", TINY)),
                                        TINY
                                ),
                                context
                        )
                ),
                // A namespace token alone is not a value.
                () -> assertRejected(evaluate(identifier("State"), context), "not a value"),
                () -> assertRejected(evaluate(attrProperty(identifier("State"), "MISSING"), context), "unknown member"),
                () -> assertRejected(
                        evaluate(binary("+", intLit(1), identifier("State")), context),
                        "must be constant values"
                )
        );
    }

    // ------------------------------------------------------------------
    // Registered member-call dispatch
    // ------------------------------------------------------------------

    @Test
    void dispatchesRegisteredMemberCallsThroughExactSelection() {
        var registry = callableRegistry(builder -> builder
                .register(
                        new ConstCallableKey(
                                GdFloatVectorType.VECTOR2, "length",
                                ConstCallableKey.InvocationKind.INSTANCE_METHOD, List.of()),
                        GdFloatType.FLOAT,
                        (receiver, args) -> {
                            var components = ((GdConstantValue.BuiltinValue) receiver).components();
                            var x = ((GdConstantValue.FloatValue) components.get(0)).value();
                            var y = ((GdConstantValue.FloatValue) components.get(1)).value();
                            return new ConstCallResult.Resolved(
                                    new GdConstantValue.FloatValue(Math.hypot(x, y)));
                        }
                )
                .register(
                        new ConstCallableKey(
                                GdFloatVectorType.VECTOR2, "move_toward",
                                ConstCallableKey.InvocationKind.INSTANCE_METHOD,
                                List.of(GdFloatVectorType.VECTOR2, GdFloatType.FLOAT)),
                        GdFloatVectorType.VECTOR2,
                        (receiver, args) -> new ConstCallResult.Resolved(receiver)
                )
        );
        var vector = GdConstantValue.BuiltinValue.ofFloats(GdFloatVectorType.VECTOR2, 3.0, 4.0);
        var context = context(Map.of("V", constOf(vector)), Map.of(), registry, null);
        assertAll(
                () -> assertResolvedFloat(5.0, evaluate(attrCall(identifier("V"), "length"), context)),
                () -> assertResolvedValue(
                        vector,
                        evaluate(
                                attrCall(identifier("V"), "move_toward", identifier("V"), intLit(2)),
                                context
                        )
                )
        );
    }

    @Test
    void normalizesArgumentsToDeclaredParameterTypes() {
        // `move_toward(to: Vector2, delta: float)` accepts a Vector2i + int call because the
        // ordinary boundary permits Vector2i -> Vector2 and int -> float; the evaluator
        // materializes both conversions before dispatching to the handler.
        var captured = new AtomicReference<List<GdConstantValue>>();
        var registry = callableRegistry(builder -> builder.register(
                new ConstCallableKey(
                        GdFloatVectorType.VECTOR2, "move_toward",
                        ConstCallableKey.InvocationKind.INSTANCE_METHOD,
                        List.of(GdFloatVectorType.VECTOR2, GdFloatType.FLOAT)),
                GdFloatVectorType.VECTOR2,
                (receiver, args) -> {
                    captured.set(args);
                    return new ConstCallResult.Resolved(args.get(0));
                }
        ));
        var context = context(
                Map.of(
                        "V", constOf(GdConstantValue.BuiltinValue.ofFloats(GdFloatVectorType.VECTOR2, 1.0, 1.0)),
                        "VI", constOf(GdConstantValue.BuiltinValue.ofInts(GdIntVectorType.VECTOR2I, 3, 4))
                ),
                Map.of(),
                registry,
                null
        );
        var result = evaluate(
                attrCall(identifier("V"), "move_toward", identifier("VI"), intLit(2)),
                context
        );
        var resolved = assertInstanceOf(Evaluation.Resolved.class, result);
        assertAll(
                () -> assertEquals(
                        GdConstantValue.BuiltinValue.ofFloats(GdFloatVectorType.VECTOR2, 3.0, 4.0),
                        resolved.value()
                ),
                () -> assertEquals(
                        List.of(
                                GdConstantValue.BuiltinValue.ofFloats(GdFloatVectorType.VECTOR2, 3.0, 4.0),
                                new GdConstantValue.FloatValue(2.0)
                        ),
                        captured.get()
                )
        );
    }

    @Test
    void registeredCallRejectionMatrix() {
        var registry = callableRegistry(builder -> builder.register(
                new ConstCallableKey(
                        GdStringType.STRING, "contains",
                        ConstCallableKey.InvocationKind.INSTANCE_METHOD, List.of(GdStringType.STRING)),
                GdBoolType.BOOL,
                (receiver, args) -> new ConstCallResult.Resolved(new GdConstantValue.BoolValue(false))
        ));
        var context = context(
                Map.of(
                        "V", constOf(GdConstantValue.BuiltinValue.ofFloats(GdFloatVectorType.VECTOR2, 1, 2)),
                        "S", constOf(new GdConstantValue.StringValue("abc")),
                        "NS", new LookupContext.LookupResult.ResolvedNamespace("NS")
                ),
                Map.of(),
                registry,
                null
        );
        assertAll(
                // Selectable method but unregistered key.
                () -> assertRejected(
                        evaluate(attrCall(identifier("V"), "length"), context),
                        "not a registered constant callable"
                ),
                // No applicable overload for the provided argument count.
                () -> assertRejected(evaluate(attrCall(identifier("V"), "length", intLit(1)), context), null),
                // Method does not exist on the receiver type at all.
                () -> assertRejected(
                        evaluate(attrCall(identifier("V"), "nonexistent"), context),
                        "not found"
                ),
                // Omitted default arguments are not filled in for constant calls.
                () -> assertRejected(
                        evaluate(attrCall(identifier("S"), "substr", intLit(1)), context),
                        "omitted-default"
                ),
                // Namespace receivers cannot own member calls.
                () -> assertRejected(
                        evaluate(attrCall(identifier("NS"), "foo"), context),
                        "namespaces"
                ),
                // Subscript steps are not constant-admissible.
                () -> assertRejected(
                        evaluate(
                                new AttributeExpression(
                                        identifier("V"),
                                        List.of(new AttributeSubscriptStep("x", List.of(intLit(0)), TINY)),
                                        TINY
                                ),
                                context
                        ),
                        "not supported"
                ),
                () -> assertRejected(
                        evaluate(
                                new AttributeExpression(
                                        identifier("V"),
                                        List.of(new MissingAttributeStep(TINY)),
                                        TINY
                                ),
                                context
                        ),
                        "not supported"
                ),
                // Member projections on values belong to the later stage, not this surface.
                () -> assertRejected(
                        evaluate(attrProperty(identifier("V"), "x"), context),
                        "not supported"
                )
        );
    }

    @Test
    void laterSiblingValidationFailureNeverRunsHandlers() {
        var invocations = new AtomicInteger();
        var registry = callableRegistry(builder -> builder.register(
                new ConstCallableKey(
                        GdStringType.STRING, "contains",
                        ConstCallableKey.InvocationKind.INSTANCE_METHOD, List.of(GdStringType.STRING)),
                GdBoolType.BOOL,
                (receiver, args) -> {
                    invocations.incrementAndGet();
                    return new ConstCallResult.Resolved(new GdConstantValue.BoolValue(true));
                }
        ));
        var context = context(
                Map.of("S", constOf(new GdConstantValue.StringValue("abc"))),
                Map.of(),
                registry,
                null
        );
        // Whole-tree validation proves `MISSING` unresolvable before any value is computed —
        // the handler in the earlier sibling must never run.
        var result = evaluate(
                binary("==", attrCall(identifier("S"), "contains", strLit("x")), identifier("MISSING")),
                context
        );
        assertAll(
                () -> assertRejected(result, "unknown identifier"),
                () -> assertEquals(0, invocations.get())
        );
    }

    @Test
    void unexecutedDeadBranchCallsValidateButNeverRun() {
        var invocations = new AtomicInteger();
        var registry = callableRegistry(builder -> builder.register(
                new ConstCallableKey(
                        GdStringType.STRING, "contains",
                        ConstCallableKey.InvocationKind.INSTANCE_METHOD, List.of(GdStringType.STRING)),
                GdBoolType.BOOL,
                (receiver, args) -> {
                    invocations.incrementAndGet();
                    return new ConstCallResult.Resolved(new GdConstantValue.BoolValue(true));
                }
        ));
        var context = context(
                Map.of("S", constOf(new GdConstantValue.StringValue("abc"))),
                Map.of(),
                registry,
                null
        );
        var call = attrCall(identifier("S"), "contains", strLit("b"));
        assertAll(
                // Taken side: the handler actually runs.
                () -> assertResolvedBool(true, evaluate(binary("or", lit("false", "false"), call), context)),
                // Dead side: signature and registration are validated, the handler never runs.
                () -> assertResolvedBool(true, evaluate(binary("or", lit("true", "true"), call), context)),
                () -> assertResolvedBool(false, evaluate(binary("and", lit("false", "false"), call), context)),
                () -> assertEquals(1, invocations.get())
        );
    }

    @Test
    void handlerContractViolationsSurface() {
        var wrongTypeRegistry = callableRegistry(builder -> builder.register(
                new ConstCallableKey(
                        GdStringType.STRING, "contains",
                        ConstCallableKey.InvocationKind.INSTANCE_METHOD, List.of(GdStringType.STRING)),
                GdBoolType.BOOL,
                (receiver, args) -> new ConstCallResult.Resolved(new GdConstantValue.IntValue(1))
        ));
        var domainRegistry = callableRegistry(builder -> builder.register(
                new ConstCallableKey(
                        GdStringType.STRING, "contains",
                        ConstCallableKey.InvocationKind.INSTANCE_METHOD, List.of(GdStringType.STRING)),
                GdBoolType.BOOL,
                (receiver, args) -> new ConstCallResult.Rejected("needle not found")
        ));
        var wrongTypeContext = context(
                Map.of("S", constOf(new GdConstantValue.StringValue("abc"))),
                Map.of(),
                wrongTypeRegistry,
                null
        );
        var domainContext = context(
                Map.of("S", constOf(new GdConstantValue.StringValue("abc"))),
                Map.of(),
                domainRegistry,
                null
        );
        var call = attrCall(identifier("S"), "contains", strLit("b"));
        assertAll(
                // A wrong-typed handler result is a programmer invariant, not a source rejection.
                () -> assertThrows(IllegalStateException.class, () -> evaluate(call, wrongTypeContext)),
                // Handler domain rejections become ordinary constant-evaluation rejections.
                () -> assertRejected(evaluate(call, domainContext), "needle not found"),
                // A selection-rejected call propagates the context's reason.
                () -> assertRejected(
                        evaluate(
                                call,
                                context(
                                        Map.of("S", constOf(new GdConstantValue.StringValue("abc"))),
                                        Map.of(),
                                        FrontendConstCallableRegistry.empty(),
                                        new LookupContext.SelectionResult.Rejected("call site rejected")
                                )
                        ),
                        "call site rejected"
                )
        );
    }

    // ------------------------------------------------------------------
    // Policy gates and unsupported shapes
    // ------------------------------------------------------------------

    @Test
    void rejectsUnsupportedShapesAndOperators() {
        assertAll(
                () -> assertRejected(
                        evaluate(new CallExpression(identifier("f"), List.of(), TINY)),
                        "not a supported constant expression"
                ),
                () -> assertRejected(
                        evaluate(new SubscriptExpression(identifier("V"), List.of(intLit(0)), TINY)),
                        "not a supported constant expression"
                ),
                () -> assertRejected(evaluate(lit("null", "null")), "not a supported constant value"),
                () -> assertRejected(evaluate(lit("string_name", "&\"s\"")), "not a supported constant value"),
                () -> assertRejected(evaluate(binary("in", intLit(1), intLit(2))), "not supported"),
                () -> assertRejected(evaluate(binary("not in", intLit(1), intLit(2))), "not supported")
        );
    }

    @Test
    void capabilityGatesAreHonoredIndividually() {
        var intOnly = new EvaluationPolicy.CapabilitySet(
                Set.of(EvaluationPolicy.Capability.INT_ARITHMETIC),
                EvaluationPolicy.EvaluationDomain.GENERAL
        );
        var minEdgeOnly = new EvaluationPolicy.CapabilitySet(
                Set.of(EvaluationPolicy.Capability.LONG_MIN_LITERAL),
                EvaluationPolicy.EvaluationDomain.GENERAL
        );
        var noComparison = new EvaluationPolicy.CapabilitySet(
                Set.of(
                        EvaluationPolicy.Capability.INT_ARITHMETIC,
                        EvaluationPolicy.Capability.FLOAT_ARITHMETIC,
                        EvaluationPolicy.Capability.BOOL_OPERATORS,
                        EvaluationPolicy.Capability.STRING_OPERATORS,
                        EvaluationPolicy.Capability.TERNARY,
                        EvaluationPolicy.Capability.ATTRIBUTE_ACCESS,
                        EvaluationPolicy.Capability.LONG_MIN_LITERAL
                ),
                EvaluationPolicy.EvaluationDomain.GENERAL
        );
        assertAll(
                () -> assertResolvedInt(3, evaluate(binary("+", intLit(1), intLit(2)), intOnly)),
                () -> assertRejected(evaluate(binary("**", intLit(2), intLit(3)), intOnly), "not supported"),
                () -> assertRejected(evaluate(lit("float", "1.5"), intOnly), "not a supported constant value"),
                // Without LONG_MIN_LITERAL the magnitude overflows before the edge can apply.
                () -> assertRejected(
                        evaluate(unary("-", lit("integer", "9223372036854775808")), intOnly),
                        "overflows"
                ),
                // LONG_MIN_LITERAL alone does not grant the integer surface: the edge still
                // requires INT_ARITHMETIC, so the literal rejects through the ordinary gate.
                () -> assertRejected(
                        evaluate(unary("-", lit("integer", "9223372036854775808")), minEdgeOnly),
                        "not a supported constant value"
                ),
                () -> assertResolvedBool(true, evaluate(binary("<", intLit(1), intLit(2)))),
                () -> assertRejected(evaluate(binary("<", intLit(1), intLit(2)), noComparison), "not supported"),
                () -> assertRejected(
                        evaluate(ternary(lit("true", "true"), intLit(1), intLit(2)), intOnly),
                        "not a supported constant expression"
                )
        );
    }

    @Test
    void enumPolicyKeepsLegacyIntOnlySurface() {
        var context = context(Map.of(), Map.of(), FrontendConstCallableRegistry.empty(), null);
        assertAll(
                () -> assertResolvedInt(
                        3,
                        evaluate(binary("+", intLit(1), intLit(2)), context, EvaluationPolicy.enumPrePass())
                ),
                () -> assertRejected(
                        evaluate(lit("float", "1.5"), context, EvaluationPolicy.enumPrePass()),
                        "not an int constant"
                ),
                () -> assertRejected(
                        evaluate(lit("string", "\"s\""), context, EvaluationPolicy.enumPrePass()),
                        "not an int constant"
                ),
                () -> assertRejected(
                        evaluate(binary("**", intLit(2), intLit(3)), context, EvaluationPolicy.enumPrePass()),
                        "not supported"
                ),
                () -> assertRejected(
                        evaluate(ternary(lit("true", "true"), intLit(1), intLit(2)), context, EvaluationPolicy.enumPrePass()),
                        "not a supported int constant expression"
                ),
                () -> assertRejected(
                        evaluate(unary("-", lit("integer", "9223372036854775808")), context, EvaluationPolicy.enumPrePass()),
                        "overflows"
                )
        );
    }

    // ------------------------------------------------------------------
    // Fixture
    // ------------------------------------------------------------------

    private static @NotNull Evaluation evaluate(@NotNull Expression expression) {
        return evaluate(expression, context(Map.of(), Map.of(), FrontendConstCallableRegistry.empty(), null));
    }

    private static @NotNull Evaluation evaluate(
            @NotNull Expression expression,
            @NotNull EvaluationPolicy policy
    ) {
        return evaluate(expression, context(Map.of(), Map.of(), FrontendConstCallableRegistry.empty(), null), policy);
    }

    private static @NotNull Evaluation evaluate(
            @NotNull Expression expression,
            @NotNull LookupContext context
    ) {
        return FrontendConstantEvaluator.evaluate(expression, context, EvaluationPolicy.general());
    }

    private static @NotNull Evaluation evaluate(
            @NotNull Expression expression,
            @NotNull LookupContext context,
            @NotNull EvaluationPolicy policy
    ) {
        return FrontendConstantEvaluator.evaluate(expression, context, policy);
    }

    private static @NotNull LookupContext.LookupResult constOf(@NotNull GdConstantValue value) {
        return new LookupContext.LookupResult.ResolvedConstant(value);
    }

    private static @NotNull LookupContext context(
            @NotNull Map<String, LookupContext.LookupResult> names,
            @NotNull Map<String, LookupContext.LookupResult> members,
            @NotNull FrontendConstCallableRegistry callables,
            @Nullable LookupContext.SelectionResult selectionOverride
    ) {
        return new TestLookupContext(names, members, callables, selectionOverride);
    }

    private static @NotNull FrontendConstCallableRegistry callableRegistry(
            @NotNull Consumer<FrontendConstCallableRegistry.Builder> registrations
    ) {
        var builder = FrontendConstCallableRegistry.builder(REGISTRY);
        registrations.accept(builder);
        return builder.build();
    }

    private static @NotNull ClassRegistry loadRegistry() {
        try {
            return new ClassRegistry(ExtensionApiLoader.loadDefault());
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    /// Deterministic map-driven `LookupContext`: bare names hit `names`, namespace tokens are
    /// plain objects so `resolveMember` keys on `"token.member"`, and selection delegates to the
    /// shared pure helper unless a canned `SelectionResult` override is installed.
    private static final class TestLookupContext implements LookupContext {
        private final Map<String, LookupResult> names;
        private final Map<String, LookupResult> members;
        private final FrontendConstCallableRegistry callables;
        private final ClassRegistry classRegistry;
        private final SelectionResult selectionOverride;

        private TestLookupContext(
                @NotNull Map<String, LookupResult> names,
                @NotNull Map<String, LookupResult> members,
                @NotNull FrontendConstCallableRegistry callables,
                @Nullable SelectionResult selectionOverride
        ) {
            this.names = Map.copyOf(names);
            this.members = Map.copyOf(members);
            this.callables = Objects.requireNonNull(callables);
            this.classRegistry = REGISTRY;
            this.selectionOverride = selectionOverride;
        }

        @Override
        public @NotNull LookupResult resolve(@NotNull String name) {
            return names.getOrDefault(name, new LookupResult.Unknown());
        }

        @Override
        public @NotNull LookupResult resolveMember(@NotNull Object namespaceIdentity, @NotNull String memberName) {
            return members.getOrDefault(namespaceIdentity + "." + memberName, new LookupResult.Unknown());
        }

        @Override
        public @NotNull FrontendConstCallableRegistry callableRegistry() {
            return callables;
        }

        @Override
        public @NotNull ClassRegistry classRegistry() {
            return classRegistry;
        }

        @Override
        public @NotNull SelectionResult selectCall(
                @NotNull GdType receiverType,
                @NotNull String methodName,
                @NotNull List<GdType> argumentTypes
        ) {
            return selectionOverride != null
                    ? selectionOverride
                    : LookupContext.super.selectCall(receiverType, methodName, argumentTypes);
        }
    }

    // ------------------------------------------------------------------
    // AST helpers
    // ------------------------------------------------------------------

    private static @NotNull LiteralExpression lit(@NotNull String kind, @NotNull String sourceText) {
        return new LiteralExpression(kind, sourceText, TINY);
    }

    private static @NotNull LiteralExpression intLit(long value) {
        return lit("integer", Long.toString(value));
    }

    private static @NotNull LiteralExpression floatLit(@NotNull String sourceText) {
        return lit("float", sourceText);
    }

    private static @NotNull LiteralExpression strLit(@NotNull String payload) {
        return lit("string", "\"" + payload + "\"");
    }

    private static @NotNull IdentifierExpression identifier(@NotNull String name) {
        return new IdentifierExpression(name, TINY);
    }

    private static @NotNull UnaryExpression unary(@NotNull String operator, @NotNull Expression operand) {
        return new UnaryExpression(operator, operand, TINY);
    }

    private static @NotNull BinaryExpression binary(
            @NotNull String operator,
            @NotNull Expression left,
            @NotNull Expression right
    ) {
        return new BinaryExpression(operator, left, right, TINY);
    }

    private static @NotNull ConditionalExpression ternary(
            @NotNull Expression condition,
            @NotNull Expression left,
            @NotNull Expression right
    ) {
        return new ConditionalExpression(condition, left, right, TINY);
    }

    private static @NotNull AttributeExpression attrProperty(@NotNull Expression base, @NotNull String name) {
        return new AttributeExpression(base, List.of(new AttributePropertyStep(name, TINY)), TINY);
    }

    private static @NotNull AttributeExpression attrCall(
            @NotNull Expression base,
            @NotNull String methodName,
            @NotNull Expression... arguments
    ) {
        return new AttributeExpression(
                base,
                List.of(new AttributeCallStep(methodName, List.of(arguments), TINY)),
                TINY
        );
    }

    // ------------------------------------------------------------------
    // Assertions
    // ------------------------------------------------------------------

    private static void assertResolvedInt(long expected, @NotNull Evaluation evaluation) {
        var resolved = assertInstanceOf(Evaluation.Resolved.class, evaluation);
        var value = assertInstanceOf(GdConstantValue.IntValue.class, resolved.value());
        assertEquals(expected, value.value());
    }

    private static void assertResolvedFloat(double expected, @NotNull Evaluation evaluation) {
        var resolved = assertInstanceOf(Evaluation.Resolved.class, evaluation);
        var value = assertInstanceOf(GdConstantValue.FloatValue.class, resolved.value());
        assertEquals(expected, value.value());
    }

    private static void assertResolvedBool(boolean expected, @NotNull Evaluation evaluation) {
        var resolved = assertInstanceOf(Evaluation.Resolved.class, evaluation);
        var value = assertInstanceOf(GdConstantValue.BoolValue.class, resolved.value());
        assertEquals(expected, value.value());
    }

    private static void assertResolvedString(@NotNull String expected, @NotNull Evaluation evaluation) {
        var resolved = assertInstanceOf(Evaluation.Resolved.class, evaluation);
        var value = assertInstanceOf(GdConstantValue.StringValue.class, resolved.value());
        assertEquals(expected, value.value());
    }

    private static void assertResolvedValue(@NotNull GdConstantValue expected, @NotNull Evaluation evaluation) {
        var resolved = assertInstanceOf(Evaluation.Resolved.class, evaluation);
        assertEquals(expected, resolved.value());
    }

    private static void assertRejected(@NotNull Evaluation evaluation, @Nullable String messagePart) {
        var rejected = assertInstanceOf(Evaluation.Rejected.class, evaluation);
        if (messagePart != null) {
            assertTrue(
                    rejected.reason().contains(messagePart),
                    "Expected reason to contain '" + messagePart + "' but was: " + rejected.reason()
            );
        }
    }
}
