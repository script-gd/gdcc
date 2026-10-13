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
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// zig → tcc → zig alternation regression on ONE shared include tree (the `shared-include`
/// sibling layout that production and CI use). [CProjectBuilder] re-extracts the runtime includes
/// on every round through [ResourceExtractor]'s content-compare skip, which must leave identical
/// files untouched — mtime included — because the zig PCH/object caches key on header mtimes. A
/// tcc round that rewrote the tree would silently degrade the FOLLOWING zig rounds, so every round
/// asserts the whole tree (path set, size, mtime, sha256) against the first round's snapshot.
///
/// Both backends target the same output file name across rounds, so each round also proves the
/// artifact is freshly produced and loadable in shape; tcc rounds additionally pin the documented
/// log-contract difference (no `Command:` sections — there is no subprocess).
class CrossBackendIncludeStabilityTest {

    @Test
    void zigTccZigAlternationKeepsIncludeTreeUntouchedAndProducesValidArtifacts(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(ZigUtil.findZig() != null, "zig is required for the zig legs");
        var bundleRoot = TinyCcCliTestSupport.findTinyCcBundleRoot();
        Assumptions.assumeTrue(bundleRoot != null, "no tinycc bundle available; skipping the alternation test");

        // The sibling `shared-include` directory is exactly the layout resolveIncludeRoot prefers
        // when no GDCC_SHARED_INCLUDE env is visible (the empty environment map keeps the ambient
        // CI env out of this test).
        Files.createDirectories(tempDir.resolve("shared-include"));
        var projectDir = Files.createDirectories(tempDir.resolve("project"));
        var builder = new CProjectBuilder(
                Map.of(
                        CCompilerKind.ZIG_CC, new ZigCcCompiler(),
                        CCompilerKind.TINY_CC, new TinyCcCompiler(() -> bundleRoot, TinyCcFfmNative::create)
                ),
                Map.of()
        );
        var host = TargetPlatform.getNativePlatform();
        var includeRoot = builder.resolveIncludeRoot(projectDir);

        Map<String, FileFingerprint> snapshot = null;
        var sequence = List.of(CCompilerKind.ZIG_CC, CCompilerKind.TINY_CC, CCompilerKind.ZIG_CC, CCompilerKind.TINY_CC, CCompilerKind.ZIG_CC);
        for (var round = 0; round < sequence.size(); round++) {
            final var roundNo = round;
            final var kind = sequence.get(round);
            var result = buildRound(builder, projectDir, kind, host);
            assertTrue(result.success(), () -> "round " + roundNo + " (" + kind + ") failed:\n" + result.buildLog());
            var artifact = result.artifacts().getFirst();
            assertTrue(Files.isRegularFile(artifact) && Files.size(artifact) > 0,
                    () -> "round " + roundNo + " produced no artifact");
            assertSharedLibraryShape(artifact);
            if (kind == CCompilerKind.TINY_CC) {
                assertFalse(result.buildLog().contains("Command:"),
                        () -> "tcc rounds never log subprocess commands:\n" + result.buildLog());
            }

            if (snapshot == null) {
                snapshot = snapshotTree(includeRoot);
            } else {
                assertEquals(snapshot, snapshotTree(includeRoot),
                        () -> "round " + roundNo + " (" + kind + ") modified the shared include tree");
            }
        }
    }

    /// One full real-pipeline round for the fixture module with the requested backend kind.
    private static @NotNull CBuildResult buildRound(
            @NotNull CProjectBuilder builder,
            @NotNull Path projectDir,
            @NotNull CCompilerKind kind,
            @NotNull TargetPlatform host
    ) throws IOException {
        var parser = new GdScriptParserService();
        var parseDiagnostics = new DiagnosticManager();
        var unit = parser.parseUnit(projectDir.resolve("alternation_probe.gd"), FIXTURE_SOURCE, parseDiagnostics);
        assertTrue(parseDiagnostics.isEmpty(), () -> "parse diagnostics: " + parseDiagnostics.snapshot());

        var diagnostics = new DiagnosticManager();
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadVersion(GodotVersion.V451));
        var lowered = new FrontendLoweringPassManager().lower(
                new FrontendModule("alternation_probe", List.of(unit), Map.of()), classRegistry, diagnostics);
        if (lowered == null || diagnostics.hasErrors()) {
            fail("lowering failed: " + diagnostics.snapshot());
        }

        var projectInfo = new CProjectInfo(
                "alternation_probe", GodotVersion.V451, projectDir, COptimizationLevel.DEBUG, host, kind);
        var codegen = new CCodegen();
        codegen.prepare(new CodegenContext(projectInfo, classRegistry), lowered);
        var result = builder.buildProject(projectInfo, codegen);
        if (result.success()) {
            assertFalse(result.artifacts().isEmpty(), "a successful round must produce artifacts");
        }
        return result;
    }

    /// Full-tree fingerprint: relative path -> (size, mtime, sha256). Comparing maps across rounds
    /// catches content rewrites (sha), metadata churn (mtime) and structural drift (path set).
    private static @NotNull Map<String, FileFingerprint> snapshotTree(@NotNull Path root) throws IOException {
        var snapshot = new TreeMap<String, FileFingerprint>();
        try (var stream = Files.walk(root)) {
            for (var file : stream.filter(Files::isRegularFile).toList()) {
                var relative = root.relativize(file).toString().replace('\\', '/');
                snapshot.put(relative, new FileFingerprint(
                        Files.size(file),
                        Files.getLastModifiedTime(file).toMillis(),
                        sha256Hex(Files.readAllBytes(file))
                ));
            }
        }
        return snapshot;
    }

    private static @NotNull String sha256Hex(byte[] content) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var hash = digest.digest(content);
            var hex = new StringBuilder(hash.length * 2);
            for (var b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private record FileFingerprint(long size, long mtimeMillis, @NotNull String sha256) {
    }

    private static void assertSharedLibraryShape(@NotNull Path artifact) throws IOException {
        // The shared inspector dispatches per host (ELF / Mach-O arm64 / PE).
        NativeArtifactInspector.assertSharedLibraryShape(artifact);
    }

    private static final String FIXTURE_SOURCE = """
            class_name AlternationProbe
            extends RefCounted
            
            var label: String = "alternation"
            
            func describe() -> String:
                return label + "-" + str(7)
            """;
}
