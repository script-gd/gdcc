package gd.script.gdcc.util;

import gd.script.gdcc.backend.c.build.TargetPlatform;

import java.util.List;

public final class ZigTestUtil {
    private ZigTestUtil() {
    }

    public static void addMacosNativeTarget(List<String> command) {
        addMacosNativeTarget(command, TargetPlatform.getNativePlatform());
    }

    static void addMacosNativeTarget(List<String> command, TargetPlatform platform) {
        if (command.contains("-target")) {
            return;
        }

        // An explicit macOS target uses Zig's bundled libc instead of the host SDK.
        switch (platform) {
            case MACOS_X86_64, MACOS_AARCH64 -> command.addAll(List.of("-target", platform.zigTarget));
            default -> {
            }
        }
    }
}
