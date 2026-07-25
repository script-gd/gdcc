package gd.script.gdcc.frontend.sema;

import dev.superice.gdparser.frontend.ast.Block;
import dev.superice.gdparser.frontend.ast.CallExpression;
import dev.superice.gdparser.frontend.ast.DeclarationKind;
import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.ForStatement;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.LiteralExpression;
import gd.script.gdcc.frontend.diagnostic.DiagnosticSnapshot;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnostic;
import gd.script.gdcc.exception.FrontendAnalysisPatchException;
import gd.script.gdcc.frontend.sema.patch.FrontendChainBindingPatch;
import gd.script.gdcc.frontend.sema.patch.FrontendExprTypePatch;
import gd.script.gdcc.frontend.sema.patch.FrontendForIterationResolutionPatch;
import gd.script.gdcc.frontend.sema.patch.FrontendLocalSlotTypeUpdate;
import gd.script.gdcc.frontend.sema.patch.FrontendLocalTypeStabilizationPatch;
import gd.script.gdcc.frontend.sema.patch.FrontendOwnerPatch;
import gd.script.gdcc.frontend.sema.patch.FrontendPatchTransaction;
import gd.script.gdcc.frontend.sema.patch.FrontendTopBindingPatch;
import gd.script.gdcc.frontend.sema.patch.FrontendVarTypePostPatch;
import gd.script.gdcc.frontend.scope.BlockScope;
import gd.script.gdcc.frontend.scope.BlockScopeKind;
import gd.script.gdcc.frontend.scope.CallableScope;
import gd.script.gdcc.frontend.scope.CallableScopeKind;
import gd.script.gdcc.frontend.scope.ClassScope;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.Scope;
import gd.script.gdcc.scope.ScopeLookupStatus;
import gd.script.gdcc.scope.ScopeValue;
import gd.script.gdcc.scope.ScopeOwnerKind;
import gd.script.gdcc.scope.ScopeValueKind;
import gd.script.gdcc.type.GdccForRangeIterType;
import gd.script.gdcc.type.GdFloatType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdVoidType;
import dev.superice.gdparser.frontend.ast.PassStatement;
import dev.superice.gdparser.frontend.ast.Point;
import dev.superice.gdparser.frontend.ast.Range;
import dev.superice.gdparser.frontend.ast.TypeRef;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendAnalysisDataTest {
    private static final Range RANGE = new Range(0, 1, new Point(0, 0), new Point(0, 1));

    @Test
    void bootstrapCreatesAllSideTablesBeforeAnyPhaseBoundaryIsPublished() {
        var analysisData = FrontendAnalysisData.bootstrap();

        assertTrue(analysisData.annotationsByAst().isEmpty());
        assertTrue(analysisData.skippedSubtreeRoots().isEmpty());
        assertTrue(analysisData.scopesByAst().isEmpty());
        assertTrue(analysisData.symbolBindings().isEmpty());
        assertTrue(analysisData.expressionTypes().isEmpty());
        assertTrue(analysisData.resolvedMembers().isEmpty());
        assertTrue(analysisData.resolvedCalls().isEmpty());
        assertTrue(analysisData.slotTypes().isEmpty());
        assertThrows(IllegalStateException.class, analysisData::moduleSkeleton);
        assertThrows(IllegalStateException.class, analysisData::diagnostics);
    }

    @Test
    void updatePublishedFieldsMakesSkeletonAndDiagnosticsReadable() {
        var analysisData = FrontendAnalysisData.bootstrap();
        var diagnostics = new DiagnosticSnapshot(List.of(
                FrontendDiagnostic.warning("sema.unsupported_annotation", "warning", null, null)
        ));
        var moduleSkeleton = new FrontendModuleSkeleton("test_module", List.of(), Map.of(), diagnostics);

        analysisData.updateModuleSkeleton(moduleSkeleton);
        analysisData.updateDiagnostics(diagnostics);

        assertSame(moduleSkeleton, analysisData.moduleSkeleton());
        assertEquals(diagnostics, analysisData.diagnostics());
    }

    @Test
    void updateAnnotationsByAstCopiesContentsWithoutReplacingStableSideTableReference() {
        var analysisData = FrontendAnalysisData.bootstrap();
        var originalSideTable = analysisData.annotationsByAst();
        var replacement = new FrontendAstSideTable<List<FrontendGdAnnotation>>();
        var astNode = passNode();
        var annotation = new FrontendGdAnnotation("tool", List.of(), null);
        replacement.put(astNode, List.of(annotation));

        analysisData.updateAnnotationsByAst(replacement);

        assertSame(originalSideTable, analysisData.annotationsByAst());
        assertEquals(List.of(annotation), analysisData.annotationsByAst().get(astNode));
    }

    @Test
    void updateScopesByAstCopiesContentsWithoutReplacingStableSideTableReference() throws Exception {
        var analysisData = FrontendAnalysisData.bootstrap();
        var originalSideTable = analysisData.scopesByAst();
        var replacement = new FrontendAstSideTable<Scope>();
        var astNode = passNode();
        var scope = new ClassRegistry(ExtensionApiLoader.loadDefault());
        replacement.put(astNode, scope);

        analysisData.updateScopesByAst(replacement);

        assertSame(originalSideTable, analysisData.scopesByAst());
        assertSame(scope, analysisData.scopesByAst().get(astNode));
    }

    @Test
    void updateSymbolBindingsClearsStaleEntriesWithoutReplacingStableSideTableReference() {
        var analysisData = FrontendAnalysisData.bootstrap();
        var originalSideTable = analysisData.symbolBindings();
        var staleNode = passNode();
        var freshNode = passNode();
        originalSideTable.put(staleNode, new FrontendBinding("stale", FrontendBindingKind.UNKNOWN, null));

        var replacement = new FrontendAstSideTable<FrontendBinding>();
        var publishedBinding = new FrontendBinding("self", FrontendBindingKind.SELF, null);
        replacement.put(freshNode, publishedBinding);

        analysisData.updateSymbolBindings(replacement);

        assertSame(originalSideTable, analysisData.symbolBindings());
        assertNull(analysisData.symbolBindings().get(staleNode));
        assertSame(publishedBinding, analysisData.symbolBindings().get(freshNode));
    }

    @Test
    void updateResolvedMembersClearsStaleEntriesWithoutReplacingStableSideTableReference() {
        var analysisData = FrontendAnalysisData.bootstrap();
        var originalSideTable = analysisData.resolvedMembers();
        var staleNode = passNode();
        var freshNode = passNode();
        originalSideTable.put(
                staleNode,
                FrontendResolvedMember.failed(
                        "hp",
                        FrontendBindingKind.PROPERTY,
                        FrontendReceiverKind.INSTANCE,
                        ScopeOwnerKind.GDCC,
                        new GdObjectType("Player"),
                        "Player.hp",
                        "stale failure"
                )
        );

        var replacement = new FrontendAstSideTable<FrontendResolvedMember>();
        var publishedMember = FrontendResolvedMember.resolved(
                "hp",
                FrontendBindingKind.PROPERTY,
                FrontendReceiverKind.INSTANCE,
                ScopeOwnerKind.GDCC,
                new GdObjectType("Player"),
                GdIntType.INT,
                "Player.hp"
        );
        replacement.put(freshNode, publishedMember);

        analysisData.updateResolvedMembers(replacement);

        assertSame(originalSideTable, analysisData.resolvedMembers());
        assertNull(analysisData.resolvedMembers().get(staleNode));
        assertSame(publishedMember, analysisData.resolvedMembers().get(freshNode));
    }

    @Test
    void updateExpressionTypesClearsStaleEntriesWithoutReplacingStableSideTableReference() {
        var analysisData = FrontendAnalysisData.bootstrap();
        var originalSideTable = analysisData.expressionTypes();
        var staleNode = passNode();
        var freshNode = passNode();
        originalSideTable.put(staleNode, FrontendExpressionType.failed("stale failure"));

        var replacement = new FrontendAstSideTable<FrontendExpressionType>();
        var publishedType = FrontendExpressionType.dynamic("runtime fallback");
        replacement.put(freshNode, publishedType);

        analysisData.updateExpressionTypes(replacement);

        var publishedExpressionType = analysisData.expressionTypes().get(freshNode);
        assertSame(originalSideTable, analysisData.expressionTypes());
        assertNull(analysisData.expressionTypes().get(staleNode));
        assertSame(publishedType, publishedExpressionType);
        assertSame(GdVariantType.VARIANT, publishedType.publishedType());
    }

    @Test
    void updateResolvedCallsClearsStaleEntriesWithoutReplacingStableSideTableReference() {
        var analysisData = FrontendAnalysisData.bootstrap();
        var originalSideTable = analysisData.resolvedCalls();
        var staleNode = passNode();
        var freshNode = passNode();
        originalSideTable.put(
                staleNode,
                FrontendResolvedCall.failed(
                        "move",
                        FrontendCallResolutionKind.INSTANCE_METHOD,
                        FrontendReceiverKind.INSTANCE,
                        ScopeOwnerKind.GDCC,
                        new GdObjectType("Player"),
                        List.of(GdIntType.INT),
                        "Player.move",
                        "stale failure"
                )
        );

        var replacement = new FrontendAstSideTable<FrontendResolvedCall>();
        var publishedCall = FrontendResolvedCall.resolved(
                "move",
                FrontendCallResolutionKind.INSTANCE_METHOD,
                FrontendReceiverKind.INSTANCE,
                ScopeOwnerKind.GDCC,
                new GdObjectType("Player"),
                GdIntType.INT,
                List.of(GdIntType.INT),
                "Player.move"
        );
        replacement.put(freshNode, publishedCall);

        analysisData.updateResolvedCalls(replacement);

        assertSame(originalSideTable, analysisData.resolvedCalls());
        assertNull(analysisData.resolvedCalls().get(staleNode));
        assertSame(publishedCall, analysisData.resolvedCalls().get(freshNode));
    }

    @Test
    void updateSlotTypesClearsStaleEntriesWithoutReplacingStableSideTableReference() {
        var analysisData = FrontendAnalysisData.bootstrap();
        var originalSideTable = analysisData.slotTypes();
        var staleNode = passNode();
        var freshNode = passNode();
        originalSideTable.put(staleNode, GdVariantType.VARIANT);

        var replacement = new FrontendAstSideTable<GdType>();
        replacement.put(freshNode, GdIntType.INT);

        analysisData.updateSlotTypes(replacement);

        assertSame(originalSideTable, analysisData.slotTypes());
        assertNull(analysisData.slotTypes().get(staleNode));
        assertEquals(GdIntType.INT, analysisData.slotTypes().get(freshNode));
    }

    @Test
    void ownerPatchCopiesSourceTablesAtConstructionTime() {
        var bindingNode = identifier("value");
        var symbolBindings = new FrontendAstSideTable<FrontendBinding>();
        var binding = new FrontendBinding("self", FrontendBindingKind.SELF, null);
        symbolBindings.put(bindingNode, binding);

        var patch = new FrontendTopBindingPatch(symbolBindings);
        symbolBindings.clear();

        assertSame(binding, patch.symbolBindings().get(bindingNode));
        assertTrue(symbolBindings.isEmpty());
    }

    @Test
    void applyPatchPublishesNewFactsWithoutReplacingStableSideTableReferences() {
        var analysisData = FrontendAnalysisData.bootstrap();
        var symbolBindingsRef = analysisData.symbolBindings();
        var resolvedMembersRef = analysisData.resolvedMembers();
        var resolvedCallsRef = analysisData.resolvedCalls();
        var expressionTypesRef = analysisData.expressionTypes();
        var slotTypesRef = analysisData.slotTypes();

        var bindingNode = identifier("local");
        var binding = new FrontendBinding("self", FrontendBindingKind.SELF, null);
        var symbolBindings = new FrontendAstSideTable<FrontendBinding>();
        symbolBindings.put(bindingNode, binding);
        analysisData.applyPatch(patch(FrontendSemanticStage.TOP_BINDING, symbolBindings));

        var memberNode = passNode();
        var member = FrontendResolvedMember.resolved(
                "hp",
                FrontendBindingKind.PROPERTY,
                FrontendReceiverKind.INSTANCE,
                ScopeOwnerKind.GDCC,
                new GdObjectType("Player"),
                GdIntType.INT,
                "Player.hp"
        );
        var resolvedMembers = new FrontendAstSideTable<FrontendResolvedMember>();
        resolvedMembers.put(memberNode, member);

        var callNode = identifier("move");
        var resolvedCalls = new FrontendAstSideTable<FrontendResolvedCall>();
        var call = FrontendResolvedCall.resolved(
                "move",
                FrontendCallResolutionKind.INSTANCE_METHOD,
                FrontendReceiverKind.INSTANCE,
                ScopeOwnerKind.GDCC,
                new GdObjectType("Player"),
                GdIntType.INT,
                List.of(GdIntType.INT),
                "Player.move"
        );
        resolvedCalls.put(callNode, call);
        analysisData.applyPatch(patch(
                FrontendSemanticStage.CHAIN_BINDING,
                new FrontendAstSideTable<>(),
                resolvedMembers,
                resolvedCalls,
                new FrontendAstSideTable<>(),
                new FrontendAstSideTable<>(),
                List.of()
        ));

        var expressionNode = identifier("value");
        var expressionTypes = new FrontendAstSideTable<FrontendExpressionType>();
        var expressionType = FrontendExpressionType.resolved(GdIntType.INT);
        expressionTypes.put(expressionNode, expressionType);
        analysisData.applyPatch(patch(
                FrontendSemanticStage.EXPR_TYPE,
                new FrontendAstSideTable<>(),
                new FrontendAstSideTable<>(),
                new FrontendAstSideTable<>(),
                expressionTypes,
                new FrontendAstSideTable<>(),
                List.of()
        ));

        var slotNode = variable("value");
        var slotTypes = new FrontendAstSideTable<GdType>();
        slotTypes.put(slotNode, GdIntType.INT);
        analysisData.applyPatch(patch(
                FrontendSemanticStage.VAR_TYPE_POST,
                new FrontendAstSideTable<>(),
                new FrontendAstSideTable<>(),
                new FrontendAstSideTable<>(),
                new FrontendAstSideTable<>(),
                slotTypes,
                List.of()
        ));

        assertSame(symbolBindingsRef, analysisData.symbolBindings());
        assertSame(resolvedMembersRef, analysisData.resolvedMembers());
        assertSame(resolvedCallsRef, analysisData.resolvedCalls());
        assertSame(expressionTypesRef, analysisData.expressionTypes());
        assertSame(slotTypesRef, analysisData.slotTypes());
        assertSame(binding, analysisData.symbolBindings().get(bindingNode));
        assertSame(member, analysisData.resolvedMembers().get(memberNode));
        assertSame(call, analysisData.resolvedCalls().get(callNode));
        assertSame(expressionType, analysisData.expressionTypes().get(expressionNode));
        assertSame(GdIntType.INT, expressionType.publishedType());
        assertSame(GdIntType.INT, analysisData.slotTypes().get(slotNode));
    }

    @Test
    void applyPatchAllowsIdempotentMergeForLogicallyEquivalentFacts() {
        var analysisData = FrontendAnalysisData.bootstrap();
        var expressionNode = identifier("value");
        var slotNode = variable("value");
        analysisData.expressionTypes().put(expressionNode, FrontendExpressionType.resolved(GdIntType.INT));
        analysisData.slotTypes().put(slotNode, GdIntType.INT);

        var idempotentExpressionTypes = new FrontendAstSideTable<FrontendExpressionType>();
        idempotentExpressionTypes.put(expressionNode, FrontendExpressionType.resolved(new GdIntType()));
        var idempotentSlotTypes = new FrontendAstSideTable<GdType>();
        idempotentSlotTypes.put(slotNode, new GdIntType());

        analysisData.applyPatch(patch(
                FrontendSemanticStage.EXPR_TYPE,
                new FrontendAstSideTable<>(),
                new FrontendAstSideTable<>(),
                new FrontendAstSideTable<>(),
                idempotentExpressionTypes,
                new FrontendAstSideTable<>(),
                List.of()
        ));
        analysisData.applyPatch(patch(
                FrontendSemanticStage.VAR_TYPE_POST,
                new FrontendAstSideTable<>(),
                new FrontendAstSideTable<>(),
                new FrontendAstSideTable<>(),
                new FrontendAstSideTable<>(),
                idempotentSlotTypes,
                List.of()
        ));

        var idempotentExpressionType = analysisData.expressionTypes().get(expressionNode);
        assertSame(GdIntType.INT, Objects.requireNonNull(idempotentExpressionType).publishedType());
        assertSame(GdIntType.INT, analysisData.slotTypes().get(slotNode));
    }

    @Test
    void applyPatchRejectsConflictingExpressionAndSlotFacts() {
        var analysisData = FrontendAnalysisData.bootstrap();
        var expressionNode = identifier("value");
        var slotNode = variable("slot");
        analysisData.expressionTypes().put(expressionNode, FrontendExpressionType.resolved(GdIntType.INT));
        analysisData.slotTypes().put(slotNode, GdIntType.INT);

        var conflictingExpressionTypes = new FrontendAstSideTable<FrontendExpressionType>();
        conflictingExpressionTypes.put(expressionNode, FrontendExpressionType.resolved(GdFloatType.FLOAT));
        assertThrows(
                FrontendAnalysisPatchException.class,
                () -> analysisData.applyPatch(patch(
                        FrontendSemanticStage.EXPR_TYPE,
                        new FrontendAstSideTable<>(),
                        new FrontendAstSideTable<>(),
                        new FrontendAstSideTable<>(),
                        conflictingExpressionTypes,
                        new FrontendAstSideTable<>(),
                        List.of()
                ))
        );

        var failedExpressionTypes = new FrontendAstSideTable<FrontendExpressionType>();
        var failedNode = identifier("failed");
        analysisData.expressionTypes().put(failedNode, FrontendExpressionType.failed("original failure"));
        failedExpressionTypes.put(failedNode, FrontendExpressionType.resolved(GdIntType.INT));
        assertThrows(
                FrontendAnalysisPatchException.class,
                () -> analysisData.applyPatch(patch(
                        FrontendSemanticStage.EXPR_TYPE,
                        new FrontendAstSideTable<>(),
                        new FrontendAstSideTable<>(),
                        new FrontendAstSideTable<>(),
                        failedExpressionTypes,
                        new FrontendAstSideTable<>(),
                        List.of()
                ))
        );

        var conflictingSlotTypes = new FrontendAstSideTable<GdType>();
        conflictingSlotTypes.put(slotNode, new GdObjectType("Player"));
        assertThrows(
                FrontendAnalysisPatchException.class,
                () -> analysisData.applyPatch(patch(
                        FrontendSemanticStage.VAR_TYPE_POST,
                        new FrontendAstSideTable<>(),
                        new FrontendAstSideTable<>(),
                        new FrontendAstSideTable<>(),
                        new FrontendAstSideTable<>(),
                        conflictingSlotTypes,
                        List.of()
                ))
        );
    }

    @Test
    void ownerPatchesRejectConflictingBindingMemberAndCallFactsWithoutMutation() {
        var analysisData = FrontendAnalysisData.bootstrap();

        var bindingNode = identifier("binding");
        var stableBinding = new FrontendBinding("binding", FrontendBindingKind.UNKNOWN, null);
        analysisData.symbolBindings().put(bindingNode, stableBinding);
        var conflictingBindings = new FrontendAstSideTable<FrontendBinding>();
        conflictingBindings.put(bindingNode, new FrontendBinding("binding", FrontendBindingKind.SELF, null));
        assertThrows(
                FrontendAnalysisPatchException.class,
                () -> analysisData.applyPatch(new FrontendTopBindingPatch(conflictingBindings))
        );
        assertSame(stableBinding, analysisData.symbolBindings().get(bindingNode));

        var memberNode = identifier("member");
        var stableMember = FrontendResolvedMember.resolved(
                "member",
                FrontendBindingKind.PROPERTY,
                FrontendReceiverKind.INSTANCE,
                ScopeOwnerKind.GDCC,
                new GdObjectType("Owner"),
                GdIntType.INT,
                "Owner.member"
        );
        analysisData.resolvedMembers().put(memberNode, stableMember);
        var conflictingMembers = new FrontendAstSideTable<FrontendResolvedMember>();
        conflictingMembers.put(memberNode, FrontendResolvedMember.resolved(
                "member",
                FrontendBindingKind.PROPERTY,
                FrontendReceiverKind.INSTANCE,
                ScopeOwnerKind.GDCC,
                new GdObjectType("Owner"),
                GdFloatType.FLOAT,
                "Owner.member"
        ));
        assertThrows(
                FrontendAnalysisPatchException.class,
                () -> analysisData.applyPatch(new FrontendChainBindingPatch(
                        conflictingMembers,
                        new FrontendAstSideTable<>()
                ))
        );
        assertSame(stableMember, analysisData.resolvedMembers().get(memberNode));

        var callNode = identifier("call");
        var stableCall = FrontendResolvedCall.resolved(
                "call",
                FrontendCallResolutionKind.STATIC_METHOD,
                FrontendReceiverKind.TYPE_META,
                ScopeOwnerKind.GDCC,
                new GdObjectType("Owner"),
                GdIntType.INT,
                List.of(),
                "Owner.call"
        );
        analysisData.resolvedCalls().put(callNode, stableCall);
        var conflictingCalls = new FrontendAstSideTable<FrontendResolvedCall>();
        conflictingCalls.put(callNode, FrontendResolvedCall.resolved(
                "call",
                FrontendCallResolutionKind.STATIC_METHOD,
                FrontendReceiverKind.TYPE_META,
                ScopeOwnerKind.GDCC,
                new GdObjectType("Owner"),
                GdFloatType.FLOAT,
                List.of(),
                "Owner.call"
        ));
        assertThrows(
                FrontendAnalysisPatchException.class,
                () -> analysisData.applyPatch(new FrontendChainBindingPatch(
                        new FrontendAstSideTable<>(),
                        conflictingCalls
                ))
        );
        assertSame(stableCall, analysisData.resolvedCalls().get(callNode));
    }

    @Test
    void applyPatchRefreshesPublishedLocalBindingsForMatchingDeclarationOnly() throws Exception {
        var analysisData = FrontendAnalysisData.bootstrap();
        var updatedScope = newBodyScope();
        var untouchedScope = newBodyScope();
        var targetDeclaration = variable("local");
        var untouchedDeclaration = variable("local");
        updatedScope.defineLocal("local", GdVariantType.VARIANT, targetDeclaration);
        untouchedScope.defineLocal("local", GdVariantType.VARIANT, untouchedDeclaration);

        var targetBindingNode = identifier("target_use");
        var untouchedBindingNode = identifier("other_use");
        var targetBinding = localBinding("local", targetDeclaration, requireLocal(updatedScope, "local"));
        var untouchedBinding = localBinding("local", untouchedDeclaration, requireLocal(untouchedScope, "local"));
        analysisData.symbolBindings().put(targetBindingNode, targetBinding);
        analysisData.symbolBindings().put(untouchedBindingNode, untouchedBinding);

        analysisData.applyPatch(patch(
                FrontendSemanticStage.LOCAL_TYPE_STABILIZATION,
                new FrontendAstSideTable<>(),
                new FrontendAstSideTable<>(),
                new FrontendAstSideTable<>(),
                new FrontendAstSideTable<>(),
                new FrontendAstSideTable<>(),
                List.of(new FrontendLocalSlotTypeUpdate(updatedScope, "local", targetDeclaration, GdIntType.INT))
        ));

        var refreshedBinding = analysisData.symbolBindings().get(targetBindingNode);
        var refreshedValue = Objects.requireNonNull(refreshedBinding).resolvedValue();
        var untouchedValue = Objects.requireNonNull(analysisData.symbolBindings().get(untouchedBindingNode)).resolvedValue();
        assertNotSame(targetBinding, refreshedBinding);
        assertSame(targetDeclaration, refreshedBinding.declarationSite());
        assertSame(targetDeclaration, Objects.requireNonNull(refreshedValue).declaration());
        assertSame(GdIntType.INT, refreshedValue.type());
        assertSame(untouchedBinding, analysisData.symbolBindings().get(untouchedBindingNode));
        assertSame(GdVariantType.VARIANT, Objects.requireNonNull(untouchedValue).type());
        assertSame(GdIntType.INT, requireLocal(updatedScope, "local").type());
    }

    @Test
    void applyPatchSkipsBindingRefreshForNoOpUpdateAndExprTypeOnlyPatch() throws Exception {
        var analysisData = FrontendAnalysisData.bootstrap();
        var localScope = newBodyScope();
        var declaration = variable("local");
        localScope.defineLocal("local", GdIntType.INT, declaration);
        var bindingNode = identifier("local_use");
        var originalBinding = localBinding("local", declaration, requireLocal(localScope, "local"));
        analysisData.symbolBindings().put(bindingNode, originalBinding);

        analysisData.applyPatch(patch(
                FrontendSemanticStage.LOCAL_TYPE_STABILIZATION,
                new FrontendAstSideTable<>(),
                new FrontendAstSideTable<>(),
                new FrontendAstSideTable<>(),
                new FrontendAstSideTable<>(),
                new FrontendAstSideTable<>(),
                List.of(new FrontendLocalSlotTypeUpdate(localScope, "local", declaration, GdIntType.INT))
        ));
        assertSame(originalBinding, analysisData.symbolBindings().get(bindingNode));

        var expressionTypes = new FrontendAstSideTable<FrontendExpressionType>();
        expressionTypes.put(identifier("initializer"), FrontendExpressionType.resolved(GdFloatType.FLOAT));
        analysisData.applyPatch(patch(
                FrontendSemanticStage.EXPR_TYPE,
                new FrontendAstSideTable<>(),
                new FrontendAstSideTable<>(),
                new FrontendAstSideTable<>(),
                expressionTypes,
                new FrontendAstSideTable<>(),
                List.of()
        ));
        assertSame(originalBinding, analysisData.symbolBindings().get(bindingNode));
    }

    @Test
    void applyPatchRejectsCompilerOnlyLeaksAcrossExpressionAndSlotTypePayloads() throws Exception {
        var analysisData = FrontendAnalysisData.bootstrap();
        var expressionTypes = new FrontendAstSideTable<FrontendExpressionType>();
        expressionTypes.put(identifier("iter"), FrontendExpressionType.resolved(GdccForRangeIterType.FOR_RANGE_ITER));
        assertThrows(
                FrontendAnalysisPatchException.class,
                () -> analysisData.applyPatch(patch(
                        FrontendSemanticStage.EXPR_TYPE,
                        new FrontendAstSideTable<>(),
                        new FrontendAstSideTable<>(),
                        new FrontendAstSideTable<>(),
                        expressionTypes,
                        new FrontendAstSideTable<>(),
                        List.of()
                ))
        );

        var slotTypes = new FrontendAstSideTable<GdType>();
        slotTypes.put(variable("iter_slot"), GdccForRangeIterType.FOR_RANGE_ITER);
        assertThrows(
                FrontendAnalysisPatchException.class,
                () -> analysisData.applyPatch(patch(
                        FrontendSemanticStage.VAR_TYPE_POST,
                        new FrontendAstSideTable<>(),
                        new FrontendAstSideTable<>(),
                        new FrontendAstSideTable<>(),
                        new FrontendAstSideTable<>(),
                        slotTypes,
                        List.of()
                ))
        );

        var localScope = newBodyScope();
        var declaration = variable("local");
        localScope.defineLocal("local", GdVariantType.VARIANT, declaration);
        assertThrows(
                FrontendAnalysisPatchException.class,
                () -> analysisData.applyPatch(patch(
                        FrontendSemanticStage.LOCAL_TYPE_STABILIZATION,
                        new FrontendAstSideTable<>(),
                        new FrontendAstSideTable<>(),
                        new FrontendAstSideTable<>(),
                        new FrontendAstSideTable<>(),
                        new FrontendAstSideTable<>(),
                        List.of(new FrontendLocalSlotTypeUpdate(
                                localScope,
                                "local",
                                declaration,
                                GdccForRangeIterType.FOR_RANGE_ITER
                        ))
                ))
        );
    }

    @Test
    void applyPatchRejectsCompilerOnlyLeaksAcrossBindingMemberAndCallPayloads() {
        var analysisData = FrontendAnalysisData.bootstrap();
        var declaration = variable("local");
        var compilerOnlyValue = new ScopeValue(
                "local",
                GdccForRangeIterType.FOR_RANGE_ITER,
                ScopeValueKind.LOCAL,
                declaration,
                false,
                true,
                false
        );
        var symbolBindings = new FrontendAstSideTable<FrontendBinding>();
        symbolBindings.put(
                identifier("local"),
                new FrontendBinding(
                        "local",
                        FrontendBindingKind.LOCAL_VAR,
                        declaration,
                        compilerOnlyValue,
                        ScopeLookupStatus.FOUND_ALLOWED
                )
        );
        assertThrows(
                FrontendAnalysisPatchException.class,
                () -> analysisData.applyPatch(patch(FrontendSemanticStage.TOP_BINDING, symbolBindings))
        );

        var resolvedMembers = new FrontendAstSideTable<FrontendResolvedMember>();
        resolvedMembers.put(identifier("member"), FrontendResolvedMember.resolved(
                "member",
                FrontendBindingKind.PROPERTY,
                FrontendReceiverKind.INSTANCE,
                ScopeOwnerKind.GDCC,
                GdccForRangeIterType.FOR_RANGE_ITER,
                GdIntType.INT,
                "owner.member"
        ));
        assertThrows(
                FrontendAnalysisPatchException.class,
                () -> analysisData.applyPatch(patch(
                        FrontendSemanticStage.CHAIN_BINDING,
                        new FrontendAstSideTable<>(),
                        resolvedMembers,
                        new FrontendAstSideTable<>(),
                        new FrontendAstSideTable<>(),
                        new FrontendAstSideTable<>(),
                        List.of()
                ))
        );

        var resolvedCalls = new FrontendAstSideTable<FrontendResolvedCall>();
        resolvedCalls.put(identifier("call"), FrontendResolvedCall.resolved(
                "call",
                FrontendCallResolutionKind.STATIC_METHOD,
                FrontendReceiverKind.TYPE_META,
                ScopeOwnerKind.GDCC,
                new GdObjectType("Owner"),
                GdIntType.INT,
                List.of(GdIntType.INT),
                "Owner.call",
                new FrontendResolvedCall.ExactCallableBoundary(
                        List.of(GdccForRangeIterType.FOR_RANGE_ITER),
                        false
                )
        ));
        assertThrows(
                FrontendAnalysisPatchException.class,
                () -> analysisData.applyPatch(patch(
                        FrontendSemanticStage.EXPR_TYPE,
                        new FrontendAstSideTable<>(),
                        new FrontendAstSideTable<>(),
                        resolvedCalls,
                        new FrontendAstSideTable<>(),
                        new FrontendAstSideTable<>(),
                        List.of()
                ))
        );
    }

    @Test
    void updateWholeTablePublicationUsesSharedCompilerOnlyGuard() {
        var analysisData = FrontendAnalysisData.bootstrap();
        var symbolBindings = new FrontendAstSideTable<FrontendBinding>();
        var declaration = variable("local");
        symbolBindings.put(identifier("local"), new FrontendBinding(
                "local",
                FrontendBindingKind.LOCAL_VAR,
                declaration,
                new ScopeValue(
                        "local",
                        GdccForRangeIterType.FOR_RANGE_ITER,
                        ScopeValueKind.LOCAL,
                        declaration,
                        false,
                        true,
                        false
                ),
                ScopeLookupStatus.FOUND_ALLOWED
        ));
        assertThrows(FrontendAnalysisPatchException.class, () -> analysisData.updateSymbolBindings(symbolBindings));

        var resolvedCalls = new FrontendAstSideTable<FrontendResolvedCall>();
        resolvedCalls.put(identifier("call"), FrontendResolvedCall.resolved(
                "call",
                FrontendCallResolutionKind.STATIC_METHOD,
                FrontendReceiverKind.TYPE_META,
                ScopeOwnerKind.GDCC,
                new GdObjectType("Owner"),
                GdccForRangeIterType.FOR_RANGE_ITER,
                List.of(GdIntType.INT),
                "Owner.call"
        ));
        assertThrows(FrontendAnalysisPatchException.class, () -> analysisData.updateResolvedCalls(resolvedCalls));
    }

    @Test
    void ownerPatchTransactionAppliesInFixedOwnerOrderAndRejectsRegressions() {
        var analysisData = FrontendAnalysisData.bootstrap();
        var bindingNode = identifier("value");
        var symbolBindings = new FrontendAstSideTable<FrontendBinding>();
        var binding = new FrontendBinding("value", FrontendBindingKind.LOCAL_VAR, variable("value"));
        symbolBindings.put(bindingNode, binding);
        var expressionNode = identifier("expr");
        var expressionTypes = new FrontendAstSideTable<FrontendExpressionType>();
        expressionTypes.put(expressionNode, FrontendExpressionType.resolved(GdIntType.INT));

        new FrontendPatchTransaction(List.of(
                new FrontendTopBindingPatch(symbolBindings),
                new FrontendExprTypePatch(expressionTypes, new FrontendAstSideTable<>())
        )).applyTo(analysisData);

        assertSame(binding, analysisData.symbolBindings().get(bindingNode));
        assertEquals(
                GdIntType.INT,
                Objects.requireNonNull(analysisData.expressionTypes().get(expressionNode)).publishedType()
        );
        assertThrows(
                FrontendAnalysisPatchException.class,
                () -> new FrontendPatchTransaction(List.of(
                        new FrontendExprTypePatch(expressionTypes, new FrontendAstSideTable<>()),
                        new FrontendTopBindingPatch(symbolBindings)
                ))
        );
        assertThrows(
                FrontendAnalysisPatchException.class,
                () -> new FrontendPatchTransaction(List.of(
                        new FrontendTopBindingPatch(symbolBindings),
                        new FrontendTopBindingPatch(symbolBindings)
                ))
        );
    }

    @Test
    void applyPatchRejectsVoidAndConflictingLocalSlotUpdates() throws Exception {
        var analysisData = FrontendAnalysisData.bootstrap();
        var localScope = newBodyScope();
        var declaration = variable("local");
        localScope.defineLocal("local", GdVariantType.VARIANT, declaration);

        assertThrows(
                FrontendAnalysisPatchException.class,
                () -> analysisData.applyPatch(patch(
                        FrontendSemanticStage.LOCAL_TYPE_STABILIZATION,
                        new FrontendAstSideTable<>(),
                        new FrontendAstSideTable<>(),
                        new FrontendAstSideTable<>(),
                        new FrontendAstSideTable<>(),
                        new FrontendAstSideTable<>(),
                        List.of(new FrontendLocalSlotTypeUpdate(localScope, "local", declaration, GdVoidType.VOID))
                ))
        );

        localScope.resetLocalType("local", declaration, GdIntType.INT);
        assertThrows(
                FrontendAnalysisPatchException.class,
                () -> analysisData.applyPatch(patch(
                        FrontendSemanticStage.LOCAL_TYPE_STABILIZATION,
                        new FrontendAstSideTable<>(),
                        new FrontendAstSideTable<>(),
                        new FrontendAstSideTable<>(),
                        new FrontendAstSideTable<>(),
                        new FrontendAstSideTable<>(),
                        List.of(new FrontendLocalSlotTypeUpdate(localScope, "local", declaration, GdFloatType.FLOAT))
                ))
        );
    }

    @Test
    void applyPatchPublishesForIterationPlansWithoutReplacingStableSideTableReference() {
        var analysisData = FrontendAnalysisData.bootstrap();
        var stableReference = analysisData.forIterationPlans();
        var statement = forStatement(bareRangeCall());
        var plan = rangePlan(statement, statement.iterable());
        var plans = new FrontendAstSideTable<FrontendForIterationPlan>();
        plans.put(statement, plan);

        analysisData.applyPatch(new FrontendForIterationResolutionPatch(plans, List.of()));

        assertSame(stableReference, analysisData.forIterationPlans());
        assertSame(plan, analysisData.forIterationPlans().get(statement));
    }

    @Test
    void applyPatchAllowsIdempotentForIterationPlanMerge() {
        var analysisData = FrontendAnalysisData.bootstrap();
        var statement = forStatement(bareRangeCall());
        var operand = statement.iterable();
        analysisData.forIterationPlans().put(statement, rangePlan(statement, operand));

        var idempotentPlans = new FrontendAstSideTable<FrontendForIterationPlan>();
        idempotentPlans.put(statement, rangePlan(statement, operand));

        analysisData.applyPatch(new FrontendForIterationResolutionPatch(idempotentPlans, List.of()));

        assertEquals(FrontendForIterationRoute.RANGE_CALL,
                Objects.requireNonNull(analysisData.forIterationPlans().get(statement)).route());
    }

    @Test
    void applyPatchRejectsConflictingForIterationPlanOnSameStatement() {
        var analysisData = FrontendAnalysisData.bootstrap();
        var statement = forStatement(bareRangeCall());
        var operand = statement.iterable();
        analysisData.forIterationPlans().put(statement, rangePlan(statement, operand));

        var conflictingPlans = new FrontendAstSideTable<FrontendForIterationPlan>();
        conflictingPlans.put(statement, intShorthandPlan(statement, operand));

        assertThrows(
                FrontendAnalysisPatchException.class,
                () -> analysisData.applyPatch(new FrontendForIterationResolutionPatch(conflictingPlans, List.of()))
        );
        assertEquals(FrontendForIterationRoute.RANGE_CALL,
                Objects.requireNonNull(analysisData.forIterationPlans().get(statement)).route());
    }

    @Test
    void forIterationResolutionPatchGuardRejectsCompilerOnlyElementTypes() {
        var statement = forStatement(bareRangeCall());
        var compilerOnlyPlan = new FrontendForIterationPlan(
                statement,
                FrontendForIterationRoute.RANGE_CALL,
                "i",
                null,
                GdccForRangeIterType.FOR_RANGE_ITER,
                GdIntType.INT,
                List.of(statement.iterable())
        );
        var plans = new FrontendAstSideTable<FrontendForIterationPlan>();
        plans.put(statement, compilerOnlyPlan);

        assertThrows(
                FrontendAnalysisPatchException.class,
                () -> new FrontendForIterationResolutionPatch(plans, List.of())
        );
    }

    @Test
    void updateForIterationPlansWholeTablePublicationUsesCompilerOnlyGuard() {
        var analysisData = FrontendAnalysisData.bootstrap();
        var statement = forStatement(bareRangeCall());
        var compilerOnlyPlan = new FrontendForIterationPlan(
                statement,
                FrontendForIterationRoute.GENERIC_VARIANT,
                "i",
                null,
                GdVariantType.VARIANT,
                GdccForRangeIterType.FOR_RANGE_ITER,
                List.of(statement.iterable())
        );
        var plans = new FrontendAstSideTable<FrontendForIterationPlan>();
        plans.put(statement, compilerOnlyPlan);

        assertThrows(FrontendAnalysisPatchException.class, () -> analysisData.updateForIterationPlans(plans));
    }

    @Test
    void forIterationResolutionPatchRefinesIteratorSlotFromVariantToExact() throws Exception {
        var analysisData = FrontendAnalysisData.bootstrap();
        var scope = newBodyScope();
        var statement = forStatement(bareRangeCall());
        scope.defineLocal("i", GdVariantType.VARIANT, statement);

        analysisData.applyPatch(new FrontendForIterationResolutionPatch(
                new FrontendAstSideTable<>(),
                List.of(new FrontendLocalSlotTypeUpdate(scope, "i", statement, GdIntType.INT))
        ));

        assertSame(GdIntType.INT, requireLocal(scope, "i").type());
    }

    @Test
    void forIterationResolutionPatchRejectsExactToExactIteratorSlotRewrite() throws Exception {
        var analysisData = FrontendAnalysisData.bootstrap();
        var scope = newBodyScope();
        var statement = forStatement(bareRangeCall());
        scope.defineLocal("i", GdIntType.INT, statement);

        assertThrows(
                FrontendAnalysisPatchException.class,
                () -> analysisData.applyPatch(new FrontendForIterationResolutionPatch(
                        new FrontendAstSideTable<>(),
                        List.of(new FrontendLocalSlotTypeUpdate(scope, "i", statement, GdFloatType.FLOAT))
                ))
        );
    }

    @Test
    void slotUpdateOwnersEnforceDisjointDeclarationIdentityDomains() throws Exception {
        var analysisData = FrontendAnalysisData.bootstrap();
        var scope = newBodyScope();
        var statement = forStatement(bareRangeCall());
        var ordinaryDeclaration = variable("local");
        scope.defineLocal("i", GdVariantType.VARIANT, statement);
        scope.defineLocal("local", GdVariantType.VARIANT, ordinaryDeclaration);

        // FOR_ITERATION_RESOLUTION must only target the owning ForStatement iterator.
        assertThrows(
                FrontendAnalysisPatchException.class,
                () -> analysisData.applyPatch(new FrontendForIterationResolutionPatch(
                        new FrontendAstSideTable<>(),
                        List.of(new FrontendLocalSlotTypeUpdate(scope, "local", ordinaryDeclaration, GdIntType.INT))
                ))
        );
        // LOCAL_TYPE_STABILIZATION must only target a VariableDeclaration.
        assertThrows(
                FrontendAnalysisPatchException.class,
                () -> analysisData.applyPatch(new FrontendLocalTypeStabilizationPatch(
                        List.of(new FrontendLocalSlotTypeUpdate(scope, "i", statement, GdIntType.INT))
                ))
        );
    }

    @Test
    void patchTransactionOrdersForIterationResolutionBetweenExprTypeAndVarTypePost() {
        var statement = forStatement(bareRangeCall());
        var plans = new FrontendAstSideTable<FrontendForIterationPlan>();
        plans.put(statement, rangePlan(statement, statement.iterable()));
        var slotTypes = new FrontendAstSideTable<GdType>();
        slotTypes.put(statement, GdIntType.INT);

        var analysisData = FrontendAnalysisData.bootstrap();
        new FrontendPatchTransaction(List.of(
                new FrontendExprTypePatch(new FrontendAstSideTable<>(), new FrontendAstSideTable<>()),
                new FrontendForIterationResolutionPatch(plans, List.of()),
                new FrontendVarTypePostPatch(slotTypes)
        )).applyTo(analysisData);
        assertSame(plans.get(statement), analysisData.forIterationPlans().get(statement));

        assertThrows(
                FrontendAnalysisPatchException.class,
                () -> new FrontendPatchTransaction(List.of(
                        new FrontendForIterationResolutionPatch(plans, List.of()),
                        new FrontendExprTypePatch(new FrontendAstSideTable<>(), new FrontendAstSideTable<>())
                ))
        );
    }

    private static PassStatement passNode() {
        return new PassStatement(RANGE);
    }

    private static @NotNull CallExpression bareRangeCall() {
        return new CallExpression(
                new IdentifierExpression("range", RANGE),
                List.of(new LiteralExpression("int", "3", RANGE)),
                RANGE
        );
    }

    private static @NotNull ForStatement forStatement(@NotNull Expression iterable) {
        return new ForStatement("i", null, iterable, new Block(List.of(passNode()), RANGE), RANGE);
    }

    private static @NotNull FrontendForIterationPlan rangePlan(
            @NotNull ForStatement statement,
            @NotNull Expression operand
    ) {
        return new FrontendForIterationPlan(
                statement,
                FrontendForIterationRoute.RANGE_CALL,
                "i",
                null,
                GdIntType.INT,
                GdIntType.INT,
                List.of(operand)
        );
    }

    private static @NotNull FrontendForIterationPlan intShorthandPlan(
            @NotNull ForStatement statement,
            @NotNull Expression operand
    ) {
        return new FrontendForIterationPlan(
                statement,
                FrontendForIterationRoute.INT_SHORTHAND,
                "i",
                null,
                GdIntType.INT,
                GdIntType.INT,
                List.of(operand)
        );
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

    @SuppressWarnings("SameParameterValue")
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

    @SuppressWarnings("SameParameterValue")
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

    @SuppressWarnings("SameParameterValue")
    private static @NotNull FrontendOwnerPatch patch(
            @NotNull FrontendSemanticStage stage,
            @NotNull FrontendAstSideTable<FrontendBinding> symbolBindings,
            @NotNull FrontendAstSideTable<FrontendResolvedMember> resolvedMembers,
            @NotNull FrontendAstSideTable<FrontendResolvedCall> resolvedCalls,
            @NotNull FrontendAstSideTable<FrontendExpressionType> expressionTypes,
            @NotNull FrontendAstSideTable<GdType> slotTypes,
            @NotNull List<FrontendLocalSlotTypeUpdate> localSlotTypeUpdates
    ) {
        return switch (stage) {
            case TOP_BINDING -> new FrontendTopBindingPatch(symbolBindings);
            case LOCAL_TYPE_STABILIZATION -> new FrontendLocalTypeStabilizationPatch(localSlotTypeUpdates);
            case CHAIN_BINDING -> new FrontendChainBindingPatch(resolvedMembers, resolvedCalls);
            case EXPR_TYPE -> new FrontendExprTypePatch(expressionTypes, resolvedCalls);
            case FOR_ITERATION_RESOLUTION -> new FrontendForIterationResolutionPatch(
                    new FrontendAstSideTable<>(),
                    localSlotTypeUpdates
            );
            case VAR_TYPE_POST -> new FrontendVarTypePostPatch(slotTypes);
        };
    }

    @SuppressWarnings("SameParameterValue")
    private static @NotNull FrontendOwnerPatch patch(
            @NotNull FrontendSemanticStage stage,
            @NotNull FrontendAstSideTable<FrontendBinding> symbolBindings
    ) {
        return patch(
                stage,
                symbolBindings,
                new FrontendAstSideTable<>(),
                new FrontendAstSideTable<>(),
                new FrontendAstSideTable<>(),
                new FrontendAstSideTable<>(),
                List.of()
        );
    }
}
