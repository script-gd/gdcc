package gd.script.gdcc.backend.c.build;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Pins the `.gdextension` rendering: arch-qualified Godot library keys (Godot's `arm64`
/// spelling, not gdcc's `aarch64`) plus debug/release compatibility aliases, because two
/// builds of the same platform family would otherwise collide on one key. Both render entry
/// points must also declare `reloadable = true` — the editor-side hot reload prerequisite
/// (hot_reload_implementation_plan.md HR-1).
class GdextensionMetadataFileTest {
    @Test
    void renderEmitsReloadableConfigurationWithCompatibilityKeys() {
        var rendered = GdextensionMetadataFile.render(
                "res://addons/gdcc/bin/libgdcc_for_editor_debug_x86_64.so",
                COptimizationLevel.DEBUG,
                TargetPlatform.LINUX_X86_64);

        assertEquals("""
                [configuration]
                
                entry_symbol = "gdextension_entry"
                compatibility_minimum = "4.5"
                reloadable = true
                
                [libraries]
                linux.debug = "res://addons/gdcc/bin/libgdcc_for_editor_debug_x86_64.so"
                linux.release = "res://addons/gdcc/bin/libgdcc_for_editor_debug_x86_64.so"
                """, rendered);
    }

    @Test
    void renderMultiPlatformEmitsArchQualifiedKeysWithCompatibilityAliases() {
        var libraryPathByPlatform = new LinkedHashMap<TargetPlatform, String>();
        libraryPathByPlatform.put(TargetPlatform.WINDOWS_X86_64, "res://addons/gdcc/bin/gdcc_for_editor_debug_x86_64.dll");
        libraryPathByPlatform.put(TargetPlatform.LINUX_X86_64, "res://addons/gdcc/bin/libgdcc_for_editor_debug_x86_64.so");
        libraryPathByPlatform.put(TargetPlatform.LINUX_AARCH64, "res://addons/gdcc/bin/libgdcc_for_editor_debug_aarch64.so");

        var rendered = GdextensionMetadataFile.renderMultiPlatform(libraryPathByPlatform, COptimizationLevel.DEBUG);

        assertEquals("""
                [configuration]

                entry_symbol = "gdextension_entry"
                compatibility_minimum = "4.5"
                reloadable = true

                [libraries]
                windows.debug.x86_64 = "res://addons/gdcc/bin/gdcc_for_editor_debug_x86_64.dll"
                windows.release.x86_64 = "res://addons/gdcc/bin/gdcc_for_editor_debug_x86_64.dll"
                linux.debug.x86_64 = "res://addons/gdcc/bin/libgdcc_for_editor_debug_x86_64.so"
                linux.release.x86_64 = "res://addons/gdcc/bin/libgdcc_for_editor_debug_x86_64.so"
                linux.debug.arm64 = "res://addons/gdcc/bin/libgdcc_for_editor_debug_aarch64.so"
                linux.release.arm64 = "res://addons/gdcc/bin/libgdcc_for_editor_debug_aarch64.so"
                """, rendered);
    }

    @Test
    void archQualifiedLibraryKeyCoversEveryPlatformWithGodotArchNames() {
        var expectedKeys = new LinkedHashMap<TargetPlatform, String>();
        expectedKeys.put(TargetPlatform.WINDOWS_X86_64, "windows.debug.x86_64");
        expectedKeys.put(TargetPlatform.WINDOWS_AARCH64, "windows.debug.arm64");
        expectedKeys.put(TargetPlatform.LINUX_X86_64, "linux.debug.x86_64");
        expectedKeys.put(TargetPlatform.LINUX_AARCH64, "linux.debug.arm64");
        expectedKeys.put(TargetPlatform.LINUX_RISCV64, "linux.debug.rv64");
        expectedKeys.put(TargetPlatform.ANDROID_X86_64, "android.debug.x86_64");
        expectedKeys.put(TargetPlatform.ANDROID_AARCH64, "android.debug.arm64");
        expectedKeys.put(TargetPlatform.WEB_WASM32, "web.debug.wasm32");

        for (var entry : expectedKeys.entrySet()) {
            assertEquals(entry.getValue(),
                    GdextensionMetadataFile.archQualifiedLibraryKey(COptimizationLevel.DEBUG, entry.getKey()));
        }
        // The whole enum must be covered; a new platform missing from the mapping fails here.
        assertEquals(TargetPlatform.values().length, expectedKeys.size());
    }

    @Test
    void renderMultiPlatformRejectsEmptyInput() {
        assertThrows(IllegalArgumentException.class,
                () -> GdextensionMetadataFile.renderMultiPlatform(new LinkedHashMap<>(), COptimizationLevel.DEBUG));
    }
}
