package gd.script.gdcc.backend.c.build;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Runtime gate for the `GD_OBJECT_CALL*` dynamic-call macros (`gdcc/gdcc_call.h`): `_GDV`'s
/// `_Generic` dispatch, the statement-expression argument marshalling, and the engine-round-trip
/// through the fixed `Object.call` method bind. The probe fakes the GDExtension interface at the
/// function-pointer level and records every call as text; both compiler legs must produce the
/// exact same event sequence.
///
/// Two contracts are anchored beyond plain round-tripping:
/// - the zero-allocation statement-expression branch is provably taken (the variadic fallback
///   branch heap-allocates its argument array via the interface allocator, so a scalar-arg call
///   must not move the allocation counter) — this is what keeps the `__TINYC__` branch selection
///   in `gdcc_call.h` honest;
/// - every argument Variant built by the macro is destroyed exactly once (create/destroy balance),
///   and the method name is prepended as a StringName Variant argument by the binding layer.
///
/// Each leg is assumption-gated on its own toolchain; the cross-compiler byte-equality test
/// additionally requires both.
class GdccCallMacroRuntimeParityTest {

    private static final List<Path> RUNTIME_SOURCES = List.of(
            RuntimeProbeToolchain.GODOT_INCLUDE_DIR.resolve("godot_binding.c"));

    private static final List<String> EXPECTED_LINES = List.of(
            "EV call argc=1 m=ping",
            "EV ret=INT:777",
            "EV call argc=4 m=add_score a1=INT:41 a2=FLOAT:2.5 a3=BOOL:1",
            "EV scalar_call_large_allocs=0",
            "EV ret=INT:777",
            "EV call argc=4 m=mix a1=SN:Target a2=V3:(1.0,2.0,3.0) a3=OBJ:abc123",
            "EV ret=INT:777",
            "EV variant_balance=ok",
            "EV mem_balance=0",
            "EV errors=0",
            "OK dynamic_call");

    @TempDir
    private static Path sharedDir;

    private static RuntimeProbeToolchain zig;
    private static RuntimeProbeToolchain tcc;

    @BeforeAll
    static void findToolchains() {
        zig = RuntimeProbeToolchain.zig();
        tcc = RuntimeProbeToolchain.tcc();
    }

    @Test
    void dynamicCallMacrosRunOnZigArtifact() throws IOException, InterruptedException {
        Assumptions.assumeTrue(zig != null, "zig is required for the baseline leg");
        var run = zig.compileLinkAndRun(sharedDir, "dynamic_call_probe", PROBE, RUNTIME_SOURCES, List.of());
        assertEquals(0, run.exitCode(), run::diagnostic);
        assertEquals(EXPECTED_LINES, run.outputLines(), run::diagnostic);
    }

    @Test
    void dynamicCallMacrosRunOnTccArtifact() throws IOException, InterruptedException {
        Assumptions.assumeTrue(tcc != null, "tinycc CLI is required for the tcc leg");
        var run = tcc.compileLinkAndRun(sharedDir, "dynamic_call_probe", PROBE, RUNTIME_SOURCES, List.of());
        assertEquals(0, run.exitCode(), run::diagnostic);
        assertEquals(EXPECTED_LINES, run.outputLines(), run::diagnostic);
    }

    @Test
    void dynamicCallMacroBehaviorIsByteIdenticalAcrossCompilers() throws IOException, InterruptedException {
        Assumptions.assumeTrue(zig != null && tcc != null, "both toolchains are required for the parity assertion");
        var zigRun = zig.compileLinkAndRun(sharedDir, "dynamic_call_probe", PROBE, RUNTIME_SOURCES, List.of());
        var tccRun = tcc.compileLinkAndRun(sharedDir, "dynamic_call_probe", PROBE, RUNTIME_SOURCES, List.of());
        assertEquals(0, zigRun.exitCode(), zigRun::diagnostic);
        assertEquals(0, tccRun.exitCode(), tccRun::diagnostic);
        assertEquals(zigRun.outputLines(), tccRun.outputLines(),
                () -> "tcc artifact diverged from the zig baseline:\nzig:\n" + zigRun.diagnostic() + "\ntcc:\n" + tccRun.diagnostic());
    }

    /// The probe source lives in `src/test/resources/c/dynamic_call_probe.c` (the fake Variant
    /// layout contract is documented at the top of that file).
    private static final String PROBE = CProbeSources.load("dynamic_call_probe.c");
}
