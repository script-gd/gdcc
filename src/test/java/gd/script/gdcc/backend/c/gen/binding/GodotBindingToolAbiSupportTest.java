package gd.script.gdcc.backend.c.gen.binding;

import gd.script.gdcc.gdextension.ExtensionAPI;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.gdextension.ExtensionEnumValue;
import gd.script.gdcc.gdextension.ExtensionGlobalConstant;
import gd.script.gdcc.gdextension.ExtensionGlobalEnum;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GodotBindingToolAbiSupportTest {
    @Test
    void generateAbiSupportShouldProduceStableStage1AHeaders(@TempDir Path tempDir) throws IOException {
        var api = ExtensionApiLoader.loadDefault();

        GodotBindingTool.generateAbiSupport(api, tempDir);
        var firstSnapshot = snapshot(tempDir);
        GodotBindingTool.generateAbiSupport(api, tempDir);
        var secondSnapshot = snapshot(tempDir);

        assertEquals(firstSnapshot, secondSnapshot);
        assertEquals(
                List.of(
                        "godot_abi.h",
                        "godot_builtin_layout.h",
                        "godot_builtin_sizes.h",
                        "godot_builtin_types.h",
                        "godot_global_constants.h",
                        "godot_global_enums.h",
                        "godot_macros.h",
                        "godot_native_structures.h"
                ),
                firstSnapshot.keySet().stream().sorted().toList()
        );

        var abi = firstSnapshot.get("godot_abi.h");
        var enums = firstSnapshot.get("godot_global_enums.h");
        var constants = firstSnapshot.get("godot_global_constants.h");
        var sizes = firstSnapshot.get("godot_builtin_sizes.h");
        var layout = firstSnapshot.get("godot_builtin_layout.h");
        var builtinTypes = firstSnapshot.get("godot_builtin_types.h");
        var nativeStructures = firstSnapshot.get("godot_native_structures.h");

        assertAll(
                () -> assertTrue(abi.contains("#include <gdextension/gdextension_interface.h>")),
                () -> assertTrue(abi.contains("typedef struct godot_Node godot_Node;")),
                () -> assertTrue(abi.contains("typedef struct godot_Node2D godot_Node2D;")),
                () -> assertTrue(abi.contains("typedef enum godot_Node_InternalMode")),
                () -> assertTrue(abi.contains("godot_Node_INTERNAL_MODE_BACK = 2")),
                () -> assertTrue(enums.contains("godot_PROPERTY_HINT_ARRAY_TYPE = 31")),
                () -> assertTrue(enums.contains("godot_METHOD_FLAG_STATIC = 32")),
                () -> assertTrue(constants.contains("Godot 4.5.1 exports no standalone global constants")),
                () -> assertTrue(sizes.contains("#error \"GDCC C backend supports only single-precision Godot real_t builds\"")),
                () -> assertTrue(sizes.contains("#error \"GDCC C backend does not support 32-bit Godot builtin ABI\"")),
                () -> assertTrue(sizes.contains("#define GDCC_GODOT_BUILD_FLOAT_64")),
                () -> assertTrue(sizes.contains("#define GDCC_GODOT_SIZE_Variant 24")),
                () -> assertTrue(sizes.contains("#define GDCC_GODOT_SIZE_Vector3 12")),
                () -> assertFalse(sizes.contains("GDCC_GODOT_BUILD_FLOAT_32")),
                () -> assertFalse(sizes.contains("GDCC_GODOT_BUILD_DOUBLE_32")),
                () -> assertFalse(sizes.contains("GDCC_GODOT_BUILD_DOUBLE_64")),
                () -> assertTrue(layout.contains("#define GDCC_GODOT_OFFSET_Vector3_z 8")),
                () -> assertTrue(layout.contains("#define GDCC_GODOT_META_Vector3_z float")),
                () -> assertTrue(layout.contains("#define GDCC_GODOT_META_Color_r float")),
                () -> assertFalse(layout.contains("GDCC_GODOT_BUILD_FLOAT_32")),
                () -> assertFalse(layout.contains("GDCC_GODOT_BUILD_DOUBLE_32")),
                () -> assertFalse(layout.contains("GDCC_GODOT_BUILD_DOUBLE_64")),
                () -> assertTrue(builtinTypes.contains("typedef struct godot_Vector3")),
                () -> assertTrue(builtinTypes.contains("typedef float godot_real_t;")),
                () -> assertFalse(builtinTypes.contains("typedef double godot_real_t;")),
                () -> assertTrue(builtinTypes.contains("GDCC_GODOT_ASSERT_LAYOUT(Vector3, z);")),
                () -> assertTrue(nativeStructures.contains("typedef enum godot_TextServer_Direction")),
                () -> assertTrue(nativeStructures.contains("godot_PhysicsServer3DExtensionMotionCollision collisions[32];")),
                () -> assertTrue(nativeStructures.contains("godot_Object * collider"))
        );
    }

    @Test
    void generateAbiSupportShouldRequireOnlySupportedFloat64Metadata() throws IOException {
        var baseApi = ExtensionApiLoader.loadDefault();
        var api = new ExtensionAPI(
                baseApi.header(),
                baseApi.builtinClassSizes().stream()
                        .filter(sizes -> sizes.buildConfiguration().equals("float_64"))
                        .toList(),
                baseApi.builtinClassMemberOffsets().stream()
                        .filter(offsets -> offsets.buildConfiguration().equals("float_64"))
                        .toList(),
                baseApi.globalConstants(),
                baseApi.globalEnums(),
                baseApi.utilityFunctions(),
                baseApi.builtinClasses(),
                baseApi.classes(),
                baseApi.singletons(),
                baseApi.nativeStructures()
        );

        var headers = GodotBindingTool.renderAbiHeaders(api);

        assertAll(
                () -> assertTrue(headers.get("godot_builtin_sizes.h").contains("GDCC_GODOT_BUILD_FLOAT_64")),
                () -> assertTrue(headers.get("godot_builtin_layout.h").contains("GDCC_GODOT_META_Vector3_z float"))
        );
    }

    @Test
    void generateAbiSupportShouldRenderNonEmptyGlobalConstantsFromModelOnly() throws IOException {
        var baseApi = ExtensionApiLoader.loadDefault();
        var fixtureApi = ExtensionApiLoader.loadFromResource("/extension_api_metadata_fixture.json");
        var api = new ExtensionAPI(
                baseApi.header(),
                baseApi.builtinClassSizes(),
                baseApi.builtinClassMemberOffsets(),
                fixtureApi.globalConstants(),
                baseApi.globalEnums(),
                baseApi.utilityFunctions(),
                baseApi.builtinClasses(),
                baseApi.classes(),
                baseApi.singletons(),
                baseApi.nativeStructures()
        );

        var constants = GodotBindingTool.renderAbiHeaders(api).get("godot_global_constants.h");

        assertTrue(constants.contains("#define godot_GDCC_TEST_BIG_FLAG 4294967296LL"));
    }

    @Test
    void generateAbiSupportShouldRejectGlobalConstantAndEnumNameCollision() {
        var api = new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(new ExtensionGlobalConstant("GDCC_DUPLICATE", 2L, false)),
                List.of(new ExtensionGlobalEnum(
                        "GdccCollision",
                        false,
                        List.of(new ExtensionEnumValue("GDCC_DUPLICATE", 1L))
                )),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );

        var failure = assertThrows(IllegalStateException.class, () -> GodotBindingTool.renderAbiHeaders(api));

        assertTrue(failure.getMessage().contains("godot_GDCC_DUPLICATE"));
    }

    @Test
    void generateAbiSupportShouldRejectMissingRequiredBuildConfiguration() {
        var api = new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );

        var failure = assertThrows(IllegalStateException.class, () -> GodotBindingTool.renderAbiHeaders(api));

        assertTrue(failure.getMessage().contains("float_64"));
    }

    private static @NotNull Map<String, String> snapshot(@NotNull Path dir) throws IOException {
        var files = new LinkedHashMap<String, String>();
        try (var stream = Files.list(dir)) {
            for (var path : stream.sorted().toList()) {
                files.put(path.getFileName().toString(), Files.readString(path));
            }
        }
        return files;
    }
}
