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

/// Automated end-to-end editor hot-reload tests for HR-1 ~ HR-5 of
/// `doc/module_impl/backend/hot_reload_implementation_plan.md` (§HR-9 scenarios 1/2/3/4/9).
///
/// Every test compiles a v1 (and v2/v3) native library from GDScript sources through the real
/// frontend -> lowering -> C codegen -> Zig pipeline, installs it into a fresh editor project,
/// and drives a headless Godot EDITOR via `GodotEditorHotReloadTestSession`. All behavioral
/// assertions (instance survival, property restore, new-code dispatch, virtual re-hooking,
/// coroutine cancellation) run inside the editor in an interpreted SceneTree driver; Java only
/// orchestrates markers, atomic library swaps, exit codes and engine-side error substrings.
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

    /// HR-9 scenario 1 + 2: basic reload — instance survives, STORAGE properties (exported and
    /// non-exported) are restored, methods run NEW code after reload.
    /// Covers HR-1 (reloadable accepted) and HR-3 (recreate + property restore). HR-4's
    /// reverse-unregistration detection needs an inheritance chain, see the virtual/lifecycle cases.
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

    /// HR-9 scenario 3: inheritance and polymorphism — an override-only class and a pass-through
    /// class keep correct virtual dispatch after reload, and engine-driven `_process` enters the
    /// NEW library implementation on the next frames (engine virtual re-hooking). Covers HR-3,
    /// and HR-4 via the three-level extension inheritance chain (any unregistration order
    /// violation makes the engine print its order error, asserted absent).
    /// The fixture classes are `@tool` because gdcc suppresses frame-loop virtuals for non-tool
    /// classes in the editor (matching Godot's non-tool script behavior).
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

    /// HR-9 scenario 4: a coroutine suspended on a signal (with an awaiting parent coroutine) is
    /// silently cancelled by reload — the asserted observable half of abandonment semantics: the
    /// awaiting parent is never resumed, the cancelled body never continues, and the stale signal
    /// emission afterwards is a no-op; a coroutine started on the NEW library completes normally.
    /// Direct observation of the state object's `completed` signal is not part of this automated
    /// leg. Covers HR-5 (and the RELOADED_SHELL path of HR-3).
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

    /// HR-9 scenario 9 (behavioral matrix): normal PREDELETE->free before reload, reload
    /// free->recreate, recreate then normal destruction, two consecutive reloads, base+derived
    /// destroyable fields (String/Array/Dictionary contents and a RefCounted ObjectDB-path
    /// property restored by the engine), HR-4 reverse unregistration across the inheritance
    /// chain, and a suspended coroutine (with a parameter slot) cancelled on reload. Without
    /// sanitizers this automated leg proves crash-freedom and value correctness; the
    /// exactly-once/leak proof stays on the manual ASan/Valgrind checklist.
    /// Covers HR-2 (behaviorally), HR-3, HR-4, HR-5.
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
                    elif stage == 3 and frames - settle_frame >= 60:
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
                    # while the extension is still loaded let the queue drain first.
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
                            if frames - settle_frame >= 60:
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
                    elif stage == 3 and frames - settle_frame >= 60:
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
                    elif stage == 4 and frames - settle_frame >= 60:
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

    private record SourceFileSpec(@NotNull Path sourcePath, @NotNull String source) {
    }
}
