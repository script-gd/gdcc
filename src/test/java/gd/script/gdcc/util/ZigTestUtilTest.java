package gd.script.gdcc.util;

import gd.script.gdcc.backend.c.build.TargetPlatform;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ZigTestUtilTest {
    @Test
    void compileAndLinkUseTheSameExplicitMacosTarget() {
        for (var platform : List.of(TargetPlatform.MACOS_X86_64, TargetPlatform.MACOS_AARCH64)) {
            var compileCommand = new ArrayList<>(List.of("zig", "cc", "-c", "probe.c", "-o", "probe.o"));
            var linkCommand = new ArrayList<>(List.of("zig", "cc", "probe.o", "-o", "probe"));

            ZigTestUtil.addMacosNativeTarget(compileCommand, platform);
            ZigTestUtil.addMacosNativeTarget(linkCommand, platform);

            var expectedTarget = platform == TargetPlatform.MACOS_AARCH64
                    ? "aarch64-macos-none" : "x86_64-macos-none";
            assertEquals(List.of("zig", "cc", "-c", "probe.c", "-o", "probe.o", "-target", expectedTarget), compileCommand);
            assertEquals(List.of("zig", "cc", "probe.o", "-o", "probe", "-target", expectedTarget), linkCommand);
        }
    }

    @Test
    void nonMacosCommandsRemainUnchanged() {
        var original = List.of("zig", "cc", "probe.o", "-o", "probe");
        for (var platform : List.of(
                TargetPlatform.WINDOWS_X86_64,
                TargetPlatform.WINDOWS_AARCH64,
                TargetPlatform.LINUX_X86_64,
                TargetPlatform.LINUX_AARCH64,
                TargetPlatform.LINUX_RISCV64,
                TargetPlatform.ANDROID_X86_64,
                TargetPlatform.ANDROID_AARCH64,
                TargetPlatform.WEB_WASM32
        )) {
            var command = new ArrayList<>(original);

            ZigTestUtil.addMacosNativeTarget(command, platform);

            assertEquals(original, command, platform.name());
        }
    }

    @Test
    void explicitCrossTargetIsPreservedOnMacos() {
        var original = List.of("zig", "cc", "-target", "x86-linux-gnu", "-c", "probe.c", "-o", "probe.o");
        for (var platform : List.of(TargetPlatform.MACOS_X86_64, TargetPlatform.MACOS_AARCH64)) {
            var command = new ArrayList<>(original);

            ZigTestUtil.addMacosNativeTarget(command, platform);

            assertEquals(original, command);
        }
    }

    @Test
    void addingMacosTargetIsIdempotent() {
        var command = new ArrayList<>(List.of("zig", "cc", "probe.o", "-o", "probe"));

        ZigTestUtil.addMacosNativeTarget(command, TargetPlatform.MACOS_AARCH64);
        ZigTestUtil.addMacosNativeTarget(command, TargetPlatform.MACOS_AARCH64);

        assertEquals(List.of("zig", "cc", "probe.o", "-o", "probe", "-target", "aarch64-macos-none"), command);
    }
}
