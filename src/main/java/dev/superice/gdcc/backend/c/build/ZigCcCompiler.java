package dev.superice.gdcc.backend.c.build;

import dev.superice.gdcc.util.ProcessUtil;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class ZigCcCompiler implements CCompiler {
    private static final String PROJECT_CACHE_DIR_NAME = "compiler-cache";
    private static final String SHARED_CACHE_DIR_NAME = "shared-compiler-cache";
    private static final Duration OUTPUT_READER_JOIN_TIMEOUT = Duration.ofSeconds(1);

    @Override
    public CBuildResult compile(@NotNull Path projectDir, @NotNull List<Path> includeDirs, @NotNull List<Path> cFiles, @NotNull String outputBaseName, @NotNull COptimizationLevel optimizationLevel, @NotNull TargetPlatform targetPlatform) {
        var zig = ZigUtil.findZig();
        if (zig == null) {
            return new CBuildResult(false, "Zig executable not found on PATH or known locations", List.of());
        }

        if (cFiles.isEmpty()) {
            return new CBuildResult(false, "No C files to compile", List.of());
        }

        // Build command: zig cc -shared -I<includeDir> -o <output> <cFiles...>
        var outName = targetPlatform.sharedLibraryFileName(outputBaseName);

        var outputPath = projectDir.resolve(outName).toAbsolutePath();
        var cachePath = resolveCompilerCacheRoot(projectDir);

        var cmd = new ArrayList<String>();
        cmd.add(zig.toString());
        cmd.add("cc");
        cmd.add("-target");
        cmd.add(targetPlatform.zigTarget);
        cmd.add("-std=c23");
        cmd.add("-shared");
        cmd.add("-flto");
        cmd.add("-Wno-macro-redefined");
        cmd.add("-Wno-pointer-sign");

        // optimization mapping
        switch (optimizationLevel) {
            case DEBUG -> cmd.add("-O0");
            case RELEASE -> cmd.add("-O2");
        }

        // primary include dir (use -I<path> form)
        for (var inc : includeDirs) {
            cmd.add("-I" + inc.toAbsolutePath());
        }

        cmd.add("-o");
        cmd.add(outputPath.toString());
        for (var f : cFiles) cmd.add(f.toAbsolutePath().toString());

        try {
            var pb = new ProcessBuilder(cmd);
            pb.directory(projectDir.toFile());
            pb.redirectErrorStream(true);
            pb.environment().put("ZIG_CACHE_DIR", cachePath.resolve("local").toString());
            pb.environment().put("ZIG_GLOBAL_CACHE_DIR", cachePath.resolve("global").toString());
            var p = pb.start();
            // Drain Zig output on a companion virtual thread so a verbose compiler cannot fill the
            // process pipe and block shutdown. The compile thread stays interruptible; cancellation
            // destroys the Zig process through ProcessUtil and interrupts the output reader.
            var outputBytes = new ByteArrayOutputStream();
            var outputFailure = new AtomicReference<IOException>();
            var outputReader = Thread.ofVirtual()
                    .name("gdcc-zig-output")
                    .start(() -> {
                        try (var input = p.getInputStream()) {
                            input.transferTo(outputBytes);
                        } catch (IOException exception) {
                            outputFailure.set(exception);
                        }
                    });
            var interrupted = false;
            int exit;
            try {
                exit = ProcessUtil.waitForInterruptibly(p, outputReader);
            } catch (InterruptedException exception) {
                interrupted = true;
                throw exception;
            } finally {
                if (interrupted) {
                    outputReader.interrupt();
                    ProcessUtil.joinThreadAfterInterrupt(outputReader, OUTPUT_READER_JOIN_TIMEOUT);
                } else {
                    outputReader.join();
                }
            }
            var readFailure = outputFailure.get();
            if (readFailure != null) {
                throw readFailure;
            }
            var out = outputBytes.toString(StandardCharsets.UTF_8);
            boolean success = exit == 0 && Files.exists(outputPath);
            var artifacts = new ArrayList<Path>(3);
            if (success) {
                artifacts.add(outputPath);
                if (targetPlatform.isWindows()) {
                    var pdbPath = projectDir.resolve(outputBaseName + ".pdb").toAbsolutePath();
                    if (Files.exists(pdbPath)) {
                        artifacts.add(pdbPath);
                    }
                }
            }
            if (!success) {
                var cmdStr = String.join(" ", cmd);
                out = "Command: " + cmdStr + "\n" + out;
            }
            return new CBuildResult(success, out, artifacts);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CBuildResult(false, "Failed to run zig: interrupted", List.of());
        } catch (IOException e) {
            return new CBuildResult(false, "Failed to run zig: " + e.getMessage(), List.of());
        }
    }

    static @NotNull Path resolveCompilerCacheRoot(@NotNull Path projectDir) {
        var normalizedProjectDir = projectDir.toAbsolutePath().normalize();
        var projectParent = normalizedProjectDir.getParent();
        if (projectParent == null) {
            return normalizedProjectDir.resolve(PROJECT_CACHE_DIR_NAME);
        }

        var sharedCacheDir = projectParent.resolve(SHARED_CACHE_DIR_NAME);
        if (Files.exists(sharedCacheDir) && !Files.isDirectory(sharedCacheDir)) {
            return normalizedProjectDir.resolve(PROJECT_CACHE_DIR_NAME);
        }
        if (Files.isDirectory(sharedCacheDir)) {
            return sharedCacheDir.toAbsolutePath().normalize();
        }

        return normalizedProjectDir.resolve(PROJECT_CACHE_DIR_NAME);
    }
}
