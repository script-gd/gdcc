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
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Portability gate: the exact compiler input set that `CProjectBuilder` assembles (one
/// generated entry TU from a real codegen pass plus the four extracted runtime TUs) must link into
/// a shared library under the mob tinycc CLI with `-std=c11` and zero diagnostics — the same TU
/// set `TinyCcCompiler` compiles through FFM (the FFM round uses per-TU `TCC_OUTPUT_OBJ` objects
/// and its own flag set, not these CLI flags). A recording fake `CCompiler` captures the
/// production input list without invoking zig, so this test exercises the real include extraction
/// and generation path. Real linking (never `-c`) keeps the crt/libc resolution chain covered.
/// Assumption-gated on the tinycc CLI and `nm` (see TinyCcCliTestSupport).
class TinyCcCliGateTest {
    private static final int PT_GNU_STACK = 0x6474e551;
    private static final int PF_R = 4;
    private static final int PF_W = 2;
    private static final String NO_EXEC_STACK = "-Wl,-z,noexecstack";
    private static final Duration STACK_PROBE_TIMEOUT = Duration.ofSeconds(30);

    @TempDir
    private Path projectDir;

    @Test
    void sharedLibraryOfFullTuSetLinksCleanlyUnderTinyCc() throws IOException, InterruptedException {
        var tcc = TinyCcCliTestSupport.findTinyCcCli();
        Assumptions.assumeTrue(tcc != null, "tinycc CLI is required for the portability link gate");
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

        NativeArtifactInspector.assertExportsSymbol(output, "gdextension_entry");
    }

    @Test
    void linuxX64SharedLinkAddsReadWriteGnuStackOnlyWhenRequested() throws IOException, InterruptedException {
        var tcc = requireLinuxX64TinyCc();
        var source = Files.writeString(projectDir.resolve("stack_probe.c"), "int gdcc_stack_probe;\n");
        var plainLibrary = projectDir.resolve("libstack_plain.so");
        var optedInLibrary = projectDir.resolve("libstack_noexec.so");

        var plain = runStackProbe(tcc, plainLibrary, List.of("-shared", source.toString()));
        assertEquals(0, plain.exitCode(), plain::diagnostic);
        assertEquals(List.of(), readGnuStackFlags(plainLibrary), "unflagged TinyCC ELF output must not gain a GNU_STACK header");

        var optedIn = runStackProbe(tcc, optedInLibrary, List.of(NO_EXEC_STACK, "-shared", source.toString()));
        assertEquals(0, optedIn.exitCode(), optedIn::diagnostic);
        assertEquals(List.of(PF_R | PF_W), readGnuStackFlags(optedInLibrary),
                () -> "expected one GNU_STACK header with exactly RW flags:\n" + optedIn.diagnostic());
    }

    @Test
    void linuxX64NoExecStackRejectsExecutableMarkerFromAssemblyAndObject() throws IOException, InterruptedException {
        var tcc = requireLinuxX64TinyCc();
        var source = Files.writeString(projectDir.resolve("stack_probe.c"), "int gdcc_stack_probe;\n");
        // The section flag alone declares the requirement; the section contains no instructions.
        var marker = Files.writeString(projectDir.resolve("stack_marker.s"), ".section .note.GNU-stack,\"x\",@progbits\n");
        var object = projectDir.resolve("stack_marker.o");
        var assembled = RuntimeProbeToolchain.runProcess(
                List.of(tcc.toString(), "-c", marker.toString(), "-o", object.toString()),
                STACK_PROBE_TIMEOUT, null);
        assertEquals(0, assembled.exitCode(), assembled::diagnostic);

        var sentinel = "existing output must stay untouched\n".getBytes(StandardCharsets.UTF_8);
        for (var input : List.of(marker, object)) {
            var plainLibrary = projectDir.resolve("libstack_plain_" + input.getFileName() + ".so");
            var plain = runStackProbe(tcc, plainLibrary, List.of("-shared", source.toString(), input.toString()));
            assertEquals(0, plain.exitCode(), plain::diagnostic);
            assertEquals(List.of(), readGnuStackFlags(plainLibrary), "the unflagged link retains its historical behavior");

            var optedInLibrary = projectDir.resolve("libstack_noexec_" + input.getFileName() + ".so");
            Files.write(optedInLibrary, sentinel);
            var rejected = runStackProbe(tcc, optedInLibrary,
                    List.of("-shared", NO_EXEC_STACK, source.toString(), input.toString()));
            assertNotEquals(0, rejected.exitCode(), rejected::diagnostic);
            assertTrue(rejected.output().contains("-z noexecstack conflicts with an executable .note.GNU-stack section"),
                    rejected::diagnostic);
            assertArrayEquals(sentinel, Files.readAllBytes(optedInLibrary), rejected::diagnostic);
        }
    }

    @Test
    void linuxX64ObjectCompileRejectsNoExecStackInStrictMode() throws IOException, InterruptedException {
        var tcc = requireLinuxX64TinyCc();
        var source = Files.writeString(projectDir.resolve("stack_probe.c"), "int gdcc_stack_probe;\n");
        var object = projectDir.resolve("stack_probe.o");
        var rejected = runStackProbe(tcc, object,
                List.of("-Wunsupported", "-Werror", NO_EXEC_STACK, "-c", source.toString()));
        assertNotEquals(0, rejected.exitCode(), rejected::diagnostic);
        assertTrue(rejected.output().contains("unsupported linker option '-z'"), rejected::diagnostic);
        assertFalse(Files.exists(object), rejected::diagnostic);
    }

    private static Path requireLinuxX64TinyCc() {
        Assumptions.assumeTrue(TargetPlatform.getNativePlatform() == TargetPlatform.LINUX_X86_64,
                "the GNU_STACK CLI gate targets Linux x64");
        var tcc = TinyCcCliTestSupport.findTinyCcCli();
        Assumptions.assumeTrue(tcc != null, "tinycc CLI is required for the GNU_STACK gate");
        return tcc;
    }

    private static RuntimeProbeToolchain.ProbeRun runStackProbe(Path tcc, Path output, List<String> arguments)
            throws IOException, InterruptedException {
        var command = new ArrayList<String>();
        command.add(tcc.toString());
        var runtimeRoot = TinyCcCliTestSupport.resolveRuntimeRoot(tcc);
        if (runtimeRoot != null) {
            command.add("-B");
            command.add(runtimeRoot.toString());
        }
        command.addAll(arguments);
        command.add("-o");
        command.add(output.toString());
        return RuntimeProbeToolchain.runProcess(command, STACK_PROBE_TIMEOUT, null);
    }

    /// Reads `p_flags` from every GNU_STACK program header in a Linux x64 shared object.
    private static List<Integer> readGnuStackFlags(Path output) throws IOException {
        var elf = ByteBuffer.wrap(Files.readAllBytes(output)).order(ByteOrder.LITTLE_ENDIAN);
        assertTrue(elf.limit() >= 64, "truncated ELF64 header: " + output);
        assertEquals(0x464c457f, elf.getInt(0), "expected ELF magic");
        assertEquals(2, Byte.toUnsignedInt(elf.get(4)), "expected ELFCLASS64");
        assertEquals(1, Byte.toUnsignedInt(elf.get(5)), "expected ELFDATA2LSB");
        assertEquals(3, Short.toUnsignedInt(elf.getShort(16)), "expected ET_DYN");
        assertEquals(62, Short.toUnsignedInt(elf.getShort(18)), "expected EM_X86_64");
        var tableOffset = elf.getLong(32);
        var entrySize = Short.toUnsignedInt(elf.getShort(54));
        var entryCount = Short.toUnsignedInt(elf.getShort(56));
        assertEquals(56, entrySize, "expected Elf64_Phdr entries");
        assertTrue(entryCount > 0 && tableOffset >= 64 && tableOffset <= elf.limit() - (long) entryCount * entrySize,
                "invalid program header table: " + output);
        var flags = new ArrayList<Integer>();
        for (var i = 0; i < entryCount; i++) {
            var offset = (int) tableOffset + i * entrySize;
            if (elf.getInt(offset) == PT_GNU_STACK) {
                flags.add(elf.getInt(offset + Integer.BYTES));
            }
        }
        return flags;
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
        // A non-ASCII literal forces the u8"..." + byte-level octal escape path into the TU.
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
        public CCompileResult compile(@NotNull Path projectDir, @NotNull List<Path> includeDirs, @NotNull List<Path> cFiles,
                                      @NotNull String outputBaseName, @NotNull COptimizationLevel optimizationLevel,
                                      @NotNull TargetPlatform targetPlatform) {
            captured = new CompilerInput(List.copyOf(includeDirs), List.copyOf(cFiles));
            return new CCompileResult(true, "", List.of());
        }
    }
}
