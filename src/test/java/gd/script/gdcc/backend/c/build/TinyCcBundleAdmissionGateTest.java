package gd.script.gdcc.backend.c.build;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Real-bundle admission gate: the bundle the build scripts actually produced must pass the
/// PRODUCTION resolution chain ([TinyCcBundle#requireBundleRoot] — layout + VERSION three-key +
/// MANIFEST SHA-256 over the whole tree) before any FFM compile may use it. The other native
/// tests inject the discovered bundle root straight into the compiler and so never exercise that
/// chain against real bytes; this gate closes the hole, anchoring that the CI-produced bundle
/// (both directions: accepts the honest bundle, rejects drifted ones) is admissible.
///
/// Gated on a real bundle (`build/tinycc-bundle/<key>/` or `GDCC_TINYCC_HOME`); without one the
/// tests skip instead of fake-passing.
class TinyCcBundleAdmissionGateTest {

    private static final String VERSION = "admission-gate";

    private static Path discoveredBundle;

    @BeforeAll
    static void requireBundle() {
        discoveredBundle = TinyCcCliTestSupport.findTinyCcBundleRoot();
        Assumptions.assumeTrue(discoveredBundle != null,
                "no tinycc bundle available (GDCC_TINYCC_HOME unset, no build/tinycc-bundle); skipping the bundle admission gate");
    }

    @Test
    void theProducedBundlePassesTheFullProductionAdmissionChain(@TempDir Path tempDir) throws IOException {
        var host = TargetPlatform.getNativePlatform();
        var platformKey = TinyCcBundle.bundlePlatformKey(host);
        var cacheRoot = tempDir.resolve("cache");

        var admitted = TinyCcBundle.requireBundleRoot(discoveredBundle.toString(), cacheRoot, failingSource(), platformKey, VERSION);

        assertEquals(discoveredBundle.toAbsolutePath().normalize(), admitted,
                "an env-override bundle is used in place, never copied");
        assertFalse(Files.exists(cacheRoot), "an env override never touches the install cache");

        // Admission granted: an FFM compile through the admitted root must succeed.
        var projectDir = Files.createDirectories(tempDir.resolve("project"));
        var tu = Files.writeString(projectDir.resolve("entry.c"), NativeArtifactInspector.EXPORT_MACRO_DEFINITION
                + "GDCC_TEST_EXPORT int gdextension_entry(void) { return 1; }\n");
        // Pin the object cache inside the temporary project tree so the round is hermetic.
        var compiler = new TinyCcCompiler(() -> admitted, TinyCcFfmNative::create,
                dir -> dir.resolve("compiler-cache"));
        var result = compiler.compile(projectDir, List.of(), List.of(tu), "admission", COptimizationLevel.DEBUG, host);
        assertTrue(result.success(), () -> "compilation through the admitted bundle failed:\n" + result.buildLog());
        NativeArtifactInspector.assertExportsSymbol(result.artifacts().getFirst(), "gdextension_entry");
    }

    @Test
    void aTamperedBundleIsRejectedByManifestIntegrity(@TempDir Path tempDir) throws IOException {
        var host = TargetPlatform.getNativePlatform();
        var platformKey = TinyCcBundle.bundlePlatformKey(host);
        var tampered = tempDir.resolve("tampered");
        copyTree(discoveredBundle, tampered);
        // Flip one byte in a file every platform layout carries, without touching MANIFEST:
        // layout and VERSION still pass, so only the hash check can catch this.
        var licenseFile = tampered.resolve("COPYING");
        Files.writeString(licenseFile, Files.readString(licenseFile) + "tampered\n");

        var exception = assertThrows(IOException.class,
                () -> TinyCcBundle.requireBundleRoot(tampered.toString(), tempDir.resolve("cache"), failingSource(), platformKey, VERSION));

        assertTrue(exception.getMessage().contains("COPYING"),
                () -> "the rejection must name the tampered file: " + exception.getMessage());
    }

    @Test
    void aBundleBuiltForAnotherPlatformIsRejected(@TempDir Path tempDir) {
        var host = TargetPlatform.getNativePlatform();
        var platformKey = TinyCcBundle.bundlePlatformKey(host);
        // Any supported key other than the host's own: the real bundle's VERSION honestly names
        // its build platform, and the admission chain must refuse the mismatch.
        var wrongKey = platformKey.equals(TinyCcBundle.MACOS_AARCH64_KEY)
                ? TinyCcBundle.LINUX_X86_64_KEY
                : TinyCcBundle.MACOS_AARCH64_KEY;

        var exception = assertThrows(IOException.class,
                () -> TinyCcBundle.requireBundleRoot(discoveredBundle.toString(), tempDir.resolve("cache"), failingSource(), wrongKey, VERSION));

        assertTrue(exception.getMessage().contains("platform"),
                () -> "the rejection must name the VERSION platform mismatch: " + exception.getMessage());
    }

    private static TinyCcBundle.BundleSource failingSource() {
        return stagingDir -> {
            throw new IOException("this source must never be consulted");
        };
    }

    private static void copyTree(Path source, Path target) throws IOException {
        try (var walk = Files.walk(source)) {
            for (var entry : walk.toList()) {
                var destination = target.resolve(source.relativize(entry).toString());
                if (Files.isDirectory(entry)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(entry, destination, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }
}
