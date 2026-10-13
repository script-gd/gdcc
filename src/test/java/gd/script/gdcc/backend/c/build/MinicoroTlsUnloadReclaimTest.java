package gd.script.gdcc.backend.c.build;

import gd.script.gdcc.enums.HardwareArchitecture;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// TLS unload-reclaim gate for the tcc-compiled coroutine runtime (the pthread-key TLS patch in
/// minicoro.h). A real tcc-produced DSO carrying the coroutine TLS machinery is dlopen'd,
/// exercised and dlclose'd 1100 times inside one host process — beyond both glibc's
/// PTHREAD_KEYS_MAX (1024) and Darwin's (512). On the pthread-key path (aarch64) a leaked key
/// aborts the process before the final round, so survival past the ceiling is the leak proof
/// there; on the native-TLS path (x86_64) no pthread keys are allocated, and the rounds instead
/// prove unload correctness and destructor execution. The host additionally asserts per round
/// that its own TLS sentinel is undisturbed and that the image really unloaded (RTLD_NOLOAD);
/// after the loop, the destructor witness log must carry one line per round, proving the image
/// destructors — minicoro's key cleanup among them — executed every time.
///
/// The DSO also reports which TLS storage it was built with, and the test pins that against the
/// platform contract (pthread key on aarch64, native TLS on x86_64), so a selection-chain
/// regression cannot pass silently on either architecture.
///
/// Windows is out of scope (no dlfcn; DLL image lifetimes follow different rules). The gate is
/// assumption-gated on zig (host loader) plus the tinycc CLI (DSO leg).
class MinicoroTlsUnloadReclaimTest {
    // Beyond glibc's 1024 and Darwin's 512 pthread-key ceilings — see the class contract above.
    private static final int ROUNDS = 1100;
    private static final Duration COMPILE_TIMEOUT = Duration.ofSeconds(180);
    private static final Duration RUN_TIMEOUT = Duration.ofSeconds(120);

    @TempDir
    private static Path sharedDir;

    private static Path zig;
    private static Path tcc;

    @BeforeAll
    static void findToolchains() {
        zig = ZigUtil.findZig();
        tcc = TinyCcCliTestSupport.findTinyCcCli();
    }

    @Test
    void tccCoroutineTlsSurvivesRepeatedLoadUnload() throws IOException, InterruptedException {
        var host = TargetPlatform.getNativePlatform();
        Assumptions.assumeTrue(!host.isWindows(), "the dlopen-based unload gate targets POSIX hosts");
        Assumptions.assumeTrue(zig != null, "zig is required to build the host loader");
        Assumptions.assumeTrue(tcc != null, "the tinycc CLI is required to build the DSO leg");

        var dso = compileDso(host);
        var loader = compileLoader(host);
        var unloadLog = sharedDir.resolve("unload-witness.log");

        var run = RuntimeProbeToolchain.runProcess(
                List.of(loader.toString(), dso.toString(), String.valueOf(ROUNDS), unloadLog.toString()),
                RUN_TIMEOUT, null);
        assertEquals(0, run.exitCode(), run::diagnostic);
        assertTrue(run.output().contains("DLOPEN_ROUNDS_PASS " + ROUNDS), run::diagnostic);

        // The DSO must report the TLS storage the platform contract mandates.
        var expectedPath = host.architecture == HardwareArchitecture.AARCH64 ? "pthread" : "native";
        assertTrue(run.output().contains("TLS_PATH " + expectedPath),
                () -> "the DSO must use the " + expectedPath + " TLS path on " + host + ":\n" + run.diagnostic());

        // One witness line per round: the image destructors ran at every dlclose.
        assertTrue(Files.isRegularFile(unloadLog), () -> "the unload witness log must exist:\n" + run.diagnostic());
        var witnessLines = Files.readString(unloadLog, StandardCharsets.UTF_8).lines()
                .filter("D"::equals).count();
        assertEquals(ROUNDS, witnessLines, () -> "every dlclose must run the image destructors:\n" + run.diagnostic());
    }

    /// Builds the coroutine-TLS DSO with the real tcc pipeline (the leg under test).
    private static Path compileDso(TargetPlatform host) throws IOException, InterruptedException {
        var dsoSource = sharedDir.resolve("minicoro_tls_unload_dso.c");
        Files.writeString(dsoSource, CProbeSources.load("minicoro_tls_unload_dso.c"), StandardCharsets.UTF_8);
        var output = sharedDir.resolve(host.isMacOS() ? "libgdcc_tls_unload_probe.dylib" : "libgdcc_tls_unload_probe.so");
        var command = new ArrayList<String>();
        command.add(tcc.toString());
        // null omits -B and keeps the compiler's built-in runtime search paths.
        var runtimeRoot = TinyCcCliTestSupport.resolveRuntimeRoot(tcc);
        if (runtimeRoot != null) {
            command.add("-B");
            command.add(runtimeRoot.toString());
        }
        command.add("-std=c11");
        command.add("-D_DEFAULT_SOURCE");
        command.add("-I" + RuntimeProbeToolchain.GDCC_INCLUDE_DIR);
        command.add("-shared");
        command.add(dsoSource.toString());
        command.add("-lpthread");
        command.add("-o");
        command.add(output.toString());
        var build = RuntimeProbeToolchain.runProcess(command, COMPILE_TIMEOUT, null);
        assertEquals(0, build.exitCode(), build::diagnostic);
        return output;
    }

    /// Builds the dlopen-loop host loader with the zig baseline toolchain.
    private static Path compileLoader(TargetPlatform host) throws IOException, InterruptedException {
        var loaderSource = sharedDir.resolve("minicoro_tls_unload_host.c");
        Files.writeString(loaderSource, CProbeSources.load("minicoro_tls_unload_host.c"), StandardCharsets.UTF_8);
        var output = sharedDir.resolve("tls_unload_host");
        var command = new ArrayList<String>();
        command.add(zig.toString());
        command.add("cc");
        command.add("-std=c23");
        command.add("-D_DEFAULT_SOURCE");
        command.add(loaderSource.toString());
        if (!host.isMacOS()) {
            // glibc < 2.34 keeps dlopen in libdl; a no-op where it was merged into libc.
            command.add("-ldl");
        }
        command.add("-o");
        command.add(output.toString());
        var build = RuntimeProbeToolchain.runProcess(command, COMPILE_TIMEOUT, null);
        assertEquals(0, build.exitCode(), build::diagnostic);
        return output;
    }
}
