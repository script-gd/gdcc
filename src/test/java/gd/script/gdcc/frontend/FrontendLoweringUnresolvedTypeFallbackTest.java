package gd.script.gdcc.frontend;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import gd.script.gdcc.api.API;
import gd.script.gdcc.api.AnalyzeOptions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/// Known limitation (found by the editor addon's temporary-inclusion engine case
/// `sync_exclusions`): with `includeLowering=true`, a member call on a receiver whose
/// declared type cannot resolve in the module trips the CFG lowering invariant
/// (`requireLoweringReadyCall` — the published call fact is neither RESOLVED nor DYNAMIC)
/// and throws `IllegalStateException`, which the RPC layer surfaces as -32603 and the editor
/// diagnostics channel treats as an outage. The sema-only path degrades gracefully
/// (WARNING `sema.type_resolution` "Unknown type ... fallback to Variant"). Expected behavior
/// after the frontend fix: the lowered analysis publishes a DYNAMIC call fact (Variant
/// receiver) plus the same warning, and this test passes without throwing.
///
/// Editor impact until fixed: opening an excluded file that references types outside the
/// module (temporary inclusion), or any project file referencing a missing plugin's types,
/// crash-loops the diagnostics channel.
@Disabled("frontend lowering must degrade unknown-type member calls to DYNAMIC instead of throwing")
class FrontendLoweringUnresolvedTypeFallbackTest {
    @Test
    void unresolvedDeclaredTypeMemberCallDoesNotCrashLoweredAnalysis() {
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
        System.out.println("outcome=" + result.outcome() + " diagnostics=" + result.diagnostics());
    }
}
