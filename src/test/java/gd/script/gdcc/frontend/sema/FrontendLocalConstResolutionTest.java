package gd.script.gdcc.frontend.sema;

import dev.superice.gdparser.frontend.ast.Block;
import dev.superice.gdparser.frontend.ast.DeclarationKind;
import dev.superice.gdparser.frontend.ast.ForStatement;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.IfStatement;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.LambdaExpression;
import dev.superice.gdparser.frontend.ast.MatchStatement;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.SourceFile;
import dev.superice.gdparser.frontend.ast.Statement;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import gd.script.gdcc.frontend.constant.Evaluation;
import gd.script.gdcc.frontend.constant.GdConstantValue;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.diagnostic.DiagnosticSnapshot;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnostic;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnosticSeverity;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.FrontendSourceUnit;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.frontend.sema.analyzer.FrontendSemanticAnalyzer;
import gd.script.gdcc.frontend.scope.BlockScope;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.ScopeLookupStatus;
import gd.script.gdcc.scope.ScopeValueKind;
import gd.script.gdcc.type.GdFloatType;
import gd.script.gdcc.type.GdIntType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/// Sema-level contract tests for block-local `const` (§7).
///
/// Each case runs the real `FrontendSemanticAnalyzer` pipeline — binding, suite resolution with
/// the `CONST_RESOLUTION` owner, and export — then asserts the frozen `Evaluation` fact, the
/// exported slot type, and the diagnostic contract. Use-site materialization is covered by
/// `FrontendConstLoweringTest`; this class pins the semantic facts those instructions consume.
class FrontendLocalConstResolutionTest {

    // ------------------------------------------------------------------
    // Positive cases
    // ------------------------------------------------------------------

    /// Scalar initializers freeze into `Resolved` facts, and the exported scope binding carries
    /// the exact value type with the read-only `CONSTANT` contract — never a `var` slot.
    @Test
    void scalarLocalConstsResolveAndPublishExactSlotTypes() throws Exception {
        var input = analyze("local_const_scalars.gd", """
                class_name LocalConstScalars
                extends RefCounted

                func get():
                    const I = 42
                    const F: float = 1
                    const B = true
                    const S = "hi"
                    const T := 3
                    return I
                """);
        var get = findFunction(input.unit().ast(), "get");
        var bodyScope = bodyScopeOf(input, get.body());

        var iConst = findLocalConst(get, "I");
        var fConst = findLocalConst(get, "F");
        var bConst = findLocalConst(get, "B");
        var sConst = findLocalConst(get, "S");
        var tConst = findLocalConst(get, "T");
        var iBinding = bodyScope.resolveValueHere("I");
        var fBinding = bodyScope.resolveValueHere("F");
        var tBinding = bodyScope.resolveValueHere("T");
        assertAll(
                () -> assertTrue(input.diagnostics().asList().isEmpty(),
                        () -> "Unexpected diagnostics: " + input.diagnostics()),
                () -> assertEquals(new Evaluation.Resolved(new GdConstantValue.IntValue(42L)),
                        input.constantFact(iConst)),
                // `const F: float = 1` exercises the int -> float declared-type conversion.
                () -> assertEquals(new Evaluation.Resolved(new GdConstantValue.FloatValue(1.0)),
                        input.constantFact(fConst)),
                () -> assertEquals(new Evaluation.Resolved(new GdConstantValue.BoolValue(true)),
                        input.constantFact(bConst)),
                () -> assertEquals(new Evaluation.Resolved(new GdConstantValue.StringValue("hi")),
                        input.constantFact(sConst)),
                () -> assertEquals(new Evaluation.Resolved(new GdConstantValue.IntValue(3L)),
                        input.constantFact(tConst)),
                () -> assertEquals(ScopeValueKind.CONSTANT, iBinding.kind()),
                () -> assertEquals(GdIntType.INT, iBinding.type()),
                () -> assertSame(iConst, iBinding.declaration()),
                () -> assertTrue(iBinding.constant()),
                () -> assertFalse(iBinding.writable()),
                () -> assertEquals(GdFloatType.FLOAT, fBinding.type()),
                () -> assertEquals(GdIntType.INT, tBinding.type()),
                () -> assertTrue(iBinding.constant() && !iBinding.writable())
        );
    }

    /// A later const reads an earlier sibling's frozen fact, not its runtime slot — `A + 2`
    /// folds to 3 while `A` stays a declaration-scoped constant.
    @Test
    void laterConstReadsEarlierConstFact() throws Exception {
        var input = analyze("local_const_chain.gd", """
                class_name LocalConstChain
                extends RefCounted

                func get():
                    const A = 1
                    const B = A + 2
                    return B
                """);
        var get = findFunction(input.unit().ast(), "get");

        var aConst = findLocalConst(get, "A");
        var bConst = findLocalConst(get, "B");
        assertAll(
                () -> assertTrue(input.diagnostics().asList().isEmpty()),
                () -> assertEquals(new Evaluation.Resolved(new GdConstantValue.IntValue(3L)),
                        input.constantFact(bConst)),
                () -> assertEquals(GdIntType.INT,
                        bodyScopeOf(input, get.body()).resolveValueHere("B").type())
        );
    }

    /// A whitelisted builtin constructor freezes component values into a `BuiltinValue` fact.
    @Test
    void builtinConstructorLocalConstFreezesComponents() throws Exception {
        var input = analyze("local_const_vector.gd", """
                class_name LocalConstVector
                extends RefCounted

                func get():
                    const V = Vector2(1.0, 2.0)
                    return V
                """);
        var vConst = findLocalConst(findFunction(input.unit().ast(), "get"), "V");
        var fact = assertInstanceOf(Evaluation.Resolved.class, input.constantFact(vConst));
        var value = assertInstanceOf(GdConstantValue.BuiltinValue.class, fact.value());
        assertAll(
                () -> assertTrue(input.diagnostics().asList().isEmpty()),
                () -> assertEquals("Vector2", value.type().getTypeName()),
                () -> assertEquals(List.of(
                        new GdConstantValue.FloatValue(1.0),
                        new GdConstantValue.FloatValue(2.0)
                ), value.components())
        );
    }

    /// Local const initializers resolve qualified namespaces — class constants, inner-class
    /// constants, and script enum members all descend through the suite lookup context.
    @Test
    void localConstReadsQualifiedConstantChains() throws Exception {
        var input = analyze("local_const_qualified.gd", """
                class_name LocalConstQualified
                extends RefCounted

                const CLASS_C = 5

                class Other:
                    const C = 7

                enum State { IDLE = 3 }

                func get():
                    const A = CLASS_C + 1
                    const B = Other.C
                    const E = State.IDLE
                    return A + B + E
                """);
        var get = findFunction(input.unit().ast(), "get");

        assertAll(
                () -> assertTrue(input.diagnostics().asList().isEmpty()),
                () -> assertEquals(new Evaluation.Resolved(new GdConstantValue.IntValue(6L)),
                        input.constantFact(findLocalConst(get, "A"))),
                () -> assertEquals(new Evaluation.Resolved(new GdConstantValue.IntValue(7L)),
                        input.constantFact(findLocalConst(get, "B"))),
                () -> assertEquals(new Evaluation.Resolved(new GdConstantValue.IntValue(3L)),
                        input.constantFact(findLocalConst(get, "E")))
        );
    }

    /// A const declared inside a nested block reads outer constants and stays invisible outside
    /// its block — the name claims only the inner scope layer.
    @Test
    void nestedBlockConstReadsOuterConstAndHidesItselfOutside() throws Exception {
        var input = analyze("local_const_nested.gd", """
                class_name LocalConstNested
                extends RefCounted

                func get(c: bool):
                    const OUTER = 1
                    if c:
                        const INNER = OUTER + 1
                        return INNER
                    return OUTER
                """);
        var get = findFunction(input.unit().ast(), "get");
        var ifStatement = findNode(get.body(), IfStatement.class, _ -> true);

        var innerConst = findStatement(
                ifStatement.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("INNER")
        );
        assertAll(
                () -> assertTrue(input.diagnostics().asList().isEmpty()),
                () -> assertEquals(new Evaluation.Resolved(new GdConstantValue.IntValue(2L)),
                        input.constantFact(innerConst)),
                // The function body scope must not see the block-scoped name.
                () -> assertNull(bodyScopeOf(input, get.body()).resolveValueHere("INNER")),
                () -> assertNotNull(bodyScopeOf(input, ifStatement.body()).resolveValueHere("INNER"))
        );
    }

    /// Constants inside `for` and `match` bodies resolve like any other suite block.
    @Test
    void constInForAndMatchBodiesResolves() throws Exception {
        var input = analyze("local_const_for_match.gd", """
                class_name LocalConstForMatch
                extends RefCounted

                func get(items: Array[int], choice: int):
                    for item in items:
                        const F = 1
                        var keep = F
                    match choice:
                        1:
                            const M = 2
                            var copy = M
                    return choice
                """);
        var get = findFunction(input.unit().ast(), "get");
        var forStatement = findNode(get.body(), ForStatement.class, _ -> true);
        var matchStatement = findNode(get.body(), MatchStatement.class, _ -> true);

        var fConst = findStatement(
                forStatement.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("F")
        );
        var mConst = findStatement(
                matchStatement.sections().getFirst().body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("M")
        );
        assertAll(
                () -> assertTrue(input.diagnostics().asList().isEmpty(),
                        () -> "Unexpected diagnostics: " + input.diagnostics()),
                () -> assertEquals(new Evaluation.Resolved(new GdConstantValue.IntValue(1L)),
                        input.constantFact(fConst)),
                () -> assertEquals(new Evaluation.Resolved(new GdConstantValue.IntValue(2L)),
                        input.constantFact(mConst))
        );
    }

    /// Constants work inside `static` functions — the read-only binding never touches the
    /// instance restriction.
    @Test
    void staticFunctionLocalConstResolves() throws Exception {
        var input = analyze("local_const_static.gd", """
                class_name LocalConstStatic
                extends RefCounted

                static func limit():
                    const L = 64
                    return L
                """);
        var limit = findFunction(input.unit().ast(), "limit");
        var lConst = findLocalConst(limit, "L");
        assertAll(
                () -> assertTrue(input.diagnostics().asList().isEmpty()),
                () -> assertEquals(new Evaluation.Resolved(new GdConstantValue.IntValue(64L)),
                        input.constantFact(lConst))
        );
    }

    /// A lambda reads an outer constant without capturing it: the frozen value arrives through
    /// the constant-fact view, so the capture plan stays empty and the binding stays CONSTANT.
    @Test
    void lambdaReadsOuterConstWithoutCapturing() throws Exception {
        var input = analyze("local_const_lambda.gd", """
                class_name LocalConstLambda
                extends RefCounted

                func get():
                    const C = 7
                    var f = func():
                        return C
                    return f
                """);
        var get = findFunction(input.unit().ast(), "get");
        var lambda = findNode(get.body(), LambdaExpression.class, _ -> true);
        var useSite = findNode(lambda.body(), IdentifierExpression.class,
                identifier -> identifier.name().equals("C"));

        var plan = input.analysisData().lambdaPlans().get(lambda);
        var binding = input.analysisData().symbolBindings().get(useSite);
        assertAll(
                () -> assertTrue(input.diagnostics().asList().isEmpty(),
                        () -> "Unexpected diagnostics: " + input.diagnostics()),
                () -> assertNotNull(plan),
                () -> assertTrue(plan.captures().stream().noneMatch(capture -> capture.name().equals("C")),
                        "a const must never become a mutable capture"),
                () -> assertFalse(plan.capturesSelf()),
                () -> assertNotNull(binding),
                () -> assertEquals(FrontendBindingKind.CONSTANT, binding.kind()),
                () -> assertEquals(ScopeLookupStatus.FOUND_ALLOWED, binding.valueAccessStatus())
        );
    }

    /// A forward reference to a later same-block const is invisible to the initializer and
    /// falls back to the outer class constant — identical to `var` forward-reference rules.
    @Test
    void forwardConstReferenceFallsBackToOuterClassConst() throws Exception {
        var input = analyze("local_const_forward_fallback.gd", """
                class_name LocalConstForwardFallback
                extends RefCounted

                const C = 9

                func get():
                    const A = C
                    const C = 1
                    return A
                """);
        var get = findFunction(input.unit().ast(), "get");

        assertAll(
                () -> assertTrue(input.diagnostics().asList().isEmpty(),
                        () -> "Unexpected diagnostics: " + input.diagnostics()),
                () -> assertEquals(new Evaluation.Resolved(new GdConstantValue.IntValue(9L)),
                        input.constantFact(findLocalConst(get, "A"))),
                () -> assertEquals(new Evaluation.Resolved(new GdConstantValue.IntValue(1L)),
                        input.constantFact(findLocalConst(get, "C")))
        );
    }

    // ------------------------------------------------------------------
    // Negative cases
    // ------------------------------------------------------------------

    /// A mutable local referenced from a const initializer is a non-constant operand: the fact
    /// is `Rejected`, the owner reports one `sema.constant_resolution` diagnostic, and the
    /// binding still claims the name so later reads see the failed constant.
    @Test
    void constInitializerCannotReadMutableLocals() throws Exception {
        var input = analyze("local_const_mutable_ref.gd", """
                class_name LocalConstMutableRef
                extends RefCounted

                func get():
                    var source = 1
                    const BAD = source + 1
                    return 0
                """);
        var get = findFunction(input.unit().ast(), "get");
        var badConst = findLocalConst(get, "BAD");

        var errors = errorDiagnostics(input.diagnostics());
        assertAll(
                () -> assertInstanceOf(Evaluation.Rejected.class, input.constantFact(badConst)),
                () -> assertEquals(1, errors.size(), errors::toString),
                () -> assertEquals("sema.constant_resolution", errors.getFirst().category()),
                () -> assertTrue(errors.getFirst().message().contains("BAD"),
                        errors.getFirst().message())
        );
    }

    /// An unknown name inside a const initializer costs exactly one diagnostic — the original
    /// binding error. The expression stage still publishes its failed-type fact but must not
    /// re-report the consequence, and the const owner suppresses its own duplicate too.
    @Test
    void unknownIdentifierInsideConstReportsBindingErrorOnly() throws Exception {
        var input = analyze("local_const_unknown_name.gd", """
                class_name LocalConstUnknownName
                extends RefCounted

                func get():
                    const U = MISSING
                    return 0
                """);
        var get = findFunction(input.unit().ast(), "get");
        var uConst = findLocalConst(get, "U");

        var errors = errorDiagnostics(input.diagnostics());
        assertAll(
                () -> assertInstanceOf(Evaluation.Rejected.class, input.constantFact(uConst)),
                () -> assertEquals(1, errors.size(), errors::toString),
                () -> assertEquals("sema.binding", errors.getFirst().category(), errors::toString),
                () -> assertTrue(errors.getFirst().message().contains("MISSING"),
                        errors.getFirst().message())
        );
    }

    /// Self reference is invisible at its own declaration site — the name resolves outward or
    /// stays unknown instead of reading the half-initialized slot.
    @Test
    void selfReferencingConstIsRejected() throws Exception {
        var input = analyze("local_const_self_ref.gd", """
                class_name LocalConstSelfRef
                extends RefCounted

                func get():
                    const X = X + 1
                    return 0
                """);
        var get = findFunction(input.unit().ast(), "get");

        assertAll(
                () -> assertInstanceOf(Evaluation.Rejected.class,
                        input.constantFact(findLocalConst(get, "X"))),
                () -> assertFalse(errorDiagnostics(input.diagnostics()).isEmpty())
        );
    }

    /// A forward reference with no outer binding is rejected — the later local const exists in
    /// the scope but is not yet visible at the earlier initializer.
    @Test
    void forwardReferenceWithoutOuterBindingIsRejected() throws Exception {
        var input = analyze("local_const_forward_no_outer.gd", """
                class_name LocalConstForwardNoOuter
                extends RefCounted

                func get():
                    const A = B
                    const B = 1
                    return A
                """);
        var get = findFunction(input.unit().ast(), "get");

        assertAll(
                () -> assertInstanceOf(Evaluation.Rejected.class,
                        input.constantFact(findLocalConst(get, "A"))),
                () -> assertFalse(errorDiagnostics(input.diagnostics()).isEmpty()),
                () -> assertEquals(new Evaluation.Resolved(new GdConstantValue.IntValue(1L)),
                        input.constantFact(findLocalConst(get, "B")))
        );
    }

    /// An unsupported initializer shape (array literal) is rejected by the const owner itself:
    /// nothing upstream complained about the subtree, so the owner produces the diagnostic.
    @Test
    void unsupportedConstInitializerShapeIsRejectedByOwner() throws Exception {
        var input = analyze("local_const_array.gd", """
                class_name LocalConstArray
                extends RefCounted

                func get():
                    const BAD = [1]
                    return 0
                """);
        var get = findFunction(input.unit().ast(), "get");

        var errors = errorDiagnostics(input.diagnostics());
        assertAll(
                () -> assertInstanceOf(Evaluation.Rejected.class,
                        input.constantFact(findLocalConst(get, "BAD"))),
                () -> assertEquals(1, errors.size(), errors::toString),
                () -> assertEquals("sema.constant_resolution", errors.getFirst().category())
        );
    }

    /// A lambda inside a const initializer is not a compile-time value: the rejection is owned
    /// by const resolution alone — no `unsupported lambda subtree` cascade from binding.
    @Test
    void lambdaInsideConstInitializerIsRejectedOnce() throws Exception {
        var input = analyze("local_const_lambda_init.gd", """
                class_name LocalConstLambdaInit
                extends RefCounted

                func get():
                    const X = func():
                        return 1
                    return 0
                """);
        var get = findFunction(input.unit().ast(), "get");

        var errors = errorDiagnostics(input.diagnostics());
        assertAll(
                () -> assertInstanceOf(Evaluation.Rejected.class,
                        input.constantFact(findLocalConst(get, "X"))),
                // Expression typing already reports the unrecorded lambda route inside the
                // initializer; const resolution suppresses its own duplicate rejection.
                () -> assertEquals(1, errors.size(),
                        () -> "expected exactly one upstream route diagnostic, got: " + errors),
                () -> assertEquals("sema.unsupported_expression_route", errors.getFirst().category())
        );
    }

    /// An unsupported annotation is an inventory-time error with a `Rejected` fact: the name is
    /// claimed from the start, so the use site reports the dependency-invalid poison rather
    /// than falling back to an outer name.
    @Test
    void unsupportedConstAnnotationRejectsAndPoisonsName() throws Exception {
        var input = analyze("local_const_variant_annotation.gd", """
                class_name LocalConstVariantAnnotation
                extends RefCounted

                var v = 0

                func get():
                    const V: Variant = 1
                    return V
                """);
        var get = findFunction(input.unit().ast(), "get");
        var vConst = findLocalConst(get, "V");
        var useSite = findNode(get.body(), IdentifierExpression.class,
                identifier -> identifier.name().equals("V"));

        var errors = errorDiagnostics(input.diagnostics());
        var binding = input.analysisData().symbolBindings().get(useSite);
        assertAll(
                () -> assertInstanceOf(Evaluation.Rejected.class, input.constantFact(vConst)),
                () -> assertEquals(2, errors.size(), errors::toString),
                () -> assertEquals("sema.variable_binding", errors.getFirst().category()),
                () -> assertTrue(errors.getFirst().message().contains("declared type"),
                        errors.getFirst().message()),
                () -> assertTrue(errors.get(1).message().contains("invalid constant"),
                        errors.get(1).message()),
                () -> assertNotNull(binding),
                () -> assertEquals(ScopeLookupStatus.FOUND_BLOCKED, binding.valueAccessStatus())
        );
    }

    /// An exact-type-mismatched annotation is rejected at the conversion boundary after
    /// evaluation — the payload type is checked against the declared type, never coerced.
    @Test
    void incompatibleDeclaredTypeRejectsAfterEvaluation() throws Exception {
        var input = analyze("local_const_type_mismatch.gd", """
                class_name LocalConstTypeMismatch
                extends RefCounted

                func get():
                    const S: int = "x"
                    return 0
                """);
        var get = findFunction(input.unit().ast(), "get");

        var errors = errorDiagnostics(input.diagnostics());
        assertAll(
                () -> assertInstanceOf(Evaluation.Rejected.class,
                        input.constantFact(findLocalConst(get, "S"))),
                () -> assertEquals(1, errors.size(), errors::toString),
                () -> assertEquals("sema.constant_resolution", errors.getFirst().category()),
                () -> assertTrue(errors.getFirst().message().contains("declared type"),
                        errors.getFirst().message())
        );
    }

    /// Reading a failed local const produces the dependency-invalid use-site diagnostic and a
    /// blocked binding — the frozen-name mask stops outer/global fallback exactly like class
    /// constants do.
    @Test
    void failedLocalConstPoisonsUseSite() throws Exception {
        var input = analyze("local_const_poison.gd", """
                class_name LocalConstPoison
                extends RefCounted

                var answer = 99

                func get():
                    const answer = [1]
                    return answer
                """);
        var get = findFunction(input.unit().ast(), "get");
        var useSite = findNode(get.body(), IdentifierExpression.class,
                identifier -> identifier.name().equals("answer"));

        var errors = errorDiagnostics(input.diagnostics());
        var binding = input.analysisData().symbolBindings().get(useSite);
        assertAll(
                // One declaration rejection (array payload) + one use-site poison diagnostic.
                () -> assertEquals(2, errors.size(), errors::toString),
                () -> assertEquals("sema.constant_resolution", errors.getFirst().category()),
                () -> assertTrue(errors.get(1).message().contains("invalid constant"),
                        errors.get(1).message()),
                () -> assertNotNull(binding),
                () -> assertEquals(ScopeLookupStatus.FOUND_BLOCKED, binding.valueAccessStatus())
        );
    }

    /// Sibling failures are independent: `A` fails on its own shape, `B` fails by depending on
    /// `A` (binding reports the poison inside the initializer, suppressing the owner
    /// diagnostic), and `C` still resolves.
    @Test
    void siblingConstFailuresStayIndependent() throws Exception {
        var input = analyze("local_const_siblings.gd", """
                class_name LocalConstSiblings
                extends RefCounted

                func get():
                    const A = [1]
                    const B = A + 1
                    const C = 2
                    return C
                """);
        var get = findFunction(input.unit().ast(), "get");

        var errors = errorDiagnostics(input.diagnostics());
        assertAll(
                () -> assertInstanceOf(Evaluation.Rejected.class,
                        input.constantFact(findLocalConst(get, "A"))),
                () -> assertInstanceOf(Evaluation.Rejected.class,
                        input.constantFact(findLocalConst(get, "B"))),
                () -> assertEquals(new Evaluation.Resolved(new GdConstantValue.IntValue(2L)),
                        input.constantFact(findLocalConst(get, "C"))),
                // A's owner rejection + B's poisoned-use binding diagnostic.
                () -> assertEquals(2, errors.size(), errors::toString)
        );
    }

    /// The exact diagnostic shape of an upstream name failure: `Missing` costs one original
    /// binding error and `NEXT` costs one dependency error — expression typing stays silent on
    /// both initializers because its consequence is already owned upstream.
    @Test
    void unknownNameAndDependencyConstEmitOneDiagnosticEach() throws Exception {
        var input = analyze("local_const_missing_dep.gd", """
                class_name LocalConstMissingDep
                extends RefCounted

                func get():
                    const BAD = Missing + 1
                    const NEXT = BAD + 1
                    return 0
                """);
        var get = findFunction(input.unit().ast(), "get");

        var errors = errorDiagnostics(input.diagnostics());
        assertAll(
                () -> assertInstanceOf(Evaluation.Rejected.class,
                        input.constantFact(findLocalConst(get, "BAD"))),
                () -> assertInstanceOf(Evaluation.Rejected.class,
                        input.constantFact(findLocalConst(get, "NEXT"))),
                () -> assertEquals(2, errors.size(), errors::toString),
                () -> assertTrue(errors.stream().anyMatch(diagnostic ->
                        diagnostic.message().contains("Missing")), errors::toString),
                () -> assertTrue(errors.stream().anyMatch(diagnostic ->
                        diagnostic.message().contains("depends on an invalid constant")),
                        errors::toString),
                () -> assertTrue(errors.stream().noneMatch(diagnostic ->
                        diagnostic.category().equals("sema.expression_resolution")), errors::toString)
        );
    }

    /// Suppression only reaches expressions that already carry an upstream error inside their
    /// own subtree: an independent expression failure in another part of the initializer still
    /// reports its own owner diagnostic instead of being swallowed by the name failure.
    @Test
    void independentExpressionFailureStillReportsInsideFailedConst() throws Exception {
        var input = analyze("local_const_indep_expr.gd", """
                class_name LocalConstIndepExpr
                extends RefCounted

                func get():
                    const BAD = (1 + 1.0) + Missing
                    return 0
                """);
        var get = findFunction(input.unit().ast(), "get");

        var errors = errorDiagnostics(input.diagnostics());
        assertAll(
                () -> assertInstanceOf(Evaluation.Rejected.class,
                        input.constantFact(findLocalConst(get, "BAD"))),
                () -> assertEquals(2, errors.size(), errors::toString),
                () -> assertTrue(errors.stream().anyMatch(diagnostic ->
                        "sema.binding".equals(diagnostic.category())
                                && diagnostic.message().contains("Missing")), errors::toString),
                () -> assertTrue(errors.stream().anyMatch(diagnostic ->
                        "sema.expression_resolution".equals(diagnostic.category())
                                && diagnostic.message().contains("not defined for operand types")),
                        errors::toString)
        );
    }

    /// With no upstream error at all, suppression must stay completely out of the picture —
    /// the nested expression failure and its propagated root failure each report once,
    /// deterministically, regardless of fact-map iteration order.
    @Test
    void nestedExpressionFailuresInsideConstReportDeterministically() throws Exception {
        var input = analyze("local_const_nested_expr.gd", """
                class_name LocalConstNestedExpr
                extends RefCounted

                func get():
                    const BAD = (1 + 1.0) + 1
                    return 0
                """);
        var get = findFunction(input.unit().ast(), "get");

        var errors = errorDiagnostics(input.diagnostics());
        assertAll(
                () -> assertInstanceOf(Evaluation.Rejected.class,
                        input.constantFact(findLocalConst(get, "BAD"))),
                () -> assertEquals(2, errors.stream()
                        .filter(diagnostic -> "sema.expression_resolution".equals(diagnostic.category()))
                        .count(), errors::toString),
                () -> assertTrue(errors.stream().noneMatch(diagnostic ->
                        "sema.binding".equals(diagnostic.category())), errors::toString)
        );
    }

    /// A `for` iterator is a mutable local — a const initialized from it is rejected.
    @Test
    void constInitializedFromForIteratorIsRejected() throws Exception {
        var input = analyze("local_const_for_iter.gd", """
                class_name LocalConstForIter
                extends RefCounted

                func get(items: Array[int]):
                    for item in items:
                        const K = item
                    return 0
                """);
        var get = findFunction(input.unit().ast(), "get");
        var forStatement = findNode(get.body(), ForStatement.class, _ -> true);
        var kConst = findStatement(
                forStatement.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("K")
        );

        var errors = errorDiagnostics(input.diagnostics());
        assertAll(
                () -> assertInstanceOf(Evaluation.Rejected.class, input.constantFact(kConst)),
                () -> assertEquals(1, errors.size(), errors::toString),
                () -> assertEquals("sema.constant_resolution", errors.getFirst().category())
        );
    }

    /// Instance members are not constants: `self`/property reads in an initializer resolve as
    /// non-constant operands and are rejected by the owner.
    @Test
    void constInitializerCannotReadInstanceMembers() throws Exception {
        var input = analyze("local_const_self.gd", """
                class_name LocalConstSelf
                extends RefCounted

                var hp = 1

                func get():
                    const BAD = hp + 1
                    return 0
                """);
        var get = findFunction(input.unit().ast(), "get");

        var errors = errorDiagnostics(input.diagnostics());
        assertAll(
                () -> assertInstanceOf(Evaluation.Rejected.class,
                        input.constantFact(findLocalConst(get, "BAD"))),
                () -> assertEquals(1, errors.size(), errors::toString),
                () -> assertEquals("sema.constant_resolution", errors.getFirst().category())
        );
    }

    /// A same-block `var`/`const` name collision is a binding-time duplicate: the const is
    /// rejected at inventory and never reaches evaluation.
    @Test
    void constNameConflictsWithSameBlockLocal() throws Exception {
        var input = analyze("local_const_name_conflict.gd", """
                class_name LocalConstNameConflict
                extends RefCounted

                func get():
                    var x = 1
                    const x = 2
                    return 0
                """);
        var get = findFunction(input.unit().ast(), "get");
        var xConst = findStatement(
                get.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("x")
                        && variableDeclaration.kind() == DeclarationKind.CONST
        );

        var errors = errorDiagnostics(input.diagnostics());
        assertAll(
                () -> assertNull(input.constantFact(xConst),
                        "an unbound const must never reach evaluation"),
                () -> assertFalse(errors.isEmpty())
        );
    }

    /// Assigning to a local const is a read-only violation at the use site — the binding kind
    /// carries the constant contract and the writable-target model refuses it.
    @Test
    void assigningToLocalConstIsRejectedAsReadOnly() throws Exception {
        var input = analyze("local_const_assign.gd", """
                class_name LocalConstAssign
                extends RefCounted

                func get():
                    const X = 1
                    X = 2
                    return 0
                """);
        var errors = errorDiagnostics(input.diagnostics());
        assertAll(
                () -> assertFalse(errors.isEmpty()),
                () -> assertTrue(errors.stream().anyMatch(diagnostic ->
                        diagnostic.message().contains("read-only")), errors::toString)
        );
    }

    /// The `n` acceptance case: writes to `n` before the const declaration legally target the
    /// class property, while identical writes after the declaration hit the read-only local
    /// const — use-site identity is fixed by declaration order, not by a blanket name rule.
    @Test
    void writesBeforeLocalConstHitOuterPropertyWhileLaterWritesAreRejected() throws Exception {
        var input = analyze("local_const_n_acceptance.gd", """
                class_name LocalConstNAcceptance
                extends RefCounted

                var n = 0

                func get():
                    n = 1
                    n += 1
                    const n = 2
                    n = 3
                    n += 1
                    return 0
                """);
        var get = findFunction(input.unit().ast(), "get");

        var errors = errorDiagnostics(input.diagnostics());
        assertAll(
                () -> assertEquals(new Evaluation.Resolved(new GdConstantValue.IntValue(2L)),
                        input.constantFact(findLocalConst(get, "n"))),
                // Only the two post-declaration writes fail; the earlier `n = 1` and `n += 1`
                // wrote to the class property and stay legal.
                () -> assertEquals(2, errors.size(), errors::toString),
                () -> assertTrue(errors.stream().allMatch(diagnostic ->
                        diagnostic.message().contains("read-only")), errors::toString)
        );
    }

    /// A frozen constant can never root a write-back chain: member and element writes through
    /// `LV`, `Other.C`, and `Vector3.ONE` are rejected, while a `var` copy and the enum
    /// container's Dictionary-subscript write keep their ordinary semantics.
    @Test
    void memberAndSubscriptWritesThroughConstantsAreRejected() throws Exception {
        var input = analyze("local_const_frozen_writes.gd", """
                class_name LocalConstFrozenWrites
                extends RefCounted

                const CV = Vector2(1.0, 2.0)

                class Other:
                    enum State { IDLE }
                    const C = Vector2(5.0, 6.0)

                func get():
                    const LV = Vector2(3.0, 4.0)
                    LV.x = 5.0
                    LV[0] = 5.0
                    CV.x = 5.0
                    Other.C.x = 5.0
                    Vector3.ONE.x = 5.0
                    var copy = LV
                    copy.x = 5.0
                    Other.State["IDLE"] = 1
                    return 0
                """);
        var errors = errorDiagnostics(input.diagnostics());
        assertAll(
                // LV.x, LV[0], CV.x, Other.C.x, Vector3.ONE.x — five frozen-root writes, plus the
                // enum container's pre-existing subscript write-protection which stays untouched.
                () -> assertEquals(6, errors.size(), errors::toString),
                () -> assertEquals(5, errors.stream()
                        .filter(diagnostic -> diagnostic.message().contains("frozen"))
                        .count(), errors::toString),
                () -> assertTrue(errors.stream().anyMatch(diagnostic ->
                        diagnostic.message().contains("not an assignable")
                                && diagnostic.message().contains("State")), errors::toString)
        );
    }

    /// The frozen-root guard is value-type neutral: a String const rejects compound and
    /// subscript writes exactly like a builtin constant does (`S[0] = ...` would attempt a
    /// write-back into the frozen value).
    @Test
    void writesThroughStringConstantsAreRejected() throws Exception {
        var input = analyze("local_const_string_writes.gd", """
                class_name LocalConstStringWrites
                extends RefCounted

                func get():
                    const S = "seed"
                    S += "x"
                    S[0] = "y"
                    var copy = S
                    copy += "x"
                    return 0
                """);
        var errors = errorDiagnostics(input.diagnostics());
        assertAll(
                () -> assertEquals(2, errors.size(), errors::toString),
                () -> assertEquals(1, errors.stream()
                        .filter(diagnostic -> diagnostic.message().contains("read-only"))
                        .count(), errors::toString),
                () -> assertEquals(1, errors.stream()
                        .filter(diagnostic -> diagnostic.message().contains("frozen"))
                        .count(), errors::toString)
        );
    }

    /// `var x := C` must infer the constant's exact type, not the Variant baseline the binding
    /// carried before `CONST_RESOLUTION` — the overlay view lets `VAR_TYPE_POST` see the frozen
    /// type the moment the const statement commits.
    @Test
    void varInferredFromLocalConstTakesExactType() throws Exception {
        var input = analyze("local_const_var_inference.gd", """
                class_name LocalConstVarInference
                extends RefCounted

                func get():
                    const C = 7
                    var x := C
                    return x
                """);
        var get = findFunction(input.unit().ast(), "get");
        var varDecl = findNode(get.body(), VariableDeclaration.class,
                declaration -> declaration.kind() == DeclarationKind.VAR
                        && declaration.name().equals("x"));

        assertAll(
                () -> assertTrue(errorDiagnostics(input.diagnostics()).isEmpty(),
                        () -> "Unexpected diagnostics: " + input.diagnostics()),
                () -> assertEquals(GdIntType.INT,
                        input.analysisData().slotTypes().get(varDecl))
        );
    }

    /// A `match` key accepts a resolved local const — the bare identifier selects the constant
    /// operand sub-mode just like an enum or class const.
    @Test
    void matchPatternAcceptsResolvedLocalConstKey() throws Exception {
        var input = analyze("local_const_match_key.gd", """
                class_name LocalConstMatchKey
                extends RefCounted

                func get(v: int):
                    const K = 1
                    match v:
                        K:
                            pass
                        _:
                            pass
                    return 0
                """);
        assertTrue(errorDiagnostics(input.diagnostics()).isEmpty(),
                () -> "Unexpected diagnostics: " + input.diagnostics());
    }

    /// String const keys ride the same Resolved-fact gate as scalars — a resolved String local
    /// const is a legal compile-time match key on a String scrutinee.
    @Test
    void matchPatternAcceptsResolvedStringLocalConstKey() throws Exception {
        var input = analyze("local_const_match_string_key.gd", """
                class_name LocalConstMatchStringKey
                extends RefCounted

                func get(v: String):
                    const K = "key"
                    match v:
                        K:
                            pass
                        _:
                            pass
                    return 0
                """);
        var get = findFunction(input.unit().ast(), "get");
        var matchStatement = findNode(get.body(), MatchStatement.class, _ -> true);
        var keyUse = findNode(matchStatement, IdentifierExpression.class,
                identifier -> identifier.name().equals("K"));

        assertAll(
                () -> assertTrue(errorDiagnostics(input.diagnostics()).isEmpty(),
                        () -> "Unexpected diagnostics: " + input.diagnostics()),
                () -> assertEquals(new Evaluation.Resolved(new GdConstantValue.StringValue("key")),
                        input.constantFact(findLocalConst(get, "K"))),
                () -> assertTrue(FrontendMatchSupport.isConstantPatternOperand(
                                input.analysisData(), keyUse),
                        "a resolved String const must classify as a constant match key")
        );
    }

    /// A rejected const must not be a constant match key: the key falls back to a runtime
    /// operand, so the only diagnostics are the declaration rejection and the use-site poison.
    @Test
    void matchPatternRejectsFailedConstAsConstantKey() throws Exception {
        var input = analyze("local_const_match_failed_key.gd", """
                class_name LocalConstMatchFailedKey
                extends RefCounted

                func get(v: int):
                    const BAD = [1]
                    match v:
                        BAD:
                            pass
                        _:
                            pass
                    return 0
                """);
        var get = findFunction(input.unit().ast(), "get");
        var matchStatement = findNode(get.body(), MatchStatement.class, _ -> true);
        var keyUse = findNode(matchStatement, IdentifierExpression.class,
                identifier -> identifier.name().equals("BAD"));

        var errors = errorDiagnostics(input.diagnostics());
        var keyBinding = input.analysisData().symbolBindings().get(keyUse);
        assertAll(
                () -> assertEquals(2, errors.size(), errors::toString),
                () -> assertEquals("sema.constant_resolution", errors.getFirst().category()),
                () -> assertNotNull(keyBinding),
                () -> assertEquals(ScopeLookupStatus.FOUND_BLOCKED, keyBinding.valueAccessStatus())
        );
    }

    /// Parameter defaults live in the `CallableScope` island: a body-local const must surface as
    /// a visibility violation (one blocked hit), not fall back to the unknown-name path.
    @Test
    void parameterDefaultCannotReadBodyLocalConst() throws Exception {
        var input = analyze("local_const_default_island.gd", """
                class_name LocalConstDefaultIsland
                extends RefCounted

                func get(a = LOCAL_C):
                    const LOCAL_C = 1
                    return a
                """);
        var errors = errorDiagnostics(input.diagnostics());
        assertAll(
                () -> assertFalse(errors.isEmpty(), "a local const reference in a default must fail"),
                () -> assertTrue(errors.stream().noneMatch(diagnostic ->
                        diagnostic.message().contains("Unable to resolve value binding")),
                        () -> "the island must not degrade to the unknown-name path: " + errors)
        );
    }

    /// A class const is a legal parameter default even when the body declares a same-named local
    /// const — the island reads the outer declaration, never the half-declared body name.
    @Test
    void parameterDefaultReadsClassConstPastSameNamedLocalConst() throws Exception {
        var input = analyze("local_const_default_class.gd", """
                class_name LocalConstDefaultClass
                extends RefCounted

                const X = 9

                func get(a = X):
                    const X = 1
                    return a
                """);
        var get = findFunction(input.unit().ast(), "get");

        assertAll(
                () -> assertTrue(errorDiagnostics(input.diagnostics()).isEmpty(),
                        () -> "Unexpected diagnostics: " + input.diagnostics()),
                () -> assertEquals(new Evaluation.Resolved(new GdConstantValue.IntValue(1L)),
                        input.constantFact(findLocalConst(get, "X")))
        );
    }

    /// A String class const is a legal parameter default — the island publishes the resolved
    /// constant binding and the synthetic default materializes the owning `literal_string`
    /// (lowering side anchored in `FrontendConstLoweringTest`).
    @Test
    void parameterDefaultReadsStringClassConst() throws Exception {
        var input = analyze("local_const_default_string_class.gd", """
                class_name LocalConstDefaultStringClass
                extends RefCounted

                const GREETING = "hello"

                func get(a = GREETING):
                    return a
                """);
        var get = findFunction(input.unit().ast(), "get");
        var defaultUse = findNode(get, IdentifierExpression.class,
                identifier -> identifier.name().equals("GREETING"));
        var binding = input.analysisData().symbolBindings().get(defaultUse);

        assertAll(
                () -> assertTrue(errorDiagnostics(input.diagnostics()).isEmpty(),
                        () -> "Unexpected diagnostics: " + input.diagnostics()),
                () -> assertNotNull(binding),
                () -> assertEquals(ScopeLookupStatus.FOUND_ALLOWED, binding.valueAccessStatus()),
                () -> assertEquals(FrontendBindingKind.CONSTANT, binding.kind())
        );
    }

    /// The compile gate demands a Resolved fact for every reachable const: a rejected const
    /// produces a `sema.compile_check` blocker on top of its declaration diagnostic.
    @Test
    void compileGateBlocksRejectedLocalConst() throws Exception {
        var analyzed = analyzeForCompile("local_const_compile_gate.gd", """
                class_name LocalConstCompileGate
                extends RefCounted

                func get():
                    const BAD = [1]
                    return 0
                """);
        var compileBlocks = analyzed.diagnostics().asList().stream()
                .filter(diagnostic -> diagnostic.category().equals("sema.compile_check"))
                .toList();
        assertAll(
                () -> assertEquals(1, compileBlocks.size(), compileBlocks::toString),
                () -> assertTrue(compileBlocks.getFirst().message().contains("BAD"),
                        compileBlocks.getFirst().message())
        );
    }

    /// A local `const` declared *after* a lambda statement can never shadow the class property
    /// for uses inside that lambda: capture planning applies the same declaration-order rule as
    /// body binding, so `n` binds to the instance property and `self` is captured. Without the
    /// filter the planner would see the later constant, skip the `self` capture, and lowering
    /// would then fail to materialize the property access inside the lambda.
    @Test
    void lambdaReadPrefersPropertyOverLaterLocalConst() throws Exception {
        var input = analyze("local_const_lambda_order.gd", """
                class_name LocalConstLambdaOrder
                extends RefCounted

                var n: int = 7

                func make():
                    var cb = func():
                        return n
                    const n = 2
                    return cb
                """);
        var make = findFunction(input.unit().ast(), "make");
        var lambda = findNode(make.body(), LambdaExpression.class, _ -> true);
        var useSite = findNode(lambda.body(), IdentifierExpression.class,
                identifier -> identifier.name().equals("n"));
        var nConst = findNode(make.body(), VariableDeclaration.class,
                declaration -> declaration.kind() == DeclarationKind.CONST
                        && declaration.name().equals("n"));

        var plan = input.analysisData().lambdaPlans().get(lambda);
        var binding = input.analysisData().symbolBindings().get(useSite);
        assertAll(
                () -> assertTrue(errorDiagnostics(input.diagnostics()).isEmpty(),
                        () -> "Unexpected diagnostics: " + input.diagnostics()),
                () -> assertEquals(new Evaluation.Resolved(new GdConstantValue.IntValue(2L)),
                        input.constantFact(nConst)),
                () -> assertNotNull(plan),
                () -> assertTrue(plan.capturesSelf(),
                        "the lambda must capture `self` because `n` binds the property"),
                () -> assertNotNull(binding),
                () -> assertEquals(FrontendBindingKind.PROPERTY, binding.kind())
        );
    }

    /// The same ordering hole existed for a later `var`: the planner must not capture a local
    /// whose declaration follows the lambda either.
    @Test
    void lambdaReadPrefersPropertyOverLaterLocalVar() throws Exception {
        var input = analyze("local_var_lambda_order.gd", """
                class_name LocalVarLambdaOrder
                extends RefCounted

                var n: int = 7

                func make():
                    var cb = func():
                        return n
                    var n = 2
                    return cb
                """);
        var make = findFunction(input.unit().ast(), "make");
        var lambda = findNode(make.body(), LambdaExpression.class, _ -> true);
        var useSite = findNode(lambda.body(), IdentifierExpression.class,
                identifier -> identifier.name().equals("n"));

        var plan = input.analysisData().lambdaPlans().get(lambda);
        var binding = input.analysisData().symbolBindings().get(useSite);
        assertAll(
                () -> assertTrue(errorDiagnostics(input.diagnostics()).isEmpty(),
                        () -> "Unexpected diagnostics: " + input.diagnostics()),
                () -> assertNotNull(plan),
                () -> assertTrue(plan.capturesSelf()),
                () -> assertNotNull(binding),
                () -> assertEquals(FrontendBindingKind.PROPERTY, binding.kind())
        );
    }

    /// `Child.C` in a const initializer follows the nearest-layer inheritance walk: a constant
    /// published on a base class is reachable through the derived class's type-meta namespace,
    /// and an inherited enum group resolves member-wise the same way.
    @Test
    void constInitializerReadsInheritedQualifiedConstants() throws Exception {
        var input = analyze("local_const_inherited.gd", """
                class_name LocalConstInherited
                extends RefCounted

                class Base:
                    const C = 7
                    enum State { IDLE }

                class Child extends Base:
                    pass

                func get():
                    const K = Child.C
                    const S = Child.State.IDLE
                    return K + S
                """);
        var get = findFunction(input.unit().ast(), "get");

        assertAll(
                () -> assertTrue(errorDiagnostics(input.diagnostics()).isEmpty(),
                        () -> "Unexpected diagnostics: " + input.diagnostics()),
                () -> assertEquals(new Evaluation.Resolved(new GdConstantValue.IntValue(7L)),
                        input.constantFact(findLocalConst(get, "K"))),
                () -> assertEquals(new Evaluation.Resolved(new GdConstantValue.IntValue(0L)),
                        input.constantFact(findLocalConst(get, "S")))
        );
    }

    /// A value-producing call severs write-back provenance: `V.normalized().x` assigns into the
    /// fresh temporary returned by `normalized()`, not into the frozen `V`, so the frozen-root
    /// rejection must not fire. Direct member writes on the same constant still fail.
    @Test
    void methodResultMemberWritesBypassFrozenRoot() throws Exception {
        var input = analyze("local_const_method_result_write.gd", """
                class_name LocalConstMethodResultWrite
                extends RefCounted

                class Other:
                    const V = Vector2(3, 4)

                func use():
                    const V = Vector2(1, 2)
                    V.normalized().x = 1.0
                    Other.V.normalized().y = 2.0
                    V.x = 1.0
                """);
        var errors = errorDiagnostics(input.diagnostics());
        var frozenWrites = errors.stream()
                .filter(diagnostic -> diagnostic.message().contains("is frozen and cannot be assigned"))
                .toList();

        assertAll(
                () -> assertEquals(1, frozenWrites.size(),
                        () -> "only the direct `V.x` write is frozen; diagnostics: " + errors),
                () -> assertTrue(frozenWrites.getFirst().message().contains("'V'"))
        );
    }

    /// Suppression of a second const-domain diagnostic must only consume errors from the same
    /// source file: byte ranges are file-local, so an overlapping diagnostic in another unit of
    /// the same module can never hide this file's own evaluation failure.
    @Test
    void crossFileErrorDoesNotSuppressConstDiagnostic() throws Exception {
        // `Q` in file A sits at the same byte range where `1 / 0` sits in file B; without the
        // source-path guard the const owner would silently drop B's own failure.
        var sourceA = """
                class_name CrossA
                extends RefCounted

                func get():
                    const A_BAD = Q
                    return 0
                """;
        var sourceB = """
                class_name CrossB
                extends RefCounted

                func get():
                    const B_BAD = 1 / 0
                    return 0
                """;
        var diagnostics = new DiagnosticManager();
        var unitA = parseUnit("cross_a.gd", sourceA);
        var unitB = parseUnit("cross_b.gd", sourceB);
        new FrontendSemanticAnalyzer().analyze(
                new FrontendModule("test_module", List.of(unitA, unitB)),
                new ClassRegistry(ExtensionApiLoader.loadDefault()),
                diagnostics
        );

        var snapshot = diagnostics.snapshot();
        var constErrors = snapshot.asList().stream()
                .filter(diagnostic -> "sema.constant_resolution".equals(diagnostic.category()))
                .toList();
        assertAll(
                () -> assertEquals(1, constErrors.size(),
                        () -> "file B must still own its evaluation error; diagnostics: " + snapshot.asList()),
                () -> assertTrue(constErrors.getFirst().message().contains("B_BAD"),
                        constErrors.getFirst().message())
        );
    }

    // ------------------------------------------------------------------
    // Harness
    // ------------------------------------------------------------------

    private static @NotNull AnalyzedConstInput analyze(
            @NotNull String fileName,
            @NotNull String source
    ) throws Exception {
        var diagnostics = new DiagnosticManager();
        var unit = parseUnit(fileName, source);
        var analysisData = new FrontendSemanticAnalyzer().analyze(
                new FrontendModule("test_module", List.of(unit)),
                new ClassRegistry(ExtensionApiLoader.loadDefault()),
                diagnostics
        );
        return new AnalyzedConstInput(unit, analysisData, diagnostics.snapshot());
    }

    private static @NotNull AnalyzedConstInput analyzeForCompile(
            @NotNull String fileName,
            @NotNull String source
    ) throws Exception {
        var diagnostics = new DiagnosticManager();
        var unit = parseUnit(fileName, source);
        var analysisData = new FrontendSemanticAnalyzer().analyzeForCompile(
                new FrontendModule("test_module", List.of(unit)),
                new ClassRegistry(ExtensionApiLoader.loadDefault()),
                diagnostics
        );
        return new AnalyzedConstInput(unit, analysisData, diagnostics.snapshot());
    }

    private static @NotNull FrontendSourceUnit parseUnit(
            @NotNull String fileName,
            @NotNull String source
    ) throws Exception {
        var parserService = new GdScriptParserService();
        var parseDiagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", fileName), source, parseDiagnostics);
        assertTrue(parseDiagnostics.isEmpty(),
                () -> "Unexpected parse diagnostics: " + parseDiagnostics.snapshot());
        return unit;
    }

    private static @NotNull FunctionDeclaration findFunction(
            @NotNull SourceFile sourceFile,
            @NotNull String name
    ) {
        return findNode(
                sourceFile,
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals(name)
        );
    }

    private static @NotNull VariableDeclaration findLocalConst(
            @NotNull Node root,
            @NotNull String name
    ) {
        return findNode(
                root,
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals(name)
        );
    }

    private static <T extends Statement> @NotNull T findStatement(
            @NotNull List<Statement> statements,
            @NotNull Class<T> statementType,
            @NotNull Predicate<T> predicate
    ) {
        return statements.stream()
                .filter(statementType::isInstance)
                .map(statementType::cast)
                .filter(predicate)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Statement not found: " + statementType.getSimpleName()));
    }

    private static @NotNull BlockScope bodyScopeOf(
            @NotNull AnalyzedConstInput input,
            @NotNull Block body
    ) {
        return assertInstanceOf(BlockScope.class, input.analysisData().scopesByAst().get(body));
    }

    private static @NotNull List<FrontendDiagnostic> errorDiagnostics(@NotNull DiagnosticSnapshot snapshot) {
        return snapshot.asList().stream()
                .filter(diagnostic -> diagnostic.severity() == FrontendDiagnosticSeverity.ERROR)
                .toList();
    }

    private static <T extends Node> @NotNull T findNode(
            @NotNull Node root,
            @NotNull Class<T> nodeType,
            @NotNull Predicate<T> predicate
    ) {
        var matches = new ArrayList<T>();
        collectMatchingNodes(root, nodeType, predicate, matches);
        return matches.stream()
                .findFirst()
                .orElseThrow(() -> new AssertionError("Node not found: " + nodeType.getSimpleName()));
    }

    private static <T extends Node> void collectMatchingNodes(
            @NotNull Node node,
            @NotNull Class<T> nodeType,
            @NotNull Predicate<T> predicate,
            @NotNull List<T> matches
    ) {
        if (nodeType.isInstance(node)) {
            var candidate = nodeType.cast(node);
            if (predicate.test(candidate)) {
                matches.add(candidate);
            }
        }
        for (var child : node.getChildren()) {
            collectMatchingNodes(child, nodeType, predicate, matches);
        }
    }

    private record AnalyzedConstInput(
            @NotNull FrontendSourceUnit unit,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticSnapshot diagnostics
    ) {
        private @Nullable Evaluation constantFact(@NotNull VariableDeclaration declaration) {
            return analysisData.constantFacts().get(declaration);
        }
    }
}
