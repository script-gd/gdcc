package gd.script.gdcc.backend.c.build;

import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.backend.c.gen.CCodegen;
import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.lowering.FrontendLoweringPassManager;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.scope.ClassRegistry;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Automated end-to-end editor hot-reload tests (source of truth:
/// `doc/module_impl/backend/hot_reload_implementation.md`). The non-editor direct-path
/// counterpart lives in `GodotRuntimeDirectPathIntegrationTest`.
///
/// Every test compiles one or more native library generations from GDScript sources through
/// the real frontend -> lowering -> C codegen -> Zig pipeline, installs it into a fresh
/// editor project, and drives a headless Godot EDITOR via `GodotEditorHotReloadTestSession`.
/// All behavioral assertions (instance survival, property restore, new-code dispatch,
/// virtual re-hooking, coroutine cancellation, Callable rebind/invalidation semantics,
/// static reset, engine-reported signature/parent errors) run inside the editor in an
/// interpreted SceneTree driver; Java only orchestrates markers, atomic library swaps, exit
/// codes and engine-side error substrings.
///
/// Environment-aware: skips through JUnit assumptions when Zig is missing, `GODOT_BIN` is unset,
/// or the binary is not editor-capable.
///
/// CONCURRENT: scenarios are fully independent — per-scenario build dirs (ZigCcCompiler serializes
/// only same-project rounds), per-scenario editor project dirs, and one shared compiler cache
/// whose PCH/zig content caches are designed for concurrent publishers. Build dirs and the shared
/// cache intentionally survive across runs so repeated executions reuse warm caches; only the
/// editor project dirs are recreated per run.
@Execution(ExecutionMode.CONCURRENT)
public class GodotEditorHotReloadIntegrationTest {
    private static final Path SCENARIO_ROOT = Path.of("tmp/test/editor_hot_reload");
    private static final String SHARED_COMPILER_CACHE_DIR_NAME = "shared-compiler-cache";
    private static final String UNREGISTER_ORDER_ERROR = "Attempt to unregister class while other extension classes inherit from it";

    /// Basic reload: the instance survives, STORAGE properties (exported and non-exported)
    /// are restored, and methods run NEW code after reload. Reverse-unregistration detection
    /// needs an inheritance chain; see the virtual/lifecycle cases.
    @Test
    void basicReloadPreservesInstancePropertiesAndRunsNewCode() throws Exception {
        requireToolingOrAbort();
        var scenario = "basic";
        var projectDir = prepareScenarioProjectDir(scenario);
        var buildDir = requireScenarioBuildDir(scenario);
        var moduleName = "hr_e2e_basic";
        var nameMap = Map.of("HrBasic", "RuntimeHrBasic");

        var v1 = buildModule(
                moduleName,
                List.of(new SourceFileSpec(sourceLabel(scenario, "hr_basic.gd"), hrBasicSource(1))),
                nameMap,
                buildDir
        );

        try (var session = new GodotEditorHotReloadTestSession(projectDir)) {
            session.prepareProject(requireSharedLibrary(v1), basicDriver());
            session.start();
            session.awaitMarker(GodotEditorHotReloadTestSession.PHASE1_MARKER);

            var v2 = buildModule(
                    moduleName,
                    List.of(new SourceFileSpec(sourceLabel(scenario, "hr_basic.gd"), hrBasicSource(2))),
                    nameMap,
                    buildDir
            );
            session.swapLibrary(requireSharedLibrary(v2));
            session.awaitMarker(GodotEditorHotReloadTestSession.PHASE2_MARKER);

            assertEditorRunClean(session);
        }
    }

    /// Inheritance and polymorphism: an override-only class and a pass-through class keep
    /// correct virtual dispatch after reload, and engine-driven `_process` enters the NEW
    /// library implementation on the next frames (engine virtual re-hooking). Any
    /// unregistration order violation makes the engine print its order error, asserted
    /// absent. The fixture classes are `@tool` because gdcc suppresses frame-loop virtuals
    /// for non-tool classes in the editor (matching Godot's non-tool script behavior).
    @Test
    void virtualDispatchAndProcessRehookAfterReload() throws Exception {
        requireToolingOrAbort();
        var scenario = "virtual";
        var projectDir = prepareScenarioProjectDir(scenario);
        var buildDir = requireScenarioBuildDir(scenario);
        var moduleName = "hr_e2e_virtual";
        var nameMap = Map.of(
                "HrVtBase", "RuntimeHrVtBase",
                "HrVtOverride", "RuntimeHrVtOverride",
                "HrVtPass", "RuntimeHrVtPass"
        );

        var v1 = buildModule(
                moduleName,
                List.of(
                        new SourceFileSpec(sourceLabel(scenario, "hr_vt_base.gd"), hrVtBaseSource(1, 1)),
                        new SourceFileSpec(sourceLabel(scenario, "hr_vt_override.gd"), hrVtOverrideSource(1)),
                        new SourceFileSpec(sourceLabel(scenario, "hr_vt_pass.gd"), hrVtPassSource())
                ),
                nameMap,
                buildDir
        );

        try (var session = new GodotEditorHotReloadTestSession(projectDir)) {
            session.prepareProject(requireSharedLibrary(v1), virtualDriver());
            session.start();
            session.awaitMarker(GodotEditorHotReloadTestSession.PHASE1_MARKER);

            var v2 = buildModule(
                    moduleName,
                    List.of(
                            new SourceFileSpec(sourceLabel(scenario, "hr_vt_base.gd"), hrVtBaseSource(2, 100)),
                            new SourceFileSpec(sourceLabel(scenario, "hr_vt_override.gd"), hrVtOverrideSource(2)),
                            new SourceFileSpec(sourceLabel(scenario, "hr_vt_pass.gd"), hrVtPassSource())
                    ),
                    nameMap,
                    buildDir
            );
            session.swapLibrary(requireSharedLibrary(v2));
            session.awaitMarker(GodotEditorHotReloadTestSession.PHASE2_MARKER);

            assertEditorRunClean(session);
        }
    }

    /// A coroutine suspended on a signal (with an awaiting parent coroutine) is silently
    /// cancelled by reload — the awaiting parent is never resumed, the cancelled body never
    /// continues, and the stale signal emission afterwards is a no-op; a coroutine started
    /// on the NEW library completes normally. Direct observation of the state object's
    /// `completed` signal is not part of this automated leg.
    @Test
    void pendingCoroutinesAreSilentlyCancelledOnReload() throws Exception {
        requireToolingOrAbort();
        var scenario = "coroutine";
        var projectDir = prepareScenarioProjectDir(scenario);
        var buildDir = requireScenarioBuildDir(scenario);
        var moduleName = "hr_e2e_coro";
        var nameMap = Map.of("HrCoro", "RuntimeHrCoro");

        var v1 = buildModule(
                moduleName,
                List.of(new SourceFileSpec(sourceLabel(scenario, "hr_coro.gd"), hrCoroSource(1))),
                nameMap,
                buildDir
        );

        try (var session = new GodotEditorHotReloadTestSession(projectDir)) {
            session.prepareProject(requireSharedLibrary(v1), coroutineDriver());
            session.start();
            session.awaitMarker(GodotEditorHotReloadTestSession.PHASE1_MARKER);

            var v2 = buildModule(
                    moduleName,
                    List.of(new SourceFileSpec(sourceLabel(scenario, "hr_coro.gd"), hrCoroSource(2))),
                    nameMap,
                    buildDir
            );
            session.swapLibrary(requireSharedLibrary(v2));
            session.awaitMarker(GodotEditorHotReloadTestSession.PHASE2_MARKER);

            assertEditorRunClean(session);
        }
    }

    /// Lifecycle matrix: normal PREDELETE->free before reload, reload free->recreate,
    /// recreate then normal destruction, two consecutive reloads, base+derived destroyable
    /// fields (String/Array/Dictionary contents and a RefCounted ObjectDB-path property
    /// restored by the engine), reverse unregistration across the inheritance chain, and a
    /// suspended coroutine (with a parameter slot) cancelled on reload. Without sanitizers
    /// this automated leg proves crash-freedom and value correctness; the exactly-once/leak
    /// proof stays on the manual ASan/Valgrind checklist.
    @Test
    void lifecycleMatrixAcrossTwoConsecutiveReloads() throws Exception {
        requireToolingOrAbort();
        var scenario = "lifecycle";
        var projectDir = prepareScenarioProjectDir(scenario);
        var buildDir = requireScenarioBuildDir(scenario);
        var moduleName = "hr_e2e_life";
        var nameMap = Map.of(
                "HrLifeBase", "RuntimeHrLifeBase",
                "HrLifeDerived", "RuntimeHrLifeDerived"
        );

        var v1 = buildModule(
                moduleName,
                List.of(
                        new SourceFileSpec(sourceLabel(scenario, "hr_life_base.gd"), hrLifeBaseSource(1)),
                        new SourceFileSpec(sourceLabel(scenario, "hr_life_derived.gd"), hrLifeDerivedSource())
                ),
                nameMap,
                buildDir
        );

        try (var session = new GodotEditorHotReloadTestSession(projectDir)) {
            session.prepareProject(requireSharedLibrary(v1), lifecycleDriver());
            session.start();
            session.awaitMarker(GodotEditorHotReloadTestSession.PHASE1_MARKER);

            var v2 = buildModule(
                    moduleName,
                    List.of(
                            new SourceFileSpec(sourceLabel(scenario, "hr_life_base.gd"), hrLifeBaseSource(2)),
                            new SourceFileSpec(sourceLabel(scenario, "hr_life_derived.gd"), hrLifeDerivedSource())
                    ),
                    nameMap,
                    buildDir
            );
            session.swapLibrary(requireSharedLibrary(v2));
            session.awaitMarker(GodotEditorHotReloadTestSession.PHASE2_MARKER);

            var v3 = buildModule(
                    moduleName,
                    List.of(
                            new SourceFileSpec(sourceLabel(scenario, "hr_life_base.gd"), hrLifeBaseSource(3)),
                            new SourceFileSpec(sourceLabel(scenario, "hr_life_derived.gd"), hrLifeDerivedSource())
                    ),
                    nameMap,
                    buildDir
            );
            session.swapLibrary(requireSharedLibrary(v3));
            session.awaitMarker(GodotEditorHotReloadTestSession.PHASE3_MARKER);

            assertEditorRunClean(session);
        }
    }

    /// A `Callable(object, "method-name")` signal connection survives reload and dispatches
    /// into the NEW method body (engine `try_update` re-bind; always valid, orthogonal to
    /// the lambda thunk machinery). The connection is armed on the first generation; the
    /// emit after reload must observe the new generation through the SAME connection.
    @Test
    void methodNameConnectionSurvivesReloadAndRunsNewLogic() throws Exception {
        requireToolingOrAbort();
        var scenario = "method_cb";
        var projectDir = prepareScenarioProjectDir(scenario);
        var buildDir = requireScenarioBuildDir(scenario);
        var moduleName = "hr_e2e_method_cb";
        var nameMap = Map.of("HrMethodCb", "RuntimeHrMethodCb");

        var v1 = buildModule(
                moduleName,
                List.of(new SourceFileSpec(sourceLabel(scenario, "hr_method_cb.gd"), hrMethodCbSource(1))),
                nameMap,
                buildDir
        );

        try (var session = new GodotEditorHotReloadTestSession(projectDir)) {
            session.prepareProject(requireSharedLibrary(v1), methodCbDriver());
            session.start();
            session.awaitMarker(GodotEditorHotReloadTestSession.PHASE1_MARKER);

            var v2 = buildModule(
                    moduleName,
                    List.of(new SourceFileSpec(sourceLabel(scenario, "hr_method_cb.gd"), hrMethodCbSource(100))),
                    nameMap,
                    buildDir
            );
            session.swapLibrary(requireSharedLibrary(v2));
            session.awaitMarker(GodotEditorHotReloadTestSession.PHASE2_MARKER);

            assertEditorRunClean(session);
        }
    }

    /// A lambda signal connection established on the first generation keeps working after
    /// reload and executes the NEW lambda body (identical impl_key + schema, changed body).
    /// Asserts both dispatch paths of the engine-held Callable copy (signal emission and a
    /// direct `call()` on a driver-retained Callable), `is_valid()` staying true across the
    /// rebind, and the thunk-path `to_string` contract (`<CallableCustom>`).
    @Test
    void lambdaConnectionRebindsToNewImplementationAfterReload() throws Exception {
        requireToolingOrAbort();
        var scenario = "lambda_rebind";
        var projectDir = prepareScenarioProjectDir(scenario);
        var buildDir = requireScenarioBuildDir(scenario);
        var moduleName = "hr_e2e_lambda_rebind";
        var nameMap = Map.of("HrLambdaRebind", "RuntimeHrLambdaRebind");

        var v1 = buildModule(
                moduleName,
                List.of(new SourceFileSpec(sourceLabel(scenario, "hr_lambda_rebind.gd"), hrLambdaRebindSource(1, 2))),
                nameMap,
                buildDir
        );

        try (var session = new GodotEditorHotReloadTestSession(projectDir)) {
            session.prepareProject(requireSharedLibrary(v1), lambdaRebindDriver());
            session.start();
            session.awaitMarker(GodotEditorHotReloadTestSession.PHASE1_MARKER);

            var v2 = buildModule(
                    moduleName,
                    List.of(new SourceFileSpec(sourceLabel(scenario, "hr_lambda_rebind.gd"), hrLambdaRebindSource(100, 10))),
                    nameMap,
                    buildDir
            );
            session.swapLibrary(requireSharedLibrary(v2));
            session.awaitMarker(GodotEditorHotReloadTestSession.PHASE2_MARKER);

            assertEditorRunClean(session);
        }
    }

    /// Lambda invalidation paths. Three legs in one scenario: (a) capture-layout change ->
    /// schema mismatch, (b) lambda deletion -> impl_key gone, (c) two DIFFERENT-schema
    /// lambdas swapping source positions -> each old key collides with the other lambda's
    /// entry but the schema gate refuses the rebind, so the old Callable is invalidated
    /// instead of being bound to the wrong body. The surviving sig_a lambda is the positive
    /// control (rebound, runs new code; it is re-asserted after emit_b so a deleted lambda
    /// rebound to its same-schema sibling would also fail). Assertions: `is_valid() ==
    /// false` on every retained Callable INCLUDING the engine-held sig_b connection (via
    /// `get_signal_connection_list`), and signal emission silently skips the dead connection.
    /// Actually CALLING an invalidated Callable is deliberately NOT exercised from the
    /// interpreted driver: on Godot 4.5.2 that raises the engine's standard error as a hard
    /// SCRIPT ERROR ("on a null instance") and would kill the driver.
    @Test
    void lambdaInvalidationPathsFailClosedAfterReload() throws Exception {
        requireToolingOrAbort();
        var scenario = "lambda_invalidate";
        var projectDir = prepareScenarioProjectDir(scenario);
        var buildDir = requireScenarioBuildDir(scenario);
        var moduleName = "hr_e2e_lambda_inv";
        var nameMap = Map.of("HrInv", "RuntimeHrInv");

        var v1 = buildModule(
                moduleName,
                List.of(new SourceFileSpec(sourceLabel(scenario, "hr_inv.gd"), hrInvSource(1))),
                nameMap,
                buildDir
        );

        try (var session = new GodotEditorHotReloadTestSession(projectDir)) {
            session.prepareProject(requireSharedLibrary(v1), lambdaInvalidateDriver());
            session.start();
            session.awaitMarker(GodotEditorHotReloadTestSession.PHASE1_MARKER);

            var v2 = buildModule(
                    moduleName,
                    List.of(new SourceFileSpec(sourceLabel(scenario, "hr_inv.gd"), hrInvSource(2))),
                    nameMap,
                    buildDir
            );
            session.swapLibrary(requireSharedLibrary(v2));
            session.awaitMarker(GodotEditorHotReloadTestSession.PHASE2_MARKER);

            assertEditorRunClean(session);
        }
    }

    /// Deferred lambda calls queued BEFORE the reload execute AFTER it with the same
    /// rebind/invalidation semantics as synchronous dispatch (the MessageQueue holds Callable
    /// copies that share the same spec). The driver queues both deferred calls and reloads
    /// within the same frame, so the queue flush at frame end observes the new generation:
    /// the compatible lambda runs new code, the capture-mismatched one is silently skipped.
    /// The engine's deferred-call error for the invalidated entry is a plain ERROR print
    /// (not a SCRIPT ERROR), pinned textually on the Java side as positive evidence that
    /// the invalid entry was actually attempted by the queue.
    @Test
    void deferredLambdaCallsFollowRebindAndInvalidationSemanticsAfterReload() throws Exception {
        requireToolingOrAbort();
        var scenario = "lambda_deferred";
        var projectDir = prepareScenarioProjectDir(scenario);
        var buildDir = requireScenarioBuildDir(scenario);
        var moduleName = "hr_e2e_lambda_deferred";
        var nameMap = Map.of("HrDeferred", "RuntimeHrDeferred");

        var v1 = buildModule(
                moduleName,
                List.of(new SourceFileSpec(sourceLabel(scenario, "hr_deferred.gd"), hrDeferredSource(1))),
                nameMap,
                buildDir
        );

        try (var session = new GodotEditorHotReloadTestSession(projectDir)) {
            session.prepareProject(requireSharedLibrary(v1), lambdaDeferredDriver());
            session.start();
            session.awaitMarker(GodotEditorHotReloadTestSession.PHASE1_MARKER);

            var v2 = buildModule(
                    moduleName,
                    List.of(new SourceFileSpec(sourceLabel(scenario, "hr_deferred.gd"), hrDeferredSource(2))),
                    nameMap,
                    buildDir
            );
            session.swapLibrary(requireSharedLibrary(v2));
            session.awaitMarker(GodotEditorHotReloadTestSession.PHASE2_MARKER);

            assertEditorRunClean(session);
            assertTrue(
                    session.combinedOutput().contains("Error calling deferred method"),
                    () -> "Engine did not report the invalidated deferred call. Output:\n" + session.combinedOutput()
            );
        }
    }

    /// After a method signature change, calls issued with the OLD signature fail through
    /// the engine's standard error path without crashing. The automated leg is a dynamic
    /// `Object.call()` with the old arity against the reloaded (try_update'd) MethodBind —
    /// on Godot 4.5.2 that reports the standard invalid-call error as a hard SCRIPT ERROR.
    /// A literally stale (pre-reload cached) MethodBind* caller is native-only and outside
    /// an interpreted driver's reach; both paths share the "error, no crash" contract. The
    /// driver advances its stage machine before triggering the error so the editor still
    /// quits cleanly, and Java pins the diagnostic text.
    @Test
    void methodSignatureChangeBreaksStaleCallsWithoutCrash() throws Exception {
        requireToolingOrAbort();
        var scenario = "sig_change";
        var projectDir = prepareScenarioProjectDir(scenario);
        var buildDir = requireScenarioBuildDir(scenario);
        var moduleName = "hr_e2e_sig_change";
        var nameMap = Map.of("HrSigChange", "RuntimeHrSigChange");

        var v1 = buildModule(
                moduleName,
                List.of(new SourceFileSpec(sourceLabel(scenario, "hr_sig_change.gd"), hrSigChangeSource(1))),
                nameMap,
                buildDir
        );

        try (var session = new GodotEditorHotReloadTestSession(projectDir)) {
            session.prepareProject(requireSharedLibrary(v1), sigChangeDriver());
            session.start();
            session.awaitMarker(GodotEditorHotReloadTestSession.PHASE1_MARKER);

            var v2 = buildModule(
                    moduleName,
                    List.of(new SourceFileSpec(sourceLabel(scenario, "hr_sig_change.gd"), hrSigChangeSource(2))),
                    nameMap,
                    buildDir
            );
            session.swapLibrary(requireSharedLibrary(v2));
            session.awaitMarker(GodotEditorHotReloadTestSession.PHASE2_MARKER);

            // Custom assertions (not assertEditorRunClean): the driver deliberately triggers the
            // engine's standard invalid-call SCRIPT ERROR as the pinned stale-signature behavior.
            var combinedOutput = session.combinedOutput();
            assertEquals(0, session.awaitExit(), () -> "Godot editor exited abnormally. Output:\n" + combinedOutput);
            assertTrue(session.failLines().isEmpty(), () -> "Driver reported failures: " + session.failLines() + "\n" + combinedOutput);
            assertTrue(
                    combinedOutput.contains("Invalid call to function 'pair_sum")
                            && combinedOutput.contains("Expected 1 argument(s)"),
                    () -> "Engine did not report the stale-signature call as invalid. Output:\n" + combinedOutput
            );
        }
    }

    /// Static variables reset to their initializer on reload — asserted against
    /// the NEW library's initializer (v1 writes 42 at runtime, v2's initializer is 5, so reading 5
    /// after reload proves the static backing was rebuilt from the new image, not preserved). A
    /// STORAGE instance property on the surviving instance is the contrast anchor (restored).
    @Test
    void staticVariablesResetToInitializerAfterReload() throws Exception {
        requireToolingOrAbort();
        var scenario = "static_reset";
        var projectDir = prepareScenarioProjectDir(scenario);
        var buildDir = requireScenarioBuildDir(scenario);
        var moduleName = "hr_e2e_static_reset";
        var nameMap = Map.of("HrStatic", "RuntimeHrStatic");

        var v1 = buildModule(
                moduleName,
                List.of(new SourceFileSpec(sourceLabel(scenario, "hr_static.gd"), hrStaticSource(3))),
                nameMap,
                buildDir
        );

        try (var session = new GodotEditorHotReloadTestSession(projectDir)) {
            session.prepareProject(requireSharedLibrary(v1), staticResetDriver());
            session.start();
            session.awaitMarker(GodotEditorHotReloadTestSession.PHASE1_MARKER);

            var v2 = buildModule(
                    moduleName,
                    List.of(new SourceFileSpec(sourceLabel(scenario, "hr_static.gd"), hrStaticSource(5))),
                    nameMap,
                    buildDir
            );
            session.swapLibrary(requireSharedLibrary(v2));
            session.awaitMarker(GodotEditorHotReloadTestSession.PHASE2_MARKER);

            assertEditorRunClean(session);
        }
    }

    /// Changing a class's ENGINE parent across reload is not supported — the engine must
    /// complain (its known "requires editor restart" semantics), and the gdcc side must not
    /// pretend success. The driver survives no matter what the engine does to the extension,
    /// reports the reload status, and quits cleanly; Java pins the observed engine
    /// diagnostic. Changing a GDCC parent is engine-silent by design and stays a documented
    /// unsupported case, not an automated assertion.
    @Test
    void engineParentChangeIsReportedByEngineAsUnsupported() throws Exception {
        requireToolingOrAbort();
        var scenario = "parent_change";
        var projectDir = prepareScenarioProjectDir(scenario);
        var buildDir = requireScenarioBuildDir(scenario);
        var moduleName = "hr_e2e_parent_change";
        var nameMap = Map.of("HrParentChange", "RuntimeHrParentChange");

        var v1 = buildModule(
                moduleName,
                List.of(new SourceFileSpec(sourceLabel(scenario, "hr_parent_change.gd"), hrParentChangeSource(1))),
                nameMap,
                buildDir
        );

        try (var session = new GodotEditorHotReloadTestSession(projectDir)) {
            session.prepareProject(requireSharedLibrary(v1), parentChangeDriver());
            session.start();
            session.awaitMarker(GodotEditorHotReloadTestSession.PHASE1_MARKER);

            var v2 = buildModule(
                    moduleName,
                    List.of(new SourceFileSpec(sourceLabel(scenario, "hr_parent_change.gd"), hrParentChangeSource(2))),
                    nameMap,
                    buildDir
            );
            session.swapLibrary(requireSharedLibrary(v2));
            session.awaitMarker(GodotEditorHotReloadTestSession.PHASE2_MARKER);

            var combinedOutput = session.combinedOutput();
            assertEquals(0, session.awaitExit(), () -> "Godot editor exited abnormally. Output:\n" + combinedOutput);
            assertTrue(session.failLines().isEmpty(), () -> "Driver reported failures: " + session.failLines() + "\n" + combinedOutput);
            // Pinned engine behavior (Godot 4.5.2): reload_extension itself returns OK (the
            // driver prints HR_RELOAD_STATUS:0), but the parent-changed class is refused with
            // the restart-required diagnostic. Follow-up noise ("Attempt to unregister unexisting
            // extension class" and exit-time RID leak reports) is the engine's own fallout of the
            // half-applied reload and is part of why the contract declares this unsupported.
            assertTrue(
                    combinedOutput.contains("cannot change parent type from 'Node' to 'Node2D' on hot reload")
                            && combinedOutput.contains("Restart Godot for this change to take effect"),
                    () -> "Engine did not report the parent change as restart-required. Output:\n" + combinedOutput
            );
            assertTrue(
                    combinedOutput.contains("HR_RELOAD_STATUS:0"),
                    () -> "reload_extension should return OK at the extension level despite the refused class. Output:\n" + combinedOutput
            );
        }
    }

    /// Lambda identity acceptance trio; all three legs run in ONE scenario.
    /// Leg 1 (swap gate): two same-schema lambdas connected to sig_swap_a/sig_swap_b swap their
    /// connect statements in the second generation. Ordinal keys stay `arm_swap#0/#1` but the
    /// callsite contexts cross (base=sig_swap_a <-> base=sig_swap_b), so the context gate must
    /// fail-closed BOTH old connections.
    /// Leg 2 (ordinal immunity): a plain non-lambda line is inserted before the sig_shift
    /// connect; the ordinal key `#0` and context are unchanged, so the connection must rebind
    /// and run the new body.
    /// Leg 3 (extract-variable false invalidation): `return func...` becomes
    /// `var cb := func...; return cb`, changing only the callsite context (`return` ->
    /// `assign(var=cb, kind=var)`), so the retained first-generation Callable must invalidate
    /// (documented expected false invalidation) while a post-reload `make_cb()` yields a
    /// working callable.
    @Test
    void lambdaOrdinalKeyAndCallsiteContextGateAfterReload() throws Exception {
        requireToolingOrAbort();
        var scenario = "lambda_ordinal";
        var projectDir = prepareScenarioProjectDir(scenario);
        var buildDir = requireScenarioBuildDir(scenario);
        var moduleName = "hr_e2e_lambda_ordinal";
        var nameMap = Map.of("HrOrdinal", "RuntimeHrOrdinal");

        var v1 = buildModule(
                moduleName,
                List.of(new SourceFileSpec(sourceLabel(scenario, "hr_ordinal.gd"), hrOrdinalSource(1))),
                nameMap,
                buildDir
        );

        try (var session = new GodotEditorHotReloadTestSession(projectDir)) {
            session.prepareProject(requireSharedLibrary(v1), lambdaOrdinalDriver());
            session.start();
            session.awaitMarker(GodotEditorHotReloadTestSession.PHASE1_MARKER);

            var v2 = buildModule(
                    moduleName,
                    List.of(new SourceFileSpec(sourceLabel(scenario, "hr_ordinal.gd"), hrOrdinalSource(2))),
                    nameMap,
                    buildDir
            );
            session.swapLibrary(requireSharedLibrary(v2));
            session.awaitMarker(GodotEditorHotReloadTestSession.PHASE2_MARKER);

            assertEditorRunClean(session);
        }
    }

    private static void requireToolingOrAbort() {
        if (ZigUtil.findZig() == null) {
            Assumptions.abort("Zig not found; skipping editor hot reload integration test");
            return;
        }
        GodotEditorHotReloadTestSession.requireEditorCapableGodotOrAbort();
    }

    private static void assertEditorRunClean(@NotNull GodotEditorHotReloadTestSession session) throws IOException, InterruptedException {
        var exitCode = session.awaitExit();
        var combinedOutput = session.combinedOutput();
        assertEquals(0, exitCode, () -> "Godot editor exited abnormally. Output:\n" + combinedOutput);
        assertTrue(session.failLines().isEmpty(), () -> "Driver reported failures: " + session.failLines() + "\n" + combinedOutput);
        assertFalse(
                combinedOutput.contains(UNREGISTER_ORDER_ERROR),
                () -> "Reverse class unregistration violated. Output:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("SCRIPT ERROR"),
                () -> "Driver script error detected. Output:\n" + combinedOutput
        );
    }

    /// Recreates the editor project dir of one scenario so every run starts from a fresh editor
    /// state (`.godot` cache, swap flags and installed libraries never leak between runs).
    private static @NotNull Path prepareScenarioProjectDir(@NotNull String scenario) throws IOException {
        var projectDir = SCENARIO_ROOT.resolve("project_" + scenario).toAbsolutePath();
        if (Files.exists(projectDir)) {
            try (var walk = Files.walk(projectDir)) {
                for (var path : walk.sorted(Comparator.reverseOrder()).toList()) {
                    if (!path.equals(projectDir)) {
                        Files.deleteIfExists(path);
                    }
                }
            }
        }
        Files.createDirectories(projectDir);
        return projectDir;
    }

    /// Returns the persistent native build dir of one scenario and guarantees the shared compiler
    /// cache root exists next to it (`ZigCcCompiler` adopts `<parent>/shared-compiler-cache` when
    /// the directory exists). Build dirs and the shared cache intentionally survive across runs:
    /// v2/v3 rebuilds hit zig's per-TU content cache for the runtime TUs (only `entry.c` changes
    /// between versions) and repeated executions of this suite start warm. PCH entries also live
    /// under this shared root but are keyed per include-tree path, so each scenario keeps its own
    /// PCH artifact.
    private static @NotNull Path requireScenarioBuildDir(@NotNull String scenario) throws IOException {
        var sharedCacheDir = SCENARIO_ROOT.resolve(SHARED_COMPILER_CACHE_DIR_NAME).toAbsolutePath();
        Files.createDirectories(sharedCacheDir);
        var buildDir = SCENARIO_ROOT.resolve("build_" + scenario).toAbsolutePath();
        Files.createDirectories(buildDir);
        return buildDir;
    }

    /// Virtual parse path of one GDScript fixture (never written to disk; used for diagnostics and
    /// default class naming only, mirroring the other frontend integration tests).
    private static @NotNull Path sourceLabel(@NotNull String scenario, @NotNull String fileName) {
        return SCENARIO_ROOT.resolve("src").resolve(scenario).resolve(fileName).toAbsolutePath();
    }

    private static @NotNull CBuildResult buildModule(
            @NotNull String moduleName,
            @NotNull List<SourceFileSpec> sources,
            @NotNull Map<String, String> topLevelCanonicalNameMap,
            @NotNull Path buildDir
    ) throws IOException {
        var parser = new GdScriptParserService();
        var parseDiagnostics = new DiagnosticManager();
        var units = sources.stream()
                .map(source -> parser.parseUnit(source.sourcePath(), source.source(), parseDiagnostics))
                .toList();
        assertTrue(parseDiagnostics.isEmpty(), () -> "Unexpected parse diagnostics: " + parseDiagnostics.snapshot());
        var module = new FrontendModule(moduleName, units, topLevelCanonicalNameMap);

        var diagnostics = new DiagnosticManager();
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadVersion(GodotVersion.V451));
        var lowered = new FrontendLoweringPassManager().lower(module, classRegistry, diagnostics);
        assertNotNull(lowered, () -> "Lowering returned null with diagnostics: " + diagnostics.snapshot());
        assertFalse(diagnostics.hasErrors(), () -> "Unexpected frontend diagnostics: " + diagnostics.snapshot());

        Files.createDirectories(buildDir);
        var projectInfo = new CProjectInfo(
                moduleName,
                GodotVersion.V451,
                buildDir,
                COptimizationLevel.DEBUG,
                TargetPlatform.getNativePlatform()
        );
        var codegen = new CCodegen();
        codegen.prepare(new CodegenContext(projectInfo, classRegistry), lowered);
        var buildResult = new CProjectBuilder().buildProject(projectInfo, codegen);
        assertTrue(buildResult.success(), () -> "Native build should succeed. Build log:\n" + buildResult.buildLog());
        return buildResult;
    }

    private static @NotNull Path requireSharedLibrary(@NotNull CBuildResult buildResult) {
        return buildResult.artifacts().stream()
                .filter(artifact -> {
                    var name = artifact.getFileName().toString();
                    return name.endsWith(".so") || name.endsWith(".dll") || name.endsWith(".dylib") || name.endsWith(".wasm");
                })
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No shared library artifact in " + buildResult.artifacts()));
    }

    private static @NotNull String hrBasicSource(int version) {
        return """
                class_name HrBasic
                extends Node

                @export var exported_num: int = 7
                var plain_num: int = 11
                var default_num: int = 5
                var label: String = "init"

                func greet() -> String:
                    return "hello-v%d"
                """.formatted(version);
    }

    private static @NotNull String hrVtBaseSource(int version, int processStep) {
        // @tool is mandatory: gdcc deliberately suppresses `_process`/`_physics_process` for
        // non-tool classes in the editor (non-tool script parity), so only a tool class can
        // observe engine virtual re-hooking inside an editor session.
        return """
                @tool
                class_name HrVtBase
                extends Node

                var ticks: int = 0

                func _process(delta: float) -> void:
                    ticks += %d

                func role() -> String:
                    return "base-v%d"

                func whoami() -> String:
                    return role()
                """.formatted(processStep, version);
    }

    private static @NotNull String hrVtOverrideSource(int version) {
        return """
                @tool
                class_name HrVtOverride
                extends HrVtBase

                func role() -> String:
                    return "override-v%d"
                """.formatted(version);
    }

    private static @NotNull String hrVtPassSource() {
        return """
                @tool
                class_name HrVtPass
                extends HrVtBase

                func marker() -> int:
                    return 3
                """;
    }

    private static @NotNull String hrCoroSource(int leafAddend) {
        return """
                class_name HrCoro
                extends Node

                signal release(value: int)

                var events: Array = []
                var result: int = -1
                var done: bool = false

                func leaf() -> int:
                    events.append("leaf:wait")
                    var value: int = await release
                    events.append("leaf:done")
                    return value + %d

                func outer() -> void:
                    events.append("outer:wait")
                    result = await leaf()
                    events.append("outer:done")
                    done = true

                func start_run() -> void:
                    outer()

                func emit_release(value: int) -> void:
                    release.emit(value)

                func read_events() -> Array:
                    return events

                func read_result() -> int:
                    return result

                func read_done() -> bool:
                    return done
                """.formatted(leafAddend);
    }

    private static @NotNull String hrLifeBaseSource(int version) {
        return """
                class_name HrLifeBase
                extends Node

                signal hold(value: int)

                var base_text: String = "base-init"
                var base_list: Array = [1, 2]
                var base_ref: RefCounted = null
                var coro_result: int = -1
                var coro_suspended: bool = false

                func base_tag() -> String:
                    return "base-v%d"

                func arm() -> void:
                    base_ref = RefCounted.new()

                func worker(amount: int) -> void:
                    coro_suspended = true
                    var got: int = await hold
                    coro_result = got + amount

                func start_coro(amount: int) -> void:
                    worker(amount)

                func emit_hold(value: int) -> void:
                    hold.emit(value)

                func read_coro_result() -> int:
                    return coro_result
                """.formatted(version);
    }

    private static @NotNull String hrLifeDerivedSource() {
        return """
                class_name HrLifeDerived
                extends HrLifeBase

                var child_text: String = "child-init"
                var child_dict: Dictionary = {"k": 1}

                func full_tag() -> String:
                    return base_tag() + "/derived"
                """;
    }

    /// Scenario 5 fixture. `_on_ping` is connected by bare method reference, i.e. the connection
    /// holds `Callable(self, "_on_ping")`; only the emitted addend changes between versions.
    private static @NotNull String hrMethodCbSource(int addend) {
        return """
                class_name HrMethodCb
                extends Node

                signal ping(value: int)

                var hits: int = 0
                var last: int = -1

                func arm() -> void:
                    ping.connect(_on_ping)

                func emit_ping(value: int) -> void:
                    ping.emit(value)

                func _on_ping(value: int) -> void:
                    hits += 1
                    last = value + %d
                """.formatted(addend);
    }

    /// Scenario 5a fixture. Both versions keep every lambda at the same source position with the
    /// same captures and signature (impl_key + schema_desc unchanged) and change only the body
    /// constants, so the reload must rebind, never invalidate. `utility_to_string` anchors the
    /// thunk-path to_string contract (standalone Callable renders `<CallableCustom>` in HRX mode).
    private static @NotNull String hrLambdaRebindSource(int addend, int multiplier) {
        return """
                class_name HrLambdaRebind
                extends Node

                signal ping(value: int)

                var hits: int = 0
                var last: int = -1

                func arm() -> void:
                    ping.connect(func(value: int) -> void:
                        hits += 1
                        last = value + %d
                    )

                func emit_ping(value: int) -> void:
                    ping.emit(value)

                func make_cb() -> Callable:
                    return func(value: int) -> int:
                        hits += 1
                        return value * %d

                func utility_to_string() -> String:
                    var cb = lerp
                    return str(cb)
                """.formatted(addend, multiplier);
    }

    /// Scenario 5b fixture, two versions. Every invalidation trigger is engineered so the
    /// unchanged parts keep their lambda keys byte-identical:
    /// - sig_a lambda: same position/captures in both versions, body change only -> rebinds;
    /// - sig_b lambda: present in v1, deleted in v2 -> impl_key disappears;
    /// - mismatch lambda: `reserved` is DECLARED in both versions (same key) but only v2's body
    ///   references it -> capture layout changes -> schema mismatch;
    /// - make_pair lambdas: the two different-schema lambdas swap source positions in v2, so each
    ///   old key collides with the OTHER lambda's entry and only the schema gate prevents a
    ///   wrong-body rebind.
    private static @NotNull String hrInvSource(int version) {
        if (version == 1) {
            return """
                    class_name HrInv
                    extends Node

                    signal sig_a(value: int)
                    signal sig_b(value: int)

                    var a_hits: int = 0
                    var b_hits: int = 0

                    func arm() -> void:
                        sig_a.connect(func(value: int) -> void:
                            a_hits += value
                        )
                        sig_b.connect(func(value: int) -> void:
                            b_hits += value
                        )

                    func emit_a(value: int) -> void:
                        sig_a.emit(value)

                    func emit_b(value: int) -> void:
                        sig_b.emit(value)

                    func make_mismatch_cb() -> Callable:
                        var reserved: int = 1000
                        return func(value: int) -> int:
                            return value + 1

                    func make_pair(first: bool) -> Callable:
                        var int_cb := func(value: int) -> int:
                            return value + 10
                        var str_cb := func(value: int) -> String:
                            return "s" + str(value)
                        if first:
                            return int_cb
                        return str_cb
                    """;
        }
        return """
                class_name HrInv
                extends Node

                signal sig_a(value: int)
                signal sig_b(value: int)

                var a_hits: int = 0
                var b_hits: int = 0

                func arm() -> void:
                    sig_a.connect(func(value: int) -> void:
                        a_hits += value * 2
                    )

                func emit_a(value: int) -> void:
                    sig_a.emit(value)

                func emit_b(value: int) -> void:
                    sig_b.emit(value)

                func make_mismatch_cb() -> Callable:
                    var reserved: int = 1000
                    return func(value: int) -> int:
                        return value + reserved

                func make_pair(first: bool) -> Callable:
                    var str_cb := func(value: int) -> String:
                        return "s" + str(value)
                    var int_cb := func(value: int) -> int:
                        return value + 10
                    if first:
                        return int_cb
                    return str_cb
                """;
    }

    /// Scenario 5c fixture. cb_a keeps position/captures/signature across versions (rebind leg);
    /// cb_b keeps its position but v2's body additionally references `reserved`, changing the
    /// capture layout (invalidation leg). `reserved` is declared in both versions so the lambda
    /// keys stay identical and only the schema differs.
    private static @NotNull String hrDeferredSource(int version) {
        if (version == 1) {
            return """
                    class_name HrDeferred
                    extends Node

                    var a_results: Array = []
                    var b_results: Array = []

                    func queue_deferred(value: int) -> void:
                        var cb_a := func(v: int) -> void:
                            a_results.append(v + 1)
                        var reserved: int = 7
                        var cb_b := func(v: int) -> void:
                            b_results.append(v + 2)
                        cb_a.call_deferred(value)
                        cb_b.call_deferred(value)

                    func clear_results() -> void:
                        a_results = []
                        b_results = []
                    """;
        }
        return """
                class_name HrDeferred
                extends Node

                var a_results: Array = []
                var b_results: Array = []

                func queue_deferred(value: int) -> void:
                    var cb_a := func(v: int) -> void:
                        a_results.append(v + 100)
                    var reserved: int = 7
                    var cb_b := func(v: int) -> void:
                        b_results.append(v + reserved)
                    cb_a.call_deferred(value)
                    cb_b.call_deferred(value)

                func clear_results() -> void:
                    a_results = []
                    b_results = []
                """;
    }

    /// Scenario 6 fixture: v2 narrows `pair_sum` from two parameters to one.
    private static @NotNull String hrSigChangeSource(int version) {
        if (version == 1) {
            return """
                    class_name HrSigChange
                    extends Node

                    func pair_sum(a: int, b: int) -> int:
                        return a + b

                    func label() -> String:
                        return "v1"
                    """;
        }
        return """
                class_name HrSigChange
                extends Node

                func pair_sum(a: int) -> int:
                    return a * 10

                func label() -> String:
                    return "v2"
                """;
    }

    /// Scenario 8 fixture: the static initializer differs between versions so the post-reload
    /// read proves the backing was rebuilt from the NEW image (v1 writes 42 at runtime; a
    /// preserved backing would still read 42, a rebuilt one reads the new initializer).
    private static @NotNull String hrStaticSource(int staticInit) {
        return """
                class_name HrStatic
                extends Node

                static var counter: int = %d
                var instance_num: int = 9

                func set_counter(value: int) -> void:
                    counter = value

                func get_counter() -> int:
                    return counter
                """.formatted(staticInit);
    }

    /// Scenario 7 fixture: v2 switches the ENGINE parent Node -> Node2D.
    private static @NotNull String hrParentChangeSource(int version) {
        if (version == 1) {
            return """
                    class_name HrParentChange
                    extends Node

                    func tag() -> String:
                        return "v1"
                    """;
        }
        return """
                class_name HrParentChange
                extends Node2D

                func tag() -> String:
                    return "v2"
                """;
    }

    /// Identity-trio fixture. First-to-second generation edits: leg 1 swaps the two
    /// same-schema connect statements inside `arm_swap` (ordinals stay #0/#1 but callsite
    /// contexts cross); leg 2 inserts a plain `var note` line before the `arm_shift` connect
    /// (ordinal unchanged, body constant x10 -> x20 proves the rebind); leg 3 rewrites
    /// `return func...` as `var cb := func...; return cb` (only the callsite context
    /// changes). All lambda bodies stay capture-compatible (self only) so the schema gate
    /// alone would rebind everywhere — legs 1 and 3 are decided purely by the
    /// callsite-context gate.
    private static @NotNull String hrOrdinalSource(int version) {
        if (version == 1) {
            return """
                    class_name HrOrdinal
                    extends Node

                    signal sig_swap_a(value: int)
                    signal sig_swap_b(value: int)
                    signal sig_shift(value: int)

                    var swap_a_hits: int = 0
                    var swap_b_hits: int = 0
                    var shift_hits: int = 0

                    func arm_swap() -> void:
                        sig_swap_a.connect(func(value: int) -> void:
                            swap_a_hits += value
                        )
                        sig_swap_b.connect(func(value: int) -> void:
                            swap_b_hits += value
                        )

                    func arm_shift() -> void:
                        sig_shift.connect(func(value: int) -> void:
                            shift_hits += value * 10
                        )

                    func make_cb() -> Callable:
                        return func(value: int) -> int:
                            return value + 7

                    func emit_swap_a(value: int) -> void:
                        sig_swap_a.emit(value)

                    func emit_swap_b(value: int) -> void:
                        sig_swap_b.emit(value)

                    func emit_shift(value: int) -> void:
                        sig_shift.emit(value)
                    """;
        }
        return """
                class_name HrOrdinal
                extends Node

                signal sig_swap_a(value: int)
                signal sig_swap_b(value: int)
                signal sig_shift(value: int)

                var swap_a_hits: int = 0
                var swap_b_hits: int = 0
                var shift_hits: int = 0

                func arm_swap() -> void:
                    sig_swap_b.connect(func(value: int) -> void:
                        swap_b_hits += value
                    )
                    sig_swap_a.connect(func(value: int) -> void:
                        swap_a_hits += value
                    )

                func arm_shift() -> void:
                    var note: int = 42
                    sig_shift.connect(func(value: int) -> void:
                        shift_hits += value * 20
                    )

                func make_cb() -> Callable:
                    var cb := func(value: int) -> int:
                        return value + 7
                    return cb

                func emit_swap_a(value: int) -> void:
                    sig_swap_a.emit(value)

                func emit_swap_b(value: int) -> void:
                    sig_swap_b.emit(value)

                func emit_shift(value: int) -> void:
                    sig_shift.emit(value)
                """;
    }

    private static @NotNull String basicDriver() {
        return """
                extends SceneTree

                const FLAG_PATH := "res://hr_swap.flag"
                const EXT_PATH := "res://HotReloadTest.gdextension"
                const WAIT_TIMEOUT_MS := 280000

                var frames := 0
                var stage := 0
                var wait_start_ms := 0
                var settle_frame := 0
                var inst = null

                func _process(_delta: float) -> bool:
                    frames += 1
                    if stage == 0 and frames >= 5:
                        stage = 1
                        _run_phase1()
                    elif stage == 1:
                        if FileAccess.file_exists(FLAG_PATH):
                            DirAccess.remove_absolute(ProjectSettings.globalize_path(FLAG_PATH))
                            stage = 2
                            _run_phase2()
                        elif Time.get_ticks_msec() - wait_start_ms > WAIT_TIMEOUT_MS:
                            _fail("timeout waiting for swap flag")
                    elif stage == 3 and frames - settle_frame >= 180:
                        print("HR_PHASE2_OK")
                        quit(0)
                    return false

                func _run_phase1() -> void:
                    inst = ClassDB.instantiate("RuntimeHrBasic")
                    if inst == null:
                        _fail("instantiate(RuntimeHrBasic) returned null")
                        return
                    if inst.call("greet") != "hello-v1":
                        _fail("phase1 greet mismatch: " + str(inst.call("greet")))
                        return
                    inst.set("exported_num", 70)
                    inst.set("plain_num", 110)
                    inst.set("label", "kept-label")
                    wait_start_ms = Time.get_ticks_msec()
                    print("HR_PHASE1_OK")

                func _run_phase2() -> void:
                    var status: int = GDExtensionManager.reload_extension(EXT_PATH)
                    if status != OK:
                        _fail("reload_extension returned " + str(status))
                        return
                    if not is_instance_valid(inst):
                        _fail("instance invalid after reload")
                        return
                    if inst.call("greet") != "hello-v2":
                        _fail("phase2 greet expected hello-v2, got " + str(inst.call("greet")))
                        return
                    if inst.get("exported_num") != 70:
                        _fail("exported_num not restored: " + str(inst.get("exported_num")))
                        return
                    if inst.get("plain_num") != 110:
                        _fail("plain_num not restored: " + str(inst.get("plain_num")))
                        return
                    if inst.get("default_num") != 5:
                        _fail("default_num changed across reload: " + str(inst.get("default_num")))
                        return
                    if inst.get("label") != "kept-label":
                        _fail("label not restored: " + str(inst.get("label")))
                        return
                    _settle_before_quit()

                func _settle_before_quit() -> void:
                    # A fresh project's first extension discovery queues deferred editor doc
                    # regeneration; quitting before the message queue drains it crashes the editor
                    # at Main::cleanup (upstream engine issue #123511, load-dependent). Idle frames
                    # while the extension is still loaded let the queue drain first. 180 frames:
                    # 60 proved insufficient when the suite runs alongside CPU-saturating native
                    # probe builds (doc generation lags on contended worker threads — the window
                    # needs wall time, not just frame count).
                    stage = 3
                    settle_frame = frames

                func _fail(msg: String) -> void:
                    print("HR_FAIL: ", msg)
                    quit(1)
                """;
    }

    private static @NotNull String virtualDriver() {
        return """
                extends SceneTree

                const FLAG_PATH := "res://hr_swap.flag"
                const EXT_PATH := "res://HotReloadTest.gdextension"
                const WAIT_TIMEOUT_MS := 280000

                var frames := 0
                var stage := 0
                var stage_frame := 0
                var wait_start_ms := 0
                var settle_frame := 0
                var override_inst = null
                var pass_inst = null
                var override_ticks0 := 0
                var pass_ticks0 := 0

                func _process(_delta: float) -> bool:
                    frames += 1
                    match stage:
                        0:
                            if frames >= 5:
                                _setup()
                        1:
                            if frames - stage_frame >= 20:
                                _phase1_asserts()
                        2:
                            if FileAccess.file_exists(FLAG_PATH):
                                DirAccess.remove_absolute(ProjectSettings.globalize_path(FLAG_PATH))
                                _phase2_reload()
                            elif Time.get_ticks_msec() - wait_start_ms > WAIT_TIMEOUT_MS:
                                _fail("timeout waiting for swap flag")
                        3:
                            if frames - stage_frame >= 30:
                                _phase2_asserts()
                        4:
                            if frames - settle_frame >= 180:
                                print("HR_PHASE2_OK")
                                quit(0)
                    return false

                func _setup() -> void:
                    stage = 1
                    override_inst = ClassDB.instantiate("RuntimeHrVtOverride")
                    pass_inst = ClassDB.instantiate("RuntimeHrVtPass")
                    if override_inst == null or pass_inst == null:
                        _fail("instantiate failed for virtual fixture classes")
                        return
                    root.add_child(override_inst)
                    root.add_child(pass_inst)
                    if override_inst.call("whoami") != "override-v1":
                        _fail("phase1 override whoami mismatch: " + str(override_inst.call("whoami")))
                        return
                    if pass_inst.call("whoami") != "base-v1":
                        _fail("phase1 pass-through whoami mismatch: " + str(pass_inst.call("whoami")))
                        return
                    stage_frame = frames

                func _phase1_asserts() -> void:
                    stage = 2
                    if override_inst.get("ticks") < 10 or pass_inst.get("ticks") < 10:
                        _fail("engine _process not driving nodes before reload: "
                                + str(override_inst.get("ticks")) + "/" + str(pass_inst.get("ticks")))
                        return
                    wait_start_ms = Time.get_ticks_msec()
                    print("HR_PHASE1_OK")

                func _phase2_reload() -> void:
                    stage = 3
                    var status: int = GDExtensionManager.reload_extension(EXT_PATH)
                    if status != OK:
                        _fail("reload_extension returned " + str(status))
                        return
                    if not is_instance_valid(override_inst) or not is_instance_valid(pass_inst):
                        _fail("instance invalid after reload")
                        return
                    if override_inst.call("whoami") != "override-v2":
                        _fail("phase2 override whoami mismatch: " + str(override_inst.call("whoami")))
                        return
                    if pass_inst.call("whoami") != "base-v2":
                        _fail("phase2 pass-through whoami mismatch: " + str(pass_inst.call("whoami")))
                        return
                    override_ticks0 = override_inst.get("ticks")
                    pass_ticks0 = pass_inst.get("ticks")
                    stage_frame = frames

                func _phase2_asserts() -> void:
                    var override_delta: int = override_inst.get("ticks") - override_ticks0
                    var pass_delta: int = pass_inst.get("ticks") - pass_ticks0
                    if override_delta < 1500 or pass_delta < 1500:
                        _fail("reloaded _process not running new code (step 100): deltas "
                                + str(override_delta) + "/" + str(pass_delta))
                        return
                    # See basicDriver._settle_before_quit (upstream engine issue #123511).
                    stage = 4
                    settle_frame = frames

                func _fail(msg: String) -> void:
                    print("HR_FAIL: ", msg)
                    quit(1)
                """;
    }

    private static @NotNull String coroutineDriver() {
        return """
                extends SceneTree

                const FLAG_PATH := "res://hr_swap.flag"
                const EXT_PATH := "res://HotReloadTest.gdextension"
                const WAIT_TIMEOUT_MS := 280000

                var frames := 0
                var stage := 0
                var wait_start_ms := 0
                var settle_frame := 0
                var inst = null

                func _process(_delta: float) -> bool:
                    frames += 1
                    if stage == 0 and frames >= 5:
                        stage = 1
                        _run_phase1()
                    elif stage == 1:
                        if FileAccess.file_exists(FLAG_PATH):
                            DirAccess.remove_absolute(ProjectSettings.globalize_path(FLAG_PATH))
                            stage = 2
                            _run_phase2()
                        elif Time.get_ticks_msec() - wait_start_ms > WAIT_TIMEOUT_MS:
                            _fail("timeout waiting for swap flag")
                    elif stage == 3 and frames - settle_frame >= 180:
                        print("HR_PHASE2_OK")
                        quit(0)
                    return false

                func _run_phase1() -> void:
                    inst = ClassDB.instantiate("RuntimeHrCoro")
                    if inst == null:
                        _fail("instantiate(RuntimeHrCoro) returned null")
                        return
                    inst.call("start_run")
                    var ev: Array = inst.call("read_events")
                    if not ev.has("outer:wait") or not ev.has("leaf:wait"):
                        _fail("coroutine did not reach suspension: " + str(ev))
                        return
                    if inst.call("read_done") != false:
                        _fail("coroutine done before resume")
                        return
                    wait_start_ms = Time.get_ticks_msec()
                    print("HR_PHASE1_OK")

                func _run_phase2() -> void:
                    var status: int = GDExtensionManager.reload_extension(EXT_PATH)
                    if status != OK:
                        _fail("reload_extension returned " + str(status))
                        return
                    if not is_instance_valid(inst):
                        _fail("instance invalid after reload")
                        return
                    if inst.call("read_done") != false:
                        _fail("coroutine completed across reload (abandonment violated)")
                        return
                    var ev: Array = inst.call("read_events")
                    if ev.has("leaf:done") or ev.has("outer:done"):
                        _fail("cancelled coroutine emitted completion: " + str(ev))
                        return
                    inst.call("emit_release", 41)
                    if inst.call("read_done") != false or inst.call("read_result") != -1:
                        _fail("stale waiter resumed after reload")
                        return
                    inst.call("start_run")
                    inst.call("emit_release", 7)
                    if inst.call("read_result") != 9:
                        _fail("new-generation coroutine result mismatch (v2 leaf adds 2): "
                                + str(inst.call("read_result")))
                        return
                    if inst.call("read_done") != true:
                        _fail("new-generation coroutine did not complete")
                        return
                    _settle_before_quit()

                func _settle_before_quit() -> void:
                    # See basicDriver._settle_before_quit (upstream engine issue #123511).
                    stage = 3
                    settle_frame = frames

                func _fail(msg: String) -> void:
                    print("HR_FAIL: ", msg)
                    quit(1)
                """;
    }

    private static @NotNull String lifecycleDriver() {
        return """
                extends SceneTree

                const FLAG_PATH := "res://hr_swap.flag"
                const EXT_PATH := "res://HotReloadTest.gdextension"
                const WAIT_TIMEOUT_MS := 280000

                var frames := 0
                var stage := 0
                var wait_start_ms := 0
                var settle_frame := 0
                var inst = null
                var inst2 = null

                func _process(_delta: float) -> bool:
                    frames += 1
                    if stage == 0 and frames >= 5:
                        stage = 1
                        _run_phase1()
                    elif stage == 1 or stage == 2:
                        if FileAccess.file_exists(FLAG_PATH):
                            DirAccess.remove_absolute(ProjectSettings.globalize_path(FLAG_PATH))
                            if stage == 1:
                                stage = 2
                                _run_phase2()
                            else:
                                stage = 3
                                _run_phase3()
                        elif Time.get_ticks_msec() - wait_start_ms > WAIT_TIMEOUT_MS:
                            _fail("timeout waiting for swap flag in stage " + str(stage))
                    elif stage == 4 and frames - settle_frame >= 180:
                        print("HR_PHASE3_OK")
                        quit(0)
                    return false

                func _run_phase1() -> void:
                    var temp = ClassDB.instantiate("RuntimeHrLifeDerived")
                    if temp == null:
                        _fail("instantiate(RuntimeHrLifeDerived) returned null")
                        return
                    temp.free()
                    inst = ClassDB.instantiate("RuntimeHrLifeDerived")
                    if inst.call("full_tag") != "base-v1/derived":
                        _fail("phase1 full_tag mismatch: " + str(inst.call("full_tag")))
                        return
                    inst.set("base_text", "b1")
                    inst.set("child_text", "c1")
                    inst.get("base_list").append(99)
                    inst.get("child_dict")["k"] = 42
                    inst.get("child_dict")["extra"] = 7
                    inst.call("arm")
                    inst.call("start_coro", 5)
                    if inst.get("coro_suspended") != true:
                        _fail("lifecycle coroutine did not reach suspension")
                        return
                    wait_start_ms = Time.get_ticks_msec()
                    print("HR_PHASE1_OK")

                func _run_phase2() -> void:
                    var status: int = GDExtensionManager.reload_extension(EXT_PATH)
                    if status != OK:
                        _fail("reload #1 returned " + str(status))
                        return
                    if not is_instance_valid(inst):
                        _fail("inst invalid after reload #1")
                        return
                    if inst.call("full_tag") != "base-v2/derived":
                        _fail("phase2 full_tag mismatch: " + str(inst.call("full_tag")))
                        return
                    if inst.get("base_text") != "b1":
                        _fail("base_text not restored: " + str(inst.get("base_text")))
                        return
                    if inst.get("child_text") != "c1":
                        _fail("child_text not restored: " + str(inst.get("child_text")))
                        return
                    if inst.get("base_list") != [1, 2, 99]:
                        _fail("base_list not restored: " + str(inst.get("base_list")))
                        return
                    if inst.get("child_dict").get("k") != 42 or inst.get("child_dict").get("extra") != 7:
                        _fail("child_dict not restored: " + str(inst.get("child_dict")))
                        return
                    if inst.get("base_ref") == null:
                        _fail("base_ref object property lost across reload")
                        return
                    if inst.get("coro_suspended") != true:
                        _fail("coro_suspended flag not restored")
                        return
                    if inst.call("read_coro_result") != -1:
                        _fail("coroutine wrote result across reload: " + str(inst.call("read_coro_result")))
                        return
                    inst.call("emit_hold", 100)
                    if inst.call("read_coro_result") != -1:
                        _fail("stale coroutine resumed after reload")
                        return
                    inst.free()
                    inst2 = ClassDB.instantiate("RuntimeHrLifeDerived")
                    if inst2 == null or inst2.call("full_tag") != "base-v2/derived":
                        _fail("new instance on reloaded library misbehaves")
                        return
                    wait_start_ms = Time.get_ticks_msec()
                    print("HR_PHASE2_OK")

                func _run_phase3() -> void:
                    var status: int = GDExtensionManager.reload_extension(EXT_PATH)
                    if status != OK:
                        _fail("reload #2 returned " + str(status))
                        return
                    if not is_instance_valid(inst2):
                        _fail("inst2 invalid after reload #2")
                        return
                    if inst2.call("full_tag") != "base-v3/derived":
                        _fail("phase3 full_tag mismatch: " + str(inst2.call("full_tag")))
                        return
                    inst2.free()
                    # See basicDriver._settle_before_quit (upstream engine issue #123511).
                    stage = 4
                    settle_frame = frames

                func _fail(msg: String) -> void:
                    print("HR_FAIL: ", msg)
                    quit(1)
                """;
    }

    private static @NotNull String methodCbDriver() {
        return """
                extends SceneTree

                const FLAG_PATH := "res://hr_swap.flag"
                const EXT_PATH := "res://HotReloadTest.gdextension"
                const WAIT_TIMEOUT_MS := 280000

                var frames := 0
                var stage := 0
                var wait_start_ms := 0
                var settle_frame := 0
                var inst = null

                func _process(_delta: float) -> bool:
                    frames += 1
                    if stage == 0 and frames >= 5:
                        stage = 1
                        _run_phase1()
                    elif stage == 1:
                        if FileAccess.file_exists(FLAG_PATH):
                            DirAccess.remove_absolute(ProjectSettings.globalize_path(FLAG_PATH))
                            stage = 2
                            _run_phase2()
                        elif Time.get_ticks_msec() - wait_start_ms > WAIT_TIMEOUT_MS:
                            _fail("timeout waiting for swap flag")
                    elif stage == 3 and frames - settle_frame >= 180:
                        print("HR_PHASE2_OK")
                        quit(0)
                    return false

                func _run_phase1() -> void:
                    inst = ClassDB.instantiate("RuntimeHrMethodCb")
                    if inst == null:
                        _fail("instantiate(RuntimeHrMethodCb) returned null")
                        return
                    inst.call("arm")
                    inst.call("emit_ping", 1)
                    if inst.get("hits") != 1 or inst.get("last") != 2:
                        _fail("phase1 v1 method connection mismatch: hits="
                                + str(inst.get("hits")) + " last=" + str(inst.get("last")))
                        return
                    wait_start_ms = Time.get_ticks_msec()
                    print("HR_PHASE1_OK")

                func _run_phase2() -> void:
                    var status: int = GDExtensionManager.reload_extension(EXT_PATH)
                    if status != OK:
                        _fail("reload_extension returned " + str(status))
                        return
                    if not is_instance_valid(inst):
                        _fail("instance invalid after reload")
                        return
                    inst.call("emit_ping", 41)
                    if inst.get("hits") != 2:
                        _fail("method-name connection lost across reload: hits=" + str(inst.get("hits")))
                        return
                    if inst.get("last") != 141:
                        _fail("method-name connection did not run NEW code (want 141): last="
                                + str(inst.get("last")))
                        return
                    _settle_before_quit()

                func _settle_before_quit() -> void:
                    # See basicDriver._settle_before_quit (upstream engine issue #123511).
                    stage = 3
                    settle_frame = frames

                func _fail(msg: String) -> void:
                    print("HR_FAIL: ", msg)
                    quit(1)
                """;
    }

    private static @NotNull String lambdaRebindDriver() {
        return """
                extends SceneTree

                const FLAG_PATH := "res://hr_swap.flag"
                const EXT_PATH := "res://HotReloadTest.gdextension"
                const WAIT_TIMEOUT_MS := 280000

                var frames := 0
                var stage := 0
                var wait_start_ms := 0
                var settle_frame := 0
                var inst = null
                var old_cb = null

                func _process(_delta: float) -> bool:
                    frames += 1
                    if stage == 0 and frames >= 5:
                        stage = 1
                        _run_phase1()
                    elif stage == 1:
                        if FileAccess.file_exists(FLAG_PATH):
                            DirAccess.remove_absolute(ProjectSettings.globalize_path(FLAG_PATH))
                            stage = 2
                            _run_phase2()
                        elif Time.get_ticks_msec() - wait_start_ms > WAIT_TIMEOUT_MS:
                            _fail("timeout waiting for swap flag")
                    elif stage == 3 and frames - settle_frame >= 180:
                        print("HR_PHASE2_OK")
                        quit(0)
                    return false

                func _run_phase1() -> void:
                    inst = ClassDB.instantiate("RuntimeHrLambdaRebind")
                    if inst == null:
                        _fail("instantiate(RuntimeHrLambdaRebind) returned null")
                        return
                    inst.call("arm")
                    inst.call("emit_ping", 1)
                    if inst.get("hits") != 1 or inst.get("last") != 2:
                        _fail("phase1 v1 lambda connection mismatch: hits="
                                + str(inst.get("hits")) + " last=" + str(inst.get("last")))
                        return
                    old_cb = inst.call("make_cb")
                    if old_cb == null or not old_cb.is_valid():
                        _fail("phase1 lambda Callable invalid")
                        return
                    if old_cb.call(3) != 6:
                        _fail("phase1 v1 lambda call mismatch (want 6): " + str(old_cb.call(3)))
                        return
                    if inst.get("hits") != 2:
                        _fail("phase1 lambda side effect missing: hits=" + str(inst.get("hits")))
                        return
                    wait_start_ms = Time.get_ticks_msec()
                    print("HR_PHASE1_OK")

                func _run_phase2() -> void:
                    var status: int = GDExtensionManager.reload_extension(EXT_PATH)
                    if status != OK:
                        _fail("reload_extension returned " + str(status))
                        return
                    if not is_instance_valid(inst):
                        _fail("instance invalid after reload")
                        return
                    inst.call("emit_ping", 41)
                    if inst.get("hits") != 3 or inst.get("last") != 141:
                        _fail("connected lambda did not run NEW code: hits="
                                + str(inst.get("hits")) + " last=" + str(inst.get("last")))
                        return
                    if not old_cb.is_valid():
                        _fail("rebound lambda Callable should stay valid")
                        return
                    if old_cb.call(5) != 50:
                        _fail("retained lambda Callable did not reach v2 impl (want 50): "
                                + str(old_cb.call(5)))
                        return
                    if inst.get("hits") != 4:
                        _fail("v2 lambda side effect missing: hits=" + str(inst.get("hits")))
                        return
                    if inst.call("utility_to_string") != "<CallableCustom>":
                        _fail("thunk-path to_string contract broken: "
                                + str(inst.call("utility_to_string")))
                        return
                    _settle_before_quit()

                func _settle_before_quit() -> void:
                    # See basicDriver._settle_before_quit (upstream engine issue #123511).
                    stage = 3
                    settle_frame = frames

                func _fail(msg: String) -> void:
                    print("HR_FAIL: ", msg)
                    quit(1)
                """;
    }

    private static @NotNull String lambdaInvalidateDriver() {
        return """
                extends SceneTree

                const FLAG_PATH := "res://hr_swap.flag"
                const EXT_PATH := "res://HotReloadTest.gdextension"
                const WAIT_TIMEOUT_MS := 280000

                var frames := 0
                var stage := 0
                var wait_start_ms := 0
                var settle_frame := 0
                var inst = null
                var mismatch_cb = null
                var pair_first = null
                var pair_second = null
                var sig_b_cb = null

                func _process(_delta: float) -> bool:
                    frames += 1
                    if stage == 0 and frames >= 5:
                        stage = 1
                        _run_phase1()
                    elif stage == 1:
                        if FileAccess.file_exists(FLAG_PATH):
                            DirAccess.remove_absolute(ProjectSettings.globalize_path(FLAG_PATH))
                            stage = 2
                            _run_phase2()
                        elif Time.get_ticks_msec() - wait_start_ms > WAIT_TIMEOUT_MS:
                            _fail("timeout waiting for swap flag")
                    elif stage == 3 and frames - settle_frame >= 180:
                        print("HR_PHASE2_OK")
                        quit(0)
                    return false

                func _run_phase1() -> void:
                    inst = ClassDB.instantiate("RuntimeHrInv")
                    if inst == null:
                        _fail("instantiate(RuntimeHrInv) returned null")
                        return
                    inst.call("arm")
                    inst.call("emit_a", 1)
                    inst.call("emit_b", 2)
                    if inst.get("a_hits") != 1 or inst.get("b_hits") != 2:
                        _fail("phase1 v1 connections mismatch: a=" + str(inst.get("a_hits"))
                                + " b=" + str(inst.get("b_hits")))
                        return
                    var conns: Array = inst.get_signal_connection_list("sig_b")
                    if conns.size() != 1:
                        _fail("sig_b should have exactly one connection")
                        return
                    sig_b_cb = conns[0]["callable"]
                    mismatch_cb = inst.call("make_mismatch_cb")
                    if not mismatch_cb.is_valid() or mismatch_cb.call(1) != 2:
                        _fail("phase1 mismatch_cb not usable")
                        return
                    pair_first = inst.call("make_pair", true)
                    pair_second = inst.call("make_pair", false)
                    if pair_first.call(5) != 15 or pair_second.call(5) != "s5":
                        _fail("phase1 make_pair results mismatch: "
                                + str(pair_first.call(5)) + "/" + str(pair_second.call(5)))
                        return
                    wait_start_ms = Time.get_ticks_msec()
                    print("HR_PHASE1_OK")

                func _run_phase2() -> void:
                    var status: int = GDExtensionManager.reload_extension(EXT_PATH)
                    if status != OK:
                        _fail("reload_extension returned " + str(status))
                        return
                    if not is_instance_valid(inst):
                        _fail("instance invalid after reload")
                        return
                    inst.call("emit_a", 1)
                    if inst.get("a_hits") != 3:
                        _fail("surviving lambda did not rebind to v2 (want a_hits=3): "
                                + str(inst.get("a_hits")))
                        return
                    inst.call("emit_b", 9)
                    if inst.get("b_hits") != 2:
                        _fail("deleted lambda connection not silently skipped: b_hits="
                                + str(inst.get("b_hits")))
                        return
                    if inst.get("a_hits") != 3:
                        _fail("deleted lambda rebound to surviving same-schema sibling: a_hits="
                                + str(inst.get("a_hits")))
                        return
                    if sig_b_cb == null or sig_b_cb.is_valid():
                        _fail("engine-held sig_b connection Callable should be invalid")
                        return
                    if mismatch_cb.is_valid():
                        _fail("capture-mismatched lambda should be invalid after reload")
                        return
                    # Note: actually CALLING an invalidated Callable is not asserted here — in
                    # interpreted GDScript that raises the engine's standard error as a hard
                    # SCRIPT ERROR ("on a null instance"), which would kill this driver. The
                    # engine error path is the documented standard behavior.
                    if pair_first.is_valid() or pair_second.is_valid():
                        _fail("swapped lambdas must invalidate, never bind the wrong body")
                        return
                    _settle_before_quit()

                func _settle_before_quit() -> void:
                    # See basicDriver._settle_before_quit (upstream engine issue #123511).
                    stage = 3
                    settle_frame = frames

                func _fail(msg: String) -> void:
                    print("HR_FAIL: ", msg)
                    quit(1)
                """;
    }

    private static @NotNull String lambdaDeferredDriver() {
        return """
                extends SceneTree

                const FLAG_PATH := "res://hr_swap.flag"
                const EXT_PATH := "res://HotReloadTest.gdextension"
                const WAIT_TIMEOUT_MS := 280000

                var frames := 0
                var stage := 0
                var stage_frame := 0
                var wait_start_ms := 0
                var settle_frame := 0
                var inst = null

                func _process(_delta: float) -> bool:
                    frames += 1
                    match stage:
                        0:
                            if frames >= 5:
                                _setup()
                        1:
                            if frames - stage_frame >= 10:
                                _phase1_asserts()
                        2:
                            if FileAccess.file_exists(FLAG_PATH):
                                DirAccess.remove_absolute(ProjectSettings.globalize_path(FLAG_PATH))
                                _queue_and_reload()
                            elif Time.get_ticks_msec() - wait_start_ms > WAIT_TIMEOUT_MS:
                                _fail("timeout waiting for swap flag")
                        3:
                            if frames - stage_frame >= 10:
                                _phase2_asserts()
                        4:
                            if frames - settle_frame >= 180:
                                print("HR_PHASE2_OK")
                                quit(0)
                    return false

                func _setup() -> void:
                    inst = ClassDB.instantiate("RuntimeHrDeferred")
                    if inst == null:
                        _fail("instantiate(RuntimeHrDeferred) returned null")
                        return
                    inst.call("queue_deferred", 1)
                    stage = 1
                    stage_frame = frames

                func _phase1_asserts() -> void:
                    if inst.get("a_results") != [2] or inst.get("b_results") != [3]:
                        _fail("v1 deferred results mismatch: " + str(inst.get("a_results"))
                                + "/" + str(inst.get("b_results")))
                        return
                    inst.call("clear_results")
                    if inst.get("a_results") != [] or inst.get("b_results") != []:
                        _fail("clear_results failed")
                        return
                    stage = 2
                    wait_start_ms = Time.get_ticks_msec()
                    print("HR_PHASE1_OK")

                func _queue_and_reload() -> void:
                    # Queue the deferred calls and reload within the SAME frame, so the message
                    # queue flushes (frame end) strictly after the reload: the calls observe the
                    # new generation even though the Callables were created on v1.
                    inst.call("queue_deferred", 10)
                    var status: int = GDExtensionManager.reload_extension(EXT_PATH)
                    if status != OK:
                        _fail("reload_extension returned " + str(status))
                        return
                    stage = 3
                    stage_frame = frames

                func _phase2_asserts() -> void:
                    if not is_instance_valid(inst):
                        _fail("instance invalid after reload")
                        return
                    if inst.get("a_results") != [110]:
                        _fail("deferred lambda did not run v2 code (want [110]): "
                                + str(inst.get("a_results")))
                        return
                    if inst.get("b_results") != []:
                        _fail("capture-mismatched deferred lambda should be skipped: "
                                + str(inst.get("b_results")))
                        return
                    # See basicDriver._settle_before_quit (upstream engine issue #123511).
                    stage = 4
                    settle_frame = frames

                func _fail(msg: String) -> void:
                    print("HR_FAIL: ", msg)
                    quit(1)
                """;
    }

    private static @NotNull String lambdaOrdinalDriver() {
        return """
                extends SceneTree

                const FLAG_PATH := "res://hr_swap.flag"
                const EXT_PATH := "res://HotReloadTest.gdextension"
                const WAIT_TIMEOUT_MS := 280000

                var frames := 0
                var stage := 0
                var wait_start_ms := 0
                var settle_frame := 0
                var inst = null
                var swap_a_cb = null
                var swap_b_cb = null
                var shift_cb = null
                var old_cb = null

                func _process(_delta: float) -> bool:
                    frames += 1
                    if stage == 0 and frames >= 5:
                        stage = 1
                        _run_phase1()
                    elif stage == 1:
                        if FileAccess.file_exists(FLAG_PATH):
                            DirAccess.remove_absolute(ProjectSettings.globalize_path(FLAG_PATH))
                            stage = 2
                            _run_phase2()
                        elif Time.get_ticks_msec() - wait_start_ms > WAIT_TIMEOUT_MS:
                            _fail("timeout waiting for swap flag")
                    elif stage == 3 and frames - settle_frame >= 180:
                        print("HR_PHASE2_OK")
                        quit(0)
                    return false

                func _run_phase1() -> void:
                    inst = ClassDB.instantiate("RuntimeHrOrdinal")
                    if inst == null:
                        _fail("instantiate(RuntimeHrOrdinal) returned null")
                        return
                    inst.call("arm_swap")
                    inst.call("arm_shift")
                    var conns_a: Array = inst.get_signal_connection_list("sig_swap_a")
                    var conns_b: Array = inst.get_signal_connection_list("sig_swap_b")
                    var conns_s: Array = inst.get_signal_connection_list("sig_shift")
                    if conns_a.size() != 1 or conns_b.size() != 1 or conns_s.size() != 1:
                        _fail("each signal should have exactly one connection: a=" + str(conns_a.size())
                                + " b=" + str(conns_b.size()) + " s=" + str(conns_s.size()))
                        return
                    inst.call("emit_swap_a", 1)
                    inst.call("emit_swap_b", 2)
                    inst.call("emit_shift", 3)
                    if inst.get("swap_a_hits") != 1 or inst.get("swap_b_hits") != 2 \\
                            or inst.get("shift_hits") != 30:
                        _fail("phase1 v1 connections mismatch: a=" + str(inst.get("swap_a_hits"))
                                + " b=" + str(inst.get("swap_b_hits"))
                                + " shift=" + str(inst.get("shift_hits")))
                        return
                    swap_a_cb = conns_a[0]["callable"]
                    swap_b_cb = conns_b[0]["callable"]
                    shift_cb = conns_s[0]["callable"]
                    old_cb = inst.call("make_cb")
                    if old_cb == null or not old_cb.is_valid() or old_cb.call(3) != 10:
                        _fail("phase1 make_cb not usable")
                        return
                    wait_start_ms = Time.get_ticks_msec()
                    print("HR_PHASE1_OK")

                func _run_phase2() -> void:
                    var status: int = GDExtensionManager.reload_extension(EXT_PATH)
                    if status != OK:
                        _fail("reload_extension returned " + str(status))
                        return
                    if not is_instance_valid(inst):
                        _fail("instance invalid after reload")
                        return
                    # Leg 1: the swapped same-schema pair must fail closed on the
                    # callsite-context gate — never rebound to the sibling's body.
                    if swap_a_cb.is_valid() or swap_b_cb.is_valid():
                        _fail("swapped lambdas must invalidate (context gate), never mis-bind")
                        return
                    inst.call("emit_swap_a", 5)
                    inst.call("emit_swap_b", 6)
                    if inst.get("swap_a_hits") != 1 or inst.get("swap_b_hits") != 2:
                        _fail("invalidated swap connections must be silently skipped: a="
                                + str(inst.get("swap_a_hits")) + " b=" + str(inst.get("swap_b_hits")))
                        return
                    # Leg 2: a non-lambda line insertion must not change the ordinal key —
                    # the connection rebinds and runs the v2 body.
                    if not shift_cb.is_valid():
                        _fail("ordinal key must survive a non-lambda line insertion")
                        return
                    inst.call("emit_shift", 4)
                    if inst.get("shift_hits") != 110:
                        _fail("shift lambda did not rebind to v2 (want 110): "
                                + str(inst.get("shift_hits")))
                        return
                    # Leg 3: extract-variable refactor changes the callsite context ->
                    # expected fail-closed false invalidation.
                    if old_cb.is_valid():
                        _fail("extract-variable refactor must invalidate the v1 lambda")
                        return
                    var new_cb = inst.call("make_cb")
                    if new_cb == null or not new_cb.is_valid() or new_cb.call(3) != 10:
                        _fail("v2 make_cb must produce a working callable")
                        return
                    _settle_before_quit()

                func _settle_before_quit() -> void:
                    # See basicDriver._settle_before_quit (upstream engine issue #123511).
                    stage = 3
                    settle_frame = frames

                func _fail(msg: String) -> void:
                    print("HR_FAIL: ", msg)
                    quit(1)
                """;
    }

    private static @NotNull String sigChangeDriver() {
        return """
                extends SceneTree

                const FLAG_PATH := "res://hr_swap.flag"
                const EXT_PATH := "res://HotReloadTest.gdextension"
                const WAIT_TIMEOUT_MS := 280000

                var frames := 0
                var stage := 0
                var wait_start_ms := 0
                var settle_frame := 0
                var inst = null

                func _process(_delta: float) -> bool:
                    frames += 1
                    if stage == 0 and frames >= 5:
                        stage = 1
                        _run_phase1()
                    elif stage == 1:
                        if FileAccess.file_exists(FLAG_PATH):
                            DirAccess.remove_absolute(ProjectSettings.globalize_path(FLAG_PATH))
                            stage = 2
                            _run_phase2()
                        elif Time.get_ticks_msec() - wait_start_ms > WAIT_TIMEOUT_MS:
                            _fail("timeout waiting for swap flag")
                    elif stage == 3 and frames - settle_frame >= 180:
                        print("HR_PHASE2_OK")
                        quit(0)
                    return false

                func _run_phase1() -> void:
                    inst = ClassDB.instantiate("RuntimeHrSigChange")
                    if inst == null:
                        _fail("instantiate(RuntimeHrSigChange) returned null")
                        return
                    if inst.call("pair_sum", 1, 2) != 3:
                        _fail("phase1 pair_sum(1,2) mismatch: " + str(inst.call("pair_sum", 1, 2)))
                        return
                    wait_start_ms = Time.get_ticks_msec()
                    print("HR_PHASE1_OK")

                func _run_phase2() -> void:
                    var status: int = GDExtensionManager.reload_extension(EXT_PATH)
                    if status != OK:
                        _fail("reload_extension returned " + str(status))
                        return
                    if not is_instance_valid(inst):
                        _fail("instance invalid after reload")
                        return
                    if inst.call("pair_sum", 5) != 50:
                        _fail("new-signature pair_sum(5) mismatch: " + str(inst.call("pair_sum", 5)))
                        return
                    if inst.call("label") != "v2":
                        _fail("label mismatch: " + str(inst.call("label")))
                        return
                    # Advance the stage machine FIRST: the stale-signature call below raises the
                    # engine's standard invalid-call error, which aborts this function on Godot
                    # 4.5.2 (observed as SCRIPT ERROR "Expected 1 argument(s)"). With the stage
                    # already settled, the driver still reaches PHASE2/quit; Java pins the
                    # diagnostic text. If a future engine reports gracefully instead, the call
                    # returns null and the missing diagnostic fails the Java assertion instead.
                    _settle_before_quit()
                    var stale = inst.call("pair_sum", 1, 2)
                    if stale != null:
                        _fail("stale-signature call should fail, got: " + str(stale))
                        return

                func _settle_before_quit() -> void:
                    # See basicDriver._settle_before_quit (upstream engine issue #123511).
                    stage = 3
                    settle_frame = frames

                func _fail(msg: String) -> void:
                    print("HR_FAIL: ", msg)
                    quit(1)
                """;
    }

    private static @NotNull String staticResetDriver() {
        return """
                extends SceneTree

                const FLAG_PATH := "res://hr_swap.flag"
                const EXT_PATH := "res://HotReloadTest.gdextension"
                const WAIT_TIMEOUT_MS := 280000

                var frames := 0
                var stage := 0
                var wait_start_ms := 0
                var settle_frame := 0
                var inst = null

                func _process(_delta: float) -> bool:
                    frames += 1
                    if stage == 0 and frames >= 5:
                        stage = 1
                        _run_phase1()
                    elif stage == 1:
                        if FileAccess.file_exists(FLAG_PATH):
                            DirAccess.remove_absolute(ProjectSettings.globalize_path(FLAG_PATH))
                            stage = 2
                            _run_phase2()
                        elif Time.get_ticks_msec() - wait_start_ms > WAIT_TIMEOUT_MS:
                            _fail("timeout waiting for swap flag")
                    elif stage == 3 and frames - settle_frame >= 180:
                        print("HR_PHASE2_OK")
                        quit(0)
                    return false

                func _run_phase1() -> void:
                    inst = ClassDB.instantiate("RuntimeHrStatic")
                    if inst == null:
                        _fail("instantiate(RuntimeHrStatic) returned null")
                        return
                    inst.call("set_counter", 42)
                    if inst.call("get_counter") != 42:
                        _fail("phase1 static write mismatch: " + str(inst.call("get_counter")))
                        return
                    inst.set("instance_num", 99)
                    wait_start_ms = Time.get_ticks_msec()
                    print("HR_PHASE1_OK")

                func _run_phase2() -> void:
                    var status: int = GDExtensionManager.reload_extension(EXT_PATH)
                    if status != OK:
                        _fail("reload_extension returned " + str(status))
                        return
                    if not is_instance_valid(inst):
                        _fail("instance invalid after reload")
                        return
                    if inst.call("get_counter") != 5:
                        _fail("static var not reset to NEW initializer (want 5): "
                                + str(inst.call("get_counter")))
                        return
                    if inst.get("instance_num") != 99:
                        _fail("instance property not restored: " + str(inst.get("instance_num")))
                        return
                    _settle_before_quit()

                func _settle_before_quit() -> void:
                    # See basicDriver._settle_before_quit (upstream engine issue #123511).
                    stage = 3
                    settle_frame = frames

                func _fail(msg: String) -> void:
                    print("HR_FAIL: ", msg)
                    quit(1)
                """;
    }

    private static @NotNull String parentChangeDriver() {
        return """
                extends SceneTree

                const FLAG_PATH := "res://hr_swap.flag"
                const EXT_PATH := "res://HotReloadTest.gdextension"
                const WAIT_TIMEOUT_MS := 280000

                var frames := 0
                var stage := 0
                var wait_start_ms := 0
                var settle_frame := 0
                var inst = null

                func _process(_delta: float) -> bool:
                    frames += 1
                    if stage == 0 and frames >= 5:
                        stage = 1
                        _run_phase1()
                    elif stage == 1:
                        if FileAccess.file_exists(FLAG_PATH):
                            DirAccess.remove_absolute(ProjectSettings.globalize_path(FLAG_PATH))
                            stage = 2
                            _run_phase2()
                        elif Time.get_ticks_msec() - wait_start_ms > WAIT_TIMEOUT_MS:
                            _fail("timeout waiting for swap flag")
                    elif stage == 3 and frames - settle_frame >= 180:
                        print("HR_PHASE2_OK")
                        quit(0)
                    return false

                func _run_phase1() -> void:
                    inst = ClassDB.instantiate("RuntimeHrParentChange")
                    if inst == null:
                        _fail("instantiate(RuntimeHrParentChange) returned null")
                        return
                    if inst.call("tag") != "v1":
                        _fail("phase1 tag mismatch: " + str(inst.call("tag")))
                        return
                    wait_start_ms = Time.get_ticks_msec()
                    print("HR_PHASE1_OK")

                func _run_phase2() -> void:
                    # Whatever the engine does with the parent-changed extension, the driver must
                    # survive to report the status and quit; Java pins the engine diagnostic.
                    var status: int = GDExtensionManager.reload_extension(EXT_PATH)
                    print("HR_RELOAD_STATUS:" + str(status))
                    _settle_before_quit()

                func _settle_before_quit() -> void:
                    # See basicDriver._settle_before_quit (upstream engine issue #123511).
                    stage = 3
                    settle_frame = frames

                func _fail(msg: String) -> void:
                    print("HR_FAIL: ", msg)
                    quit(1)
                """;
    }

    private record SourceFileSpec(@NotNull Path sourcePath, @NotNull String source) {
    }
}
