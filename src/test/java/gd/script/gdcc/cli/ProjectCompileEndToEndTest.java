package gd.script.gdcc.cli;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import gd.script.gdcc.backend.c.build.GodotEditorHotReloadTestSession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// End-to-end proof of the project-compilation product path: a temporary Godot project with two
/// `.gd3` sources, one explicitly passed `.gd`, and one unlisted `.gd` is compiled by the REAL
/// launcher binary (`build/launcher/zig` → packaged jar → bundled TinyCC), after which the
/// product-written `bin/gdcc.gdextension` and library are exercised by a real Godot editor scan
/// plus an interpreted SceneTree driver.
///
/// Nothing about the installation is staged by hand: the only `.gdextension`/library on disk is
/// what the launcher-published product wrote, and `.godot/extension_list.cfg` is produced by the
/// editor's own filesystem scan — not by the test. Missing tools (launcher, dist jar, TinyCC
/// bundle, `GODOT_BIN`) only skip the affected case; they never declare the flow verified.
class ProjectCompileEndToEndTest {
    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath().normalize();
    private static final Path LAUNCHER_BINARY = PROJECT_ROOT.resolve("build/launcher/zig/bin/linux-x86_64/gdcc");
    private static final Path DIST_LIBS_DIR = PROJECT_ROOT.resolve("build/libs");
    private static final Path PARSER_NATIVE_DIR = PROJECT_ROOT.resolve("native");
    private static final Path TINYCC_BUNDLE_DIR = PROJECT_ROOT.resolve("build/tinycc-bundle/linux-x86_64");
    private static final Path BUILD_SCRIPT = PROJECT_ROOT.resolve("build.gradle.kts");

    private static final long LAUNCHER_TIMEOUT_MINUTES = 5;
    private static final long GODOT_TIMEOUT_SECONDS = 180;
    /// Frames the headless editor stays alive after the first scan so deferred work (documentation
    /// regeneration queued by the freshly discovered extension) finishes before the quit; quitting
    /// earlier can race that deferred call (upstream godotengine/godot#111048).
    private static final int EDITOR_SCAN_FRAMES = 600;
    private static final String PASS_MARKER = "GDCC_E2E_PASS::project_compile_runtime";

    private static final String PROJECT_GODOT = """
            config_version=5

            [application]

            config/name="GdccE2EGame"
            """;
    private static final String ALPHA_GD3 = """
            class_name Alpha
            extends RefCounted

            func base_value() -> int:
                return 41
            """;
    private static final String BETA_GD3 = """
            class_name Beta
            extends RefCounted

            func use_alpha() -> int:
                var a := Alpha.new()
                return a.base_value() + 1
            """;
    private static final String EXTRA_GD = """
            class_name ExtraHelper
            extends RefCounted

            func combine() -> int:
                var b := Beta.new()
                return b.use_alpha() * 2
            """;
    private static final String UNLISTED_GD = """
            class_name UnlistedHelper
            extends RefCounted

            func never_compiled() -> int:
                return -1
            """;

    /// Interpreted driver run as a `-s` SceneTree script after the editor scan registered the
    /// published extension. It verifies ClassDB registration, cross-file calls (explicit `.gd` →
    /// `.gd3` → `.gd3`), the compiled `_gdcc_get_metadata` source fields, absence of the unlisted
    /// `.gd`'s class, and a Godot-`ConfigFile` load/save/reload round-trip of the descriptor —
    /// which is exactly the format contract the editor addon relies on. The unique pass marker is
    /// printed only when every check held; failures go to `push_error` plus a non-zero quit.
    private static final String DRIVER_GD = """
            extends SceneTree

            var _ok: bool = true

            func _initialize() -> void:
                for native in ["Alpha", "Beta", "ExtraHelper"]:
                    _check(ClassDB.class_exists(native), "ClassDB registers " + native)
                _check(not ClassDB.class_exists("UnlistedHelper"),
                        "unlisted .gd is not a compiled native class")

                var expected_lib: String = "%%EXPECTED_LIB%%"

                var extra: Object = ClassDB.instantiate("ExtraHelper")
                _check(extra != null, "instantiate ExtraHelper")
                if extra != null:
                    _check(int(extra.call("combine")) == 84, "explicit .gd -> Beta -> Alpha chain")

                _check_metadata("Alpha", "res://src/alpha.gd3")
                _check_metadata("Beta", "res://src/beta.gd3")
                _check_metadata("ExtraHelper", "res://src/extra.gd")

                var cfg := ConfigFile.new()
                var err := cfg.load("res://bin/gdcc.gdextension")
                _check(err == OK, "Godot ConfigFile parses published descriptor")
                if err == OK:
                    _check(str(cfg.get_value("configuration", "entry_symbol")) == "gdextension_entry",
                            "entry_symbol")
                    _check(str(cfg.get_value("configuration", "compatibility_minimum")) == "4.5",
                            "compatibility_minimum")
                    _check(cfg.get_value("configuration", "reloadable") == true, "reloadable")
                    _check(cfg.get_value("configuration", "gdcc_managed") == true, "gdcc_managed")
                    var keys: PackedStringArray = cfg.get_section_keys("libraries")
                    _check(keys.size() == 1 and keys[0] == "linux.x86_64",
                            "single canonical library key, got " + str(keys))
                    var lib: String = str(cfg.get_value("libraries", "linux.x86_64", ""))
                    _check(lib == "res://bin/" + expected_lib,
                            "canonical key binds the published library, got " + lib)
                    _check(FileAccess.file_exists(lib), "referenced library exists: " + lib)
                    var owned: PackedStringArray = cfg.get_value(
                            "configuration", "gdcc_libraries", PackedStringArray())
                    _check(owned.has(lib), "gdcc_libraries owns " + lib)
                    err = cfg.save("user://roundtrip.cfg")
                    _check(err == OK, "ConfigFile save round-trip")
                    var reread := ConfigFile.new()
                    err = reread.load("user://roundtrip.cfg")
                    _check(err == OK, "ConfigFile reload round-trip")
                    if err == OK:
                        _check(str(reread.get_value("libraries", "linux.x86_64", "")) == lib,
                                "round-trip keeps the canonical key")
                        _check(reread.get_value("configuration", "gdcc_managed") == true,
                                "round-trip keeps gdcc_managed")

                if _ok:
                    print("GDCC_E2E_PASS::project_compile_runtime")
                else:
                    push_error("GDCC_E2E_FAIL")
                quit(0 if _ok else 1)

            func _check_metadata(native: String, res_path: String) -> void:
                var md: Variant = ClassDB.class_call_static(native, "_gdcc_get_metadata")
                _check(md is Dictionary, native + " metadata dictionary")
                if not (md is Dictionary):
                    return
                var gdcc: Dictionary = (md as Dictionary).get("gdcc", {})
                _check(str(gdcc.get("source_res_path", "")) == res_path,
                        native + " source_res_path")
                _check(str(gdcc.get("source_path", "")) == ProjectSettings.globalize_path(res_path),
                        native + " source_path")
                _check(str(gdcc.get("source_name", "")) == native, native + " source_name")
                _check(str(gdcc.get("module", "")).begins_with("GdccE2EGame_"),
                        native + " module identity")
                _check(float(gdcc.get("format", 0)) == 1.0, native + " metadata format")

            func _check(cond: bool, name: String) -> void:
                if not cond:
                    _ok = false
                    push_error("CHECK FAILED: " + name)
            """;

    @org.junit.jupiter.api.BeforeEach
    void requireLinuxX86_64Host() {
        var os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        var arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (!os.contains("linux") || !(arch.equals("amd64") || arch.equals("x86_64"))) {
            Assumptions.abort("the project-compile end-to-end test requires a linux-x86_64 host");
        }
    }

    /// Launcher leg: the real `gdcc` binary compiles the project sources and publishes
    /// `bin/gdcc.gdextension` plus the hashed library — the descriptor contract is verified
    /// Java-side through the same `GodotConfigFile` parser the CLI itself uses.
    @Test
    void projectCompilePublishesThroughRealLauncher(@TempDir Path tempDir) throws Exception {
        var stage = stageDistribution(tempDir);
        var game = writeProjectFixture(tempDir);
        var outcome = publishWithLauncher(stage, game);

        assertEquals(0, outcome.exitCode(), () -> outcome.describe());
        assertTrue(outcome.stderr().isBlank(), () -> "successful run must not write stderr\n" + outcome.describe());
        assertTrue(outcome.stdout().contains("Compiled module "), () -> outcome.describe());
        assertTrue(outcome.stdout().contains("Artifact: "), () -> outcome.describe());
        var descriptor = game.resolve("bin/gdcc.gdextension");
        // The launcher reports the descriptor under the project root's real path.
        assertTrue(outcome.stdout().contains("Published: " + descriptor.toRealPath()), () -> outcome.describe());

        var publishedLibraryName = requirePublishedLibraryName(game);

        var parsed = GodotConfigFile.parse(Files.readString(descriptor), descriptor.toString());
        assertEquals("gdextension_entry", stringValue(parsed, "configuration", "entry_symbol"));
        assertEquals("4.5", stringValue(parsed, "configuration", "compatibility_minimum"));
        assertEquals("true", rawValue(parsed, "configuration", "reloadable"));
        assertEquals("true", rawValue(parsed, "configuration", "gdcc_managed"));
        var librariesEntry = parsed.findEntry("libraries", "linux.x86_64");
        assertTrue(librariesEntry.isPresent(), () -> "canonical linux.x86_64 library key missing:\n" + parsed.sections());
        var resPath = ((GodotConfigFile.StringValue) librariesEntry.get().value()).text();
        // The canonical key must name exactly the file the product installed under bin/, so a
        // traversal such as `res://bin/../<workdir>/lib.so` cannot satisfy the check.
        assertEquals("res://bin/" + publishedLibraryName, resPath);
        assertTrue(Files.isRegularFile(game.resolve(resPath.substring("res://".length()))),
                () -> "referenced library missing on disk: " + resPath);
        var owned = parsed.findEntry("configuration", "gdcc_libraries");
        assertTrue(owned.isPresent()
                        && owned.get().value() instanceof GodotConfigFile.StringArrayValue array
                        && array.items().equals(List.of(resPath)),
                () -> "gdcc_libraries must own exactly the published library");

        try (var stream = Files.walk(game.resolve(".godot/gdcc"))) {
            var strayMetadata = stream.filter(path -> path.getFileName().toString().endsWith(".gdextension")).toList();
            assertTrue(strayMetadata.isEmpty(),
                    () -> "project mode must not emit a sidecar .gdextension in the work directory: " + strayMetadata);
        }
    }

    /// Godot leg: an isolated editor scans the published project (producing `extension_list.cfg`
    /// and loading the extension), then a headless runtime drives the interpreted driver against
    /// the registered classes. Requires `GODOT_BIN` pointing at an editor-capable binary.
    @Test
    void publishedProjectLoadsInGodotRuntime(@TempDir Path tempDir) throws Exception {
        var godotBinary = GodotEditorHotReloadTestSession.requireEditorCapableGodotOrAbort();
        var stage = stageDistribution(tempDir);
        var game = writeProjectFixture(tempDir);
        var publish = publishWithLauncher(stage, game);
        assertEquals(0, publish.exitCode(), () -> publish.describe());

        var expectedLib = requirePublishedLibraryName(game);
        Files.writeString(
                game.resolve("driver.gd"),
                DRIVER_GD.replace("%%EXPECTED_LIB%%", expectedLib),
                StandardCharsets.UTF_8);
        var godotHome = Files.createDirectories(tempDir.resolve("godot-home"));

        var editorRun = runGodot(godotBinary, game, godotHome, List.of(
                "--editor", "--path", game.toString(), "--quit-after", Integer.toString(EDITOR_SCAN_FRAMES)));
        assertEquals(0, editorRun.exitCode(), () -> "editor import/scan failed\n" + editorRun.describe());
        var extensionList = game.resolve(".godot/extension_list.cfg");
        assertTrue(Files.isRegularFile(extensionList),
                () -> "editor scan must persist .godot/extension_list.cfg\n" + editorRun.describe());
        assertTrue(Files.readString(extensionList).contains("res://bin/gdcc.gdextension"),
                () -> "editor must discover the published descriptor itself\n" + editorRun.describe());
        assertTrue(editorRun.stdout().contains("Loading GdccE2EGame_"),
                () -> "editor must load the published extension during scan\n" + editorRun.describe());

        var driverRun = runGodot(godotBinary, game, godotHome, List.of(
                "--path", game.toString(), "-s", game.resolve("driver.gd").toString()));
        assertEquals(0, driverRun.exitCode(), () -> driverRun.describe());
        assertTrue(driverRun.stdout().contains(PASS_MARKER), () -> driverRun.describe());
        assertFalse(driverRun.stderr().contains("CHECK FAILED"), () -> driverRun.describe());
        assertFalse(driverRun.stderr().contains("GDCC_E2E_FAIL"), () -> driverRun.describe());
        assertFalse(driverRun.stderr().contains("SCRIPT ERROR"), () -> driverRun.describe());
        assertFalse(driverRun.stderr().contains("ERROR"), () -> driverRun.describe());
        assertFalse(editorRun.stderr().contains("ERROR"), () -> "editor reported errors\n" + editorRun.describe());
    }

    /// `bin/` must hold exactly the descriptor plus one published library whose name matches the
    /// addon-compatible hash naming; returns the library file name for binding assertions.
    private static @org.jetbrains.annotations.NotNull String requirePublishedLibraryName(Path game) throws IOException {
        try (var stream = Files.list(game.resolve("bin"))) {
            var binEntries = stream.map(path -> path.getFileName().toString()).sorted().toList();
            assertEquals(2, binEntries.size(),
                    () -> "bin must contain exactly the descriptor and the published library, got " + binEntries);
            assertEquals("gdcc.gdextension", binEntries.getFirst());
            var libraryName = binEntries.get(1);
            assertTrue(libraryName.matches("libGdccE2EGame_linux_debug_x86_64-[0-9a-f]{16}\\.so"),
                    () -> "unexpected library name: " + binEntries);
            return libraryName;
        }
    }

    /// Stages the packaged layout the launcher resolves: the binary, exactly one current-version
    /// `gdcc-*.jar`, `lib/` dependencies, and the parser `native/` resources beside it.
    private static @org.jetbrains.annotations.NotNull Path stageDistribution(Path tempDir) throws IOException {
        if (!Files.isExecutable(LAUNCHER_BINARY)) {
            Assumptions.abort("launcher binary missing; run the Gradle `buildZigLauncher` task first");
        }
        if (!isUsableBundle(TINYCC_BUNDLE_DIR)) {
            Assumptions.abort("tinycc bundle not built; run the tinycc bundle build script first");
        }
        var stage = Files.createDirectories(tempDir.resolve("stage"));
        var stagedLauncher = stage.resolve("gdcc");
        Files.copy(LAUNCHER_BINARY, stagedLauncher);
        if (!stagedLauncher.toFile().setExecutable(true)) {
            throw new IOException("could not mark the staged launcher executable: " + stagedLauncher);
        }
        Files.copy(requireCurrentDistJar(), stage.resolve(requireCurrentDistJar().getFileName().toString()));
        copyRecursively(DIST_LIBS_DIR.resolve("lib"), stage.resolve("lib"));
        copyRecursively(PARSER_NATIVE_DIR, stage.resolve("native"));
        return stage;
    }

    /// The jar whose version matches the one the build script currently produces; `build/libs`
    /// keeps historical jars, and the launcher rejects a directory holding more than one.
    private static @org.jetbrains.annotations.NotNull Path requireCurrentDistJar() {
        String version = null;
        try {
            var matcher = Pattern.compile("version\\s*=\\s*\"([^\"]+)\"").matcher(Files.readString(BUILD_SCRIPT));
            if (matcher.find()) {
                version = matcher.group(1);
            }
        } catch (IOException _) {
            // Fall through to the abort below.
        }
        if (version != null) {
            var jar = DIST_LIBS_DIR.resolve("gdcc-" + version + ".jar");
            if (Files.isRegularFile(jar)) {
                return jar;
            }
        }
        Assumptions.abort("current-version distribution jar not built; run the Gradle `jar` task first");
        throw new IllegalStateException("unreachable");
    }

    /// Builds the project fixture: two `.gd3`, one explicit `.gd`, and one unlisted `.gd`
    /// (valid — its mere absence from the compiled registry is what proves it was never
    /// picked up).
    private static @org.jetbrains.annotations.NotNull Path writeProjectFixture(Path tempDir) throws IOException {
        var game = Files.createDirectories(tempDir.resolve("game"));
        var src = Files.createDirectories(game.resolve("src"));
        Files.writeString(game.resolve("project.godot"), PROJECT_GODOT, StandardCharsets.UTF_8);
        Files.writeString(src.resolve("alpha.gd3"), ALPHA_GD3, StandardCharsets.UTF_8);
        Files.writeString(src.resolve("beta.gd3"), BETA_GD3, StandardCharsets.UTF_8);
        Files.writeString(src.resolve("extra.gd"), EXTRA_GD, StandardCharsets.UTF_8);
        Files.writeString(src.resolve("unlisted.gd"), UNLISTED_GD, StandardCharsets.UTF_8);
        return game;
    }

    /// Runs `gdcc <project.godot> <explicit .gd>` through the staged launcher. `HOME` and
    /// `JAVA_HOME` are pinned so launcher Java discovery and any per-user caches stay inside the
    /// test's temp dir.
    private static ExecResult publishWithLauncher(Path stage, Path game) throws IOException, InterruptedException {
        var environment = new HashMap<String, String>();
        environment.put("HOME", Files.createDirectories(stage.resolve("home")).toString());
        environment.put("JAVA_HOME", System.getProperty("java.home"));
        environment.put("PATH", "/usr/bin:/bin");
        return run(
                List.of(stage.resolve("gdcc").toString(),
                        game.resolve("project.godot").toString(),
                        game.resolve("src/extra.gd").toString()),
                environment,
                LAUNCHER_TIMEOUT_MINUTES,
                TimeUnit.MINUTES
        );
    }

    /// Runs Godot with the given mode arguments in an editor-state sandbox (`HOME`/`XDG_*` all
    /// under `godotHome`), so the import/scan and runtime legs cannot read or write the user's
    /// real Godot configuration.
    private static ExecResult runGodot(Path godotBinary, Path game, Path godotHome, List<String> modeArgs)
            throws IOException, InterruptedException {
        var environment = new HashMap<String, String>();
        environment.put("HOME", godotHome.toString());
        environment.put("XDG_CONFIG_HOME", godotHome.resolve("config").toString());
        environment.put("XDG_DATA_HOME", godotHome.resolve("data").toString());
        environment.put("XDG_CACHE_HOME", godotHome.resolve("cache").toString());
        environment.put("PATH", "/usr/bin:/bin");
        var command = new ArrayList<String>();
        command.add(godotBinary.toString());
        command.add("--headless");
        command.addAll(modeArgs);
        return run(command, environment, GODOT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private static ExecResult run(List<String> command, Map<String, String> environment,
                                  long timeout, TimeUnit unit) throws IOException, InterruptedException {
        var stdoutFile = Files.createTempFile("gdcc-e2e-", ".out");
        var stderrFile = Files.createTempFile("gdcc-e2e-", ".err");
        try {
            var processBuilder = new ProcessBuilder(command);
            processBuilder.environment().clear();
            processBuilder.environment().putAll(environment);
            processBuilder.redirectOutput(stdoutFile.toFile());
            processBuilder.redirectError(stderrFile.toFile());
            var process = processBuilder.start();
            try {
                if (!process.waitFor(timeout, unit)) {
                    terminateProcessTree(process);
                    fail("process timed out: " + String.join(" ", command)
                            + "\npartial stdout:\n" + Files.readString(stdoutFile)
                            + "\npartial stderr:\n" + Files.readString(stderrFile));
                }
            } catch (InterruptedException exception) {
                // The launcher keeps its Java child alive while it waits on it, so an aborted
                // wait still has to reap the whole tree — a leftover compiler could otherwise
                // keep writing into directories the test is about to delete.
                terminateProcessTree(process);
                Thread.currentThread().interrupt();
                throw exception;
            }
            return new ExecResult(
                    process.exitValue(),
                    Files.readString(stdoutFile, StandardCharsets.UTF_8),
                    Files.readString(stderrFile, StandardCharsets.UTF_8),
                    String.join(" ", command)
            );
        } finally {
            Files.deleteIfExists(stdoutFile);
            Files.deleteIfExists(stderrFile);
        }
    }

    /// Force-terminates `process` together with every descendant it spawned (the launcher only
    /// supervises its Java child; killing the parent alone would orphan it), then gives the tree
    /// a bounded window to die before returning.
    private static void terminateProcessTree(Process process) {
        var descendants = process.descendants().toList();
        descendants.forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException _) {
            // Still terminating; the caller restores the interrupt status when it propagates.
        }
        for (var descendant : descendants) {
            descendant.onExit().orTimeout(5, TimeUnit.SECONDS).exceptionally(_ -> null).join();
        }
    }

    private static boolean isUsableBundle(Path root) {
        return Files.isRegularFile(root.resolve("VERSION"))
                && Files.isRegularFile(root.resolve("MANIFEST"))
                && Files.isRegularFile(root.resolve("bin/libtcc.so"))
                && Files.isRegularFile(root.resolve("libtcc1.a"));
    }

    private static void copyRecursively(Path source, Path target) throws IOException {
        try (var walk = Files.walk(source)) {
            for (var entry : walk.toList()) {
                var destination = target.resolve(source.relativize(entry).toString());
                if (Files.isDirectory(entry)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(entry, destination);
                }
            }
        }
    }

    private static String stringValue(GodotConfigFile file, String section, String key) {
        var entry = file.findEntry(section, key);
        assertTrue(entry.isPresent() && entry.get().value() instanceof GodotConfigFile.StringValue,
                () -> "expected string " + section + "/" + key);
        return ((GodotConfigFile.StringValue) entry.get().value()).text();
    }

    private static String rawValue(GodotConfigFile file, String section, String key) {
        var entry = file.findEntry(section, key);
        assertTrue(entry.isPresent(), () -> "missing " + section + "/" + key);
        return entry.get().value().raw();
    }

    private record ExecResult(int exitCode, String stdout, String stderr, String command) {
        private String describe() {
            return "command: " + command + "\nexit: " + exitCode + "\nstdout:\n" + stdout + "\nstderr:\n" + stderr;
        }
    }
}
