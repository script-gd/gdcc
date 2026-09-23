package gd.script.gdcc.rpc;

import gd.script.gdcc.api.API;
import gd.script.gdcc.api.AnalysisResult;
import gd.script.gdcc.api.AnalyzeOptions;
import gd.script.gdcc.backend.c.build.COptimizationLevel;
import gd.script.gdcc.backend.c.build.TargetPlatform;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnostic;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Phase 1 static gate of the .gd3 editor integration (plan §7/§8.1), sibling to
/// `EditorAddonClientAnalysisTest` (which keeps pinning the RPC client alone). Covers:
/// - every compiled `.gd3` source of the addon (language / script / loader / saver / service /
///   client) analyzed and lowered together in one module, so cross-class references resolve;
/// - the no-coroutine interop contract on every compiled source (the engine calls these
///   classes synchronously, so a coroutine anywhere would break the boundary exactly like it
///   would in the RPC client);
/// - parse-level gating of the three interpreted scripts (editor-only APIs are not gdcc
///   compile targets, so the check stops at parsing);
/// - the `reloadable = false` post-processing on every installer exit (single-platform,
///   multi-platform), the hot-reload dangling-pointer guard of the plan's §6.
class EditorAddonScriptLanguageAnalysisTest {
    private static final Path ADDON_DIR = Path.of("src/editor_addon/addons/gdcc");
    private static final String MODULE_ID = "editor-addon-language";
    private static final List<String> INTERPRETED_SCRIPTS = List.of(
            "plugin.gd", "gdcc_dock.gd", "server_launcher.gd");
    private static final Pattern AWAIT_PATTERN = Pattern.compile("\\bawait\\b");

    @TempDir
    Path tempDir;

    @Test
    void addonModuleAnalyzesAndLowersCleanly() throws IOException {
        var sources = listAddonSources();
        // The module must contain the whole integration set, not just the RPC client.
        assertTrue(sources.size() >= 6, () -> "expected the full addon source set, got " + sources);

        var api = new API();
        api.createModule(MODULE_ID, "Editor Addon Language");
        for (var source : sources) {
            api.putFile(MODULE_ID, "/src/" + source.getFileName().toString(), Files.readString(source));
        }

        var result = api.analyze(MODULE_ID, new AnalyzeOptions(true));

        assertEquals(AnalysisResult.Outcome.COMPLETED, result.outcome(), () -> diagnosticsText(result));
        assertFalse(result.hasErrors(), () -> diagnosticsText(result));
        assertEquals(AnalysisResult.LoweringStatus.SUCCEEDED, result.loweringStatus(), () -> diagnosticsText(result));
        var expectedPaths = sources.stream()
                .map(source -> "/src/" + source.getFileName().toString())
                .toList();
        assertEquals(expectedPaths, result.sourcePaths());
    }

    @Test
    void compiledSourcesContainNoCoroutines() throws IOException {
        // Contract assertions run on code with comments and string-literal contents stripped:
        // the sources' own documentation mentions `await` and the language's reserved-words
        // table carries it as DATA, so matching raw text would fail on non-code occurrences.
        for (var source : listAddonSources()) {
            var code = stripCommentsAndStringContents(Files.readString(source).replace("\r\n", "\n"));
            assertFalse(AWAIT_PATTERN.matcher(code).find(),
                    () -> source.getFileName() + " must not contain a coroutine (await found in code)");
        }
    }

    @Test
    void interpretedScriptsParseCleanly() throws IOException {
        var parserService = new GdScriptParserService();
        for (var scriptName : INTERPRETED_SCRIPTS) {
            var scriptPath = ADDON_DIR.resolve(scriptName);
            var diagnosticManager = new DiagnosticManager();
            parserService.parseUnit(scriptPath, Files.readString(scriptPath), diagnosticManager);
            assertFalse(diagnosticManager.hasErrors(), () -> {
                var text = new StringBuilder("parse errors in ").append(scriptPath).append(":\n");
                for (FrontendDiagnostic diagnostic : diagnosticManager.snapshot().asList()) {
                    text.append(diagnostic.severity()).append(' ')
                            .append(diagnostic.category()).append(' ')
                            .append(diagnostic.message()).append('\n');
                }
                return text.toString();
            });
        }
    }

    @Test
    void installedExtensionMetadataDisablesHotReloadOnAllExits() throws IOException {
        var fakeWindows = Files.writeString(tempDir.resolve("gdcc_for_editor_debug_x86_64.dll"), "fake");
        var fakeLinux = Files.writeString(tempDir.resolve("libgdcc_for_editor_debug_x86_64.so"), "fake");

        // Single-platform exit (covers both the in-place install and the root-level one — they
        // funnel through the same overload).
        var singleDir = Files.createDirectories(tempDir.resolve("single"));
        EditorAddonProjectInstaller.installExtension(
                singleDir, EditorAddonProjectInstaller.EXTENSION_SUB_DIR, List.of(fakeWindows),
                EditorAddonProjectInstaller.EXTENSION_FILE_NAME,
                COptimizationLevel.DEBUG, TargetPlatform.WINDOWS_X86_64, false);
        var single = Files.readString(singleDir
                .resolve(EditorAddonProjectInstaller.EXTENSION_SUB_DIR)
                .resolve(EditorAddonProjectInstaller.EXTENSION_FILE_NAME));
        assertTrue(single.contains("reloadable = false"), () -> "single-platform metadata:\n" + single);
        assertFalse(single.contains("reloadable = true"), () -> "single-platform metadata:\n" + single);
        assertTrue(single.contains("entry_symbol"), () -> "metadata mangled by the rewrite:\n" + single);

        // Multi-platform exit.
        var multiDir = Files.createDirectories(tempDir.resolve("multi"));
        var byPlatform = new LinkedHashMap<TargetPlatform, List<Path>>();
        byPlatform.put(TargetPlatform.WINDOWS_X86_64, List.of(fakeWindows));
        byPlatform.put(TargetPlatform.LINUX_X86_64, List.of(fakeLinux));
        EditorAddonProjectInstaller.installMultiPlatformExtension(
                multiDir, EditorAddonProjectInstaller.EXTENSION_SUB_DIR, byPlatform,
                EditorAddonProjectInstaller.EXTENSION_FILE_NAME, COptimizationLevel.DEBUG, false);
        var multi = Files.readString(multiDir
                .resolve(EditorAddonProjectInstaller.EXTENSION_SUB_DIR)
                .resolve(EditorAddonProjectInstaller.EXTENSION_FILE_NAME));
        assertTrue(multi.contains("reloadable = false"), () -> "multi-platform metadata:\n" + multi);
        assertFalse(multi.contains("reloadable = true"), () -> "multi-platform metadata:\n" + multi);
    }

    @Test
    void installWithoutDynamicLibraryArtifactFails() throws IOException {
        // Negative anchor for the install exits: existing but non-library artifacts must be
        // rejected with the no-library error, never silently installed.
        var notALibrary = Files.writeString(tempDir.resolve("readme.txt"), "not a library");
        var exception = assertThrows(IOException.class, () -> EditorAddonProjectInstaller.installExtension(
                tempDir.resolve("bad"), "", List.of(notALibrary),
                "gdcc_for_editor.gdextension", COptimizationLevel.DEBUG,
                TargetPlatform.WINDOWS_X86_64, false));
        assertTrue(exception.getMessage().contains("No dynamic library artifact"),
                () -> "unexpected failure mode: " + exception.getMessage());
    }

    private static List<Path> listAddonSources() throws IOException {
        try (Stream<Path> stream = Files.list(ADDON_DIR)) {
            return stream
                    .filter(path -> path.getFileName().toString().endsWith(".gd3"))
                    .sorted()
                    .toList();
        }
    }

    /// Removes `#` comments per line and blanks the CONTENTS of double-quoted string literals
    /// (escape-aware). Sufficient for the addon sources, which use neither triple-quoted
    /// strings nor `#` inside literals; keyword data such as the reserved-words `"await"`
    /// entry must not trip the no-coroutine check.
    private static String stripCommentsAndStringContents(String source) {
        var codeOnly = new StringBuilder(source.length());
        for (var line : source.split("\n", -1)) {
            var inString = false;
            var escaped = false;
            var end = line.length();
            var builder = new StringBuilder(line.length());
            for (var index = 0; index < line.length(); index++) {
                var character = line.charAt(index);
                if (!inString && character == '#') {
                    end = index;
                    break;
                }
                if (character == '"' && !escaped) {
                    inString = !inString;
                    builder.append(character);
                } else if (inString && !escaped) {
                    builder.append(' '); // literal content: blanked
                } else {
                    builder.append(character);
                }
                escaped = inString && character == '\\' && !escaped;
            }
            codeOnly.append(builder, 0, Math.min(end, builder.length())).append('\n');
        }
        return codeOnly.toString();
    }

    private static String diagnosticsText(AnalysisResult result) {
        var text = new StringBuilder("Analysis diagnostics:\n");
        for (FrontendDiagnostic diagnostic : result.diagnostics().asList()) {
            text.append(diagnostic.severity())
                    .append(' ')
                    .append(diagnostic.category())
                    .append(' ')
                    .append(diagnostic.message())
                    .append('\n');
        }
        return text.toString();
    }
}
