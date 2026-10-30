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

import java.util.HashMap;
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
                // Member projections resolve through the §5.4 whitelist — they are not
                // registry calls and never consult `callableRegistry()`.
                () -> assertResolvedFloat(
                        1,
                        evaluate(attrProperty(identifier("V"), "x"), context)
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
                // With constructors open, `f()` reaches callee resolution — the name is
                // unknown, so the rejection is the name-binding diagnostic rather than a
                // shape rejection.
                () -> assertRejected(
                        evaluate(new CallExpression(identifier("f"), List.of(), TINY)),
                        "unknown identifier 'f'"
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
    // §5.3 scalar constructor whitelist
    // ------------------------------------------------------------------

    @Test
    void scalarConstructorsConvertAndCopy() {
        var context = typeContext(GdIntType.INT, GdFloatType.FLOAT, GdBoolType.BOOL, GdStringType.STRING);
        assertAll(
                () -> assertResolvedInt(0, evaluate(call(identifier("int")), context)),
                () -> assertResolvedInt(5, evaluate(call(identifier("int"), intLit(5)), context)),
                // int(float) truncates toward zero; only finite values inside int64 admit.
                () -> assertResolvedInt(2, evaluate(call(identifier("int"), floatLit("2.9")), context)),
                () -> assertResolvedInt(-2, evaluate(call(identifier("int"),
                        unary("-", floatLit("2.9"))), context)),
                () -> assertResolvedInt(1, evaluate(call(identifier("int"), lit("true", "true")), context)),
                () -> assertResolvedInt(0, evaluate(call(identifier("int"), lit("false", "false")), context)),
                () -> assertResolvedFloat(0.0, evaluate(call(identifier("float")), context)),
                () -> assertResolvedFloat(2.0, evaluate(call(identifier("float"), intLit(2)), context)),
                () -> assertResolvedFloat(1.0, evaluate(call(identifier("float"), lit("true", "true")), context)),
                () -> assertResolvedFloat(0.5, evaluate(call(identifier("float"), floatLit("0.5")), context)),
                () -> assertResolvedBool(false, evaluate(call(identifier("bool")), context)),
                () -> assertResolvedBool(true, evaluate(call(identifier("bool"), intLit(1)), context)),
                () -> assertResolvedBool(false, evaluate(call(identifier("bool"), intLit(0)), context)),
                () -> assertResolvedBool(true, evaluate(call(identifier("bool"), floatLit("0.5")), context)),
                () -> assertResolvedBool(false, evaluate(call(identifier("bool"), floatLit("0.0")), context)),
                () -> assertResolvedString("", evaluate(call(identifier("String")), context)),
                () -> assertResolvedString("abc", evaluate(call(identifier("String"), strLit("abc")), context))
        );
    }

    @Test
    void excludedScalarConstructorFormsReject() {
        var context = typeContext(GdIntType.INT, GdFloatType.FLOAT, GdStringType.STRING);
        assertAll(
                // int(String) / float(String) exist in metadata but are outside the §5.3 whitelist.
                () -> assertRejected(
                        evaluate(call(identifier("int"), strLit("5")), context), "whitelist"),
                () -> assertRejected(
                        evaluate(call(identifier("float"), strLit("1.5")), context), "whitelist"),
                // int(float) fails closed: non-finite and out-of-int64-range values reject.
                () -> assertRejected(
                        evaluate(call(identifier("int"), floatLit("1e19")), context), "not convertible"),
                () -> assertRejected(
                        evaluate(call(identifier("int"), unary("-", floatLit("1e19"))), context),
                        "not convertible"),
                // No int->String overload exists at all — a selection failure, not a whitelist miss.
                () -> assertRejected(
                        evaluate(call(identifier("String"), intLit(5)), context), "applicable"),
                () -> assertRejected(
                        evaluate(call(identifier("String"),
                                call(identifier("String"), strLit("a"), intLit(1))), context),
                        "applicable")
        );
    }

    // ------------------------------------------------------------------
    // §5.3 geometry constructor whitelist
    // ------------------------------------------------------------------

    @Test
    void vectorConstructorsCoverWhitelistedShapes() {
        var context = typeContext(
                GdFloatVectorType.VECTOR2, GdFloatVectorType.VECTOR3, GdFloatVectorType.VECTOR4,
                GdIntVectorType.VECTOR2I, GdIntVectorType.VECTOR3I, GdIntVectorType.VECTOR4I);
        assertAll(
                () -> assertResolvedFloatLeaves(GdFloatVectorType.VECTOR2, new double[]{0, 0},
                        evaluate(call(identifier("Vector2")), context)),
                // VectorN accepts N float components — int args normalize through int->float.
                () -> assertResolvedFloatLeaves(GdFloatVectorType.VECTOR2, new double[]{1, 2},
                        evaluate(call(identifier("Vector2"), intLit(1), intLit(2)), context)),
                () -> assertResolvedFloatLeaves(GdFloatVectorType.VECTOR3, new double[]{1, 2, 3},
                        evaluate(call(identifier("Vector3"), intLit(1), intLit(2), intLit(3)), context)),
                () -> assertResolvedFloatLeaves(GdFloatVectorType.VECTOR4, new double[]{1, 2, 3, 4},
                        evaluate(call(identifier("Vector4"), intLit(1), intLit(2), intLit(3), intLit(4)), context)),
                // Copy construction passes the canonical value through unchanged.
                () -> assertResolvedFloatLeaves(GdFloatVectorType.VECTOR2, new double[]{1, 2},
                        evaluate(call(identifier("Vector2"), v2(1, 2)), context)),
                // Same-dimension VectorNi -> VectorN widens each int leaf exactly.
                () -> assertResolvedFloatLeaves(GdFloatVectorType.VECTOR2, new double[]{1, -2},
                        evaluate(call(identifier("Vector2"), call(
                                identifier("Vector2i"), intLit(1), unary("-", intLit(2)))), context)),
                () -> assertResolvedFloatLeaves(GdFloatVectorType.VECTOR3, new double[]{1, 2, 3},
                        evaluate(call(identifier("Vector3"), call(
                                identifier("Vector3i"), intLit(1), intLit(2), intLit(3))), context)),
                // VectorNi takes int leaves inside the int32 component range.
                () -> assertResolvedIntLeaves(GdIntVectorType.VECTOR2I, new long[]{0, 0},
                        evaluate(call(identifier("Vector2i")), context)),
                () -> assertResolvedIntLeaves(GdIntVectorType.VECTOR2I, new long[]{1, -2},
                        evaluate(call(identifier("Vector2i"), intLit(1), unary("-", intLit(2))), context)),
                () -> assertResolvedIntLeaves(GdIntVectorType.VECTOR3I, new long[]{1, 2, 3},
                        evaluate(call(identifier("Vector3i"), intLit(1), intLit(2), intLit(3)), context)),
                () -> assertResolvedIntLeaves(GdIntVectorType.VECTOR4I, new long[]{1, 2, 3, 4},
                        evaluate(call(identifier("Vector4i"), intLit(1), intLit(2), intLit(3), intLit(4)), context)),
                // int32 component endpoints admit.
                () -> assertResolvedIntLeaves(GdIntVectorType.VECTOR2I,
                        new long[]{Integer.MAX_VALUE, Integer.MIN_VALUE},
                        evaluate(call(identifier("Vector2i"),
                                intLit(Integer.MAX_VALUE),
                                unary("-", lit("integer", "2147483648"))), context)),
                // VectorN -> VectorNi truncates toward zero per component.
                () -> assertResolvedIntLeaves(GdIntVectorType.VECTOR2I, new long[]{1, -2},
                        evaluate(call(identifier("Vector2i"), v2(1.9, -2.9)), context))
        );
    }

    @Test
    void compositeConstructorsAssembleCanonicalLayouts() {
        var context = typeContext(
                GdFloatVectorType.VECTOR2, GdFloatVectorType.VECTOR3, GdFloatVectorType.VECTOR4,
                GdIntVectorType.VECTOR2I, GdRect2Type.RECT2, GdRect2iType.RECT2I,
                GdPlaneType.PLANE, GdQuaternionType.QUATERNION, GdAABBType.AABB,
                GdBasisType.BASIS, GdTransform2DType.TRANSFORM2D, GdTransform3DType.TRANSFORM3D,
                GdProjectionType.PROJECTION, GdColorType.COLOR);
        assertAll(
                // Rect2: two same-type vectors (Vector2i converts to Vector2), four floats,
                // or a Rect2i widening — components are position then size.
                () -> assertResolvedFloatLeaves(GdRect2Type.RECT2, new double[]{1, 2, 3, 4},
                        evaluate(call(identifier("Rect2"), v2(1, 2), v2(3, 4)), context)),
                () -> assertResolvedFloatLeaves(GdRect2Type.RECT2, new double[]{1, 2, 3, 4},
                        evaluate(call(identifier("Rect2"), intLit(1), intLit(2), intLit(3), intLit(4)), context)),
                () -> assertResolvedFloatLeaves(GdRect2Type.RECT2, new double[]{1, 2, 3, 4},
                        evaluate(call(identifier("Rect2"), call(
                                identifier("Rect2i"), intLit(1), intLit(2), intLit(3), intLit(4))), context)),
                () -> assertResolvedFloatLeaves(GdRect2Type.RECT2, new double[]{1, 2, 3, 4},
                        evaluate(call(identifier("Rect2"), call(identifier("Vector2i"), intLit(1), intLit(2)),
                                call(identifier("Vector2i"), intLit(3), intLit(4))), context)),
                // Rect2i mirrors the shapes with int32 leaves.
                () -> assertResolvedIntLeaves(GdRect2iType.RECT2I, new long[]{1, 2, 3, 4},
                        evaluate(call(identifier("Rect2i"), intLit(1), intLit(2), intLit(3), intLit(4)), context)),
                () -> assertResolvedIntLeaves(GdRect2iType.RECT2I, new long[]{1, 2, 3, 4},
                        evaluate(call(identifier("Rect2i"),
                                call(identifier("Vector2i"), intLit(1), intLit(2)),
                                call(identifier("Vector2i"), intLit(3), intLit(4))), context)),
                () -> assertResolvedIntLeaves(GdRect2iType.RECT2I, new long[]{1, 2, 3, 4},
                        evaluate(call(identifier("Rect2i"), call(
                                identifier("Rect2"), intLit(1), intLit(2), intLit(3), intLit(4))), context)),
                // Plane: (Vector3 normal, float d) and four floats share the canonical layout.
                () -> assertResolvedFloatLeaves(GdPlaneType.PLANE, new double[]{0, 1, 0, 5},
                        evaluate(call(identifier("Plane"), v3(0, 1, 0), intLit(5)), context)),
                () -> assertResolvedFloatLeaves(GdPlaneType.PLANE, new double[]{0, 1, 0, 5},
                        evaluate(call(identifier("Plane"), intLit(0), intLit(1), intLit(0), intLit(5)), context)),
                // Quaternion defaults to identity and takes four floats.
                () -> assertResolvedFloatLeaves(GdQuaternionType.QUATERNION, new double[]{0, 0, 0, 1},
                        evaluate(call(identifier("Quaternion")), context)),
                () -> assertResolvedFloatLeaves(GdQuaternionType.QUATERNION, new double[]{1, 2, 3, 4},
                        evaluate(call(identifier("Quaternion"), intLit(1), intLit(2), intLit(3), intLit(4)), context)),
                // AABB takes position and size Vector3s.
                () -> assertResolvedFloatLeaves(GdAABBType.AABB, new double[]{1, 2, 3, 4, 5, 6},
                        evaluate(call(identifier("AABB"), v3(1, 2, 3), v3(4, 5, 6)), context)),
                // Basis keeps logical axis columns — asymmetric data pins the column order.
                () -> assertResolvedFloatLeaves(GdBasisType.BASIS,
                        new double[]{1, 2, 3, 4, 5, 6, 7, 8, 10},
                        evaluate(call(identifier("Basis"), v3(1, 2, 3), v3(4, 5, 6), v3(7, 8, 10)), context)),
                // Transform2D takes three Vector2 columns (x, y, origin).
                () -> assertResolvedFloatLeaves(GdTransform2DType.TRANSFORM2D,
                        new double[]{1, 2, 3, 4, 5, 6},
                        evaluate(call(identifier("Transform2D"), v2(1, 2), v2(3, 4), v2(5, 6)), context)),
                // Transform3D: (Basis, Vector3) and four-Vector3 forms share the layout —
                // x/y/z basis columns then origin.
                () -> assertResolvedFloatLeaves(GdTransform3DType.TRANSFORM3D,
                        new double[]{1, 2, 3, 4, 5, 6, 7, 8, 10, 11, 12, 13},
                        evaluate(call(identifier("Transform3D"),
                                call(identifier("Basis"), v3(1, 2, 3), v3(4, 5, 6), v3(7, 8, 10)),
                                v3(11, 12, 13)), context)),
                () -> assertResolvedFloatLeaves(GdTransform3DType.TRANSFORM3D,
                        new double[]{1, 2, 3, 4, 5, 6, 7, 8, 10, 11, 12, 13},
                        evaluate(call(identifier("Transform3D"),
                                v3(1, 2, 3), v3(4, 5, 6), v3(7, 8, 10), v3(11, 12, 13)), context)),
                // Projection takes four Vector4 columns.
                () -> assertResolvedFloatLeaves(GdProjectionType.PROJECTION,
                        new double[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16},
                        evaluate(call(identifier("Projection"),
                                v4(1, 2, 3, 4), v4(5, 6, 7, 8), v4(9, 10, 11, 12), v4(13, 14, 15, 16)), context)),
                // Color: rgb implies alpha=1; (Color, float alpha) keeps rgb and replaces alpha.
                () -> assertResolvedFloatLeaves(GdColorType.COLOR, new double[]{0, 0, 0, 1},
                        evaluate(call(identifier("Color")), context)),
                () -> assertResolvedFloatLeaves(GdColorType.COLOR, new double[]{1, 2, 3, 1},
                        evaluate(call(identifier("Color"), intLit(1), intLit(2), intLit(3)), context)),
                () -> assertResolvedFloatLeaves(GdColorType.COLOR, new double[]{1, 2, 3, 4},
                        evaluate(call(identifier("Color"), intLit(1), intLit(2), intLit(3), intLit(4)), context)),
                () -> assertResolvedFloatLeaves(GdColorType.COLOR, new double[]{1, 2, 3, 5},
                        evaluate(call(identifier("Color"),
                                call(identifier("Color"), intLit(1), intLit(2), intLit(3), intLit(4)),
                                intLit(5)), context))
        );
    }

    @Test
    void defaultConstructorsProduceDocumentedDefaults() {
        var context = typeContext(
                GdRect2Type.RECT2, GdRect2iType.RECT2I, GdPlaneType.PLANE, GdAABBType.AABB,
                GdBasisType.BASIS, GdTransform2DType.TRANSFORM2D, GdTransform3DType.TRANSFORM3D,
                GdProjectionType.PROJECTION);
        assertAll(
                () -> assertResolvedFloatLeaves(GdRect2Type.RECT2, new double[]{0, 0, 0, 0},
                        evaluate(call(identifier("Rect2")), context)),
                () -> assertResolvedIntLeaves(GdRect2iType.RECT2I, new long[]{0, 0, 0, 0},
                        evaluate(call(identifier("Rect2i")), context)),
                () -> assertResolvedFloatLeaves(GdPlaneType.PLANE, new double[]{0, 0, 0, 0},
                        evaluate(call(identifier("Plane")), context)),
                () -> assertResolvedFloatLeaves(GdAABBType.AABB, new double[]{0, 0, 0, 0, 0, 0},
                        evaluate(call(identifier("AABB")), context)),
                // Identity transforms — zero defaults would be silently wrong here.
                () -> assertResolvedFloatLeaves(GdBasisType.BASIS,
                        new double[]{1, 0, 0, 0, 1, 0, 0, 0, 1},
                        evaluate(call(identifier("Basis")), context)),
                () -> assertResolvedFloatLeaves(GdTransform2DType.TRANSFORM2D,
                        new double[]{1, 0, 0, 1, 0, 0},
                        evaluate(call(identifier("Transform2D")), context)),
                () -> assertResolvedFloatLeaves(GdTransform3DType.TRANSFORM3D,
                        new double[]{1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0},
                        evaluate(call(identifier("Transform3D")), context)),
                () -> assertResolvedFloatLeaves(GdProjectionType.PROJECTION,
                        new double[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1},
                        evaluate(call(identifier("Projection")), context))
        );
    }

    @Test
    void excludedGeometryConstructorOverloadsReject() {
        var context = typeContext(
                GdFloatVectorType.VECTOR2, GdFloatVectorType.VECTOR3, GdIntVectorType.VECTOR2I,
                GdRect2iType.RECT2I, GdPlaneType.PLANE, GdQuaternionType.QUATERNION,
                GdBasisType.BASIS, GdTransform2DType.TRANSFORM2D, GdTransform3DType.TRANSFORM3D,
                GdProjectionType.PROJECTION, GdColorType.COLOR);
        assertAll(
                // Axis-angle, arc, and Basis conversions are outside the Quaternion whitelist.
                () -> assertRejected(
                        evaluate(call(identifier("Quaternion"), v3(0, 1, 0), floatLit("0.5")), context),
                        "whitelist"),
                () -> assertRejected(
                        evaluate(call(identifier("Quaternion"), v3(0, 0, 1), v3(1, 0, 0)), context),
                        "whitelist"),
                () -> assertRejected(
                        evaluate(call(identifier("Quaternion"), call(identifier("Basis"))), context),
                        "whitelist"),
                () -> assertRejected(
                        evaluate(call(identifier("Basis"), call(identifier("Quaternion"))), context),
                        "whitelist"),
                () -> assertRejected(
                        evaluate(call(identifier("Basis"), v3(0, 1, 0), floatLit("0.5")), context),
                        "whitelist"),
                // Plane normal-only, normal+point, and three-point forms are excluded.
                () -> assertRejected(
                        evaluate(call(identifier("Plane"), v3(0, 1, 0)), context), "whitelist"),
                () -> assertRejected(
                        evaluate(call(identifier("Plane"), v3(0, 1, 0), v3(1, 0, 0)), context),
                        "whitelist"),
                () -> assertRejected(
                        evaluate(call(identifier("Plane"), v3(0, 0, 0), v3(1, 0, 0), v3(0, 1, 0)), context),
                        "whitelist"),
                // Transform2D rotation forms and the Projection cross-conversions are excluded.
                () -> assertRejected(
                        evaluate(call(identifier("Transform2D"), floatLit("0.5"), v2(1, 2)), context),
                        "whitelist"),
                () -> assertRejected(
                        evaluate(call(identifier("Transform2D"),
                                floatLit("0.5"), v2(1, 2), floatLit("0.1"), v2(3, 4)), context),
                        "whitelist"),
                () -> assertRejected(
                        evaluate(call(identifier("Transform3D"), call(identifier("Projection"))), context),
                        "whitelist"),
                () -> assertRejected(
                        evaluate(call(identifier("Projection"), call(identifier("Transform3D"))), context),
                        "whitelist"),
                // Named-color parsing and float->int-vector arguments are excluded.
                () -> assertRejected(
                        evaluate(call(identifier("Color"), strLit("red")), context), "whitelist"),
                () -> assertRejected(
                        evaluate(call(identifier("Color"), strLit("red"), floatLit("0.5")), context),
                        "whitelist"),
                // Arity and argument-type mismatches fail overload selection itself.
                () -> assertRejected(
                        evaluate(call(identifier("Vector2"), intLit(1)), context), "applicable"),
                () -> assertRejected(
                        evaluate(call(identifier("Vector2i"), floatLit("1.5"), intLit(2)), context),
                        "applicable"),
                () -> assertRejected(
                        evaluate(call(identifier("Rect2i"), v2(1, 2), v2(3, 4)), context), "applicable"),
                // int64 leaves that exceed the int32 component range reject at construction.
                () -> assertRejected(
                        evaluate(call(identifier("Vector2i"), intLit(2147483648L), intLit(0)), context),
                        "int32")
        );
    }

    @Test
    void constructorCallShapeValidation() {
        var constant = context(
                Map.of("V", constOf(GdConstantValue.BuiltinValue.ofFloats(
                        GdFloatVectorType.VECTOR2, 1, 2))),
                Map.of(),
                FrontendConstCallableRegistry.empty(), null);
        var namespaces = context(
                Map.of(
                        "State", new LookupContext.LookupResult.ResolvedNamespace(new Object()),
                        "Vector2", typeName(GdFloatVectorType.VECTOR2),
                        "Variant", typeName(GdVariantType.VARIANT),
                        "StringName", typeName(GdStringNameType.STRING_NAME)),
                Map.of(),
                FrontendConstCallableRegistry.empty(), null);
        assertAll(
                // A value callee is not a constructor.
                () -> assertRejected(evaluate(call(identifier("V"), intLit(1)), constant),
                        "not a type constructor"),
                // A namespace whose identity is not a builtin type (enum groups, script
                // classes) cannot construct constants either.
                () -> assertRejected(evaluate(call(identifier("State"), intLit(1)), namespaces),
                        "not a builtin type"),
                // Nested callee results are values too.
                () -> assertRejected(
                        evaluate(call(call(identifier("Vector2")), intLit(1)), namespaces),
                        "not a type constructor"),
                // Unknown callees and non-value arguments keep rejecting through the operand pass.
                () -> assertRejected(evaluate(call(identifier("Missing"), intLit(1)), namespaces),
                        "unknown identifier"),
                () -> assertRejected(
                        evaluate(call(identifier("Vector2"), identifier("Missing"), intLit(2)), namespaces),
                        "unknown identifier"),
                () -> assertRejected(
                        evaluate(call(identifier("Vector2"), identifier("State"), intLit(2)), namespaces),
                        "constant values"),
                // Types outside the constant surface (Variant) and builtin classes outside the
                // constructor whitelist (StringName) both fail the constructibility gate.
                () -> assertRejected(evaluate(call(identifier("Variant"), intLit(1)), namespaces),
                        "constant-constructible"),
                () -> assertRejected(evaluate(call(identifier("StringName"), strLit("x")), namespaces),
                        "constant-constructible")
        );
    }

    // ------------------------------------------------------------------
    // §5.1 geometry operators
    // ------------------------------------------------------------------

    @Test
    void unaryPlusMinusOnMetadataAdmittedTypes() {
        var context = context(
                Map.of(
                        "V", constOf(GdConstantValue.BuiltinValue.ofFloats(GdFloatVectorType.VECTOR2, 1, -2)),
                        "VI", constOf(GdConstantValue.BuiltinValue.ofInts(GdIntVectorType.VECTOR3I, 1, -2, 3)),
                        "P", constOf(GdConstantValue.BuiltinValue.ofFloats(GdPlaneType.PLANE, 0, 1, 0, 5)),
                        "Q", constOf(GdConstantValue.BuiltinValue.ofFloats(GdQuaternionType.QUATERNION, 1, 2, 3, 4)),
                        "C", constOf(GdConstantValue.BuiltinValue.ofFloats(GdColorType.COLOR, 0.5, 0.25, 0.125, 1)),
                        "B", constOf(GdConstantValue.BuiltinValue.ofFloats(GdBasisType.BASIS,
                                1, 0, 0, 0, 1, 0, 0, 0, 1)),
                        "R", constOf(GdConstantValue.BuiltinValue.ofFloats(GdRect2Type.RECT2, 1, 2, 3, 4))),
                Map.of(),
                FrontendConstCallableRegistry.empty(), null);
        assertAll(
                () -> assertResolvedFloatLeaves(GdFloatVectorType.VECTOR2, new double[]{-1, 2},
                        evaluate(unary("-", identifier("V")), context)),
                () -> assertResolvedFloatLeaves(GdFloatVectorType.VECTOR2, new double[]{1, -2},
                        evaluate(unary("+", identifier("V")), context)),
                () -> assertResolvedIntLeaves(GdIntVectorType.VECTOR3I, new long[]{-1, 2, -3},
                        evaluate(unary("-", identifier("VI")), context)),
                () -> assertResolvedFloatLeaves(GdPlaneType.PLANE, new double[]{-0.0, -1, -0.0, -5},
                        evaluate(unary("-", identifier("P")), context)),
                () -> assertResolvedFloatLeaves(GdQuaternionType.QUATERNION, new double[]{-1, -2, -3, -4},
                        evaluate(unary("-", identifier("Q")), context)),
                () -> assertResolvedFloatLeaves(GdColorType.COLOR, new double[]{-0.5, -0.25, -0.125, -1},
                        evaluate(unary("-", identifier("C")), context)),
                // Metadata owns no unary entry for Basis/Rect2/transforms — those reject even
                // though they are controlled builtins.
                () -> assertRejected(evaluate(unary("-", identifier("B")), context), "not supported"),
                () -> assertRejected(evaluate(unary("-", identifier("R")), context), "not supported")
        );
    }

    @Test
    void componentArithmeticOnVectorsAndColor() {
        var context = context(
                Map.of(
                        "V", constOf(GdConstantValue.BuiltinValue.ofFloats(GdFloatVectorType.VECTOR2, 8, -6)),
                        "VI", constOf(GdConstantValue.BuiltinValue.ofInts(GdIntVectorType.VECTOR2I, 7, -6)),
                        "C", constOf(GdConstantValue.BuiltinValue.ofFloats(GdColorType.COLOR, 0.5, 0.5, 0.5, 1)),
                        "Vector2", typeName(GdFloatVectorType.VECTOR2),
                        "Vector2i", typeName(GdIntVectorType.VECTOR2I),
                        "Color", typeName(GdColorType.COLOR)),
                Map.of(),
                FrontendConstCallableRegistry.empty(), null);
        assertAll(
                // Same-type component + - * / for float vectors.
                () -> assertResolvedFloatLeaves(GdFloatVectorType.VECTOR2, new double[]{9, -4},
                        evaluate(binary("+", identifier("V"), v2(1, 2)), context)),
                () -> assertResolvedFloatLeaves(GdFloatVectorType.VECTOR2, new double[]{7, -8},
                        evaluate(binary("-", identifier("V"), v2(1, 2)), context)),
                () -> assertResolvedFloatLeaves(GdFloatVectorType.VECTOR2, new double[]{16, -12},
                        evaluate(binary("*", identifier("V"), v2(2, 2)), context)),
                () -> assertResolvedFloatLeaves(GdFloatVectorType.VECTOR2, new double[]{4, -3},
                        evaluate(binary("/", identifier("V"), v2(2, 2)), context)),
                // *i vectors additionally admit % — toward-zero division, dividend-signed
                // remainder, both matching Godot semantics.
                () -> assertResolvedIntLeaves(GdIntVectorType.VECTOR2I, new long[]{9, -4},
                        evaluate(binary("+", identifier("VI"),
                                call(identifier("Vector2i"), intLit(2), intLit(2))), context)),
                () -> assertResolvedIntLeaves(GdIntVectorType.VECTOR2I, new long[]{3, -3},
                        evaluate(binary("/", identifier("VI"),
                                call(identifier("Vector2i"), intLit(2), intLit(2))), context)),
                () -> assertResolvedIntLeaves(GdIntVectorType.VECTOR2I, new long[]{1, 0},
                        evaluate(binary("%", identifier("VI"),
                                call(identifier("Vector2i"), intLit(3), intLit(2))), context)),
                // Color component arithmetic shares the vector rules.
                () -> assertResolvedFloatLeaves(GdColorType.COLOR, new double[]{0.25, 0.25, 0.25, 1},
                        evaluate(binary("*", identifier("C"), call(
                                identifier("Color"), floatLit("0.5"), floatLit("0.5"), floatLit("0.5"), intLit(1))),
                                context))
        );
    }

    @Test
    void scalarPairsFollowMetadataDirection() {
        var context = context(
                Map.of(
                        "V", constOf(GdConstantValue.BuiltinValue.ofFloats(GdFloatVectorType.VECTOR2, 4, -6)),
                        "VI", constOf(GdConstantValue.BuiltinValue.ofInts(GdIntVectorType.VECTOR2I, 4, -6)),
                        "C", constOf(GdConstantValue.BuiltinValue.ofFloats(GdColorType.COLOR, 0.5, 0.5, 0.5, 1))),
                Map.of(),
                FrontendConstCallableRegistry.empty(), null);
        assertAll(
                // Right-scalar * / keep the builtin type when metadata says so.
                () -> assertResolvedFloatLeaves(GdFloatVectorType.VECTOR2, new double[]{8, -12},
                        evaluate(binary("*", identifier("V"), intLit(2)), context)),
                () -> assertResolvedFloatLeaves(GdFloatVectorType.VECTOR2, new double[]{10, -15},
                        evaluate(binary("*", identifier("V"), floatLit("2.5")), context)),
                () -> assertResolvedFloatLeaves(GdFloatVectorType.VECTOR2, new double[]{2, -3},
                        evaluate(binary("/", identifier("V"), intLit(2)), context)),
                () -> assertResolvedIntLeaves(GdIntVectorType.VECTOR2I, new long[]{8, -12},
                        evaluate(binary("*", identifier("VI"), intLit(2)), context)),
                () -> assertResolvedIntLeaves(GdIntVectorType.VECTOR2I, new long[]{2, -3},
                        evaluate(binary("/", identifier("VI"), intLit(2)), context)),
                // *i * float promotes to the float vector the metadata declares.
                () -> assertResolvedFloatLeaves(GdFloatVectorType.VECTOR2, new double[]{10, -15},
                        evaluate(binary("*", identifier("VI"), floatLit("2.5")), context)),
                // Left-scalar * mirrors the right-scalar result type.
                () -> assertResolvedFloatLeaves(GdFloatVectorType.VECTOR2, new double[]{8, -12},
                        evaluate(binary("*", intLit(2), identifier("V")), context)),
                () -> assertResolvedIntLeaves(GdIntVectorType.VECTOR2I, new long[]{8, -12},
                        evaluate(binary("*", intLit(2), identifier("VI")), context)),
                () -> assertResolvedFloatLeaves(GdFloatVectorType.VECTOR2, new double[]{10, -15},
                        evaluate(binary("*", floatLit("2.5"), identifier("VI")), context)),
                () -> assertResolvedFloatLeaves(GdColorType.COLOR, new double[]{1, 1, 1, 2},
                        evaluate(binary("*", identifier("C"), intLit(2)), context))
        );
    }

    @Test
    void geometryOperatorWhitelistRejectsOtherPairs() {
        var context = context(
                Map.of(
                        "V", constOf(GdConstantValue.BuiltinValue.ofFloats(GdFloatVectorType.VECTOR2, 1, 2)),
                        "VI", constOf(GdConstantValue.BuiltinValue.ofInts(GdIntVectorType.VECTOR2I, 1, 2)),
                        "B", constOf(GdConstantValue.BuiltinValue.ofFloats(GdBasisType.BASIS,
                                1, 0, 0, 0, 1, 0, 0, 0, 1)),
                        "T", constOf(GdConstantValue.BuiltinValue.ofFloats(GdTransform3DType.TRANSFORM3D,
                                1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0)),
                        "Q", constOf(GdConstantValue.BuiltinValue.ofFloats(GdQuaternionType.QUATERNION, 0, 0, 0, 1))),
                Map.of(),
                FrontendConstCallableRegistry.empty(), null);
        assertAll(
                // Relational geometry comparisons are not in the §5.1 surface.
                () -> assertRejected(evaluate(binary("<", identifier("V"), identifier("V")), context), "not supported"),
                // Different builtin types never pair — Vector2 vs Vector2i stays rejected.
                () -> assertRejected(evaluate(binary("+", identifier("V"), identifier("VI")), context), "not supported"),
                // % on float vectors and scalar-sided % are excluded.
                () -> assertRejected(evaluate(binary("%", identifier("V"), identifier("V")), context), "not supported"),
                () -> assertRejected(evaluate(binary("%", identifier("VI"), intLit(2)), context), "not supported"),
                // scalar + Vector / scalar - Vector / scalar / Vector all reject.
                () -> assertRejected(evaluate(binary("+", intLit(1), identifier("V")), context), "not supported"),
                () -> assertRejected(evaluate(binary("-", intLit(1), identifier("V")), context), "not supported"),
                () -> assertRejected(evaluate(binary("/", intLit(2), identifier("V")), context), "not supported"),
                // vector + scalar / vector - scalar reject — only * / open on the right.
                () -> assertRejected(evaluate(binary("+", identifier("V"), intLit(1)), context), "not supported"),
                () -> assertRejected(evaluate(binary("-", identifier("V"), intLit(1)), context), "not supported"),
                // Matrix/transform/quaternion arithmetic is outside v1 even though metadata
                // lists entries for it.
                () -> assertRejected(evaluate(binary("*", identifier("B"), identifier("B")), context), "not supported"),
                () -> assertRejected(evaluate(binary("*", identifier("T"), identifier("T")), context), "not supported"),
                () -> assertRejected(evaluate(binary("*", identifier("Q"), identifier("Q")), context), "not supported"),
                () -> assertRejected(evaluate(binary("+", identifier("Q"), identifier("Q")), context), "not supported")
        );
    }

    @Test
    void geometryArithmeticFailsClosedOnDomainErrors() {
        var context = context(
                Map.ofEntries(
                        Map.entry("V", constOf(GdConstantValue.BuiltinValue.ofFloats(GdFloatVectorType.VECTOR2, 4, -6))),
                        Map.entry("VI", constOf(GdConstantValue.BuiltinValue.ofInts(GdIntVectorType.VECTOR2I, 4, -6))),
                        Map.entry("VIZERO", constOf(GdConstantValue.BuiltinValue.ofInts(GdIntVectorType.VECTOR2I, 0, 1))),
                        Map.entry("VZERO", constOf(GdConstantValue.BuiltinValue.ofFloats(GdFloatVectorType.VECTOR2, 0, 1))),
                        Map.entry("VIMAX", constOf(GdConstantValue.BuiltinValue.ofInts(
                                GdIntVectorType.VECTOR2I, Integer.MAX_VALUE, 1))),
                        Map.entry("VIMIN", constOf(GdConstantValue.BuiltinValue.ofInts(
                                GdIntVectorType.VECTOR2I, Integer.MIN_VALUE, 1))),
                        Map.entry("Vector2i", typeName(GdIntVectorType.VECTOR2I)),
                        Map.entry("Vector3i", typeName(GdIntVectorType.VECTOR3I))),
                Map.of(),
                FrontendConstCallableRegistry.empty(), null);
        assertAll(
                // Per-component zero divisors reject.
                () -> assertRejected(evaluate(binary("/", identifier("V"), identifier("VZERO")), context),
                        "division by zero"),
                () -> assertRejected(evaluate(binary("/", identifier("VI"), identifier("VIZERO")), context),
                        "division by zero"),
                () -> assertRejected(evaluate(binary("%", identifier("VI"), identifier("VIZERO")), context),
                        "remainder by zero"),
                () -> assertRejected(evaluate(binary("/", identifier("V"), intLit(0)), context),
                        "division by zero"),
                () -> assertRejected(evaluate(binary("/", identifier("VI"), intLit(0)), context),
                        "division by zero"),
                // int32 component overflow rejects rather than wrapping — addition and the
                // MIN/-1 edge both fail closed.
                () -> assertRejected(
                        evaluate(binary("+", identifier("VIMAX"),
                                call(identifier("Vector2i"), intLit(1), intLit(0))), context),
                        "int32"),
                () -> assertRejected(
                        evaluate(binary("/", identifier("VIMIN"),
                                call(identifier("Vector2i"), unary("-", intLit(1)), intLit(1))), context),
                        "int32"),
                () -> assertRejected(
                        evaluate(binary("*", identifier("VIMIN"), unary("-", intLit(1))), context), "int32"),
                () -> assertRejected(evaluate(unary("-", identifier("VIMIN")), context), "int32"),
                // An unrestricted int64 scalar can wrap the int32 leaf product back inside
                // the component window (MAX*2 wraps to -2) — the multiply is checked, not
                // left for the post-hoc range test to miss.
                () -> assertRejected(
                        evaluate(binary("*",
                                call(identifier("Vector2i"), intLit(2), intLit(0)),
                                lit("integer", "9223372036854775807")), context),
                        "int32"),
                () -> assertRejected(
                        evaluate(binary("*",
                                lit("integer", "9223372036854775807"),
                                call(identifier("Vector2i"), intLit(2), intLit(0))), context),
                        "int32"),
                // The negative extreme wraps too: MIN*2 wraps to 0.
                () -> assertRejected(
                        evaluate(binary("*",
                                call(identifier("Vector2i"), intLit(2), intLit(0)),
                                unary("-", lit("integer", "9223372036854775808"))), context),
                        "int32")
        );
    }

    @Test
    void builtinEqualityUsesIeeeComponentSemantics() {
        var context = context(
                Map.of(
                        "V", constOf(GdConstantValue.BuiltinValue.ofFloats(GdFloatVectorType.VECTOR2, 1, -2)),
                        "NEGZERO", constOf(GdConstantValue.BuiltinValue.ofFloats(
                                GdFloatVectorType.VECTOR2, 1, -0.0)),
                        "POSZERO", constOf(GdConstantValue.BuiltinValue.ofFloats(
                                GdFloatVectorType.VECTOR2, 1, 0.0)),
                        "NANV", constOf(GdConstantValue.BuiltinValue.ofFloats(
                                GdFloatVectorType.VECTOR2, Double.NaN, 0)),
                        "VI", constOf(GdConstantValue.BuiltinValue.ofInts(GdIntVectorType.VECTOR2I, 1, -2)),
                        "B", constOf(GdConstantValue.BuiltinValue.ofFloats(GdBasisType.BASIS,
                                1, 0, 0, 0, 1, 0, 0, 0, 1)),
                        "Vector2", typeName(GdFloatVectorType.VECTOR2)),
                Map.of(),
                FrontendConstCallableRegistry.empty(), null);
        assertAll(
                () -> assertResolvedBool(true, evaluate(binary("==", identifier("V"), v2(1, -2)), context)),
                () -> assertResolvedBool(false, evaluate(binary("==", identifier("V"), v2(1, 2)), context)),
                () -> assertResolvedBool(true, evaluate(binary("!=", identifier("V"), v2(1, 2)), context)),
                () -> assertResolvedBool(true, evaluate(binary("==", identifier("VI"), identifier("VI")), context)),
                // IEEE -0.0 == +0.0 at the component level — `Double.equals` would reject.
                () -> assertResolvedBool(true, evaluate(binary("==", identifier("NEGZERO"), identifier("POSZERO")), context)),
                () -> assertResolvedBool(false, evaluate(binary("!=", identifier("NEGZERO"), identifier("POSZERO")), context)),
                // NaN never equals itself — record-style `Double.equals` would say it does.
                () -> assertResolvedBool(false, evaluate(binary("==", identifier("NANV"), identifier("NANV")), context)),
                () -> assertResolvedBool(true, evaluate(binary("!=", identifier("NANV"), identifier("NANV")), context)),
                // All controlled builtin types admit same-type equality, including Basis.
                () -> assertResolvedBool(true, evaluate(binary("==", identifier("B"), identifier("B")), context)),
                // Cross-type builtin equality stays rejected — Vector2i != Vector2.
                () -> assertRejected(evaluate(binary("==", identifier("VI"), identifier("V")), context), "not supported"),
                () -> assertRejected(evaluate(binary("==", identifier("V"), intLit(1)), context), "not supported")
        );
    }

    // ------------------------------------------------------------------
    // §5.4 member projections and builtin metadata constants
    // ------------------------------------------------------------------

    @Test
    void memberProjectionsExposeDocumentedMembers() {
        var context = context(
                Map.ofEntries(
                        Map.entry("V3", constOf(GdConstantValue.BuiltinValue.ofFloats(GdFloatVectorType.VECTOR3, 1, 2, 3))),
                        Map.entry("V4I", constOf(GdConstantValue.BuiltinValue.ofInts(GdIntVectorType.VECTOR4I, 1, 2, 3, 4))),
                        Map.entry("R", constOf(GdConstantValue.BuiltinValue.ofFloats(GdRect2Type.RECT2, 1, 2, 3, 4))),
                        Map.entry("RI", constOf(GdConstantValue.BuiltinValue.ofInts(GdRect2iType.RECT2I, 1, 2, 3, 4))),
                        Map.entry("A", constOf(GdConstantValue.BuiltinValue.ofFloats(GdAABBType.AABB, 1, 2, 3, 4, 5, 6))),
                        Map.entry("P", constOf(GdConstantValue.BuiltinValue.ofFloats(GdPlaneType.PLANE, 1, 2, 3, 4))),
                        Map.entry("Q", constOf(GdConstantValue.BuiltinValue.ofFloats(GdQuaternionType.QUATERNION, 1, 2, 3, 4))),
                        Map.entry("B", constOf(GdConstantValue.BuiltinValue.ofFloats(GdBasisType.BASIS,
                                1, 2, 3, 4, 5, 6, 7, 8, 10))),
                        Map.entry("T2", constOf(GdConstantValue.BuiltinValue.ofFloats(GdTransform2DType.TRANSFORM2D,
                                1, 2, 3, 4, 5, 6))),
                        Map.entry("T3", constOf(GdConstantValue.BuiltinValue.ofFloats(GdTransform3DType.TRANSFORM3D,
                                1, 0, 0, 0, 1, 0, 0, 0, 1, 7, 8, 9))),
                        Map.entry("PR", constOf(GdConstantValue.BuiltinValue.ofFloats(GdProjectionType.PROJECTION,
                                1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16))),
                        Map.entry("C", constOf(GdConstantValue.BuiltinValue.ofFloats(GdColorType.COLOR, 0.1, 0.2, 0.3, 0.4)))),
                Map.of(),
                FrontendConstCallableRegistry.empty(), null);
        assertAll(
                () -> assertResolvedFloat(1, evaluate(attrProperty(identifier("V3"), "x"), context)),
                () -> assertResolvedFloat(2, evaluate(attrProperty(identifier("V3"), "y"), context)),
                () -> assertResolvedFloat(3, evaluate(attrProperty(identifier("V3"), "z"), context)),
                () -> assertResolvedInt(4, evaluate(attrProperty(identifier("V4I"), "w"), context)),
                // Rect2/Rect2i members — stored slices plus the computed `end` (pos+size).
                () -> assertResolvedFloatLeaves(GdFloatVectorType.VECTOR2, new double[]{1, 2},
                        evaluate(attrProperty(identifier("R"), "position"), context)),
                () -> assertResolvedFloatLeaves(GdFloatVectorType.VECTOR2, new double[]{3, 4},
                        evaluate(attrProperty(identifier("R"), "size"), context)),
                () -> assertResolvedFloatLeaves(GdFloatVectorType.VECTOR2, new double[]{4, 6},
                        evaluate(attrProperty(identifier("R"), "end"), context)),
                () -> assertResolvedIntLeaves(GdIntVectorType.VECTOR2I, new long[]{4, 6},
                        evaluate(attrProperty(identifier("RI"), "end"), context)),
                () -> assertResolvedFloatLeaves(GdFloatVectorType.VECTOR3, new double[]{5, 7, 9},
                        evaluate(attrProperty(identifier("A"), "end"), context)),
                () -> assertResolvedFloatLeaves(GdFloatVectorType.VECTOR3, new double[]{1, 2, 3},
                        evaluate(attrProperty(identifier("A"), "position"), context)),
                // Plane exposes normal (Vector3), axis leaves, and d.
                () -> assertResolvedFloatLeaves(GdFloatVectorType.VECTOR3, new double[]{1, 2, 3},
                        evaluate(attrProperty(identifier("P"), "normal"), context)),
                () -> assertResolvedFloat(4, evaluate(attrProperty(identifier("P"), "d"), context)),
                () -> assertResolvedFloat(2, evaluate(attrProperty(identifier("P"), "y"), context)),
                () -> assertResolvedFloat(4, evaluate(attrProperty(identifier("Q"), "w"), context)),
                // Basis axis columns slice the canonical 9-leaf layout.
                () -> assertResolvedFloatLeaves(GdFloatVectorType.VECTOR3, new double[]{1, 2, 3},
                        evaluate(attrProperty(identifier("B"), "x"), context)),
                () -> assertResolvedFloatLeaves(GdFloatVectorType.VECTOR3, new double[]{4, 5, 6},
                        evaluate(attrProperty(identifier("B"), "y"), context)),
                () -> assertResolvedFloatLeaves(GdFloatVectorType.VECTOR3, new double[]{7, 8, 10},
                        evaluate(attrProperty(identifier("B"), "z"), context)),
                // Transform2D column slices and origin.
                () -> assertResolvedFloatLeaves(GdFloatVectorType.VECTOR2, new double[]{5, 6},
                        evaluate(attrProperty(identifier("T2"), "origin"), context)),
                () -> assertResolvedFloatLeaves(GdFloatVectorType.VECTOR2, new double[]{1, 2},
                        evaluate(attrProperty(identifier("T2"), "x"), context)),
                // Transform3D exposes the nested Basis and the origin.
                () -> assertResolvedFloatLeaves(GdBasisType.BASIS,
                        new double[]{1, 0, 0, 0, 1, 0, 0, 0, 1},
                        evaluate(attrProperty(identifier("T3"), "basis"), context)),
                () -> assertResolvedFloatLeaves(GdFloatVectorType.VECTOR3, new double[]{7, 8, 9},
                        evaluate(attrProperty(identifier("T3"), "origin"), context)),
                // Projection column slices.
                () -> assertResolvedFloatLeaves(GdFloatVectorType.VECTOR4, new double[]{9, 10, 11, 12},
                        evaluate(attrProperty(identifier("PR"), "z"), context)),
                // Color channels.
                () -> assertResolvedFloat(0.1f, evaluate(attrProperty(identifier("C"), "r"), context)),
                () -> assertResolvedFloat(0.4f, evaluate(attrProperty(identifier("C"), "a"), context))
        );
    }

    @Test
    void unsupportedMembersAndScalarReceiversReject() {
        var context = context(
                Map.of(
                        "V", constOf(GdConstantValue.BuiltinValue.ofFloats(GdFloatVectorType.VECTOR2, 1, 2)),
                        "C", constOf(GdConstantValue.BuiltinValue.ofFloats(GdColorType.COLOR, 1, 0, 0, 1)),
                        "N", constOf(new GdConstantValue.IntValue(5)),
                        "S", constOf(new GdConstantValue.StringValue("abc")),
                        "RI", constOf(GdConstantValue.BuiltinValue.ofInts(
                                GdRect2iType.RECT2I, Integer.MAX_VALUE, 0, 1, 1))),
                Map.of(),
                FrontendConstCallableRegistry.empty(), null);
        assertAll(
                // Whitelisted types reject non-whitelisted members.
                () -> assertRejected(evaluate(attrProperty(identifier("V"), "width"), context),
                        "not a supported constant member"),
                // Color conversions (h/s/v/r8/...) are explicitly outside v1.
                () -> assertRejected(evaluate(attrProperty(identifier("C"), "h"), context),
                        "not a supported constant member"),
                () -> assertRejected(evaluate(attrProperty(identifier("C"), "r8"), context),
                        "not a supported constant member"),
                // Scalar receivers have no projections at all — the same "not a supported
                // member" rejection the check pass phrases uniformly.
                () -> assertRejected(evaluate(attrProperty(identifier("N"), "x"), context),
                        "not a supported constant member"),
                () -> assertRejected(evaluate(attrProperty(identifier("S"), "x"), context),
                        "not a supported constant member"),
                // Rect2i.end overflows the int32 component range -> fail closed.
                () -> assertRejected(evaluate(attrProperty(identifier("RI"), "end"), context),
                        "int32")
        );
    }

    @Test
    void builtinMetadataConstantsDecodeThroughNamespaces() {
        var context = typeContext(
                GdFloatVectorType.VECTOR2, GdFloatVectorType.VECTOR3, GdIntVectorType.VECTOR2I,
                GdIntVectorType.VECTOR3I, GdPlaneType.PLANE, GdQuaternionType.QUATERNION,
                GdBasisType.BASIS, GdTransform2DType.TRANSFORM2D, GdTransform3DType.TRANSFORM3D,
                GdProjectionType.PROJECTION, GdColorType.COLOR);
        assertAll(
                () -> assertResolvedFloatLeaves(GdFloatVectorType.VECTOR2, new double[]{0, 0},
                        evaluate(attrProperty(identifier("Vector2"), "ZERO"), context)),
                () -> assertResolvedFloatLeaves(GdFloatVectorType.VECTOR2, new double[]{1, 1},
                        evaluate(attrProperty(identifier("Vector2"), "ONE"), context)),
                () -> assertResolvedFloatLeaves(GdFloatVectorType.VECTOR2,
                        new double[]{Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY},
                        evaluate(attrProperty(identifier("Vector2"), "INF"), context)),
                () -> assertResolvedFloatLeaves(GdFloatVectorType.VECTOR3, new double[]{0, 1, 0},
                        evaluate(attrProperty(identifier("Vector3"), "UP"), context)),
                () -> assertResolvedIntLeaves(GdIntVectorType.VECTOR2I, new long[]{1, 1},
                        evaluate(attrProperty(identifier("Vector2i"), "ONE"), context)),
                () -> assertResolvedIntLeaves(GdIntVectorType.VECTOR3I,
                        new long[]{Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE},
                        evaluate(attrProperty(identifier("Vector3i"), "MAX"), context)),
                () -> assertResolvedFloatLeaves(GdPlaneType.PLANE, new double[]{1, 0, 0, 0},
                        evaluate(attrProperty(identifier("Plane"), "PLANE_YZ"), context)),
                () -> assertResolvedFloatLeaves(GdQuaternionType.QUATERNION, new double[]{0, 0, 0, 1},
                        evaluate(attrProperty(identifier("Quaternion"), "IDENTITY"), context)),
                // Basis.FLIP_X stores -1 on the x column — row-major flat text must still land
                // on the canonical column layout.
                () -> assertResolvedFloatLeaves(GdBasisType.BASIS,
                        new double[]{-1, 0, 0, 0, 1, 0, 0, 0, 1},
                        evaluate(attrProperty(identifier("Basis"), "FLIP_X"), context)),
                () -> assertResolvedFloatLeaves(GdTransform3DType.TRANSFORM3D,
                        new double[]{-1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0},
                        evaluate(attrProperty(identifier("Transform3D"), "FLIP_X"), context)),
                () -> assertResolvedFloatLeaves(GdProjectionType.PROJECTION,
                        new double[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1},
                        evaluate(attrProperty(identifier("Projection"), "IDENTITY"), context)),
                () -> assertResolvedFloatLeaves(GdColorType.COLOR, new double[]{1, 0, 0, 1},
                        evaluate(attrProperty(identifier("Color"), "RED"), context)),
                // Builtin-class enum members (AXIS_X and friends) live outside `constants` —
                // they stay unknown, not mis-decoded.
                () -> assertRejected(evaluate(attrProperty(identifier("Vector2"), "AXIS_X"), context),
                        "unknown member"),
                () -> assertRejected(evaluate(attrProperty(identifier("Vector2"), "MISSING"), context),
                        "unknown member")
        );
    }

    @Test
    void projectionsComposeWithConstructorsAndOperators() {
        var context = typeContext(
                GdFloatVectorType.VECTOR2, GdFloatVectorType.VECTOR3, GdRect2Type.RECT2,
                GdQuaternionType.QUATERNION, GdTransform3DType.TRANSFORM3D, GdBasisType.BASIS);
        assertAll(
                // Constructor results feed the projection chain directly.
                () -> assertResolvedFloat(2, evaluate(
                        attrProperty(call(identifier("Vector2"), intLit(1), intLit(2)), "y"), context)),
                () -> assertResolvedFloatLeaves(GdFloatVectorType.VECTOR2, new double[]{4, 6},
                        evaluate(attrProperty(
                                call(identifier("Rect2"), v2(1, 2), v2(3, 4)), "end"), context)),
                () -> assertResolvedFloatLeaves(GdFloatVectorType.VECTOR3, new double[]{1, 2, 3},
                        evaluate(attrProperty(attrProperty(
                                call(identifier("Transform3D"),
                                        call(identifier("Basis"), v3(1, 2, 3), v3(4, 5, 6), v3(7, 8, 10)),
                                        v3(0, 0, 0)),
                                "basis"), "x"), context)),
                // Projected leaves join ordinary arithmetic — a float leaf pairs with a
                // float literal; an int literal would hit the mixed-scalar rejection.
                () -> assertResolvedFloat(4, evaluate(
                        binary("+", attrProperty(call(identifier("Vector2"), intLit(1), intLit(2)), "y"),
                                floatLit("2.0")), context)),
                () -> assertResolvedFloatLeaves(GdFloatVectorType.VECTOR2, new double[]{2, 4},
                        evaluate(binary("+",
                                call(identifier("Vector2"), intLit(1), intLit(2)),
                                call(identifier("Vector2"), intLit(1), intLit(2))), context)),
                () -> assertResolvedFloatLeaves(GdFloatVectorType.VECTOR2, new double[]{1, 1},
                        evaluate(binary("+", attrProperty(identifier("Vector2"), "ONE"),
                                attrProperty(identifier("Vector2"), "ZERO")), context))
        );
    }

    @Test
    void geometryCapabilityGatesAreHonored() {
        var noCtor = new EvaluationPolicy.CapabilitySet(
                Set.of(
                        EvaluationPolicy.Capability.INT_ARITHMETIC,
                        EvaluationPolicy.Capability.FLOAT_ARITHMETIC,
                        EvaluationPolicy.Capability.BOOL_OPERATORS,
                        EvaluationPolicy.Capability.STRING_OPERATORS,
                        EvaluationPolicy.Capability.COMPARISON,
                        EvaluationPolicy.Capability.TERNARY,
                        EvaluationPolicy.Capability.ATTRIBUTE_ACCESS,
                        EvaluationPolicy.Capability.BUILTIN_OPERATORS,
                        EvaluationPolicy.Capability.MEMBER_PROJECTION,
                        EvaluationPolicy.Capability.REGISTERED_MEMBER_CALL
                ),
                EvaluationPolicy.EvaluationDomain.GENERAL
        );
        var noBuiltinOps = new EvaluationPolicy.CapabilitySet(
                Set.of(
                        EvaluationPolicy.Capability.INT_ARITHMETIC,
                        EvaluationPolicy.Capability.FLOAT_ARITHMETIC,
                        EvaluationPolicy.Capability.BOOL_OPERATORS,
                        EvaluationPolicy.Capability.STRING_OPERATORS,
                        EvaluationPolicy.Capability.COMPARISON,
                        EvaluationPolicy.Capability.TERNARY,
                        EvaluationPolicy.Capability.ATTRIBUTE_ACCESS,
                        EvaluationPolicy.Capability.CONSTRUCTOR,
                        EvaluationPolicy.Capability.MEMBER_PROJECTION,
                        EvaluationPolicy.Capability.REGISTERED_MEMBER_CALL
                ),
                EvaluationPolicy.EvaluationDomain.GENERAL
        );
        var noProjection = new EvaluationPolicy.CapabilitySet(
                Set.of(
                        EvaluationPolicy.Capability.INT_ARITHMETIC,
                        EvaluationPolicy.Capability.FLOAT_ARITHMETIC,
                        EvaluationPolicy.Capability.BOOL_OPERATORS,
                        EvaluationPolicy.Capability.STRING_OPERATORS,
                        EvaluationPolicy.Capability.COMPARISON,
                        EvaluationPolicy.Capability.TERNARY,
                        EvaluationPolicy.Capability.ATTRIBUTE_ACCESS,
                        EvaluationPolicy.Capability.CONSTRUCTOR,
                        EvaluationPolicy.Capability.BUILTIN_OPERATORS,
                        EvaluationPolicy.Capability.REGISTERED_MEMBER_CALL
                ),
                EvaluationPolicy.EvaluationDomain.GENERAL
        );
        var names = context(
                Map.of(
                        "Vector2", typeName(GdFloatVectorType.VECTOR2),
                        "V", constOf(GdConstantValue.BuiltinValue.ofFloats(GdFloatVectorType.VECTOR2, 1, 2))),
                Map.of(),
                FrontendConstCallableRegistry.empty(), null);
        assertAll(
                // Without CONSTRUCTOR the whole call shape rejects — same wording enum uses.
                () -> assertRejected(
                        evaluate(call(identifier("Vector2"), intLit(1), intLit(2)), names, noCtor),
                        "not a supported constant expression"),
                // Without BUILTIN_OPERATORS, operator admission rejects with the capability phrase.
                () -> assertRejected(
                        evaluate(binary("+", identifier("V"), identifier("V")), names, noBuiltinOps),
                        "binary operator '+' is not supported in this constant evaluation"),
                () -> assertRejected(
                        evaluate(unary("-", identifier("V")), names, noBuiltinOps),
                        "unary operator '-' is not supported in this constant evaluation"),
                // Without MEMBER_PROJECTION the projection phrase names the member.
                () -> assertRejected(
                        evaluate(attrProperty(identifier("V"), "x"), names, noProjection),
                        "member projection 'x' is not supported in this constant evaluation")
        );
    }

    @Test
    void deadBranchesStillValidateGeometryShapes() {
        var context = typeContext(GdFloatVectorType.VECTOR2, GdQuaternionType.QUATERNION);
        assertAll(
                // Whole-tree validation: a non-whitelisted constructor in the dead arm rejects.
                () -> assertRejected(
                        evaluate(ternary(lit("true", "true"), intLit(1),
                                call(identifier("Quaternion"), attrProperty(identifier("Vector2"), "MISSING"),
                                        floatLit("0.5"))), context),
                        "unknown member"),
                () -> assertRejected(
                        evaluate(ternary(lit("true", "true"), intLit(1),
                                call(identifier("Vector2"), identifier("Missing"), intLit(2))), context),
                        "unknown identifier"),
                // The taken arm evaluates alone — the dead arm's zero division never runs.
                () -> assertResolvedInt(1, evaluate(
                        ternary(lit("true", "true"), intLit(1),
                                binary("/", intLit(1), intLit(0))), context))
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

    /// A builtin-type namespace token: `resolve("Vector2")` returning this lets
    /// `Vector2(1, 2)` construct and `Vector2.ZERO` decode through the real metadata.
    private static @NotNull LookupContext.LookupResult typeName(@NotNull GdType type) {
        return new LookupContext.LookupResult.ResolvedNamespace(type);
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
            // The map wins for test-driven members; builtin type namespaces (`GdType`
            // identities) fall through to the default decoder seam — `Vector2.ZERO` and
            // friends resolve through the real metadata, unregistered members stay unknown.
            return members.getOrDefault(
                    namespaceIdentity + "." + memberName,
                    LookupContext.super.resolveMember(namespaceIdentity, memberName));
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

    private static @NotNull CallExpression call(
            @NotNull Expression callee,
            @NotNull Expression... arguments
    ) {
        return new CallExpression(callee, List.of(arguments), TINY);
    }

    /// Short vector-constructor expressions — int components arrive as int literals and the
    /// ordinary `int -> float` parameter normalization converts them.
    private static @NotNull CallExpression v2(double x, double y) {
        return call(identifier("Vector2"), scalarLeaf(x), scalarLeaf(y));
    }

    private static @NotNull CallExpression v3(double x, double y, double z) {
        return call(identifier("Vector3"), scalarLeaf(x), scalarLeaf(y), scalarLeaf(z));
    }

    private static @NotNull CallExpression v4(double x, double y, double z, double w) {
        return call(identifier("Vector4"), scalarLeaf(x), scalarLeaf(y), scalarLeaf(z), scalarLeaf(w));
    }

    private static @NotNull Expression scalarLeaf(double value) {
        // Literal lexemes never carry a sign — negatives go through the unary operator.
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return value < 0 ? unary("-", intLit((long) -value)) : intLit((long) value);
        }
        return value < 0
                ? unary("-", floatLit(Double.toString(-value)))
                : floatLit(Double.toString(value));
    }

    /// `resolve` answers each type's source name with its builtin namespace token.
    private static @NotNull Map<String, LookupContext.LookupResult> typeNames(@NotNull GdType... types) {
        var names = new HashMap<String, LookupContext.LookupResult>(types.length);
        for (var type : types) {
            names.put(type.getTypeName(), typeName(type));
        }
        return Map.copyOf(names);
    }

    private static @NotNull LookupContext typeContext(@NotNull GdType... types) {
        return context(typeNames(types), Map.of(), FrontendConstCallableRegistry.empty(), null);
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

    /// Float-leaf comparison uses the float32 narrowing rule plus raw-bit equality: `NaN`
    /// checks via `isNaN` and every other leaf compares `doubleToRawLongBits`, so `-0.0` and
    /// `+0.0` stay distinguishable instead of collapsing under tolerance.
    private static void assertResolvedFloatLeaves(
            @NotNull GdType type,
            double @NotNull [] expected,
            @NotNull Evaluation evaluation
    ) {
        var resolved = assertInstanceOf(Evaluation.Resolved.class, evaluation);
        var value = assertInstanceOf(GdConstantValue.BuiltinValue.class, resolved.value());
        assertEquals(type, value.type());
        assertEquals(expected.length, value.components().size());
        for (var index = 0; index < expected.length; index++) {
            var leaf = assertInstanceOf(
                    GdConstantValue.FloatValue.class, value.components().get(index),
                    "component " + index + " of '" + type.getTypeName() + "'");
            if (Double.isNaN(expected[index])) {
                assertTrue(Double.isNaN(leaf.value()), "component " + index + " should be NaN");
            } else {
                assertEquals(
                        Double.doubleToRawLongBits((double) (float) expected[index]),
                        Double.doubleToRawLongBits(leaf.value()),
                        "component " + index + " of '" + type.getTypeName() + "'");
            }
        }
    }

    private static void assertResolvedIntLeaves(
            @NotNull GdType type,
            long @NotNull [] expected,
            @NotNull Evaluation evaluation
    ) {
        var resolved = assertInstanceOf(Evaluation.Resolved.class, evaluation);
        var value = assertInstanceOf(GdConstantValue.BuiltinValue.class, resolved.value());
        assertEquals(type, value.type());
        assertEquals(expected.length, value.components().size());
        for (var index = 0; index < expected.length; index++) {
            var leaf = assertInstanceOf(
                    GdConstantValue.IntValue.class, value.components().get(index),
                    "component " + index + " of '" + type.getTypeName() + "'");
            assertEquals(expected[index], leaf.value(),
                    "component " + index + " of '" + type.getTypeName() + "'");
        }
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
