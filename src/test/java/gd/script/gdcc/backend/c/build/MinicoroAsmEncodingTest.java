package gd.script.gdcc.backend.c.build;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// Locks the `_mco_switch` resume-address encoding (minicoro.h x86_64 asm): the `lea` at function
/// entry must compute the address of the instruction immediately following `jmp *(%rsi)` (the
/// `ret`). tcc's inline assembler mis-encodes hard-coded RIP-relative displacements, so the
/// displacement must come from a label reference; this test disassembles the linked artifact of
/// BOTH compilers and asserts the resolved target, so a regression cannot hide behind either
/// toolchain. Each leg is assumption-gated on its own toolchain plus GNU objdump.
class MinicoroAsmEncodingTest {
    private static final Path GDCC_INCLUDE_DIR = Path.of("src/main/c/codegen/include_451/gdcc").toAbsolutePath().normalize();
    private static final String MAIN_C = "int main(void) { return 0; }\n";

    @TempDir
    private static Path sharedDir;

    @Test
    void zigArtifactShouldEncodeResumeAddressViaLabel() throws IOException, InterruptedException {
        // The asserted instruction pattern is the x86_64 SysV asm block; other hosts compile a
        // different arch block (or the Windows blob), which needs its own assertion shape.
        Assumptions.assumeTrue(TargetPlatform.getNativePlatform() == TargetPlatform.LINUX_X86_64,
                "disassembly gate targets the Linux x86_64 SysV context switch");
        var zig = ZigUtil.findZig();
        var objdump = TinyCcCliTestSupport.findObjdump();
        Assumptions.assumeTrue(zig != null && objdump != null,
                "zig and GNU objdump are required for the zig disassembly leg");

        var object = sharedDir.resolve("minicoro_zig.o");
        runChecked(List.of(zig.toString(), "cc", "-std=c23", "-D_DEFAULT_SOURCE", "-fPIC",
                "-c", GDCC_INCLUDE_DIR.resolve("minicoro.c").toString(), "-o", object.toString()));
        var executable = sharedDir.resolve("mco_switch_zig");
        var mainC = sharedDir.resolve("main_zig.c");
        Files.writeString(mainC, MAIN_C, StandardCharsets.UTF_8);
        runChecked(List.of(zig.toString(), "cc", object.toString(), mainC.toString(), "-o", executable.toString()));

        assertResumeAddressEncoding(objdump, executable);
    }

    @Test
    void tccArtifactShouldEncodeResumeAddressViaLabel() throws IOException, InterruptedException {
        Assumptions.assumeTrue(TargetPlatform.getNativePlatform() == TargetPlatform.LINUX_X86_64,
                "disassembly gate targets the Linux x86_64 SysV context switch");
        var tcc = TinyCcCliTestSupport.findTinyCcCli();
        var objdump = TinyCcCliTestSupport.findObjdump();
        Assumptions.assumeTrue(tcc != null && objdump != null,
                "tinycc CLI and GNU objdump are required for the tcc disassembly leg");
        var runtimeRoot = TinyCcCliTestSupport.resolveRuntimeRoot(tcc);

        var object = sharedDir.resolve("minicoro_tcc.o");
        var compile = new ArrayList<String>();
        compile.add(tcc.toString());
        if (runtimeRoot != null) {
            compile.add("-B");
            compile.add(runtimeRoot.toString());
        }
        compile.add("-std=c11");
        compile.add("-c");
        compile.add(GDCC_INCLUDE_DIR.resolve("minicoro.c").toString());
        compile.add("-o");
        compile.add(object.toString());
        runChecked(compile);
        var executable = sharedDir.resolve("mco_switch_tcc");
        var mainC = sharedDir.resolve("main_tcc.c");
        Files.writeString(mainC, MAIN_C, StandardCharsets.UTF_8);
        var link = new ArrayList<String>();
        link.add(tcc.toString());
        if (runtimeRoot != null) {
            link.add("-B");
            link.add(runtimeRoot.toString());
        }
        link.add(object.toString());
        link.add(mainC.toString());
        link.add("-o");
        link.add(executable.toString());
        runChecked(link);

        assertResumeAddressEncoding(objdump, executable);
    }

    // ---------------------------------------------------------------------------

    /// Parses `objdump -d` output: the `_mco_switch` body is recognized without symbol-table help
    /// (tcc strips it) by its unique opening pair `lea disp(%rip),%rax` + `mov %rax,(%rdi)`,
    /// followed within the straight-line body by `jmp *(%rsi)`. GNU objdump annotates RIP-relative
    /// targets as `# <hex-addr>`; that annotation must equal the address of the instruction right
    /// after the jump.
    private static void assertResumeAddressEncoding(Path objdump, Path executable) throws IOException, InterruptedException {
        var process = new ProcessBuilder(objdump.toString(), "-d", executable.toString())
                .redirectErrorStream(true).start();
        var disassembly = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), () -> "objdump failed:\n" + disassembly);

        var instructions = parseInstructions(disassembly);
        for (var i = 0; i + 1 < instructions.size(); i++) {
            var lea = instructions.get(i);
            if (!lea.text().contains("lea") || !lea.text().contains("(%rip),%rax")) {
                continue;
            }
            if (!instructions.get(i + 1).text().matches(".*mov\\s+%rax,\\(%rdi\\).*")) {
                continue;
            }
            var commentStart = lea.raw().indexOf('#');
            assertTrue(commentStart > 0, () -> "lea lacks a resolved-target comment:\n" + lea.raw());
            var targetText = lea.raw().substring(commentStart + 1).trim().split("\\s")[0];
            var leaTarget = Long.parseUnsignedLong(targetText, 16);

            for (var j = i + 1; j < instructions.size(); j++) {
                if (instructions.get(j).text().matches(".*jmp\\s+\\*\\(%rsi\\).*")) {
                    var jmpInsn = instructions.get(j);
                    var resumeInsn = instructions.get(j + 1);
                    assertEquals(leaTarget, resumeInsn.address(),
                            () -> "lea resume target must be the instruction after jmp *(%rsi):\n"
                                    + lea.raw() + "\n" + jmpInsn.raw() + "\n" + resumeInsn.raw());
                    assertTrue(resumeInsn.text().contains("ret"),
                            () -> "resume instruction should be ret, got: " + resumeInsn.raw());
                    return;
                }
            }
            fail("found the _mco_switch lea but no following jmp *(%rsi) in " + executable);
        }
        fail("no _mco_switch opening pair (lea (%rip),%rax / mov %rax,(%rdi)) found in " + executable);
    }

    private static List<Instruction> parseInstructions(String disassembly) {
        var instructions = new ArrayList<Instruction>();
        for (var line : disassembly.split("\n")) {
            var trimmed = line.trim();
            var colon = trimmed.indexOf(':');
            if (colon <= 0 || colon > 16) {
                continue;
            }
            long address;
            try {
                address = Long.parseUnsignedLong(trimmed.substring(0, colon), 16);
            } catch (NumberFormatException e) {
                continue;
            }
            // Instruction text sits after the raw-byte columns; fall back to the whole line when
            // the tab-separated shape is absent (still fine for contains/matches checks).
            var tab = trimmed.indexOf('\t');
            var text = tab < 0 ? trimmed : trimmed.substring(tab + 1);
            var secondTab = text.indexOf('\t');
            if (secondTab >= 0) {
                text = text.substring(secondTab + 1);
            }
            instructions.add(new Instruction(address, text, line));
        }
        return instructions;
    }

    private static void runChecked(List<String> command) throws IOException, InterruptedException {
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), () -> String.join(" ", command) + "\n" + output);
    }

    private record Instruction(long address, String text, String raw) {
    }
}
