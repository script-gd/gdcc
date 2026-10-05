package gd.script.gdcc.frontend;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import gd.script.gdcc.api.API;
import gd.script.gdcc.api.AnalysisResult;
import gd.script.gdcc.api.AnalyzeOptions;
import org.junit.jupiter.api.Test;

/// Regression for the dynamic-chain call-suffix gap (found by the editor addon's
/// temporary-inclusion engine case `sync_exclusions`): with `includeLowering=true`, a member
/// call CHAINED on an already-dynamic receiver (e.g. a variable whose declared type fell back
/// to Variant) previously crashed the CFG lowering invariant `requireLoweringReadyCall` —
/// `propagateStep` published dynamic member facts for property suffixes but no call fact for
/// call suffixes. The fix publishes a DYNAMIC call fact for that suffix; the module analyzes
/// with only the unknown-type warning.
class FrontendLoweringUnresolvedTypeFallbackTest {
    @Test
    void unresolvedDeclaredTypeMemberCallChainLowersAsDynamic() {
        var api = new API();
        api.createModule("m", "M");
        api.putFile("m", "/src/probe.gd3", """
                extends Node

                var service: UnknownServiceType = null

                func check() -> bool:
                    if not service.is_active() or not service.lifecycle().is_ready():
                        return false
                    return true
                """);
        var result = assertDoesNotThrow(() -> api.analyze("m", new AnalyzeOptions(true)));
        assertEquals(AnalysisResult.Outcome.COMPLETED, result.outcome());
        // The only diagnostic is the unknown-type Variant-fallback warning; the chained calls
        // degrade to dynamic without additional errors.
        var diagnostics = result.diagnostics().diagnostics();
        assertEquals(1, diagnostics.size());
        assertEquals("sema.type_resolution", diagnostics.getFirst().category());
    }
}

