package gd.script.gdcc.backend.c.build;

import gd.script.gdcc.enums.HardwareArchitecture;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

public enum TargetPlatform {
    WINDOWS_X86_64(PlatformFamily.WINDOWS, HardwareArchitecture.X86_64, "x86_64-windows-msvc"),
    WINDOWS_AARCH64(PlatformFamily.WINDOWS, HardwareArchitecture.AARCH64, "aarch64-windows-msvc"),
    LINUX_X86_64(PlatformFamily.LINUX, HardwareArchitecture.X86_64, "x86_64-linux-gnu"),
    LINUX_AARCH64(PlatformFamily.LINUX, HardwareArchitecture.AARCH64, "aarch64-linux-gnu"),
    LINUX_RISCV64(PlatformFamily.LINUX, HardwareArchitecture.RISCV64, "riscv64-linux-gnu"),
    MACOS_X86_64(PlatformFamily.MACOS, HardwareArchitecture.X86_64, "x86_64-macos-none"),
    MACOS_AARCH64(PlatformFamily.MACOS, HardwareArchitecture.AARCH64, "aarch64-macos-none"),
    ANDROID_X86_64(PlatformFamily.ANDROID, HardwareArchitecture.X86_64, "x86_64-linux-android"),
    ANDROID_AARCH64(PlatformFamily.ANDROID, HardwareArchitecture.AARCH64, "aarch64-linux-android"),
    WEB_WASM32(PlatformFamily.WEB, HardwareArchitecture.WASM32, "wasm32-emscripten"),
    ;

    private final @NotNull PlatformFamily family;
    public final @NotNull HardwareArchitecture architecture;
    public final @NotNull String zigTarget;

    TargetPlatform(
            @NotNull PlatformFamily family,
            @NotNull HardwareArchitecture architecture,
            @NotNull String zigTarget
    ) {
        this.family = family;
        this.architecture = architecture;
        this.zigTarget = zigTarget;
    }

    public boolean isWindows() {
        return family == PlatformFamily.WINDOWS;
    }

    public @NotNull String sharedLibraryFileName(@NotNull String outputBaseName) {
        return switch (family) {
            case WINDOWS -> outputBaseName + ".dll";
            case LINUX, ANDROID -> "lib" + outputBaseName + ".so";
            case MACOS -> "lib" + outputBaseName + ".dylib";
            case WEB -> outputBaseName + ".wasm";
        };
    }

    public static @NotNull TargetPlatform getNativePlatform() {
        return getNativePlatform(
                System.getProperty("os.name", ""),
                System.getProperty("os.arch", ""),
                System.getProperty("java.vendor", ""),
                System.getProperty("java.vm.name", "")
        );
    }

    static @NotNull TargetPlatform getNativePlatform(
            @NotNull String osName,
            @NotNull String osArch,
            @NotNull String javaVendor,
            @NotNull String vmName
    ) {
        var normalizedOsName = osName.toLowerCase(Locale.ROOT);
        var normalizedOsArch = osArch.toLowerCase(Locale.ROOT);
        var isAndroidRuntime = normalizedOsName.contains("android")
                || javaVendor.toLowerCase(Locale.ROOT).contains("android")
                || vmName.toLowerCase(Locale.ROOT).contains("dalvik")
                || vmName.toLowerCase(Locale.ROOT).contains("art");
        var arch = parseArchitecture(normalizedOsName, normalizedOsArch);
        if (isAndroidRuntime) {
            return switch (arch) {
                case X86_64 -> ANDROID_X86_64;
                case AARCH64 -> ANDROID_AARCH64;
                case RISCV64, WASM32 -> throw unsupportedNativePlatform(osName, osArch);
            };
        }
        return switch (normalizedOsName) {
            case String s when s.contains("mac") || s.contains("darwin") -> switch (arch) {
                case X86_64 -> MACOS_X86_64;
                case AARCH64 -> MACOS_AARCH64;
                case RISCV64, WASM32 -> throw unsupportedNativePlatform(osName, osArch);
            };
            case String s when s.contains("win") -> switch (arch) {
                case X86_64 -> WINDOWS_X86_64;
                case AARCH64 -> WINDOWS_AARCH64;
                case RISCV64, WASM32 -> throw unsupportedNativePlatform(osName, osArch);
            };
            case String s when s.contains("linux") -> switch (arch) {
                case X86_64 -> LINUX_X86_64;
                case AARCH64 -> LINUX_AARCH64;
                case RISCV64 -> LINUX_RISCV64;
                case WASM32 -> throw unsupportedNativePlatform(osName, osArch);
            };
            default -> throw unsupportedNativePlatform(osName, osArch);
        };
    }

    private static @NotNull HardwareArchitecture parseArchitecture(@NotNull String osName, @NotNull String osArch) {
        return switch (osArch) {
            case "x86_64", "amd64", "x64" -> HardwareArchitecture.X86_64;
            case "aarch64", "arm64" -> HardwareArchitecture.AARCH64;
            case "riscv64" -> HardwareArchitecture.RISCV64;
            case "wasm32" -> HardwareArchitecture.WASM32;
            default -> throw unsupportedNativePlatform(osName, osArch);
        };
    }

    private static @NotNull IllegalStateException unsupportedNativePlatform(@NotNull String osName, @NotNull String osArch) {
        return new IllegalStateException("Unsupported native platform: os.name='" + osName + "', os.arch='" + osArch + "'");
    }

    private enum PlatformFamily {
        WINDOWS,
        LINUX,
        MACOS,
        ANDROID,
        WEB,
    }
}
