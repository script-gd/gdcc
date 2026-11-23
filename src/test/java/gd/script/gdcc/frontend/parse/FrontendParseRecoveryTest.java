package gd.script.gdcc.frontend.parse;

import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnostic;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnosticSeverity;
import gd.script.gdcc.frontend.diagnostic.FrontendPoint;
import gd.script.gdcc.frontend.diagnostic.FrontendRange;
import gd.script.gdcc.frontend.sema.FrontendAnalysisData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class FrontendParseRecoveryTest {
    @Test
    void liveCoverageUsesOnlyTheSuppliedManagerAndPrefersTheExactParserError() {
        var range = new FrontendRange(0, 1, new FrontendPoint(1, 1), new FrontendPoint(1, 2));
        var source = "sample.gd";
        var recovery = new FrontendParseRecovery(
                "parse.syntax", FrontendDiagnosticSeverity.ERROR, "parser error", range, null, null
        );
        var original = recovery.toDiagnostic(source);
        var fallback = FrontendDiagnostic.error("sema.type_check", "fallback", source, range);
        var analysisData = FrontendAnalysisData.bootstrap();
        analysisData.claimParseRecovery(recovery, fallback);
        var originalManager = new DiagnosticManager();
        var separateManager = new DiagnosticManager();
        originalManager.report(fallback);
        separateManager.reportAll(List.of(
                FrontendDiagnostic.warning(recovery.code(), recovery.message(), source, range),
                FrontendDiagnostic.error(recovery.code(), "different parser message", source, range),
                recovery.toDiagnostic("other.gd")
        ));

        assertSame(fallback, FrontendParseRecovery.findActiveCoveringDiagnostic(
                recovery, analysisData, originalManager, source));
        assertNull(FrontendParseRecovery.findActiveCoveringDiagnostic(recovery, analysisData, separateManager, source));
        assertSame(fallback, FrontendParseRecovery.findActiveCoveringDiagnostic(
                recovery, analysisData, originalManager, source));

        separateManager.report(original);
        assertEquals(original, FrontendParseRecovery.findActiveCoveringDiagnostic(
                recovery, analysisData, separateManager, source));
        originalManager.report(original);
        assertEquals(original, FrontendParseRecovery.findActiveCoveringDiagnostic(
                recovery, analysisData, originalManager, source));
        assertSame(fallback, analysisData.claimedParseRecoveries().get(recovery));
    }

    @Test
    void liveCoverageDoesNotTreatAnActiveWarningClaimAsBlocking() {
        var recovery = new FrontendParseRecovery(
                "parse.syntax", FrontendDiagnosticSeverity.ERROR, "parser error", null, null, null
        );
        var warning = FrontendDiagnostic.warning("sema.type_check", "warning", null, null);
        var manager = new DiagnosticManager();
        manager.report(warning);
        var analysisData = FrontendAnalysisData.bootstrap();
        analysisData.claimParseRecovery(recovery, warning);

        assertNull(FrontendParseRecovery.findActiveCoveringDiagnostic(recovery, analysisData, manager, null));
        assertSame(warning, analysisData.claimedParseRecoveries().get(recovery));
    }
}
