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
    void publishPhaseBoundaryMakesSkeletonAndDiagnosticsReadable() {
        var analysisData = FrontendAnalysisData.bootstrap();
        var diagnostics = new DiagnosticSnapshot(List.of(
                FrontendDiagnostic.warning("sema.unsupported_annotation", "warning", null, null)
        ));
        var moduleSkeleton = new FrontendModuleSkeleton("test_module", List.of(), List.of(), diagnostics);

        analysisData.publishPhaseBoundary(moduleSkeleton, diagnostics);

        assertSame(moduleSkeleton, analysisData.moduleSkeleton());
        assertEquals(diagnostics, analysisData.diagnostics());
    }
}
