package dev.superice.gdcc.frontend.sema;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticSnapshot;
import dev.superice.gdcc.frontend.diagnostic.FrontendDiagnostic;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.scope.Scope;
import dev.superice.gdcc.scope.ScopeOwnerKind;
import dev.superice.gdcc.type.GdType;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdVariantType;
import dev.superice.gdparser.frontend.ast.PassStatement;
import dev.superice.gdparser.frontend.ast.Point;
import dev.superice.gdparser.frontend.ast.Range;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

        assertSame(originalSideTable, analysisData.expressionTypes());
        assertNull(analysisData.expressionTypes().get(staleNode));
        assertSame(publishedType, analysisData.expressionTypes().get(freshNode));
        assertEquals(GdVariantType.VARIANT, analysisData.expressionTypes().get(freshNode).publishedType());
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

    private static PassStatement passNode() {
        return new PassStatement(RANGE);
    }
}
