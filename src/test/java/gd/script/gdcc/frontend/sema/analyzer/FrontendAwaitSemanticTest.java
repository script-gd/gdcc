package gd.script.gdcc.frontend.sema.analyzer;

import dev.superice.gdparser.frontend.ast.AwaitExpression;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.LambdaExpression;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.SourceFile;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.diagnostic.DiagnosticSnapshot;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnostic;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.frontend.sema.FrontendAnalysisData;
import gd.script.gdcc.frontend.sema.FrontendExpressionType;
import gd.script.gdcc.frontend.sema.FrontendExpressionTypeStatus;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdArrayType;
import gd.script.gdcc.type.GdVariantType;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Pipeline-level tests for `frontend_await_implementation.md` §8: await operand classification
/// (§3.5), coroutine function marking with order-independent transitive propagation, the
/// `sema.redundant_await` warning, the fail-closed lambda/property-initializer boundaries, and the
/// compile-gate await blocker plus coroutine-call position rules.
class FrontendAwaitSemanticTest {

    @Test
    void awaitSignalWithoutParamsPublishesVariantAndMarksCoroutine() throws Exception {
        var analyzed = analyze("await_signal_no_params.gd", """
                class_name AwaitSignalNoParams
                extends Node
                
                signal pinged
                
                func ping():
                    await pinged
                """);

        var awaitType = awaitTypeOf(analyzed, "ping");
        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors(), analyzed.diagnostics()::toString),
                () -> assertEquals(FrontendExpressionTypeStatus.RESOLVED, awaitType.status()),
                () -> assertEquals("Variant", awaitType.publishedType().getTypeName()),
                () -> assertTrue(coroutineNames(analyzed).contains("ping"), analyzed.diagnostics()::toString)
        );
    }

    @Test
    void awaitSignalPublishesSingleParamTypeAndVariantArrayForMultiParam() throws Exception {
        var analyzed = analyze("await_signal_params.gd", """
                class_name AwaitSignalParams
                extends Node
                
                signal one(value: int)
                signal two(a: int, b: String)
                
                func ping():
                    var first = await one
                    var second = await two
                """);

        var awaits = awaitExpressionsIn(analyzed, "ping");
        assertEquals(2, awaits.size());
        var firstType = analyzed.analysisData().expressionTypes().get(awaits.get(0));
        var secondType = analyzed.analysisData().expressionTypes().get(awaits.get(1));
        var secondArray = assertInstanceOf(GdArrayType.class, secondType.publishedType());
        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors(), analyzed.diagnostics()::toString),
                () -> assertEquals("int", firstType.publishedType().getTypeName()),
                // Multi-param signals resume as `Array[Variant]`, i.e. the generic array here.
                () -> assertTrue(secondArray.isGenericArray()),
                () -> assertInstanceOf(GdVariantType.class, secondArray.getValueType()),
                () -> assertTrue(coroutineNames(analyzed).contains("ping"))
        );
    }

    @Test
    void awaitEngineSignalPublishesVariantAndMarksCoroutine() throws Exception {
        var analyzed = analyze("await_engine_signal.gd", """
                class_name AwaitEngineSignal
                extends Node
                
                func ping(other: AwaitEngineSignal):
                    await other.ready
                """);

        var awaitType = awaitTypeOf(analyzed, "ping");
        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors(), analyzed.diagnostics()::toString),
                () -> assertEquals(FrontendExpressionTypeStatus.RESOLVED, awaitType.status()),
                () -> assertEquals("Variant", awaitType.publishedType().getTypeName()),
                () -> assertTrue(coroutineNames(analyzed).contains("ping"))
        );
    }

    @Test
    void awaitDynamicOperandPublishesVariantAndMarksCoroutine() throws Exception {
        var analyzed = analyze("await_dynamic_operand.gd", """
                class_name AwaitDynamicOperand
                extends Node
                
                func ping(target):
                    await target
                """);

        var awaitType = awaitTypeOf(analyzed, "ping");
        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors(), analyzed.diagnostics()::toString),
                () -> assertEquals(FrontendExpressionTypeStatus.RESOLVED, awaitType.status()),
                () -> assertEquals("Variant", awaitType.publishedType().getTypeName()),
                () -> assertTrue(coroutineNames(analyzed).contains("ping"))
        );
    }

    /// Caller-before-callee source order: the coroutine classification of `await inner()` must not
    /// depend on `inner` being resolved first; the post-suite fixed point handles it.
    @Test
    void awaitCoroutineCallMarksBothFunctionsAndPublishesCalleeReturnType() throws Exception {
        var analyzed = analyze("await_coroutine_call.gd", """
                class_name AwaitCoroutineCall
                extends Node
                
                signal pinged
                
                func outer():
                    var result = await inner()
                
                func inner() -> int:
                    await pinged
                    return 1
                """);

        var awaitType = awaitTypeOf(analyzed, "outer");
        var coroutineNames = coroutineNames(analyzed);
        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors(), analyzed.diagnostics()::toString),
                () -> assertEquals("int", awaitType.publishedType().getTypeName()),
                () -> assertTrue(coroutineNames.contains("inner"), analyzed.diagnostics()::toString),
                () -> assertTrue(coroutineNames.contains("outer"), analyzed.diagnostics()::toString)
        );
    }

    @Test
    void awaitVoidCoroutineCallPublishesVariant() throws Exception {
        var analyzed = analyze("await_void_coroutine_call.gd", """
                class_name AwaitVoidCoroutineCall
                extends Node
                
                signal pinged
                
                func outer():
                    var result = await inner()
                
                func inner():
                    await pinged
                """);

        var awaitType = awaitTypeOf(analyzed, "outer");
        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors(), analyzed.diagnostics()::toString),
                () -> assertEquals("Variant", awaitType.publishedType().getTypeName()),
                () -> assertTrue(coroutineNames(analyzed).contains("outer"))
        );
    }

    @Test
    void awaitCoroutineChainPropagatesMarkingTransitively() throws Exception {
        var analyzed = analyze("await_coroutine_chain.gd", """
                class_name AwaitCoroutineChain
                extends Node
                
                signal pinged
                
                func first():
                    await second()
                
                func second():
                    await third()
                
                func third():
                    await pinged
                """);

        var coroutineNames = coroutineNames(analyzed);
        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors(), analyzed.diagnostics()::toString),
                () -> assertTrue(coroutineNames.containsAll(List.of("first", "second", "third")))
        );
    }

    @Test
    void awaitNonCoroutineCallWarnsAndPassesThroughWithoutMarking() throws Exception {
        var analyzed = analyze("await_non_coroutine_call.gd", """
                class_name AwaitNonCoroutineCall
                extends Node
                
                func user():
                    var result = await plain()
                
                func plain() -> String:
                    return "x"
                """);

        var redundant = diagnosticsByCategory(analyzed.diagnostics(), "sema.redundant_await");
        var awaitType = awaitTypeOf(analyzed, "user");
        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors(), analyzed.diagnostics()::toString),
                () -> assertEquals(1, redundant.size(), analyzed.diagnostics()::toString),
                () -> assertTrue(redundant.getFirst().message().contains("plain"), redundant.getFirst()::message),
                () -> assertEquals("String", awaitType.publishedType().getTypeName()),
                () -> assertFalse(coroutineNames(analyzed).contains("user")),
                () -> assertFalse(coroutineNames(analyzed).contains("plain"))
        );
    }

    @Test
    void awaitEngineMethodCallWarnsWithoutMarking() throws Exception {
        var analyzed = analyze("await_engine_call.gd", """
                class_name AwaitEngineCall
                extends Node
                
                func user():
                    var result = await get_name()
                """);

        var redundant = diagnosticsByCategory(analyzed.diagnostics(), "sema.redundant_await");
        var awaitType = awaitTypeOf(analyzed, "user");
        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors(), analyzed.diagnostics()::toString),
                () -> assertEquals(1, redundant.size(), analyzed.diagnostics()::toString),
                () -> assertTrue(redundant.getFirst().message().contains("get_name"), redundant.getFirst()::message),
                () -> assertEquals("StringName", awaitType.publishedType().getTypeName()),
                () -> assertFalse(coroutineNames(analyzed).contains("user"))
        );
    }

    @Test
    void awaitPlainStaticValueFailsAndKeepsOtherSubtreesWorking() throws Exception {
        var analyzed = analyze("await_plain_value.gd", """
                class_name AwaitPlainValue
                extends Node
                
                signal pinged
                
                func bad():
                    var counter := 1
                    await counter
                
                func good():
                    await pinged
                """);

        var unsupported = diagnosticsByCategory(analyzed.diagnostics(), "sema.unsupported_expression_route");
        var coroutineNames = coroutineNames(analyzed);
        assertAll(
                () -> assertTrue(analyzed.diagnostics().hasErrors(), analyzed.diagnostics()::toString),
                () -> assertFalse(unsupported.isEmpty(), analyzed.diagnostics()::toString),
                () -> assertTrue(unsupported.stream().anyMatch(d -> d.message().contains("not a Signal"))),
                // The bad subtree is skipped (no published await type) but sibling owners keep working.
                () -> assertFalse(coroutineNames.contains("bad")),
                () -> assertTrue(coroutineNames.contains("good"))
        );
    }

    @Test
    void awaitInsideLambdaMarksLambdaOwnerOnlyAndKeepsOtherSubtreesWorking() throws Exception {
        var analyzed = analyze("await_inside_lambda.gd", """
                class_name AwaitInsideLambda
                extends Node
                
                signal pinged
                
                func bad():
                    var callback = func():
                        await pinged
                
                func good():
                    await pinged
                """);

        var unsupported = diagnosticsByCategory(analyzed.diagnostics(), "sema.unsupported_expression_route");
        var coroutineNames = coroutineNames(analyzed);
        var lambdaOwners = analyzed.analysisData().coroutineLambdaOwners();
        // Identity pinning: the marked owner must be exactly the lambda declared inside `bad`
        // (its shell does not exist at sema time; the lowering bridge asserts the shell side).
        var badFunction = findFunction(analyzed.ast(), "bad");
        assertNotNull(badFunction, "function 'bad' must exist");
        var lambdaInBad = findLambda(badFunction);
        assertNotNull(lambdaInBad, "function 'bad' must contain the lambda");
        var lambdaInBadFinal = lambdaInBad;
        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors(), analyzed.diagnostics()::toString),
                () -> assertTrue(unsupported.isEmpty(), analyzed.diagnostics()::toString),
                // Attribution is pinned in both directions: the lambda owner (by AST identity)
                // is marked, while the enclosing named function `bad` stays unmarked — a marking
                // that leaked through `enclosingCallable()` would wrongly turn `bad` into one.
                () -> assertEquals(1, lambdaOwners.size(), analyzed.diagnostics()::toString),
                () -> assertTrue(lambdaOwners.contains(lambdaInBadFinal)),
                () -> assertFalse(coroutineNames.contains("bad")),
                () -> assertTrue(coroutineNames.contains("good"))
        );
    }

    @Test
    void awaitInsidePropertyInitializerFailsAndKeepsOtherSubtreesWorking() throws Exception {
        var analyzed = analyze("await_inside_property_initializer.gd", """
                class_name AwaitInsidePropertyInitializer
                extends Node
                
                signal pinged
                
                var pending = await 1
                
                func good():
                    await pinged
                """);

        var unsupported = diagnosticsByCategory(analyzed.diagnostics(), "sema.unsupported_expression_route");
        assertAll(
                () -> assertTrue(analyzed.diagnostics().hasErrors(), analyzed.diagnostics()::toString),
                () -> assertTrue(
                        unsupported.stream().anyMatch(d -> d.message().contains("property initializers")),
                        analyzed.diagnostics()::toString
                ),
                () -> assertTrue(coroutineNames(analyzed).contains("good"))
        );
    }

    /// Classification-legal awaits pass the compile-only gate.
    @Test
    void analyzeForCompileAllowsClassifiedAwait() throws Exception {
        var compiled = analyzeForCompile("await_gate_blocker.gd", """
                class_name AwaitGateBlocker
                extends Node
                
                signal pinged
                
                func ping():
                    await pinged
                """);

        var compileDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check");
        assertAll(
                () -> assertFalse(compiled.diagnostics().hasErrors(), compiled.diagnostics()::toString),
                () -> assertTrue(compileDiagnostics.isEmpty(), compileDiagnostics::toString)
        );
    }

    @Test
    void analyzeForCompileRejectsValuePositionCoroutineCall() throws Exception {
        var compiled = analyzeForCompile("await_gate_value_position.gd", """
                class_name AwaitGateValuePosition
                extends Node
                
                signal pinged
                
                func outer():
                    var state = inner()
                
                func inner():
                    await pinged
                """);

        var compileDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check");
        assertAll(
                () -> assertTrue(compiled.diagnostics().hasErrors(), compiled.diagnostics()::toString),
                () -> assertTrue(
                        compileDiagnostics.stream().anyMatch(d -> d.message().contains("is a coroutine")
                                && d.message().contains("inner")),
                        compileDiagnostics::toString
                )
        );
    }

    /// Godot root-expression rule: a statement-root instance coroutine call is fire-and-forget and
    /// must not produce a coroutine-position diagnostic.
    @Test
    void analyzeForCompileAllowsStatementPositionCoroutineCall() throws Exception {
        var compiled = analyzeForCompile("await_gate_statement_position.gd", """
                class_name AwaitGateStatementPosition
                extends Node
                
                signal pinged
                
                func outer():
                    inner()
                
                func inner():
                    await pinged
                """);

        var compileDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check");
        assertAll(
                () -> assertFalse(compiled.diagnostics().hasErrors(), compiled.diagnostics()::toString),
                () -> assertTrue(
                        compileDiagnostics.stream().noneMatch(d -> d.message().contains("is a coroutine")),
                        compileDiagnostics::toString
                )
        );
    }

    /// A statement-root static coroutine call is fire-and-forget, same as instance —
    /// no compile diagnostic, and the caller is NOT marked (no await pending is produced, so the
    /// fixed point never propagates; the caller does not suspend).
    @Test
    void analyzeForCompileAllowsStaticCoroutineCallAtStatementRoot() throws Exception {
        var compiled = analyzeForCompile("await_gate_static_coroutine.gd", """
                class_name AwaitGateStaticCoroutine
                extends Node
                
                static func worker(target):
                    await target
                
                func outer():
                    worker(1)
                """);

        var compileDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check");
        assertAll(
                () -> assertFalse(compiled.diagnostics().hasErrors(), compiled.diagnostics()::toString),
                () -> assertTrue(
                        compileDiagnostics.stream().noneMatch(d -> d.message().contains("is a coroutine")),
                        compileDiagnostics::toString
                ),
                () -> assertFalse(coroutineNames(compiled).contains("outer"), compiled.diagnostics()::toString)
        );
    }

    /// Unannotated functions declare a `Variant` return. Even when the callee is not a coroutine,
    /// the returned runtime value may be a Signal, so Godot keeps the caller on dynamic await.
    @Test
    void awaitCallWithUnannotatedVariantReturnMarksCallerWithoutRedundantWarning() throws Exception {
        var analyzed = analyze("await_variant_return_call.gd", """
                class_name AwaitVariantReturnCall
                extends Node
                
                func inner():
                    return 1
                
                func user():
                    var state = await inner()
                """);

        var awaitType = awaitTypeOf(analyzed, "user");
        var redundant = diagnosticsByCategory(analyzed.diagnostics(), "sema.redundant_await");
        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors(), analyzed.diagnostics()::toString),
                () -> assertEquals("Variant", awaitType.publishedType().getTypeName()),
                () -> assertTrue(redundant.isEmpty(), analyzed.diagnostics()::toString),
                () -> assertTrue(coroutineNames(analyzed).contains("user"))
        );
    }

    @Test
    void variantReturningAwaitPropagatesCoroutineMarkingTransitively() throws Exception {
        var analyzed = analyze("await_variant_transitive.gd", """
                class_name AwaitVariantTransitive
                extends Node
                
                func outer():
                    return await middle()
                
                func middle():
                    return await leaf()
                
                func leaf():
                    return 1
                """);

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors(), analyzed.diagnostics()::toString),
                () -> assertEquals(Set.of("middle", "outer"), coroutineNames(analyzed)),
                () -> assertTrue(
                        diagnosticsByCategory(analyzed.diagnostics(), "sema.redundant_await").isEmpty(),
                        analyzed.diagnostics()::toString
                )
        );
    }

    /// A non-coroutine call returning Signal awaits that returned signal. Its await result is the
    /// signal resume payload, not the Signal object returned by the callee.
    @Test
    void awaitNonCoroutineSignalReturningCallUsesSignalResumeType() throws Exception {
        var analyzed = analyze("await_signal_return_call.gd", """
                class_name AwaitSignalReturnCall
                extends Node
                
                signal counted(value: int)
                
                func signal_value() -> Signal:
                    return counted
                
                func user():
                    var result: Variant = await signal_value()
                """);

        var awaitType = awaitTypeOf(analyzed, "user");
        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors(), analyzed.diagnostics()::toString),
                () -> assertEquals("Variant", awaitType.publishedType().getTypeName()),
                () -> assertTrue(coroutineNames(analyzed).contains("user")),
                () -> assertTrue(
                        diagnosticsByCategory(analyzed.diagnostics(), "sema.redundant_await").isEmpty(),
                        analyzed.diagnostics()::toString
                )
        );
    }

    /// A `-> Variant` coroutine must still mark its callers through the call route; falling into
    /// the dynamic route would skip the pending bookkeeping but must not change the outcome here.
    @Test
    void awaitVariantAnnotatedCoroutineCallMarksCallerThroughCallRoute() throws Exception {
        var analyzed = analyze("await_variant_coroutine_call.gd", """
                class_name AwaitVariantCoroutineCall
                extends Node
                
                signal pinged
                
                func inner() -> Variant:
                    await pinged
                    return 1
                
                func user():
                    var state = await inner()
                """);

        var awaitType = awaitTypeOf(analyzed, "user");
        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors(), analyzed.diagnostics()::toString),
                () -> assertEquals("Variant", awaitType.publishedType().getTypeName()),
                () -> assertTrue(coroutineNames(analyzed).contains("user")),
                () -> assertTrue(
                        diagnosticsByCategory(analyzed.diagnostics(), "sema.redundant_await").isEmpty(),
                        analyzed.diagnostics()::toString
                )
        );
    }

    /// An awaited static coroutine call is a legal await operand; the caller suspends
    /// through it, so the fixed point must mark the caller as a coroutine.
    @Test
    void analyzeForCompileAllowsAwaitedStaticCoroutineCall() throws Exception {
        var compiled = analyzeForCompile("await_gate_awaited_static.gd", """
                class_name AwaitGateAwaitedStatic
                extends Node
                
                static func worker(target):
                    await target
                
                func outer():
                    await worker(1)
                """);

        var compileDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check");
        assertAll(
                () -> assertFalse(compiled.diagnostics().hasErrors(), compiled.diagnostics()::toString),
                () -> assertTrue(
                        compileDiagnostics.stream().noneMatch(d -> d.message().contains("is a coroutine")),
                        compileDiagnostics::toString
                ),
                () -> assertTrue(coroutineNames(compiled).contains("outer"), compiled.diagnostics()::toString)
        );
    }

    /// Fail-closed regression: a static coroutine call in a value position (neither await operand
    /// nor statement root) stays rejected with Godot's `must be called with "await"` contract,
    /// exactly like an instance call.
    @Test
    void analyzeForCompileRejectsValuePositionStaticCoroutineCall() throws Exception {
        var compiled = analyzeForCompile("await_gate_value_position_static.gd", """
                class_name AwaitGateValuePositionStatic
                extends Node
                
                static func worker(target):
                    await target
                
                func outer():
                    var state = worker(1)
                """);

        var compileDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check");
        assertAll(
                () -> assertTrue(compiled.diagnostics().hasErrors(), compiled.diagnostics()::toString),
                () -> assertTrue(
                        compileDiagnostics.stream().anyMatch(d -> d.message().contains("is a coroutine")
                                && d.message().contains("worker")),
                        compileDiagnostics::toString
                )
        );
    }

    /// Static caller marking goes through the same pending route as instance callers: `outer` is
    /// resolved before the callee's coroutine marking exists, so the post-suite fixed point must
    /// propagate it.
    @Test
    void awaitStaticCoroutineCallMarksStaticCallerThroughPending() throws Exception {
        var analyzed = analyze("await_static_pending_mark.gd", """
                class_name AwaitStaticPendingMark
                extends Node
                
                signal pinged
                
                static func outer(peer: AwaitStaticPendingMark):
                    var result = await AwaitStaticPendingMark.inner(peer)
                
                static func inner(peer: AwaitStaticPendingMark) -> int:
                    await peer.pinged
                    return 1
                """);

        var coroutineNames = coroutineNames(analyzed);
        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors(), analyzed.diagnostics()::toString),
                () -> assertTrue(coroutineNames.contains("inner"), analyzed.diagnostics()::toString),
                () -> assertTrue(coroutineNames.contains("outer"), analyzed.diagnostics()::toString)
        );
    }

    /// Static-to-static two-level chain with caller-before-callee source order: `second` is only
    /// known as a coroutine after `third` propagates, so `first` requires a second fixed-point round.
    @Test
    void awaitStaticCoroutineChainPropagatesMarkingTransitively() throws Exception {
        var analyzed = analyze("await_static_chain.gd", """
                class_name AwaitStaticChain
                extends Node
                
                signal pinged
                
                static func first(target):
                    await AwaitStaticChain.second(target)
                
                static func second(target):
                    await AwaitStaticChain.third(target)
                
                static func third(target):
                    await target
                """);

        var coroutineNames = coroutineNames(analyzed);
        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors(), analyzed.diagnostics()::toString),
                () -> assertTrue(
                        coroutineNames.containsAll(List.of("first", "second", "third")),
                        analyzed.diagnostics()::toString
                )
        );
    }

    /// Mixed chain: a static caller awaiting an instance coroutine through an explicit receiver and
    /// an instance caller awaiting a static coroutine must both be marked — the fixed point no
    /// longer distinguishes the two call kinds.
    @Test
    void awaitMixedStaticInstanceChainPropagatesMarking() throws Exception {
        var analyzed = analyze("await_mixed_static_instance_chain.gd", """
                class_name AwaitMixedStaticInstanceChain
                extends Node
                
                signal pinged
                
                static func static_caller(peer: AwaitMixedStaticInstanceChain):
                    await peer.instance_leaf()
                
                func instance_caller():
                    await AwaitMixedStaticInstanceChain.static_leaf(self)
                
                static func static_leaf(target):
                    await target
                
                func instance_leaf():
                    await pinged
                """);

        var coroutineNames = coroutineNames(analyzed);
        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors(), analyzed.diagnostics()::toString),
                () -> assertTrue(
                        coroutineNames.containsAll(
                                List.of("static_caller", "instance_caller", "static_leaf", "instance_leaf")),
                        analyzed.diagnostics()::toString
                )
        );
    }

    /// Lambda bodies never inherit fire-and-forget privilege from an enclosing statement root:
    /// calls inside the lambda are value positions. (A bare lambda as a statement root is itself
    /// an unsupported frontend form, so the supported call-argument lambda shape is used here.)
    @Test
    void analyzeForCompileRejectsCoroutineCallInsideLambdaBody() throws Exception {
        var compiled = analyzeForCompile("await_gate_lambda_body.gd", """
                class_name AwaitGateLambdaBody
                extends Node
                
                signal pinged
                
                func outer():
                    pinged.connect(func():
                        var state = inner()
                    )
                
                func inner():
                    await pinged
                """);

        var compileDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check");
        assertAll(
                () -> assertTrue(compiled.diagnostics().hasErrors(), compiled.diagnostics()::toString),
                () -> assertTrue(
                        compileDiagnostics.stream().anyMatch(d -> d.message().contains("is a coroutine")
                                && d.message().contains("inner")),
                        compileDiagnostics::toString
                )
        );
    }

    /// Nested value positions (container literal item, return value) never inherit statement-root
    /// privilege.
    @Test
    void analyzeForCompileRejectsNestedValuePositionCoroutineCalls() throws Exception {
        var compiled = analyzeForCompile("await_gate_nested_value_position.gd", """
                class_name AwaitGateNestedValuePosition
                extends Node
                
                signal pinged
                
                func collect():
                    var states = [inner()]
                    return inner()
                
                func inner():
                    await pinged
                """);

        var compileDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check");
        var coroutinePositionDiagnostics = compileDiagnostics.stream()
                .filter(d -> d.message().contains("is a coroutine") && d.message().contains("inner"))
                .toList();
        assertAll(
                () -> assertTrue(compiled.diagnostics().hasErrors(), compiled.diagnostics()::toString),
                () -> assertEquals(2, coroutinePositionDiagnostics.size(), compileDiagnostics::toString)
        );
    }

    /// Releasing the await root must not hide coroutine calls nested inside the operand's argument
    /// list. Only the top-level awaited call receives the await-position exemption.
    @Test
    void analyzeForCompileRejectsCoroutineCallNestedInsideAwaitOperand() throws Exception {
        var compiled = analyzeForCompile("await_gate_nested_operand_call.gd", """
                class_name AwaitGateNestedOperandCall
                extends Node
                
                signal pinged
                
                func passthrough(value: Variant) -> Variant:
                    return value
                
                func outer():
                    await passthrough(inner())
                
                func inner():
                    await pinged
                """);

        var compileDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check");
        assertAll(
                () -> assertTrue(compiled.diagnostics().hasErrors(), compiled.diagnostics()::toString),
                () -> assertEquals(
                        1,
                        compileDiagnostics.stream()
                                .filter(d -> d.message().contains("is a coroutine")
                                        && d.message().contains("inner"))
                                .count(),
                        compileDiagnostics::toString
                )
        );
    }

    /// An intermediate chain call is a value position even when the chain itself is the statement
    /// root: `inner().name` must flag the `inner()` call step.
    @Test
    void analyzeForCompileRejectsIntermediateChainCoroutineCall() throws Exception {
        var compiled = analyzeForCompile("await_gate_intermediate_chain.gd", """
                class_name AwaitGateIntermediateChain
                extends Node
                
                signal pinged
                
                func outer():
                    inner().name
                
                func inner():
                    await pinged
                """);

        var compileDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check");
        assertAll(
                () -> assertTrue(compiled.diagnostics().hasErrors(), compiled.diagnostics()::toString),
                () -> assertTrue(
                        compileDiagnostics.stream().anyMatch(d -> d.message().contains("is a coroutine")
                                && d.message().contains("inner")),
                        compileDiagnostics::toString
                )
        );
    }

    /// The true intermediate `AttributeCallStep` path: in `self.inner().name` the `inner()` call
    /// step sits between the receiver and the trailing property step, so the nested walk must
    /// flag it as a value position while the root check sees no call anchor at all.
    @Test
    void analyzeForCompileRejectsIntermediateAttributeCallStep() throws Exception {
        var compiled = analyzeForCompile("await_gate_intermediate_call_step.gd", """
                class_name AwaitGateIntermediateCallStep
                extends Node
                
                signal pinged
                
                func outer():
                    self.inner().name
                
                func inner():
                    await pinged
                """);

        var compileDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check");
        assertAll(
                () -> assertTrue(compiled.diagnostics().hasErrors(), compiled.diagnostics()::toString),
                () -> assertTrue(
                        compileDiagnostics.stream().anyMatch(d -> d.message().contains("is a coroutine")
                                && d.message().contains("inner")),
                        compileDiagnostics::toString
                )
        );
    }

    /// The trailing call step of a statement-root chain keeps fire-and-forget privilege, and the
    /// nested re-walk must not double-report it.
    @Test
    void analyzeForCompileAllowsChainedStatementRootCoroutineCall() throws Exception {
        var compiled = analyzeForCompile("await_gate_chained_statement_root.gd", """
                class_name AwaitGateChainedStatementRoot
                extends Node
                
                signal pinged
                
                func outer():
                    self.inner()
                
                func inner():
                    await pinged
                """);

        var compileDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check");
        assertAll(
                () -> assertFalse(compiled.diagnostics().hasErrors(), compiled.diagnostics()::toString),
                () -> assertTrue(
                        compileDiagnostics.stream().noneMatch(d -> d.message().contains("is a coroutine")),
                        compileDiagnostics::toString
                )
        );
    }

    private static @NotNull FrontendExpressionType awaitTypeOf(
            @NotNull AnalyzedScript analyzed,
            @NotNull String functionName
    ) {
        var awaits = awaitExpressionsIn(analyzed, functionName);
        assertEquals(1, awaits.size(), "expected exactly one await in function '" + functionName + "'");
        var awaitType = analyzed.analysisData().expressionTypes().get(awaits.getFirst());
        assertNotNull(awaitType, "await expression must have a published type");
        return awaitType;
    }

    private static @NotNull List<AwaitExpression> awaitExpressionsIn(
            @NotNull AnalyzedScript analyzed,
            @NotNull String functionName
    ) {
        var function = findFunction(analyzed.ast(), functionName);
        assertNotNull(function, "function '" + functionName + "' must exist");
        var awaits = new java.util.ArrayList<AwaitExpression>();
        collectAwaits(function, awaits);
        return awaits;
    }

    private static void collectAwaits(@NotNull Node node, @NotNull List<AwaitExpression> awaits) {
        if (node instanceof AwaitExpression awaitExpression) {
            awaits.add(awaitExpression);
        }
        for (var child : node.getChildren()) {
            collectAwaits(child, awaits);
        }
    }

    private static @NotNull Set<String> coroutineNames(@NotNull AnalyzedScript analyzed) {
        var names = new TreeSet<String>();
        for (LirFunctionDef function : analyzed.analysisData().coroutineFunctions()) {
            names.add(function.getName());
        }
        return names;
    }

    private static @NotNull List<FrontendDiagnostic> diagnosticsByCategory(
            @NotNull DiagnosticSnapshot diagnostics,
            @NotNull String category
    ) {
        return diagnostics.asList().stream()
                .filter(diagnostic -> diagnostic.category().equals(category))
                .toList();
    }

    private static FunctionDeclaration findFunction(@NotNull Node node, @NotNull String name) {
        if (node instanceof FunctionDeclaration functionDeclaration && functionDeclaration.name().equals(name)) {
            return functionDeclaration;
        }
        for (var child : node.getChildren()) {
            var found = findFunction(child, name);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static LambdaExpression findLambda(@NotNull Node node) {
        if (node instanceof LambdaExpression lambdaExpression) {
            return lambdaExpression;
        }
        for (var child : node.getChildren()) {
            var found = findLambda(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static @NotNull AnalyzedScript analyze(
            @NotNull String fileName,
            @NotNull String source
    ) throws Exception {
        return runPipeline(fileName, source, false);
    }

    private static @NotNull AnalyzedScript analyzeForCompile(
            @NotNull String fileName,
            @NotNull String source
    ) throws Exception {
        return runPipeline(fileName, source, true);
    }

    private static @NotNull AnalyzedScript runPipeline(
            @NotNull String fileName,
            @NotNull String source,
            boolean compileMode
    ) throws Exception {
        var diagnostics = new DiagnosticManager();
        var parserService = new GdScriptParserService();
        var unit = parserService.parseUnit(Path.of("tmp", fileName), source, diagnostics);
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var analyzer = new FrontendSemanticAnalyzer();
        var module = new FrontendModule("test_module", List.of(unit));
        var analysisData = compileMode
                ? analyzer.analyzeForCompile(module, classRegistry, diagnostics)
                : analyzer.analyze(module, classRegistry, diagnostics);
        return new AnalyzedScript(unit.ast(), analysisData);
    }

    private record AnalyzedScript(
            @NotNull SourceFile ast,
            @NotNull FrontendAnalysisData analysisData
    ) {
        private @NotNull DiagnosticSnapshot diagnostics() {
            return analysisData.diagnostics();
        }
    }
}
