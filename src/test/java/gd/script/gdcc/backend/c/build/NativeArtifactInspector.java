package gd.script.gdcc.backend.c.build;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// Cross-platform shared-library assertions for backend tests: ELF exports are checked with
/// `nm -D` (Linux), PE exports with the pure-Java [PeExportReader] (Windows), and the image-magic
/// shape check works everywhere. Centralizing the dispatch keeps every test class consistent on
/// which host needs which tool — and keeps the Windows legs from silently skipping the export
/// assertions that Linux enforces.
final class NativeArtifactInspector {
    private NativeArtifactInspector() {
    }

    /// Minimal freshness proof for a produced artifact: ELF magic on Linux, MZ header on Windows.
    static void assertSharedLibraryShape(@NotNull Path artifact) throws IOException {
        var bytes = Files.readAllBytes(artifact);
        assertTrue(bytes.length > 4, "artifact is suspiciously small: " + bytes.length + " bytes");
        if (isWindowsHost()) {
            assertTrue(bytes[0] == 'M' && bytes[1] == 'Z', "the artifact must be a PE image");
        } else {
            assertTrue(bytes[0] == 0x7F && bytes[1] == 'E' && bytes[2] == 'L' && bytes[3] == 'F',
                    "the artifact must be an ELF shared library");
        }
    }

    /// Asserts `symbol` is in the artifact's dynamic export table. Linux leg uses `nm -D`
    /// (assumption-gated on the tool); the Windows leg parses the PE export directory directly.
    static void assertExportsSymbol(@NotNull Path artifact, @NotNull String symbol) throws IOException {
        if (isWindowsHost()) {
            var info = PeExportReader.parse(artifact);
            assertTrue(info.exportedSymbols().contains(symbol),
                    () -> symbol + " missing from the PE exports: " + info.exportedSymbols());
            return;
        }
        var nm = TinyCcCliTestSupport.findOnPath("nm");
        Assumptions.assumeTrue(nm != null, "nm is required to assert exported symbols");
        var output = run(nm, List.of("-D", "--defined-only", artifact.toString()));
        assertTrue(output.lines().anyMatch(line -> line.endsWith(" T " + symbol)),
                () -> symbol + " missing from the dynamic exports:\n" + output);
    }

    /// Asserts `ownSymbol` is exported while `foreignSymbol` is absent — the cross-link guard for
    /// concurrent rounds sharing an output directory.
    static void assertExportsOnlyOwnSymbol(@NotNull Path artifact, @NotNull String ownSymbol, @NotNull String foreignSymbol) throws IOException {
        if (isWindowsHost()) {
            var info = PeExportReader.parse(artifact);
            assertTrue(info.exportedSymbols().contains(ownSymbol),
                    () -> ownSymbol + " missing from the PE exports: " + info.exportedSymbols());
            assertFalse(info.exportedSymbols().contains(foreignSymbol),
                    () -> "the artifact must not carry the other round's symbol: " + info.exportedSymbols());
            return;
        }
        var nm = TinyCcCliTestSupport.findOnPath("nm");
        Assumptions.assumeTrue(nm != null, "nm is required to assert per-artifact symbol isolation");
        var output = run(nm, List.of("-D", "--defined-only", artifact.toString()));
        assertTrue(output.lines().anyMatch(line -> line.endsWith(" T " + ownSymbol)),
                () -> ownSymbol + " missing from the exports:\n" + output);
        assertFalse(output.contains(foreignSymbol),
                () -> "the artifact must not carry the other round's symbol:\n" + output);
    }

    /// The artifact's full dynamic export set: `nm -D --defined-only` on Linux (assumption-gated
    /// on the tool), the PE export directory on Windows. Used to pin that two rounds/artifacts
    /// export exactly the same symbols.
    static java.util.@NotNull Set<String> exportedSymbols(@NotNull Path artifact) throws IOException {
        if (isWindowsHost()) {
            return new java.util.TreeSet<>(PeExportReader.parse(artifact).exportedSymbols());
        }
        var nm = TinyCcCliTestSupport.findOnPath("nm");
        Assumptions.assumeTrue(nm != null, "nm is required to list exported symbols");
        var output = run(nm, List.of("-D", "--defined-only", artifact.toString()));
        var symbols = new java.util.TreeSet<String>();
        for (var line : output.split("\n")) {
            var trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            var lastSpace = trimmed.lastIndexOf(' ');
            symbols.add(lastSpace >= 0 ? trimmed.substring(lastSpace + 1) : trimmed);
        }
        return symbols;
    }

    private static @NotNull String run(@NotNull Path tool, @NotNull List<String> arguments) throws IOException {
        var command = new java.util.ArrayList<String>();
        command.add(tool.toString());
        command.addAll(arguments);
        try {
            var process = new ProcessBuilder(command).redirectErrorStream(true).start();
            var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.waitFor() != 0) {
                fail("symbol dump failed:\n" + output);
            }
            return output;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while inspecting the artifact", e);
        }
    }

    private static boolean isWindowsHost() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }
}
