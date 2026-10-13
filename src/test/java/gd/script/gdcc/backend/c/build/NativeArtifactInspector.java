package gd.script.gdcc.backend.c.build;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static java.util.Locale.*;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// Cross-platform shared-library assertions for backend tests: ELF exports are checked with
/// `nm -D` (Linux), Mach-O exports with `nm -gU` (macOS), PE exports with the pure-Java
/// [PeExportReader] (Windows), and the image-magic shape check works everywhere. Centralizing
/// the dispatch keeps every test class consistent on which host needs which tool — and keeps
/// the Windows legs from silently skipping the export assertions that Linux enforces.
final class NativeArtifactInspector {
    /// Export attribute prelude for fixture C sources. On PE targets both tinycc and zig cc
    /// (clang) only enter a symbol into the DLL export table when it carries
    /// `__declspec(dllexport)` — bare definitions stay invisible there, unlike ELF where every
    /// global symbol is dynamic by default. Every fixture symbol checked by an export assertion
    /// (or looked up via FFM) must be defined through this macro. Both compilers predefine
    /// `_WIN32` on Windows targets.
    static final String EXPORT_MACRO_DEFINITION = """
            #ifdef _WIN32
            #define GDCC_TEST_EXPORT __declspec(dllexport)
            #else
            #define GDCC_TEST_EXPORT __attribute__((visibility("default")))
            #endif
            """;

    private NativeArtifactInspector() {
    }

    /// Minimal freshness proof for a produced artifact: ELF magic on Linux, a 64-bit arm64
    /// Mach-O header on macOS, MZ header on Windows.
    static void assertSharedLibraryShape(@NotNull Path artifact) throws IOException {
        var bytes = Files.readAllBytes(artifact);
        assertTrue(bytes.length > 8, "artifact is suspiciously small: " + bytes.length + " bytes");
        if (isWindowsHost()) {
            assertTrue(bytes[0] == 'M' && bytes[1] == 'Z', "the artifact must be a PE image");
        } else if (isMacOSHost()) {
            // Little-endian MH_MAGIC_64, then CPU_TYPE_ARM64 (0x0100000C) — every targeted
            // macOS host is Apple silicon, so a fat or wrong-arch image is a defect.
            assertTrue(bytes[0] == (byte) 0xCF && bytes[1] == (byte) 0xFA && bytes[2] == (byte) 0xED && bytes[3] == (byte) 0xFE,
                    "the artifact must be a 64-bit Mach-O image");
            assertTrue(bytes[4] == 0x0C && bytes[5] == 0x00 && bytes[6] == 0x00 && bytes[7] == 0x01,
                    "the artifact must target arm64 (CPU_TYPE_ARM64)");
        } else {
            assertTrue(bytes[0] == 0x7F && bytes[1] == 'E' && bytes[2] == 'L' && bytes[3] == 'F',
                    "the artifact must be an ELF shared library");
        }
    }

    /// Asserts `symbol` is in the artifact's dynamic export table. The Linux leg uses `nm -D`
    /// and the macOS leg `nm -gU` (both assumption-gated on the tool); the Windows leg parses
    /// the PE export directory directly.
    static void assertExportsSymbol(@NotNull Path artifact, @NotNull String symbol) throws IOException {
        if (isWindowsHost()) {
            var info = PeExportReader.parse(artifact);
            assertTrue(info.exportedSymbols().contains(symbol),
                    () -> symbol + " missing from the PE exports: " + info.exportedSymbols());
            return;
        }
        if (isMacOSHost()) {
            var symbols = darwinExportedSymbols(artifact);
            assertTrue(symbols.contains(symbol),
                    () -> symbol + " missing from the Mach-O exports:\n" + symbols);
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
        if (isMacOSHost()) {
            var symbols = darwinExportedSymbols(artifact);
            assertTrue(symbols.contains(ownSymbol), () -> ownSymbol + " missing from the Mach-O exports:\n" + symbols);
            assertFalse(symbols.contains(foreignSymbol),
                    () -> "the artifact must not carry the other round's symbol:\n" + symbols);
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

    /// The artifact's full dynamic export set: `nm -D --defined-only` on Linux, `nm -gU` on
    /// macOS (both assumption-gated on the tool), the PE export directory on Windows. Used to
    /// pin that two rounds/artifacts export exactly the same symbols.
    static java.util.@NotNull Set<String> exportedSymbols(@NotNull Path artifact) throws IOException {
        if (isWindowsHost()) {
            return new java.util.TreeSet<>(PeExportReader.parse(artifact).exportedSymbols());
        }
        if (isMacOSHost()) {
            return darwinExportedSymbols(artifact);
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

    /// Mach-O exports via Apple's `nm -gU` (external, defined only), with the Mach-O leading
    /// underscore decoration stripped so callers compare plain C symbol names.
    private static java.util.@NotNull Set<String> darwinExportedSymbols(@NotNull Path artifact) throws IOException {
        var nm = TinyCcCliTestSupport.findOnPath("nm");
        Assumptions.assumeTrue(nm != null, "nm (Xcode CLT) is required to list exported symbols");
        var output = run(nm, List.of("-gU", artifact.toString()));
        var symbols = new java.util.TreeSet<String>();
        for (var line : output.split("\n")) {
            var fields = line.trim().split("\\s+");
            // Canonical rows are `<hex-address> <type-letter> <name>`; anything else (section
            // headers, multi-architecture banners) is not a symbol row.
            if (fields.length != 3 || !fields[0].matches("[0-9a-fA-F]+") || fields[1].length() != 1) {
                continue;
            }
            var name = fields[2];
            symbols.add(name.startsWith("_") ? name.substring(1) : name);
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
        return System.getProperty("os.name", "").toLowerCase(ROOT).contains("win");
    }

    private static boolean isMacOSHost() {
        var osName = System.getProperty("os.name", "").toLowerCase(ROOT);
        return osName.contains("mac") || osName.contains("darwin");
    }
}
