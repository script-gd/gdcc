package gd.script.gdcc.frontend.sema;

import dev.superice.gdparser.frontend.ast.AttributeCallStep;
import dev.superice.gdparser.frontend.ast.AttributePropertyStep;
import dev.superice.gdparser.frontend.ast.AttributeSubscriptStep;
import dev.superice.gdparser.frontend.ast.DeclarationKind;
import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.Point;
import dev.superice.gdparser.frontend.ast.Range;
import dev.superice.gdparser.frontend.ast.TypeRef;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import gd.script.gdcc.exception.FrontendAnalysisPatchException;
import gd.script.gdcc.frontend.diagnostic.DiagnosticSnapshot;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnostic;
import gd.script.gdcc.frontend.scope.BlockScope;
import gd.script.gdcc.frontend.scope.BlockScopeKind;
import gd.script.gdcc.frontend.scope.CallableScope;
import gd.script.gdcc.frontend.scope.CallableScopeKind;
import gd.script.gdcc.frontend.scope.ClassScope;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.ScopeLookupStatus;
import gd.script.gdcc.scope.ScopeOwnerKind;
import gd.script.gdcc.scope.ScopeValue;
import gd.script.gdcc.type.GdccForRangeIterType;
import gd.script.gdcc.type.GdFloatType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdVariantType;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// API-level coverage for the legacy window publication shim.
///
/// These tests prove direct `FrontendWindowPublicationSurface` scratch writes are isolated and
/// guarded. They must not be read as proof that every legacy `analyzeInWindow(...)` implementation
/// is scratch-safe; `FrontendVarTypePostAnalyzer.analyzeInWindow(...)` is documented separately as a
/// stable `slotTypes()` contamination path until the SuiteResolver rewrite replaces it.
class FrontendWindowPublicationSurfaceTest {
    private static final Range RANGE = new Range(0, 1, new Point(0, 0), new Point(0, 1));

    @Test
    void windowContextKeepsStableDataAndEffectiveReadsPreferScratch() {
        var analysisData = FrontendAnalysisData.bootstrap();
        var stableNode = identifier("stable_value");
        var fallbackNode = identifier("fallback_value");
        var stableType = FrontendExpressionType.resolved(GdIntType.INT);
        var fallbackType = FrontendExpressionType.failed("stable only");
        analysisData.expressionTypes().put(stableNode, stableType);
        analysisData.expressionTypes().put(fallbackNode, fallbackType);

        var window = new FrontendWindowAnalysisContext(analysisData);
        var scratchType = FrontendExpressionType.resolved(new GdIntType());
        window.publications().expressionTypes().put(stableNode, scratchType);

        assertSame(analysisData, window.stableData());
        assertSame(scratchType, window.publications().expressionTypes().get(stableNode));
        assertSame(scratchType, window.publications().expressionTypes().getScratch(stableNode));
        assertSame(stableType, window.publications().expressionTypes().getStable(stableNode));
        assertSame(fallbackType, window.publications().expressionTypes().get(fallbackNode));
    }

    @Test
    void scratchWritesDoNotMutateStableSideTablesBeforeCommit() {
        var analysisData = FrontendAnalysisData.bootstrap();
        var window = new FrontendWindowAnalysisContext(analysisData);
        var bindingNode = identifier("local_use");
        var callNode = call("move", identifier("distance"));
        var expressionNode = identifier("value");

        window.publications().symbolBindings().put(
                bindingNode,
                new FrontendBinding("self", FrontendBindingKind.SELF, null)
        );
        window.publications().resolvedCalls().put(
                callNode,
                FrontendResolvedCall.resolved(
                        "move",
                        FrontendCallResolutionKind.INSTANCE_METHOD,
                        FrontendReceiverKind.INSTANCE,
                        ScopeOwnerKind.GDCC,
                        new GdObjectType("Player"),
                        GdIntType.INT,
                        List.of(GdIntType.INT),
                        "Player.move"
                )
        );
        window.publications().expressionTypes().put(expressionNode, FrontendExpressionType.resolved(GdIntType.INT));

        assertNull(analysisData.symbolBindings().get(bindingNode));
        assertNull(analysisData.resolvedCalls().get(callNode));
        assertNull(analysisData.expressionTypes().get(expressionNode));
        assertTrue(window.publications().symbolBindings().containsKey(bindingNode));
        assertTrue(window.publications().resolvedCalls().containsKey(callNode));
        assertTrue(window.publications().expressionTypes().containsKey(expressionNode));
    }

    @Test
    void toPatchCopiesOnlyScratchFactsWithoutStableFallbackEntries() {
        var analysisData = FrontendAnalysisData.bootstrap();
        var stableNode = identifier("published");
        var scratchNode = identifier("pending");
        analysisData.expressionTypes().put(stableNode, FrontendExpressionType.resolved(GdIntType.INT));

        var window = new FrontendWindowAnalysisContext(analysisData);
        var scratchType = FrontendExpressionType.dynamic("retry result");
        window.publications().expressionTypes().put(scratchNode, scratchType);

        var patch = window.toPatch(FrontendSemanticStage.EXPR_TYPE);

        assertNull(patch.expressionTypes().get(stableNode));
        assertSame(scratchType, patch.expressionTypes().get(scratchNode));
        assertSame(analysisData.expressionTypes().get(stableNode), window.publications().expressionTypes().get(stableNode));
    }

    @Test
    void toPatchRejectsNonLocalStabilizationSlotUpdateOwner() throws Exception {
        var analysisData = FrontendAnalysisData.bootstrap();
        var window = new FrontendWindowAnalysisContext(analysisData);
        var localScope = newBodyScope();
        var declaration = variable("local");
        localScope.defineLocal("local", GdVariantType.VARIANT, declaration);
        window.publications().addLocalSlotTypeUpdate(
                new FrontendLocalSlotTypeUpdate(localScope, "local", declaration, GdIntType.INT)
        );

        assertThrows(
                FrontendAnalysisPatchException.class,
                () -> window.toPatch(FrontendSemanticStage.EXPR_TYPE)
        );
        assertSame(GdVariantType.VARIANT, requireLocal(localScope, "local").type());
    }

    @Test
    void discardDropsScratchFactsWithoutTouchingStableDiagnosticsOrScope() throws Exception {
        var analysisData = FrontendAnalysisData.bootstrap();
        var diagnostics = new DiagnosticSnapshot(List.of(
                FrontendDiagnostic.warning("sema.window", "window warning", null, null)
        ));
        analysisData.updateDiagnostics(diagnostics);
        var localScope = newBodyScope();
        var declaration = variable("local");
        localScope.defineLocal("local", GdVariantType.VARIANT, declaration);
        var expressionNode = identifier("pending_value");

        var window = new FrontendWindowAnalysisContext(analysisData);
        window.publications().expressionTypes().put(expressionNode, FrontendExpressionType.resolved(GdIntType.INT));
        window.publications().addLocalSlotTypeUpdate(
                new FrontendLocalSlotTypeUpdate(localScope, "local", declaration, GdIntType.INT)
        );
        window.discard();

        assertSame(diagnostics, analysisData.diagnostics());
        assertNull(analysisData.expressionTypes().get(expressionNode));
        assertTrue(analysisData.symbolBindings().isEmpty());
        assertSame(GdVariantType.VARIANT, requireLocal(localScope, "local").type());
    }

    @Test
    void sameKeyIdempotentWriteCanShadowStableFactWithoutReplacingItOnCommit() {
        var analysisData = FrontendAnalysisData.bootstrap();
        var expressionNode = identifier("value");
        var stableType = FrontendExpressionType.resolved(GdIntType.INT);
        analysisData.expressionTypes().put(expressionNode, stableType);

        var window = new FrontendWindowAnalysisContext(analysisData);
        var scratchType = FrontendExpressionType.resolved(new GdIntType());
        window.publications().expressionTypes().put(expressionNode, scratchType);
        analysisData.applyPatch(window.drainPatch(FrontendSemanticStage.EXPR_TYPE));

        assertSame(stableType, window.publications().expressionTypes().getStable(expressionNode));
        assertSame(stableType, analysisData.expressionTypes().get(expressionNode));
    }

    @Test
    void sameKeyConflictsRejectStableShadowingAndNegativeToSuccessOverride() {
        var analysisData = FrontendAnalysisData.bootstrap();
        var stableNode = identifier("value");
        var failedNode = identifier("failed");
        var slotNode = variable("slot");
        analysisData.expressionTypes().put(stableNode, FrontendExpressionType.resolved(GdIntType.INT));
        analysisData.expressionTypes().put(failedNode, FrontendExpressionType.failed("original failure"));
        analysisData.slotTypes().put(slotNode, GdIntType.INT);

        var window = new FrontendWindowAnalysisContext(analysisData);

        assertThrows(
                FrontendAnalysisPatchException.class,
                () -> window.publications().expressionTypes().put(stableNode, FrontendExpressionType.resolved(GdFloatType.FLOAT))
        );
        assertThrows(
                FrontendAnalysisPatchException.class,
                () -> window.publications().expressionTypes().put(failedNode, FrontendExpressionType.resolved(GdIntType.INT))
        );
        assertThrows(
                FrontendAnalysisPatchException.class,
                () -> window.publications().slotTypes().put(slotNode, GdFloatType.FLOAT)
        );
    }

    @Test
    void finalRetryFactsStayScratchLocalUntilPatchCommit() {
        var analysisData = FrontendAnalysisData.bootstrap();
        var expressionNode = identifier("retry_result");
        var window = new FrontendWindowAnalysisContext(analysisData);
        var finalizedType = FrontendExpressionType.resolved(GdIntType.INT);

        window.publications().expressionTypes().put(expressionNode, finalizedType);

        assertSame(finalizedType, window.publications().expressionTypes().get(expressionNode));
        assertNull(analysisData.expressionTypes().get(expressionNode));

        var patch = window.drainPatch(FrontendSemanticStage.EXPR_TYPE);
        assertNull(analysisData.expressionTypes().get(expressionNode));
        analysisData.applyPatch(patch);

        assertSame(finalizedType, analysisData.expressionTypes().get(expressionNode));
    }

    @Test
    void attributeStepKeysKeepIdentityLookupAndDuplicateGuards() {
        var analysisData = FrontendAnalysisData.bootstrap();
        var window = new FrontendWindowAnalysisContext(analysisData);
        var propertyStep = property("marker");
        var callStep = call("fetch", identifier("seed"));
        var subscriptStep = subscript("items", identifier("index"));
        var propertyMember = FrontendResolvedMember.resolved(
                "marker",
                FrontendBindingKind.PROPERTY,
                FrontendReceiverKind.INSTANCE,
                ScopeOwnerKind.GDCC,
                new GdObjectType("Player"),
                GdIntType.INT,
                "Player.marker"
        );
        var resolvedCall = FrontendResolvedCall.resolved(
                "fetch",
                FrontendCallResolutionKind.INSTANCE_METHOD,
                FrontendReceiverKind.INSTANCE,
                ScopeOwnerKind.GDCC,
                new GdObjectType("Player"),
                GdIntType.INT,
                List.of(GdVariantType.VARIANT),
                "Player.fetch"
        );

        window.publications().resolvedMembers().put(propertyStep, propertyMember);
        window.publications().resolvedCalls().put(callStep, resolvedCall);
        window.publications().expressionTypes().put(subscriptStep, FrontendExpressionType.resolved(GdIntType.INT));
        window.publications().expressionTypes().put(subscriptStep, FrontendExpressionType.resolved(new GdIntType()));

        assertSame(propertyMember, window.publications().resolvedMembers().get(propertyStep));
        assertSame(resolvedCall, window.publications().resolvedCalls().get(callStep));
        assertNull(window.publications().resolvedMembers().get(property("marker")));
        assertNull(window.publications().resolvedCalls().get(call("fetch", identifier("seed"))));
        assertThrows(
                FrontendAnalysisPatchException.class,
                () -> window.publications().expressionTypes().put(subscriptStep, FrontendExpressionType.resolved(GdFloatType.FLOAT))
        );
    }

    @Test
    void compilerOnlyTypesAreRejectedBeforeCommit() throws Exception {
        var analysisData = FrontendAnalysisData.bootstrap();
        var window = new FrontendWindowAnalysisContext(analysisData);

        assertThrows(
                FrontendAnalysisPatchException.class,
                () -> window.publications().expressionTypes().put(
                        identifier("iter"),
                        FrontendExpressionType.resolved(GdccForRangeIterType.FOR_RANGE_ITER)
                )
        );
        assertThrows(
                FrontendAnalysisPatchException.class,
                () -> window.publications().slotTypes().put(variable("iter_slot"), GdccForRangeIterType.FOR_RANGE_ITER)
        );

        var localScope = newBodyScope();
        var declaration = variable("local");
        localScope.defineLocal("local", GdVariantType.VARIANT, declaration);
        assertThrows(
                FrontendAnalysisPatchException.class,
                () -> window.publications().addLocalSlotTypeUpdate(
                        new FrontendLocalSlotTypeUpdate(
                                localScope,
                                "local",
                                declaration,
                                GdccForRangeIterType.FOR_RANGE_ITER
                        )
                )
        );
    }

    @Test
    void localSlotUpdatesRemainIsolatedUntilPatchApply() throws Exception {
        var analysisData = FrontendAnalysisData.bootstrap();
        var localScope = newBodyScope();
        var declaration = variable("local");
        localScope.defineLocal("local", GdVariantType.VARIANT, declaration);
        var bindingNode = identifier("local_use");
        var originalBinding = localBinding("local", declaration, requireLocal(localScope, "local"));
        analysisData.symbolBindings().put(bindingNode, originalBinding);

        var window = new FrontendWindowAnalysisContext(analysisData);
        window.publications().addLocalSlotTypeUpdate(
                new FrontendLocalSlotTypeUpdate(localScope, "local", declaration, GdIntType.INT)
        );

        assertSame(GdVariantType.VARIANT, requireLocal(localScope, "local").type());
        assertSame(originalBinding, analysisData.symbolBindings().get(bindingNode));

        var patch = window.toPatch(FrontendSemanticStage.LOCAL_TYPE_STABILIZATION);

        assertSame(GdVariantType.VARIANT, requireLocal(localScope, "local").type());
        assertSame(originalBinding, analysisData.symbolBindings().get(bindingNode));
        analysisData.applyPatch(patch);

        var refreshedBinding = analysisData.symbolBindings().get(bindingNode);
        var refreshedValue = Objects.requireNonNull(refreshedBinding).resolvedValue();
        assertNotSame(originalBinding, refreshedBinding);
        assertSame(GdIntType.INT, Objects.requireNonNull(refreshedValue).type());
        assertSame(GdIntType.INT, requireLocal(localScope, "local").type());
    }

    private static @NotNull IdentifierExpression identifier(@NotNull String name) {
        return new IdentifierExpression(name, RANGE);
    }

    private static @NotNull VariableDeclaration variable(@NotNull String name) {
        return new VariableDeclaration(
                DeclarationKind.VAR,
                name,
                new TypeRef(":=", RANGE),
                null,
                false,
                "variable_declaration",
                RANGE
        );
    }

    private static @NotNull AttributePropertyStep property(@NotNull String name) {
        return new AttributePropertyStep(name, RANGE);
    }

    private static @NotNull AttributeCallStep call(@NotNull String name, @NotNull Expression... arguments) {
        return new AttributeCallStep(name, List.of(arguments), RANGE);
    }

    private static @NotNull AttributeSubscriptStep subscript(@NotNull String name, @NotNull Expression... arguments) {
        return new AttributeSubscriptStep(name, List.of(arguments), RANGE);
    }

    private static @NotNull FrontendBinding localBinding(
            @NotNull String name,
            @NotNull Object declaration,
            @NotNull ScopeValue value
    ) {
        return new FrontendBinding(
                name,
                FrontendBindingKind.LOCAL_VAR,
                declaration,
                value,
                ScopeLookupStatus.FOUND_ALLOWED
        );
    }

    private static @NotNull ScopeValue requireLocal(@NotNull BlockScope scope, @NotNull String name) {
        var value = scope.resolveValueHere(name);
        if (value == null) {
            throw new IllegalStateException("missing local '" + name + "'");
        }
        return value;
    }

    private static @NotNull BlockScope newBodyScope() throws Exception {
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var ownerClass = new LirClassDef("SyntheticOwner", "RefCounted");
        var classScope = new ClassScope(registry, registry, ownerClass);
        var callableScope = new CallableScope(classScope, CallableScopeKind.FUNCTION_DECLARATION);
        return new BlockScope(callableScope, BlockScopeKind.FUNCTION_BODY);
    }
}
