package dev.superice.gdcc.frontend.sema;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticSnapshot;
import dev.superice.gdcc.frontend.diagnostic.FrontendDiagnostic;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendAnalysisDataTest {
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
        var moduleSkeleton = new FrontendModuleSkeleton("test_module", List.of(), List.of(), diagnostics);

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
        var astNode = new Object();
        var annotation = new FrontendGdAnnotation("tool", List.of(), null);
        replacement.put(astNode, List.of(annotation));

        analysisData.updateAnnotationsByAst(replacement);

        assertSame(originalSideTable, analysisData.annotationsByAst());
        assertEquals(List.of(annotation), analysisData.annotationsByAst().get(astNode));
    }
}
