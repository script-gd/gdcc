package gd.script.gdcc.frontend.sema.analyzer;

import dev.superice.gdparser.frontend.ast.LambdaExpression;
import dev.superice.gdparser.frontend.ast.Node;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.frontend.sema.FrontendAnalysisData;
import gd.script.gdcc.frontend.sema.FrontendLambdaIdentity;
import gd.script.gdcc.frontend.sema.FrontendLambdaPlan;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.scope.ClassRegistry;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Lambda identity contract tests: the ordinal primary key, the normalized call-site
/// context descriptor, and the traversal domain. Each behavior is anchored in both
/// directions — what MUST stay stable across a reload, and what MUST change so stale
/// connections fail closed.
class FrontendLambdaIdentityAnalyzerTest {

    // ----- ordinal contract -----

    @Test
    void ordinalsFollowSourcePreOrderWithinEachFunction() throws Exception {
        var plans = analyze("""
                class_name LambdaOrdinalPreOrder
                extends Node

                func first():
                    var a = func():
                        return 1
                    var b = func():
                        return 2

                func second():
                    var c = func():
                        return 3
                """);
        assertEquals(3, plans.size());
        assertEquals("LambdaOrdinalPreOrder::first#0", plans.get(0).sourceIdentityKey());
        assertEquals("LambdaOrdinalPreOrder::first#1", plans.get(1).sourceIdentityKey());
        // Per-function ordinal domain: the second function restarts at 0.
        assertEquals("LambdaOrdinalPreOrder::second#0", plans.get(2).sourceIdentityKey());
    }

    @Test
    void ordinalsNumberLambdaBeforeItsNestedBody() throws Exception {
        // collectLambdaContexts parity: a lambda is numbered BEFORE recursing into its body,
        // so the outer lambda is #0 and the nested one #1 within the same function domain.
        var plans = analyze("""
                class_name LambdaOrdinalNested
                extends Node

                func ping():
                    var outer = func():
                        var inner = func():
                            return 1
                        return inner
                """);
        assertEquals(2, plans.size());
        assertEquals("LambdaOrdinalNested::ping#0", plans.get(0).sourceIdentityKey());
        assertEquals("LambdaOrdinalNested::ping#1", plans.get(1).sourceIdentityKey());
        // Nested lambdas anchor the context within their own enclosing lambda, never escaping
        // into the outer callable's statement stream.
        assertEquals("assign(var=inner, kind=var)", plans.get(1).callSiteContext());
    }

    @Test
    void constructorOrdinalsAnchorAtInit() throws Exception {
        var plans = analyze("""
                class_name LambdaOrdinalInit
                extends Node

                func _init():
                    var cb = func():
                        pass
                """);
        assertEquals(1, plans.size());
        assertEquals("LambdaOrdinalInit::_init#0", plans.getFirst().sourceIdentityKey());
    }

    @Test
    void insertingALambdaBeforeShiftsLaterOrdinals() throws Exception {
        // Fail-closed direction of the ordinal contract: a NEW lambda inserted before shifts
        // the later lambda's ordinal, so its old connection invalidates (schema/context gates
        // decide rebind vs invalidation downstream).
        var before = analyze("""
                class_name LambdaOrdinalInsert
                extends Node

                func ping():
                    var cb = func():
                        return 1
                """);
        var after = analyze("""
                class_name LambdaOrdinalInsert
                extends Node

                func ping():
                    var inserted = func():
                        return 0
                    var cb = func():
                        return 1
                """);
        assertEquals("LambdaOrdinalInsert::ping#0", before.getFirst().sourceIdentityKey());
        assertEquals("LambdaOrdinalInsert::ping#1", after.get(1).sourceIdentityKey());
        assertNotEquals(before.getFirst().sourceIdentityKey(), after.get(1).sourceIdentityKey());
    }

    @Test
    void nonLambdaEditsKeepOrdinalsStable() throws Exception {
        // Inserting NON-lambda lines before a lambda, or editing its body, keeps both the
        // key and the context — the connection rebinds.
        var before = analyze("""
                class_name LambdaOrdinalStable
                extends Node

                func ping():
                    var cb = func():
                        return 1
                """);
        var after = analyze("""
                class_name LambdaOrdinalStable
                extends Node

                func ping():
                    var noise := 1
                    print(noise)
                    var cb = func():
                        return 2
                """);
        assertEquals(before.getFirst().sourceIdentityKey(), after.getFirst().sourceIdentityKey());
        assertEquals(before.getFirst().callSiteContext(), after.getFirst().callSiteContext());
    }

    // ----- call-site context descriptors -----

    @Test
    void connectDescriptorsAnchorAtTheSignalChainPrefix() throws Exception {
        var plans = analyze("""
                class_name LambdaContextConnect
                extends Node
                signal sig_a
                signal sig_b

                func arm():
                    sig_a.connect(func():
                        pass)
                    self.sig_b.connect(func():
                        pass)
                """);
        assertEquals(2, plans.size());
        assertEquals("call(base=sig_a, method=connect, arg=0)", plans.get(0).callSiteContext());
        assertEquals("call(base=self.sig_b, method=connect, arg=0)", plans.get(1).callSiteContext());
    }

    @Test
    void getNodeChainDescriptorKeepsTheFullPrefix() throws Exception {
        var plans = analyze("""
                class_name LambdaContextGetNode
                extends Node

                func arm():
                    $Timer.timeout.connect(func():
                        pass)
                """);
        assertEquals(1, plans.size());
        assertEquals("call(base=$Timer.timeout, method=connect, arg=0)", plans.getFirst().callSiteContext());
    }

    @Test
    void plainCallDescriptorCarriesCalleeSliceAndArgIndex() throws Exception {
        var plans = analyze("""
                class_name LambdaContextPlainCall
                extends Node

                func helper(x, cb):
                    pass

                func arm():
                    helper(1, func():
                        pass)
                """);
        assertEquals(1, plans.size());
        assertEquals("call(callee=helper, arg=1)", plans.getFirst().callSiteContext());
    }

    @Test
    void assignmentExpressionDescriptorCarriesTheLhsSlice() throws Exception {
        var plans = analyze("""
                class_name LambdaContextAssignExpr
                extends Node

                var cb

                func arm():
                    cb = func():
                        pass
                    self.cb = func():
                        pass
                """);
        assertEquals(2, plans.size());
        assertEquals("assign_expr(lhs=cb, op==)", plans.get(0).callSiteContext());
        assertEquals("assign_expr(lhs=self.cb, op==)", plans.get(1).callSiteContext());
    }

    @Test
    void variableDeclarationDescriptorDistinguishesVarKind() throws Exception {
        var plans = analyze("""
                class_name LambdaContextVarDecl
                extends Node

                func arm():
                    var cb = func():
                        pass
                """);
        assertEquals(1, plans.size());
        assertEquals("assign(var=cb, kind=var)", plans.getFirst().callSiteContext());
    }

    @Test
    void awaitLambdaOperandProducesBareAwaitDescriptor() throws Exception {
        // When the awaited operand IS the lambda, the descriptor is bare `await` — slicing
        // the operand would embed the lambda body and break body-edit identity stability.
        // (Unparenthesized `await func...` is rejected by the grammar; only the parenthesized
        // form parses.) Two `await (func...)` lambdas in one function are therefore
        // indistinguishable — a documented acceptable blind spot.
        var plans = analyze("""
                class_name LambdaContextAwaitOperand
                extends Node

                func arm():
                    await (func():
                        pass)
                """);
        assertEquals(1, plans.size());
        assertEquals("await", plans.getFirst().callSiteContext());
    }

    @Test
    void awaitWrapperIsTransparentWhenInnerAnchorMatches() throws Exception {
        // Nearest-anchor rule: the inner CallExpression is closer to the lambda than the
        // AwaitExpression, so `await` never truncates a call descriptor.
        var plans = analyze("""
                class_name LambdaContextAwaitCall
                extends Node

                func helper(cb):
                    pass

                func arm():
                    await helper(func():
                        pass)
                """);
        assertEquals(1, plans.size());
        assertEquals("call(callee=helper, arg=0)", plans.getFirst().callSiteContext());
    }

    @Test
    void returnAndNestedLambdaDescriptors() throws Exception {
        var plans = analyze("""
                class_name LambdaContextReturn
                extends Node

                func make():
                    var f = func():
                        return func():
                            pass
                    return f
                """);
        // The outer lambda anchors at `var f`; the inner (returned) lambda anchors at `return`
        // — computed within the outer lambda boundary, never leaking to `make`'s statements.
        assertEquals(2, plans.size());
        assertEquals("assign(var=f, kind=var)", plans.get(0).callSiteContext());
        assertEquals("return", plans.get(1).callSiteContext());
    }

    @Test
    void arrayElementDescriptorCarriesTheIndex() throws Exception {
        var plans = analyze("""
                class_name LambdaContextArray
                extends Node

                func arm():
                    var a = [1, func():
                        pass]
                """);
        assertEquals(1, plans.size());
        assertEquals("array(idx=1)", plans.getFirst().callSiteContext());
    }

    @Test
    void formattingNeverChangesADescriptor() throws Exception {
        // Descriptors are whitespace normalized: formatting-only edits must rebind. (Comments
        // inside an expression are lowered to comment AST nodes by gdparser, so the in-slice
        // comment stripping stays a defensive normalization path.)
        var compact = analyze("""
                class_name LambdaContextNormalized
                extends Node
                signal sig_a

                func arm():
                    self.sig_a.connect(func():
                        pass)
                """);
        var spaced = analyze("""
                class_name LambdaContextNormalized
                extends Node
                signal sig_a

                func arm():
                    # pick the handler below
                    self . sig_a . connect(
                        func():
                            pass
                    )
                """);
        assertEquals(1, compact.size());
        assertEquals(1, spaced.size());
        assertEquals(compact.getFirst().callSiteContext(), spaced.getFirst().callSiteContext());
    }

    // ----- swap disambiguation (the fixed blind spot) -----

    @Test
    void distinguishableCallsiteSwapChangesContexts() throws Exception {
        // Two same-schema lambdas swapped between different signals. The impl_key ordinals
        // collide (same function, same slots), but the contexts differ, so the runtime must
        // fail closed instead of rebinding to each other's bodies.
        var v1 = analyze("""
                class_name LambdaContextSwap
                extends Node
                signal sig_a
                signal sig_b

                func arm():
                    sig_a.connect(func(value: int):
                        pass)
                    sig_b.connect(func(value: int):
                        pass)
                """);
        var v2 = analyze("""
                class_name LambdaContextSwap
                extends Node
                signal sig_a
                signal sig_b

                func arm():
                    sig_b.connect(func(value: int):
                        pass)
                    sig_a.connect(func(value: int):
                        pass)
                """);
        // Same keys after the swap (ordinal slots collided)…
        assertEquals(v1.get(0).sourceIdentityKey(), v2.get(0).sourceIdentityKey());
        assertEquals(v1.get(1).sourceIdentityKey(), v2.get(1).sourceIdentityKey());
        // …but the contexts diverge: v1's sig_a-slot now reads sig_b and vice versa.
        assertEquals("call(base=sig_a, method=connect, arg=0)", v1.get(0).callSiteContext());
        assertEquals("call(base=sig_b, method=connect, arg=0)", v2.get(0).callSiteContext());
        assertNotEquals(v1.get(0).callSiteContext(), v2.get(0).callSiteContext());
        assertNotEquals(v1.get(1).callSiteContext(), v2.get(1).callSiteContext());
    }

    @Test
    void identicalFormSwapKeepsIdenticalDescriptors() throws Exception {
        // Documented residual blind spot: two connects to the SAME signal with identical
        // shapes are indistinguishable — this test pins the current (intentional) limitation
        // so any future fix changes this expectation loudly.
        var v1 = analyze("""
                class_name LambdaContextSameForm
                extends Node
                signal sig_a

                func arm():
                    sig_a.connect(func(value: int):
                        pass)
                    sig_a.connect(func(value: int):
                        pass)
                """);
        var v2 = analyze("""
                class_name LambdaContextSameForm
                extends Node
                signal sig_a

                func arm():
                    sig_a.connect(func(value: int):
                        pass)
                    sig_a.connect(func(value: int):
                        pass)
                """);
        assertEquals(v1.get(0).sourceIdentityKey(), v2.get(0).sourceIdentityKey());
        assertEquals(v1.get(0).callSiteContext(), v2.get(0).callSiteContext());
    }

    @Test
    void extractRefactorChangesContextFailClosed() throws Exception {
        // Documented false-invalidation: extracting the lambda into a local changes the
        // descriptor from call(...) to assign(...) — the old connection invalidates instead of
        // rebinding (fail-closed, never a wrong-body rebind).
        var inline = analyze("""
                class_name LambdaContextExtract
                extends Node
                signal sig_a

                func arm():
                    sig_a.connect(func():
                        pass)
                """);
        var extracted = analyze("""
                class_name LambdaContextExtract
                extends Node
                signal sig_a

                func arm():
                    var cb = func():
                        pass
                    sig_a.connect(cb)
                """);
        assertEquals(1, inline.size());
        assertEquals(1, extracted.size());
        // Same ordinal (still the first lambda in `arm`)…
        assertEquals(inline.getFirst().sourceIdentityKey(), extracted.getFirst().sourceIdentityKey());
        // …but the anchor moved: invalidation, not rebind.
        assertNotEquals(inline.getFirst().callSiteContext(), extracted.getFirst().callSiteContext());
    }

    @Test
    void callSiteContextIsBodyIndependent() throws Exception {
        // The descriptor must NOT embed the lambda body: editing the body rebinds (never
        // invalidates). Only the anchors participate.
        var before = analyze("""
                class_name LambdaContextBodyIndependent
                extends Node
                signal sig_a

                func arm():
                    sig_a.connect(func():
                        return 1)
                """);
        var after = analyze("""
                class_name LambdaContextBodyIndependent
                extends Node
                signal sig_a

                func arm():
                    sig_a.connect(func():
                        print("totally different body")
                        return 2)
                """);
        assertEquals(before.getFirst().sourceIdentityKey(), after.getFirst().sourceIdentityKey());
        assertEquals(before.getFirst().callSiteContext(), after.getFirst().callSiteContext());
    }

    // ----- traversal domain -----

    @Test
    void propertyInitializerLambdasAreNotNumbered() throws Exception {
        // Property initializers never record lambdas (fail-closed upstream); the identity pass
        // must not spend ordinals on them, keeping the ordinal domain equal to the plan set.
        var result = analyzeRaw("""
                class_name LambdaDomainPropertyInit
                extends Node

                var cb = func():
                    return 1

                func ping():
                    var local = func():
                        return 2
                """);
        assertEquals(1, result.plans().size());
        assertEquals("LambdaDomainPropertyInit::ping#0", result.plans().getFirst().sourceIdentityKey());
        // The pass skips the property initializer subtree entirely.
        assertEquals(1, result.identityCount());
    }

    @Test
    void parameterDefaultLambdasAreNotNumbered() throws Exception {
        // Parameter defaults stay fail-closed upstream (binding analysis unsupported); the
        // identity pass must not number them either.
        var result = analyzeRaw("""
                class_name LambdaDomainParamDefault
                extends Node

                func ping(cb = func():
                    return 0):
                    var local = func():
                        return 1
                """);
        assertEquals(1, result.plans().size());
        assertEquals("LambdaDomainParamDefault::ping#0", result.plans().getFirst().sourceIdentityKey());
    }

    @Test
    void lambdasAcrossClassesGetIndependentIdentityPaths() throws Exception {
        var plans = analyze("""
                class_name LambdaDomainOuter
                extends Node

                class Inner:
                    func go():
                        var inner_cb = func():
                            return 1

                func ping():
                    var outer_cb = func():
                        return 2
                """);
        assertEquals(2, plans.size());
        // Class name anchors the key, so same-named functions in different classes never share.
        var keys = plans.stream().map(FrontendLambdaPlan::sourceIdentityKey).toList();
        assertTrue(keys.stream().anyMatch(key -> key.endsWith("::ping#0")), keys::toString);
        assertTrue(keys.stream().anyMatch(key -> key.endsWith("::go#0")), keys::toString);
    }

    // ----- pass self-consistency -----

    @Test
    void analyzerCoversExactlyTheRecordedLambdaSet() throws Exception {
        // Every recorded lambda (published plan) must have an identity entry and vice versa —
        // the resolver fails fast on a miss, so the pass must be total over the recorded set.
        var result = analyzeRaw("""
                class_name LambdaCoverage
                extends Node
                signal sig

                func ping():
                    sig.connect(func():
                        pass)
                    var cb = func():
                        return func():
                            pass
                    return cb
                """);
        assertEquals(3, result.plans().size());
        assertEquals(3, result.identityCount());
    }

    @Test
    void identityRecordRejectsBadValues() throws Exception {
        assertThrows(
                IllegalArgumentException.class,
                () -> new FrontendLambdaIdentity(-1, "return")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new FrontendLambdaIdentity(0, "  ")
        );
        assertThrows(
                NullPointerException.class,
                () -> new FrontendLambdaIdentity(0, null)
        );
    }

    // ----- fixture helpers -----

    private record AnalyzedModule(@NotNull FrontendAnalysisData analysisData, @NotNull List<FrontendLambdaPlan> plans) {
        private int identityCount() {
            // Reads the table published by the semantic pipeline, NOT a re-run of the pass.
            return analysisData.lambdaIdentities().size();
        }
    }

    private static @NotNull List<FrontendLambdaPlan> analyze(@NotNull String source) throws Exception {
        return analyzeRaw(source).plans();
    }

    private static @NotNull AnalyzedModule analyzeRaw(@NotNull String source) throws Exception {
        var diagnostics = new DiagnosticManager();
        var unit = new GdScriptParserService().parseUnit(Path.of("tmp", "lambda_identity_test.gd"), source, diagnostics);
        assertTrue(diagnostics.isEmpty(), () -> "parse diagnostics: " + diagnostics.snapshot());
        var analysisData = new FrontendSemanticAnalyzer().analyze(
                new FrontendModule("test_module", List.of(unit)),
                new ClassRegistry(ExtensionApiLoader.loadDefault()),
                diagnostics
        );
        var lambdas = new ArrayList<LambdaExpression>();
        collectLambdas(unit.ast(), lambdas);
        var plans = lambdas.stream()
                .map(lambda -> analysisData.lambdaPlans().get(lambda))
                .filter(Objects::nonNull)
                .toList();
        return new AnalyzedModule(analysisData, plans);
    }

    private static void collectLambdas(@NotNull Node node, @NotNull List<LambdaExpression> out) {
        if (node instanceof LambdaExpression lambda) {
            out.add(lambda);
        }
        for (var child : node.getChildren()) {
            collectLambdas(child, out);
        }
    }
}
