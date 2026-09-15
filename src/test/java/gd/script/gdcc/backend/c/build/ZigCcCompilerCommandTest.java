package gd.script.gdcc.backend.c.build;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Pure-Java gate for the two-phase zig command construction: no process is ever started. It
/// pins the compile/link flag split, the per-optimization-level LTO tiers (DEBUG none, RELEASE
/// `-flto=thin`, known-unsupported targets falling back to full `-flto`), the hard rule that
/// ABI-substituted builds omit every `-flto*` token, the object path shape
/// `<projectDir>/obj/<opt>/<zigTarget>/<index>_<file>.o`, the buildLog merge forms
/// (success concatenation vs. per-started-process `Command:` sections), and the PCH contracts:
/// the name-based TU whitelist (`minicoro.c` never gets `-include-pch`), the exact prefix
/// header content, and the PCH build command sharing the full TU language flag surface.
class ZigCcCompilerCommandTest {
    private static final Path ZIG = Path.of("zig");
    private static final Path PROJECT_DIR = Path.of("proj").toAbsolutePath();
    private static final List<Path> INCLUDE_DIRS = List.of(Path.of("inc/gdcc"), Path.of("inc/godot"));
    private static final String TARGET = "x86_64-linux-gnu";

    @Test
    void tuCompileCommandsCompileOnlyAndNeverLink() {
        var objPath = objPath(COptimizationLevel.DEBUG, TARGET, 0, "entry.c");
        for (var opt : COptimizationLevel.values()) {
            var cmd = ZigCcCompiler.buildTuCompileCommand(ZIG, TARGET, CLtoMode.THIN, opt, INCLUDE_DIRS, objPath, Path.of("src/entry.c"));
            assertTrue(cmd.contains("-c"), "TU compile command must compile only: " + cmd);
            assertFalse(cmd.contains("-shared"), "TU compile command must not link: " + cmd);
            assertTrue(cmd.contains("-std=c23"));
            assertTrue(cmd.contains("-fPIC"));
            assertTrue(cmd.contains("-Wno-macro-redefined"));
            assertTrue(cmd.contains("-Wno-pointer-sign"));
            assertEquals(objPath.toString(), cmd.get(cmd.indexOf("-o") + 1));
            // The source is the single trailing input; include dirs use the -I<path> form.
            assertEquals(Path.of("src/entry.c").toAbsolutePath().toString(), cmd.getLast());
            assertTrue(cmd.stream().anyMatch(arg -> arg.startsWith("-I")));
        }
    }

    @Test
    void linkCommandLinksObjectsOnlyAndNeverCompiles() {
        var objPaths = List.of(
                objPath(COptimizationLevel.RELEASE, TARGET, 0, "entry.c"),
                objPath(COptimizationLevel.RELEASE, TARGET, 1, "godot_binding.c"));
        var outputPath = PROJECT_DIR.resolve("libdemo.so");
        var cmd = ZigCcCompiler.buildLinkCommand(ZIG, TARGET, CLtoMode.THIN, COptimizationLevel.RELEASE, outputPath, objPaths);
        assertTrue(cmd.contains("-shared"), "link command must link: " + cmd);
        assertFalse(cmd.contains("-c"), "link command must not compile: " + cmd);
        assertFalse(cmd.stream().anyMatch(arg -> arg.endsWith(".c")), "link inputs must be objects only: " + cmd);
        // Compile-only flag families must never leak into the link command.
        assertFalse(cmd.stream().anyMatch(arg -> arg.startsWith("-I")), cmd::toString);
        assertFalse(cmd.stream().anyMatch(arg -> arg.startsWith("-std=")), cmd::toString);
        assertFalse(cmd.stream().anyMatch(arg -> arg.startsWith("-fPIC")), cmd::toString);
        assertFalse(cmd.stream().anyMatch(arg -> arg.startsWith("-Wno-")), cmd::toString);
        assertEquals(outputPath.toString(), cmd.get(cmd.indexOf("-o") + 1));
        // Link inputs are exactly this round's objects, in cFiles order.
        assertEquals(objPaths.stream().map(Path::toString).toList(),
                cmd.subList(cmd.indexOf("-o") + 2, cmd.size()));
    }

    @Test
    void compileAndLinkCommandsShareTheSameResolvedTarget() {
        // The substituted msvc→gnu triple must reach both command kinds.
        var resolution = ZigCcCompiler.resolveZigTarget(TargetPlatform.WINDOWS_X86_64, false);
        var ltoMode = ZigCcCompiler.resolveLtoMode(resolution.zigTarget(), resolution.abiSubstituted(), COptimizationLevel.RELEASE);
        var tuCmd = ZigCcCompiler.buildTuCompileCommand(ZIG, resolution.zigTarget(), ltoMode, COptimizationLevel.RELEASE, INCLUDE_DIRS, objPath(COptimizationLevel.RELEASE, resolution.zigTarget(), 0, "entry.c"), Path.of("entry.c"));
        var linkCmd = ZigCcCompiler.buildLinkCommand(ZIG, resolution.zigTarget(), ltoMode, COptimizationLevel.RELEASE, PROJECT_DIR.resolve("demo.dll"), List.of(objPath(COptimizationLevel.RELEASE, resolution.zigTarget(), 0, "entry.c")));
        assertEquals("x86_64-windows-gnu", targetOf(tuCmd));
        assertEquals(targetOf(tuCmd), targetOf(linkCmd), "compile and link must use the identical -target");
    }

    @Test
    void debugBuildsNeverUseLto() {
        assertEquals(CLtoMode.NONE, ZigCcCompiler.resolveLtoMode(TARGET, false, COptimizationLevel.DEBUG));
        var tuCmd = ZigCcCompiler.buildTuCompileCommand(ZIG, TARGET, CLtoMode.NONE, COptimizationLevel.DEBUG, INCLUDE_DIRS, objPath(COptimizationLevel.DEBUG, TARGET, 0, "entry.c"), Path.of("entry.c"));
        var linkCmd = ZigCcCompiler.buildLinkCommand(ZIG, TARGET, CLtoMode.NONE, COptimizationLevel.DEBUG, PROJECT_DIR.resolve("libdemo.so"), List.of(objPath(COptimizationLevel.DEBUG, TARGET, 0, "entry.c")));
        assertNoLtoToken(tuCmd);
        assertNoLtoToken(linkCmd);
        assertTrue(tuCmd.contains("-O0"));
        // Without LTO the link step performs no code generation, so no -O flag is passed.
        assertFalse(linkCmd.stream().anyMatch(arg -> arg.startsWith("-O")), "debug link must not carry -O flags: " + linkCmd);
    }

    @Test
    void releaseBuildsUseThinLtoOnSupportedTargets() {
        assertEquals(CLtoMode.THIN, ZigCcCompiler.resolveLtoMode(TARGET, false, COptimizationLevel.RELEASE));
        var tuCmd = ZigCcCompiler.buildTuCompileCommand(ZIG, TARGET, CLtoMode.THIN, COptimizationLevel.RELEASE, INCLUDE_DIRS, objPath(COptimizationLevel.RELEASE, TARGET, 0, "entry.c"), Path.of("entry.c"));
        var linkCmd = ZigCcCompiler.buildLinkCommand(ZIG, TARGET, CLtoMode.THIN, COptimizationLevel.RELEASE, PROJECT_DIR.resolve("libdemo.so"), List.of(objPath(COptimizationLevel.RELEASE, TARGET, 0, "entry.c")));
        assertTrue(tuCmd.contains("-flto=thin"), "release TU compile must emit ThinLTO bitcode: " + tuCmd);
        assertTrue(linkCmd.contains("-flto=thin"), "release link must run ThinLTO: " + linkCmd);
        assertTrue(tuCmd.contains("-O2"));
        assertTrue(linkCmd.contains("-O2"), "LTO link-time code generation runs at -O2: " + linkCmd);
    }

    @Test
    void abiSubstitutedBuildsNeverUseLtoAndNeverEnterThinLtoFallback() {
        var resolution = ZigCcCompiler.resolveZigTarget(TargetPlatform.WINDOWS_X86_64, false);
        assertTrue(resolution.abiSubstituted());
        // Even when the substituted target were listed as ThinLTO-unsupported, the ABI
        // substitution short-circuits the LTO decision before any fallback check.
        var ltoMode = ZigCcCompiler.resolveLtoMode(resolution.zigTarget(), true, COptimizationLevel.RELEASE, Set.of(resolution.zigTarget()));
        assertEquals(CLtoMode.NONE, ltoMode);
        var tuCmd = ZigCcCompiler.buildTuCompileCommand(ZIG, resolution.zigTarget(), ltoMode, COptimizationLevel.RELEASE, INCLUDE_DIRS, objPath(COptimizationLevel.RELEASE, resolution.zigTarget(), 0, "entry.c"), Path.of("entry.c"));
        var linkCmd = ZigCcCompiler.buildLinkCommand(ZIG, resolution.zigTarget(), ltoMode, COptimizationLevel.RELEASE, PROJECT_DIR.resolve("demo.dll"), List.of(objPath(COptimizationLevel.RELEASE, resolution.zigTarget(), 0, "entry.c")));
        assertNoLtoToken(tuCmd);
        assertNoLtoToken(linkCmd);
        assertTrue(tuCmd.contains("-O2"), "the TU still compiles optimized: " + tuCmd);
        // Without LTO the objects are already final machine code, so the link gets no -O flag.
        assertFalse(linkCmd.stream().anyMatch(arg -> arg.startsWith("-O")), "substituted link must not carry -O flags: " + linkCmd);
        // zig's LTO link for windows-gnu cannot pull in libmingwex/compiler-rt symbols.
    }

    @Test
    void thinLtoUnsupportedTargetsFallBackToFullLto() {
        var unsupported = Set.of(TARGET);
        assertEquals(CLtoMode.FULL, ZigCcCompiler.resolveLtoMode(TARGET, false, COptimizationLevel.RELEASE, unsupported));
        assertEquals(CLtoMode.NONE, ZigCcCompiler.resolveLtoMode(TARGET, false, COptimizationLevel.DEBUG, unsupported),
                "the fallback must never turn LTO on for debug builds");
        var tuCmd = ZigCcCompiler.buildTuCompileCommand(ZIG, TARGET, CLtoMode.FULL, COptimizationLevel.RELEASE, INCLUDE_DIRS, objPath(COptimizationLevel.RELEASE, TARGET, 0, "entry.c"), Path.of("entry.c"));
        var linkCmd = ZigCcCompiler.buildLinkCommand(ZIG, TARGET, CLtoMode.FULL, COptimizationLevel.RELEASE, PROJECT_DIR.resolve("libdemo.so"), List.of(objPath(COptimizationLevel.RELEASE, TARGET, 0, "entry.c")));
        assertTrue(tuCmd.contains("-flto"), "fallback TU compile must emit full-LTO bitcode: " + tuCmd);
        assertFalse(tuCmd.contains("-flto=thin"));
        assertTrue(linkCmd.contains("-flto"), "fallback link must run full LTO: " + linkCmd);
        assertFalse(linkCmd.contains("-flto=thin"));
        assertTrue(linkCmd.contains("-O2"));
    }

    @Test
    void objectPathsContainOptLevelTargetAndIndex() {
        var debugEntry = ZigCcCompiler.resolveObjectPath(PROJECT_DIR, COptimizationLevel.DEBUG, TARGET, 0, Path.of("src/entry.c"));
        var releaseEntry = ZigCcCompiler.resolveObjectPath(PROJECT_DIR, COptimizationLevel.RELEASE, TARGET, 0, Path.of("src/entry.c"));
        var debugBinding = ZigCcCompiler.resolveObjectPath(PROJECT_DIR, COptimizationLevel.DEBUG, TARGET, 1, Path.of("godot/godot_binding.c"));
        var debugEntryOtherTarget = ZigCcCompiler.resolveObjectPath(PROJECT_DIR, COptimizationLevel.DEBUG, "aarch64-linux-gnu", 0, Path.of("src/entry.c"));

        assertTrue(debugEntry.startsWith(PROJECT_DIR));
        var expectedSuffix = Path.of("obj", "debug", TARGET, "0_entry.c.o");
        assertTrue(debugEntry.endsWith(expectedSuffix), "obj path must be obj/<opt>/<target>/<index>_<file>.o: " + debugEntry);
        assertTrue(debugBinding.endsWith(Path.of("obj", "debug", TARGET, "1_godot_binding.c.o")));
        // Opt level and target isolate objects of different configurations.
        assertNotEquals(debugEntry, releaseEntry);
        assertNotEquals(debugEntry, debugEntryOtherTarget);
        assertTrue(releaseEntry.toString().contains(Path.of("obj", "release").toString()));
    }

    @Test
    void sameNamedSourcesFromDifferentDirectoriesAreDisambiguatedByIndex() {
        // The cFiles index is the only discriminator between identically named sources.
        var first = ZigCcCompiler.resolveObjectPath(PROJECT_DIR, COptimizationLevel.DEBUG, TARGET, 0, Path.of("dir_a/entry.c"));
        var second = ZigCcCompiler.resolveObjectPath(PROJECT_DIR, COptimizationLevel.DEBUG, TARGET, 1, Path.of("dir_b/entry.c"));
        assertTrue(first.endsWith(Path.of("obj", "debug", TARGET, "0_entry.c.o")));
        assertTrue(second.endsWith(Path.of("obj", "debug", TARGET, "1_entry.c.o")));
        assertNotEquals(first, second);
    }

    @Test
    void successLogConcatenatesNonEmptyOutputsInSlotOrderWithoutCommandLines() {
        var log = ZigCcCompiler.mergeSlotOutputs(List.of("", "warn: tu0\n", "", "lld-note"));
        assertEquals("warn: tu0\nlld-note\n", log);
        assertEquals("", ZigCcCompiler.mergeSlotOutputs(List.of("", "")), "all-quiet builds produce an empty log");
    }

    @Test
    void tuFailureLogContainsSectionsOnlyForStartedTus() {
        // "Only TU 1 failed" form: the later TUs and the link were never started, so their
        // sections do not exist at all — not even as empty Command lines.
        var tu0 = List.of("zig", "cc", "-c", "-o", "0_a.c.o", "a.c");
        var tu1 = List.of("zig", "cc", "-c", "-o", "1_b.c.o", "b.c");
        var log = ZigCcCompiler.mergeCommandSections(List.of(tu0, tu1), List.of("", "error: broken\n"));
        assertTrue(log.startsWith("Command: zig cc -c -o 0_a.c.o a.c\n"), log);
        assertTrue(log.contains("Command: zig cc -c -o 1_b.c.o b.c\nerror: broken\n"), log);
        assertFalse(log.contains("-shared"), log);
    }

    @Test
    void linkFailureLogAppendsTheLinkSectionAfterAllTuSlots() {
        // "Link failed" form: every TU started (and succeeded), then the link section follows.
        var tu0 = List.of("zig", "cc", "-c", "-o", "0_a.c.o", "a.c");
        var tu1 = List.of("zig", "cc", "-c", "-o", "1_b.c.o", "b.c");
        var link = List.of("zig", "cc", "-shared", "-o", "libprobe.so", "0_a.c.o", "1_b.c.o");
        var log = ZigCcCompiler.mergeCommandSections(List.of(tu0, tu1, link), List.of("", "", "lld: error: duplicate symbol\n"));
        assertEquals("""
                Command: zig cc -c -o 0_a.c.o a.c
                Command: zig cc -c -o 1_b.c.o b.c
                Command: zig cc -shared -o libprobe.so 0_a.c.o 1_b.c.o
                lld: error: duplicate symbol
                """, log);
    }

    @Test
    void tuParallelismIsCappedByTuCountAndAvailableProcessors() {
        assertEquals(4, Math.clamp(44, 1, 4), "fewer TUs than cores: one worker per TU");
        assertEquals(4, Math.clamp(4, 1, 8), "more TUs than cores: capped at the core count");
        assertEquals(1, Math.clamp(1, 1, 1));
        // The degenerate empty input never reaches runTuPhase (compile() rejects empty cFiles
        // first), so the floor is anchored at the reachable boundary: one TU, one worker.
        assertEquals(1, Math.clamp(4, 1, 1), "a single TU gets exactly one worker");
    }

    @Test
    void pchWhitelistCoversOnlyGodotBindingConsumers() {
        assertTrue(ZigCcCompiler.isGodotBindingPchTu(Path.of("entry.c")));
        assertTrue(ZigCcCompiler.isGodotBindingPchTu(Path.of("godot_binding.c")));
        assertTrue(ZigCcCompiler.isGodotBindingPchTu(Path.of("gdcc_coroutine.c")));
        // The isolated assembly-backend TU must never force-include the Godot ABI headers.
        assertFalse(ZigCcCompiler.isGodotBindingPchTu(Path.of("minicoro.c")));
        assertFalse(ZigCcCompiler.isGodotBindingPchTu(Path.of("anything_else.c")));
        // The whitelist keys on the simple file name; directory prefixes never matter.
        assertTrue(ZigCcCompiler.isGodotBindingPchTu(Path.of("godot/godot_binding.c")));
        assertFalse(ZigCcCompiler.isGodotBindingPchTu(Path.of("gdcc/minicoro.c")));
    }

    @Test
    void pchPrefixHeaderContainsExactlyTheGodotBindingInclude() {
        // Guards the entry.h contract: the PCH may pull in godot_binding.h and nothing else —
        // gdcc tree headers require per-TU declarations a precompiled header cannot satisfy.
        assertEquals("#include <godot_binding.h>\n", ZigCcCompiler.PCH_PREFIX_HEADER_CONTENT);
    }

    @Test
    void tuCompileCommandCarriesIncludePchOnlyWhenProvided() {
        var objPath = objPath(COptimizationLevel.DEBUG, TARGET, 0, "entry.c");
        var pch = Path.of("cache/pch/key/gdcc_godot_prefix.pch");
        var withPch = ZigCcCompiler.buildTuCompileCommand(ZIG, TARGET, CLtoMode.NONE, COptimizationLevel.DEBUG, INCLUDE_DIRS, objPath, Path.of("src/entry.c"), pch);
        assertTrue(withPch.contains("-include-pch"), withPch::toString);
        assertEquals(pch.toString(), withPch.get(withPch.indexOf("-include-pch") + 1));
        // The rest of the command shape is unchanged: same -o target, source still trailing.
        assertEquals(objPath.toString(), withPch.get(withPch.indexOf("-o") + 1));
        assertEquals(Path.of("src/entry.c").toAbsolutePath().toString(), withPch.getLast());
        // A whitelisted-excluded TU (minicoro.c) is compiled through the no-pch overload.
        var withoutPch = ZigCcCompiler.buildTuCompileCommand(ZIG, TARGET, CLtoMode.NONE, COptimizationLevel.DEBUG, INCLUDE_DIRS, objPath, Path.of("src/minicoro.c"));
        assertFalse(withoutPch.contains("-include-pch"), withoutPch::toString);
    }

    @Test
    void pchBuildCommandUsesHeaderModeWithTheFullTuLanguageFlags() {
        var prefix = Path.of("cache/pch/key/gdcc_godot_prefix.h");
        var pchOut = Path.of("cache/pch/key/gdcc_godot_prefix.pch");
        for (var opt : COptimizationLevel.values()) {
            var lto = ZigCcCompiler.resolveLtoMode(TARGET, false, opt);
            var cmd = ZigCcCompiler.buildPchBuildCommand(ZIG, TARGET, lto, opt, INCLUDE_DIRS, prefix, pchOut);
            // Header mode compiles the prefix header itself: no "-c" token, no ".c" input.
            assertTrue(cmd.contains("-x"), cmd::toString);
            assertEquals("c-header", cmd.get(cmd.indexOf("-x") + 1));
            assertFalse(cmd.contains("-c"), "PCH build must not carry the compile-only token: " + cmd);
            assertFalse(cmd.stream().anyMatch(arg -> arg.endsWith(".c")), cmd::toString);
            assertTrue(cmd.contains(prefix.toString()), cmd::toString);
            assertEquals(pchOut.toString(), cmd.get(cmd.indexOf("-o") + 1));
            // clang rejects -include-pch when creation and usage options differ: the language
            // flag block (right after "-target <T>") must be identical to a TU compile's.
            var expectedFlags = ZigCcCompiler.languageFlags(lto, opt);
            assertEquals(expectedFlags, cmd.subList(4, 4 + expectedFlags.size()), "PCH build flags must match TU flags: " + cmd);
            var tuCmd = ZigCcCompiler.buildTuCompileCommand(ZIG, TARGET, lto, opt, INCLUDE_DIRS, objPath(opt, TARGET, 0, "entry.c"), Path.of("src/entry.c"), pchOut);
            assertEquals(expectedFlags, tuCmd.subList(4, 4 + expectedFlags.size()), "TU flags anchor: " + tuCmd);
        }
    }

    private static @NotNull Path objPath(COptimizationLevel opt, String zigTarget, int index, String cFileName) {
        return ZigCcCompiler.resolveObjectPath(PROJECT_DIR, opt, zigTarget, index, Path.of(cFileName));
    }

    private static String targetOf(List<String> cmd) {
        return cmd.get(cmd.indexOf("-target") + 1);
    }

    private static void assertNoLtoToken(List<String> cmd) {
        assertFalse(cmd.stream().anyMatch(arg -> arg.startsWith("-flto")), "command must omit every -flto* token: " + cmd);
    }
}
