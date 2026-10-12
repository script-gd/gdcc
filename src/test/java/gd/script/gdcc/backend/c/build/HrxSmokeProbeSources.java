package gd.script.gdcc.backend.c.build;

/// Shared C fixtures for the HRX hot-reload thunk runtime probes (contract:
/// doc/module_impl/backend/hot_reload_implementation.md). Both the zig baseline
/// ([GdccHrxRuntimeSmokeTest]) and the tinycc gate ([TinyCcHrxRuntimeGateTest]) compile the
/// exact same probe sources, so the two compiler backends are anchored to byte-identical
/// fixture behavior: each probe prints its `OK <name>` marker only after every CHECK passes,
/// and exits non-zero otherwise. The C sources themselves live in `src/test/resources/c/hrx/`;
/// this class only names them.
///
/// The fake engine sits at the GDExtension function-pointer level: Variant is opaque storage
/// whose bytes the fixture owns, Callable is a custom object whose callbacks stay invocable so
/// the real heap thunks execute on the host CPU, and "reload" is simulated by
/// deinitialize -> `gdcc_hrx_test_reset_image_state` -> initialize cycles (the runtime under
/// test is compiled with `-DGDCC_HRX_TEST_HOOKS`).
final class HrxSmokeProbeSources {
    private HrxSmokeProbeSources() {
    }

    /// Fake Godot engine behind the GDExtension interface table, including the Callable
    /// custom-object layer and the instance-binding table with real engine append/tombstone
    /// semantics.
    static final String FAKE_ENGINE = CProbeSources.load("hrx/fake_engine.c");

    /// The real RW -> write -> RX -> execute round trip must succeed on the host;
    /// HRX_UNAVAILABLE fail-closed behavior is anchored by the unavailable-mode probe in the
    /// zig suite.
    static final String EXECMEM_PROBE = CProbeSources.load("hrx/execmem_probe.c");

    /// Executes all four thunk kinds on real executable memory: call (arg0 swap to the capture
    /// block), is_valid, get_argument_count, and free (refcount zero crossing + two-phase sweep
    /// reclaim after a simulated reload).
    static final String LAMBDA_ROUNDTRIP_PROBE = CProbeSources.load("hrx/lambda_roundtrip_probe.c");

    /// Simulated reload with a rebind table: the schema-compatible survivor is upgraded in place
    /// to the new implementation (argument count refreshed), while the descriptor-mismatched one
    /// fails closed - stays invalid, never runs any impl, and takes the engine error path.
    static final String REBIND_MISMATCH_PROBE = CProbeSources.load("hrx/rebind_probe.c");

    /// Retaining a Callable across a reload cycle rebuilds a lookup key EQUAL to the connection's
    /// own (same thunk + spec, refcount only), and NULL/dead specs refuse retention.
    static final String RETAIN_PROBE = CProbeSources.load("hrx/retain_probe.c");
}
