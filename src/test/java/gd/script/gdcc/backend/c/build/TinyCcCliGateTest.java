package gd.script.gdcc.backend.c.build;

import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.backend.c.gen.CCodegen;
import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.lir.LirBasicBlock;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirModule;
import gd.script.gdcc.lir.LirParameterDef;
import gd.script.gdcc.lir.LirPropertyDef;
import gd.script.gdcc.lir.insn.LiteralStringInsn;
import gd.script.gdcc.lir.insn.ReturnInsn;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdStringType;
import gd.script.gdcc.type.GdVoidType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// G2 portability gate: the exact compiler input set that `CProjectBuilder` assembles (one
/// generated entry TU from a real codegen pass plus the four extracted runtime TUs) must link into
/// a shared library under the mob tinycc CLI with `-std=c11` and zero diagnostics — the same round
/// `TinyCcCompiler` will drive through FFM later. A recording fake `CCompiler` captures the
/// production input list without invoking zig, so this test exercises the real include extraction
/// and generation path. Real linking (never `-c`) keeps the crt/libc resolution chain covered.
/// Assumption-gated on the tinycc CLI and `nm` (see TinyCcCliTestSupport).
class TinyCcCliGateTest {
    @TempDir
    private Path projectDir;

    @Test
    void sharedLibraryOfFullTuSetLinksCleanlyUnderTinyCc() throws IOException, InterruptedException {
        var tcc = TinyCcCliTestSupport.findTinyCcCli();
        Assumptions.assumeTrue(tcc != null, "tinycc CLI is required for the G2 link gate");
        // null omits -B and keeps this binary's compiled-in runtime search paths.
        var runtimeRoot = TinyCcCliTestSupport.resolveRuntimeRoot(tcc);

        var input = collectProductionCompilerInputs();
        assertEquals(5, input.cFiles().size(),
                () -> "entry TU + 4 runtime TUs expected, got " + input.cFiles());

        var output = projectDir.resolve("libtccgate.so");
        var command = new ArrayList<String>();
        command.add(tcc.toString());
        if (runtimeRoot != null) {
            command.add("-B");
            command.add(runtimeRoot.toString());
        }
        command.add("-std=c11");
        command.add("-shared");
        command.add("-fPIC");
        for (var includeDir : input.includeDirs()) {
            command.add("-I" + includeDir);
        }
        for (var cFile : input.cFiles()) {
            command.add(cFile.toString());
        }
        command.add("-o");
        command.add(output.toString());

        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        var buildOutput = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        var exitCode = process.waitFor();
        assertEquals(0, exitCode, () -> String.join(" ", command) + "\n" + buildOutput);
        // Zero-diagnostic gate: a clean tcc link prints nothing at all; any warning text
        // (not just "error") fails the surface contract here.
        assertTrue(buildOutput.isBlank(),
                () -> "tcc reported diagnostics on the shared C layer:\n" + buildOutput);
        assertTrue(Files.isRegularFile(output) && Files.size(output) > 0, "shared library artifact missing");

        var nm = TinyCcCliTestSupport.findOnPath("nm");
        Assumptions.assumeTrue(nm != null, "nm is required to assert exported entry symbol");
        var nmProcess = new ProcessBuilder(nm.toString(), "-D", "--defined-only", output.toString())
                .redirectErrorStream(true).start();
        var nmOutput = new String(nmProcess.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, nmProcess.waitFor(), () -> "nm failed:\n" + nmOutput);
        assertTrue(nmOutput.contains("gdextension_entry"),
                () -> "entry symbol missing from dynamic exports:\n" + nmOutput);
    }

    /// Runs the production generation/extraction pipeline with the native compile step replaced by
    /// a recording fake, yielding exactly the include dirs and TU list a real build would compile.
    private CompilerInput collectProductionCompilerInputs() throws IOException {
        var recording = new RecordingCCompiler();
        var builder = new CProjectBuilder(recording);
        var projectInfo = new CProjectInfo("tccgate", GodotVersion.V451, projectDir,
                COptimizationLevel.DEBUG, TargetPlatform.getNativePlatform());
        builder.initProject(projectInfo);

        var api = ExtensionApiLoader.loadVersion(GodotVersion.V451);
        var ctx = new CodegenContext(projectInfo, new ClassRegistry(api));
        var probeClass = new LirClassDef("GDTccGateProbe", "RefCounted");
        probeClass.setSourceFile("tcc_gate_probe.gd");
        probeClass.addProperty(new LirPropertyDef("title", GdStringType.STRING, false, null, null, null, Map.of()));
        var describeFunc = new LirFunctionDef("describe", "entry");
        describeFunc.setReturnType(GdStringType.STRING);
        describeFunc.addParameter(new LirParameterDef("self", new GdObjectType("GDTccGateProbe"), null, describeFunc));
        describeFunc.createAndAddVariable("0", GdStringType.STRING);
        var entry = new LirBasicBlock("entry");
        // A non-ASCII literal forces the u8"..." + UCN escape path into the generated TU.
        entry.appendInstruction(new LiteralStringInsn("0", "café 中文"));
        entry.appendInstruction(new ReturnInsn("0"));
        describeFunc.addBasicBlock(entry);
        probeClass.addFunction(describeFunc);

        var codegen = new CCodegen();
        codegen.prepare(ctx, new LirModule("tcc_gate_module", List.of(probeClass)));
        var result = builder.buildProject(projectInfo, codegen);
        assertTrue(result.success(), () -> "recording fake must succeed by construction:\n" + result.buildLog());
        assertNotNull(recording.captured);
        return recording.captured;
    }

    private record CompilerInput(List<Path> includeDirs, List<Path> cFiles) {
    }

    /// Captures the compiler inputs and reports success without compiling anything; the gate drives
    /// the real tcc CLI itself so the production input collection is exercised end to end.
    private static final class RecordingCCompiler implements CCompiler {
        private CompilerInput captured;

        @Override
        public CCompileResult compile(Path projectDir, List<Path> includeDirs, List<Path> cFiles,
                                      String outputBaseName, COptimizationLevel optimizationLevel,
                                      TargetPlatform targetPlatform) {
            captured = new CompilerInput(List.copyOf(includeDirs), List.copyOf(cFiles));
            return new CCompileResult(true, "", List.of());
        }
    }
}
