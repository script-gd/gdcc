package gd.script.gdcc.backend.c.build;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// Structural gate for the cross-built `windows-x86_64` tinycc bundle, runnable on any host
/// (a PE artifact cannot be loaded here, so this is the host-independent evidence: layout,
/// VERSION/MANIFEST integrity, and the PE surface the FFM binding needs). Anchors the bundle
/// contract:
///
/// - full layout — merged include tree with `winapi/` (windows.h depends on `_mingw.h` sitting
///   next to it), `lib/libtcc1.a` (PE library search expands `{B}/lib`), the five import
///   definitions, license files;
/// - `VERSION` pins bundleFormat=1, the pinned tinycc commit and the platform key;
/// - `MANIFEST` covers every regular file in the tree and every sha256 matches (fail-closed);
/// - `bin/libtcc.dll` is an AMD64 DLL exporting every symbol the FFM binding ([TinyCcLibrary])
///   binds, importing only system CRT/kernel modules.
///
/// Assumption-gated on the cross-built bundle directory; the on-host runtime verification
/// (FFM load, Godot, HRX) is covered by the regular tcc test classes once a Windows CI job
/// builds the bundle.
class TinyCcWindowsBundleParityTest {

    private static final Path BUNDLE_ROOT = Path.of("build/tinycc-bundle/windows-x86_64").toAbsolutePath().normalize();

    /// The FFM binding contract (handoff §7.1): every downcall handle must resolve.
    private static final Set<String> REQUIRED_EXPORTS = Set.of(
            "tcc_new", "tcc_delete", "tcc_set_lib_path", "tcc_set_error_func", "tcc_set_options",
            "tcc_set_output_type", "tcc_add_include_path", "tcc_add_file", "tcc_compile_string", "tcc_output_file");

    private static final List<String> REQUIRED_LAYOUT = List.of(
            "bin/libtcc.dll",
            "lib/libtcc1.a",
            "lib/msvcrt.def",
            "lib/kernel32.def",
            "lib/user32.def",
            "lib/gdi32.def",
            "lib/ws2_32.def",
            "include/tccdefs.h",
            "include/stddef.h",
            "include/stdarg.h",
            "include/stdbool.h",
            "include/tcclib.h",
            "include/_mingw.h",
            "include/winapi/windows.h",
            "VERSION",
            "MANIFEST",
            "COPYING",
            "RELICENSING");

    @BeforeAll
    static void requireBundle() {
        Assumptions.assumeTrue(Files.isDirectory(BUNDLE_ROOT),
                "no cross-built windows-x86_64 bundle at " + BUNDLE_ROOT + " (run build-script/build-tinycc-bundle-windows-x86_64.sh); skipping the structural gate");
    }

    @Test
    void layoutContainsEveryContractEntry() {
        for (var entry : REQUIRED_LAYOUT) {
            assertTrue(Files.isRegularFile(BUNDLE_ROOT.resolve(entry)),
                    () -> "bundle is missing " + entry);
        }
    }

    @Test
    void versionFilePinsFormatCommitAndPlatform() throws IOException {
        var properties = new Properties();
        try (var reader = Files.newBufferedReader(BUNDLE_ROOT.resolve("VERSION"), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        assertEquals("1", properties.getProperty("bundleFormat"), "bundleFormat");
        assertEquals("43c7708b85681a2fd4451c8a541af4494a8919b2", properties.getProperty("tinyccCommit"), "tinyccCommit");
        assertEquals("windows-x86_64", properties.getProperty("platform"), "platform");
    }

    @Test
    void manifestCoversAndMatchesEveryFile() throws IOException {
        var manifestLines = Files.readAllLines(BUNDLE_ROOT.resolve("MANIFEST"), StandardCharsets.UTF_8);
        assertFalse(manifestLines.isEmpty(), "MANIFEST must not be empty (fail-closed)");
        var covered = new HashSet<String>();
        for (var line : manifestLines) {
            // sha256sum format: `<64-hex>  <path>` (two spaces).
            var parts = line.split("  ", 2);
            assertEquals(2, parts.length, () -> "malformed MANIFEST line: " + line);
            assertEquals(64, parts[0].length(), () -> "bad sha256 in MANIFEST line: " + line);
            var relative = parts[1];
            var file = BUNDLE_ROOT.resolve(relative);
            assertTrue(Files.isRegularFile(file), () -> "MANIFEST entry missing from the tree: " + relative);
            assertEquals(parts[0], sha256Hex(Files.readAllBytes(file)),
                    () -> "sha256 mismatch for " + relative);
            covered.add(relative);
        }
        try (var stream = Files.walk(BUNDLE_ROOT)) {
            for (var file : stream.filter(Files::isRegularFile).toList()) {
                var relative = BUNDLE_ROOT.relativize(file).toString().replace('\\', '/');
                if (relative.equals("MANIFEST")) {
                    continue;
                }
                assertTrue(covered.contains(relative), () -> "file not covered by MANIFEST: " + relative);
            }
        }
    }

    @Test
    void libtccDllExportsTheFfmBindingSurface() throws IOException {
        var info = PeExportReader.parse(BUNDLE_ROOT.resolve("bin/libtcc.dll"));
        assertEquals(0x8664, info.machine(), "the bundle DLL must target AMD64");
        assertTrue(info.dll(), "bin/libtcc.dll must carry the DLL characteristic");
        for (var symbol : REQUIRED_EXPORTS) {
            assertTrue(info.exportedSymbols().contains(symbol),
                    () -> symbol + " missing from libtcc.dll exports: " + info.exportedSymbols());
        }
        // Only system CRT/kernel modules: anything else would be an undeclared runtime dependency.
        for (var module : info.importedModules()) {
            var lower = module.toLowerCase(Locale.ROOT);
            assertTrue(lower.startsWith("api-ms-win-crt-") || lower.equals("msvcrt.dll") || lower.equals("kernel32.dll"),
                    () -> "unexpected import dependency of libtcc.dll: " + module + " (all imports: " + info.importedModules() + ")");
        }
    }

    @Test
    void runtimeArchiveAndImportDefinitionsAreWellFormed() throws IOException {
        var archiveMagic = Files.readAllBytes(BUNDLE_ROOT.resolve("lib/libtcc1.a"));
        var magic = new String(archiveMagic, 0, Math.min(8, archiveMagic.length), StandardCharsets.US_ASCII);
        assertEquals("!<arch>\n", magic, "libtcc1.a must be a static archive");
        for (var def : List.of("msvcrt.def", "kernel32.def", "user32.def", "gdi32.def", "ws2_32.def")) {
            var content = Files.readString(BUNDLE_ROOT.resolve("lib").resolve(def), StandardCharsets.UTF_8);
            assertTrue(content.startsWith("LIBRARY "), () -> def + " must start with a LIBRARY directive");
            assertTrue(content.contains("EXPORTS"), () -> def + " must carry an EXPORTS section");
        }
    }

    private static @NotNull String sha256Hex(byte[] content) {
        try {
            var hash = MessageDigest.getInstance("SHA-256").digest(content);
            var hex = new StringBuilder(hash.length * 2);
            for (var b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            fail("SHA-256 unavailable");
            throw new IllegalStateException(e);
        }
    }
}
