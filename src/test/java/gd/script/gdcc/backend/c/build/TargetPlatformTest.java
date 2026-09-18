package gd.script.gdcc.backend.c.build;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TargetPlatformTest {
    @Test
    void sharedLibraryFileNameCoversEveryPlatform() {
        var expected = new LinkedHashMap<TargetPlatform, String>();
        expected.put(TargetPlatform.WINDOWS_X86_64, "demo.dll");
        expected.put(TargetPlatform.WINDOWS_AARCH64, "demo.dll");
        expected.put(TargetPlatform.LINUX_X86_64, "libdemo.so");
        expected.put(TargetPlatform.LINUX_AARCH64, "libdemo.so");
        expected.put(TargetPlatform.LINUX_RISCV64, "libdemo.so");
        expected.put(TargetPlatform.MACOS_X86_64, "libdemo.dylib");
        expected.put(TargetPlatform.MACOS_AARCH64, "libdemo.dylib");
        expected.put(TargetPlatform.ANDROID_X86_64, "libdemo.so");
        expected.put(TargetPlatform.ANDROID_AARCH64, "libdemo.so");
        expected.put(TargetPlatform.WEB_WASM32, "demo.wasm");

        for (var entry : expected.entrySet()) {
            assertEquals(entry.getValue(), entry.getKey().sharedLibraryFileName("demo"));
        }
        assertEquals(TargetPlatform.values().length, expected.size());
    }

    @Test
    void zigTargetsAreCanonical() {
        assertEquals("x86_64-macos-none", TargetPlatform.MACOS_X86_64.zigTarget);
        assertEquals("aarch64-macos-none", TargetPlatform.MACOS_AARCH64.zigTarget);
    }

    @Test
    void getNativePlatformDetectsMacosHostSpellings() {
        assertEquals(TargetPlatform.MACOS_AARCH64, TargetPlatform.getNativePlatform("Mac OS X", "aarch64", "", ""));
        assertEquals(TargetPlatform.MACOS_AARCH64, TargetPlatform.getNativePlatform("Mac OS X", "arm64", "", ""));
        assertEquals(TargetPlatform.MACOS_X86_64, TargetPlatform.getNativePlatform("Darwin", "x86_64", "", ""));
        assertEquals(TargetPlatform.MACOS_X86_64, TargetPlatform.getNativePlatform("macOS", "amd64", "", ""));
    }
}
