package dev.superice.gdcc.frontend.sema;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticSnapshot;
import dev.superice.gdcc.frontend.diagnostic.FrontendDiagnostic;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.scope.Scope;
import dev.superice.gdparser.frontend.ast.PassStatement;
import dev.superice.gdparser.frontend.ast.Point;
import dev.superice.gdparser.frontend.ast.Range;
import org.junit.jupiter.api.Test;

import java.util.List;

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
        assertThrows(IllegalStateException.class, analysisData::moduleSkeleton);
        assertThrows(IllegalStateException.class, analysisData::diagnostics);
    }

    @Test
    void updatePublishedFieldsMakesSkeletonAndDiagnosticsReadable() {
        var analysisData = FrontendAnalysisData.bootstrap();
        var diagnostics = new DiagnosticSnapshot(List.of(
                FrontendDiagnostic.warning("sema.unsupported_annotation", "warning", null, null)
        ));
        var moduleSkeleton = new FrontendModuleSkeleton("test_module", List.of(), diagnostics);

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

    private static PassStatement passNode() {
        return new PassStatement(RANGE);
    }
}
