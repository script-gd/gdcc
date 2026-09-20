package gd.script.gdcc.frontend.sema.analyzer.support;

import dev.superice.gdparser.frontend.ast.AttributeExpression;
import dev.superice.gdparser.frontend.ast.AttributePropertyStep;
import dev.superice.gdparser.frontend.ast.BinaryExpression;
import dev.superice.gdparser.frontend.ast.CallExpression;
import dev.superice.gdparser.frontend.ast.ConditionalExpression;
import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.LiteralExpression;
import dev.superice.gdparser.frontend.ast.Point;
import dev.superice.gdparser.frontend.ast.Range;
import dev.superice.gdparser.frontend.ast.UnaryExpression;
import gd.script.gdcc.gdextension.ExtensionAPI;
import gd.script.gdcc.gdextension.ExtensionEnumValue;
import gd.script.gdcc.gdextension.ExtensionGlobalConstant;
import gd.script.gdcc.gdextension.ExtensionGlobalEnum;
import gd.script.gdcc.gdextension.ExtensionHeader;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.GdScriptClassConstant;
import gd.script.gdcc.scope.GdScriptEnumConstant;
import gd.script.gdcc.scope.GdScriptEnumGroup;
import gd.script.gdcc.type.GdDictionaryType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdVariantType;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Unit contracts for the restricted enum constant-expression evaluator: every accepted shape must
/// reduce to a concrete int, every rejected shape must carry a human-readable reason and must never
/// throw.
class FrontendEnumConstantEvaluatorTest {
    private static final @NotNull Range TINY = new Range(0, 1, new Point(0, 0), new Point(0, 1));

    @Test
    void evaluatesIntegerLiteralsAcrossRadixesAndSeparators() {
        assertAll(
                () -> assertResolved(42, evaluate(integerLiteral("42"))),
                () -> assertResolved(0xF0, evaluate(integerLiteral("0xF0"))),
                () -> assertResolved(0b101, evaluate(integerLiteral("0b101"))),
                () -> assertResolved(15, evaluate(integerLiteral("0o17"))),
                () -> assertResolved(1_000_000, evaluate(integerLiteral("1_000_000")))
        );
    }

    @Test
    void evaluatesUnaryAndBinaryIntOperators() {
        assertAll(
                () -> assertResolved(-1, evaluate(unary("-", integerLiteral("1")))),
                () -> assertResolved(1, evaluate(unary("+", integerLiteral("1")))),
                () -> assertResolved(~0, evaluate(unary("~", integerLiteral("0")))),
                () -> assertResolved(7, evaluate(binary("+", integerLiteral("3"), integerLiteral("4")))),
                () -> assertResolved(-1, evaluate(binary("-", integerLiteral("3"), integerLiteral("4")))),
                () -> assertResolved(12, evaluate(binary("*", integerLiteral("3"), integerLiteral("4")))),
                // Integer division truncates toward zero, matching Godot int64 semantics.
                () -> assertResolved(3, evaluate(binary("/", integerLiteral("7"), integerLiteral("2")))),
                () -> assertResolved(-3, evaluate(binary("/", unary("-", integerLiteral("7")), integerLiteral("2")))),
                () -> assertResolved(1, evaluate(binary("%", integerLiteral("7"), integerLiteral("3")))),
                () -> assertResolved(8, evaluate(binary("<<", integerLiteral("1"), integerLiteral("3")))),
                () -> assertResolved(2, evaluate(binary(">>", integerLiteral("16"), integerLiteral("3")))),
                () -> assertResolved(0b1010, evaluate(binary("&", integerLiteral("0b1110"), integerLiteral("0b1011")))),
                () -> assertResolved(0b1111, evaluate(binary("|", integerLiteral("0b1100"), integerLiteral("0b0111")))),
                () -> assertResolved(0b0110, evaluate(binary("^", integerLiteral("0b1100"), integerLiteral("0b1010")))),
                // Nested composite: (1 << 3) | -1
                () -> assertResolved(-1, evaluate(binary(
                        "|",
                        binary("<<", integerLiteral("1"), integerLiteral("3")),
                        unary("-", integerLiteral("1"))
                )))
        );
    }

    @Test
    void rejectsDivisionAndRemainderByZero() {
        assertAll(
                () -> assertRejected(
                        evaluate(binary("/", integerLiteral("1"), integerLiteral("0"))),
                        "division by zero"
                ),
                () -> assertRejected(
                        evaluate(binary("%", integerLiteral("1"), integerLiteral("0"))),
                        "remainder by zero"
                )
        );
    }

    @Test
    void rejectsOutOfRangeShiftCountsInsteadOfMasking() {
        // Java would silently mask the shift distance to its low 6 bits; the evaluator stays
        // fail-closed so a published constant never diverges from the runtime shift guards.
        assertAll(
                () -> assertRejected(
                        evaluate(binary("<<", integerLiteral("1"), unary("-", integerLiteral("1")))),
                        "shift count -1"
                ),
                () -> assertRejected(
                        evaluate(binary(">>", integerLiteral("8"), unary("-", integerLiteral("1")))),
                        "shift count -1"
                ),
                () -> assertRejected(
                        evaluate(binary("<<", integerLiteral("1"), integerLiteral("64"))),
                        "shift count 64"
                ),
                // The largest legal distance stays accepted.
                () -> assertResolved(Long.MIN_VALUE, evaluate(binary("<<", integerLiteral("1"), integerLiteral("63"))))
        );
    }

    @Test
    void rejectsNonIntLiteralsAndMalformedLexemes() {
        assertAll(
                () -> assertRejected(evaluate(new LiteralExpression("float", "1.5", TINY)), "not an int constant"),
                () -> assertRejected(evaluate(new LiteralExpression("string", "\"s\"", TINY)), "not an int constant"),
                () -> assertRejected(evaluate(new LiteralExpression("true", "true", TINY)), "not an int constant"),
                () -> assertRejected(evaluate(integerLiteral("0xGG")), "malformed"),
                () -> assertRejected(evaluate(integerLiteral("99999999999999999999")), "overflows")
        );
    }

    @Test
    void rejectsUnsupportedOperatorsAndExpressionShapes() {
        assertAll(
                () -> assertRejected(
                        evaluate(binary("**", integerLiteral("2"), integerLiteral("3"))),
                        "not supported"
                ),
                () -> assertRejected(
                        evaluate(binary("==", integerLiteral("2"), integerLiteral("2"))),
                        "not supported"
                ),
                () -> assertRejected(
                        evaluate(binary("and", integerLiteral("1"), integerLiteral("1"))),
                        "not supported"
                ),
                () -> assertRejected(
                        evaluate(unary("not", integerLiteral("1"))),
                        "not supported"
                ),
                () -> assertRejected(
                        evaluate(new CallExpression(new IdentifierExpression("f", TINY), List.of(), TINY)),
                        "not a supported int constant expression"
                ),
                () -> assertRejected(
                        evaluate(new AttributeExpression(
                                new IdentifierExpression("State", TINY),
                                List.of(new AttributePropertyStep("IDLE", TINY)),
                                TINY
                        )),
                        "not a supported int constant expression"
                ),
                () -> assertRejected(
                        evaluate(new ConditionalExpression(
                                new IdentifierExpression("c", TINY),
                                integerLiteral("1"),
                                integerLiteral("2"),
                                TINY
                        )),
                        "not a supported int constant expression"
                )
        );
    }

    @Test
    void resolvesIdentifiersFromEarlierSameEnumMembers() {
        var earlierMembers = Map.of("A", 1L, "B", 2L);

        var evaluation = evaluate(identifier("B"), earlierMembers, List.of(), Set.of());

        assertResolved(2, evaluation);
    }

    @Test
    void resolvesIdentifiersFromEarlierSameClassEnumConstants() {
        var classConstants = List.of(
                new GdScriptClassConstant(
                        "BASE",
                        GdIntType.INT,
                        new GdScriptEnumConstant("BASE", 41, null, "Owner")
                )
        );

        var evaluation = evaluate(identifier("BASE"), Map.of(), classConstants, Set.of());

        assertResolved(41, evaluation);
    }

    @Test
    void sameEnumMemberShadowsSameClassConstantAndGlobalValue() {
        var earlierMembers = Map.of("A", 5L);
        var classConstants = List.of(
                new GdScriptClassConstant(
                        "A",
                        GdIntType.INT,
                        new GdScriptEnumConstant("A", 99, null, "Owner")
                )
        );

        var evaluation = evaluate(identifier("A"), earlierMembers, classConstants, Set.of());

        assertResolved(5, evaluation);
    }

    @Test
    void rejectsNamedEnumGroupReferenceAsNonIntConstant() {
        var classConstants = List.of(
                new GdScriptClassConstant(
                        "State",
                        new GdDictionaryType(GdVariantType.VARIANT, GdVariantType.VARIANT),
                        new GdScriptEnumGroup("State", List.of(
                                new GdScriptEnumConstant("IDLE", 0, "State", "Owner")
                        ), "Owner")
                )
        );

        var evaluation = evaluate(identifier("State"), Map.of(), classConstants, Set.of());

        assertRejected(evaluation, "not an int constant");
    }

    @Test
    void resolvesIdentifiersFromGlobalEnumValuesAndGlobalConstants() {
        var registry = new ClassRegistry(createEnumFixtureApi());

        assertAll(
                () -> assertResolved(
                        1,
                        evaluate(identifier("READY"), Map.of(), List.of(), Set.of(), registry)
                ),
                () -> assertResolved(
                        4_294_967_296L,
                        evaluate(identifier("GDCC_TEST_BIG_FLAG"), Map.of(), List.of(), Set.of(), registry)
                )
        );
    }

    @Test
    void ancestorEnumNameBlockerWinsOverGlobalFallback() {
        // Parent declares `enum { READY = 123 }`; a same-named global enum value READY=1 exists.
        // The blocker must reject instead of silently resolving to the global value.
        var registry = new ClassRegistry(createEnumFixtureApi());

        var evaluation = evaluate(identifier("READY"), Map.of(), List.of(), Set.of("READY"), registry);

        assertRejected(evaluation, "inherited enum constant");
    }

    @Test
    void rejectsLanguageConstantsAsNonInt() {
        // GDScript language constants (PI etc.) are compiler-synthesized floats and therefore
        // never valid enum int values, regardless of the loaded extension API.
        var registry = new ClassRegistry(createEnumFixtureApi());

        var evaluation = evaluate(identifier("PI"), Map.of(), List.of(), Set.of(), registry);

        assertRejected(evaluation, "not an int constant");
    }

    @Test
    void rejectsUnknownIdentifiers() {
        var evaluation = evaluate(identifier("MISSING_NAME"), Map.of(), List.of(), Set.of());

        assertRejected(evaluation, "unknown identifier");
    }

    private static @NotNull FrontendEnumConstantEvaluator.Evaluation evaluate(@NotNull Expression expression) {
        return evaluate(expression, Map.of(), List.of(), Set.of());
    }

    private static @NotNull FrontendEnumConstantEvaluator.Evaluation evaluate(
            @NotNull Expression expression,
            @NotNull Map<String, Long> earlierEnumMembers,
            @NotNull List<GdScriptClassConstant> classConstants,
            @NotNull Set<String> ancestorEnumNames
    ) {
        return evaluate(
                expression,
                earlierEnumMembers,
                classConstants,
                ancestorEnumNames,
                new ClassRegistry(createEnumFixtureApi())
        );
    }

    private static @NotNull FrontendEnumConstantEvaluator.Evaluation evaluate(
            @NotNull Expression expression,
            @NotNull Map<String, Long> earlierEnumMembers,
            @NotNull List<GdScriptClassConstant> classConstants,
            @NotNull Set<String> ancestorEnumNames,
            @NotNull ClassRegistry registry
    ) {
        return FrontendEnumConstantEvaluator.evaluate(
                expression,
                earlierEnumMembers,
                classConstants,
                ancestorEnumNames,
                registry
        );
    }

    private static void assertResolved(long expected, FrontendEnumConstantEvaluator.Evaluation evaluation) {
        var resolved = assertInstanceOf(FrontendEnumConstantEvaluator.Evaluation.Resolved.class, evaluation);
        assertEquals(expected, resolved.value());
    }

    private static void assertRejected(
            FrontendEnumConstantEvaluator.Evaluation evaluation,
            String messagePart
    ) {
        var rejected = assertInstanceOf(FrontendEnumConstantEvaluator.Evaluation.Rejected.class, evaluation);
        assertTrue(rejected.reason().contains(messagePart),
                "Expected reason to contain '" + messagePart + "' but was: " + rejected.reason());
    }

    private static @NotNull LiteralExpression integerLiteral(@NotNull String sourceText) {
        return new LiteralExpression("integer", sourceText, TINY);
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

    private static @NotNull ExtensionAPI createEnumFixtureApi() {
        return new ExtensionAPI(
                new ExtensionHeader(4, 4, 0, "stable", "test", "test", "single"),
                List.of(),
                List.of(),
                List.of(new ExtensionGlobalConstant("GDCC_TEST_BIG_FLAG", 4_294_967_296L, true)),
                List.of(new ExtensionGlobalEnum("GameFlags", false, List.of(new ExtensionEnumValue("READY", 1)))),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }
}
