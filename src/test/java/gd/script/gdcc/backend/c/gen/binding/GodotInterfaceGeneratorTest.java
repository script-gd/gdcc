package gd.script.gdcc.backend.c.gen.binding;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GodotInterfaceGeneratorTest {
    private static final @NotNull Path INTERFACE_HEADER = Path.of(
            "src/main/c/codegen/include_451/godot/gdextension/gdextension_interface.h"
    );

    @Test
    void renderInterfaceSupportShouldMatchGodotHeaderNameTypedefPairs() throws IOException {
        var headerText = Files.readString(INTERFACE_HEADER);
        var functions = GodotInterfaceGenerator.parseInterfaceFunctions(headerText);
        var firstSnapshot = GodotInterfaceGenerator.renderInterfaceSupport(INTERFACE_HEADER);
        var secondSnapshot = GodotInterfaceGenerator.renderInterfaceSupport(INTERFACE_HEADER);

        assertEquals(firstSnapshot, secondSnapshot);
        assertEquals(countNames(headerText), functions.size());
        assertEquals(
                Map.of(
                        "godot_interface.h", firstSnapshot.get("godot_interface.h"),
                        "godot_interface.c", firstSnapshot.get("godot_interface.c")
                ),
                firstSnapshot
        );

        var generatedHeader = firstSnapshot.get("godot_interface.h");
        var generatedSource = firstSnapshot.get("godot_interface.c");

        assertAll(
                () -> assertTrue(generatedHeader.contains(
                        "GDCC_GODOT_DECL GDExtensionBool godot_initialize_interface("
                                + "GDExtensionInterfaceGetProcAddress get_proc_address);"
                )),
                () -> assertTrue(generatedHeader.contains("typedef struct gdcc_binding_lookup_context {")),
                () -> assertTrue(generatedHeader.contains("const char *function_name;")),
                () -> assertTrue(generatedHeader.contains("GDExtensionBool has_primary_hash;")),
                () -> assertTrue(generatedHeader.contains("const GDExtensionInt *compatibility_hashes;")),
                () -> assertTrue(generatedHeader.contains("GDExtensionBool suppress_internal_error;")),
                () -> assertTrue(generatedHeader.contains(
                        "GDCC_GODOT_DECL GDExtensionBool gdcc_binding_lookup_fail("
                                + "\n        const gdcc_binding_lookup_context *context);"
                )),
                () -> assertFalse(generatedHeader.contains("GDCC_NORETURN")),
                () -> assertFalse(generatedHeader.contains("void gdcc_binding_lookup_fail(const char *kind")),
                () -> assertTrue(generatedHeader.contains("GDCC_GODOT_INLINE void *godot_mem_alloc(size_t p_bytes)")),
                () -> assertTrue(generatedHeader.contains(
                        "GDCC_GODOT_DECL extern GDExtensionInterfaceMemAlloc gdcc_interface_mem_alloc;"
                )),
                () -> assertFalse(generatedHeader.contains("static GDExtensionInterfaceMemAlloc")),
                () -> assertTrue(generatedHeader.contains("""
                        GDCC_GODOT_INLINE void *godot_mem_alloc(size_t p_bytes) {
                            return gdcc_interface_mem_alloc(p_bytes);
                        }
                        """)),
                () -> assertFalse(generatedHeader.contains("void *godot_mem_alloc(size_t p_bytes);\n")),
                () -> assertTrue(generatedHeader.contains(
                        "GDCC_GODOT_INLINE void godot_variant_new_nil(GDExtensionUninitializedVariantPtr r_dest)"
                )),
                () -> assertTrue(generatedHeader.contains("""
                        GDCC_GODOT_INLINE void godot_variant_new_nil(GDExtensionUninitializedVariantPtr r_dest) {
                            gdcc_interface_variant_new_nil(r_dest);
                        }
                        """)),
                () -> assertTrue(generatedHeader.contains(
                        "GDCC_GODOT_INLINE void godot_string_new_with_utf8_chars("
                                + "GDExtensionUninitializedStringPtr r_dest, const char *p_contents)"
                )),
                () -> assertTrue(generatedHeader.contains(
                        "GDCC_GODOT_INLINE void godot_object_method_bind_call(GDExtensionMethodBindPtr p_method_bind, "
                                + "GDExtensionObjectPtr p_instance, const GDExtensionConstVariantPtr *p_args, "
                                + "GDExtensionInt p_arg_count, GDExtensionUninitializedVariantPtr r_ret, "
                                + "GDExtensionCallError *r_error)"
                )),
                () -> assertTrue(generatedHeader.contains(
                        "GDCC_GODOT_INLINE void godot_editor_help_load_xml_from_utf8_chars(const char *p_data)"
                )),
                () -> assertTrue(generatedHeader.contains(
                        "GDCC_GODOT_INLINE void godot_editor_register_get_classes_used_callback("
                                + "GDExtensionClassLibraryPtr p_library, "
                                + "GDExtensionEditorGetClassesUsedCallback p_callback)"
                )),
                () -> assertTrue(generatedHeader.contains(
                        "GDCC_GODOT_INLINE void godot_register_main_loop_callbacks("
                                + "GDExtensionClassLibraryPtr p_library, "
                                + "const GDExtensionMainLoopCallbacks *p_callbacks)"
                )),
                () -> assertFalse(generatedHeader.contains("godot_get_proc_address")),
                () -> assertFalse(generatedHeader.contains("get_proc_address(")),
                () -> assertFalse(generatedHeader.contains("if (gdcc_interface_mem_alloc == NULL)")),
                () -> assertTrue(generatedSource.contains(
                        "GDCC_GODOT_DECL GDExtensionInterfaceMemAlloc gdcc_interface_mem_alloc = NULL;"
                )),
                () -> assertFalse(generatedSource.contains(
                        "static GDExtensionInterfaceMemAlloc gdcc_interface_mem_alloc = NULL;"
                )),
                () -> assertTrue(generatedSource.contains(
                        "GDCC_RESOLVE_INTERFACE(gdcc_interface_mem_alloc, "
                                + "GDExtensionInterfaceMemAlloc, \"godot_mem_alloc\", \"mem_alloc\");"
                )),
                () -> assertTrue(generatedSource.contains(
                        "GDCC_RESOLVE_INTERFACE(gdcc_interface_editor_help_load_xml_from_utf8_chars, "
                                + "GDExtensionsInterfaceEditorHelpLoadXmlFromUtf8Chars, "
                                + "\"godot_editor_help_load_xml_from_utf8_chars\", "
                                + "\"editor_help_load_xml_from_utf8_chars\");"
                )),
                () -> assertTrue(generatedSource.contains("GDExtensionInterfacePrintErrorWithMessage")),
                () -> assertTrue(generatedSource.contains("primary_hash=%lld")),
                () -> assertTrue(generatedSource.contains("compatibility_hashes=[")),
                () -> assertTrue(generatedSource.contains("context->suppress_internal_error")),
                () -> assertTrue(generatedSource.contains("gdcc_binding_lookup_fail(&(gdcc_binding_lookup_context){")),
                () -> assertTrue(generatedSource.contains("goto gdcc_initialize_interface_failed;")),
                () -> assertTrue(generatedSource.contains("gdcc_initialize_interface_failed:")),
                () -> assertTrue(generatedSource.contains(
                        "return gdcc_binding_lookup_fail(&(gdcc_binding_lookup_context){"
                )),
                () -> assertTrue(generatedSource.contains(".function_name = \"godot_initialize_interface\"")),
                () -> assertTrue(generatedSource.contains(".lookup_name = \"get_proc_address\"")),
                () -> assertFalse(generatedSource.contains("gdcc_binding_lookup_fail(\"interface\"")),
                () -> assertFalse(generatedSource.contains("GDCC_NORETURN")),
                () -> assertFalse(generatedSource.contains("abort();")),
                () -> assertFalse(generatedSource.contains("gdcc_interface_lookup_fail")),
                () -> assertTrue(generatedSource.contains("gdcc_clear_interface_pointers();")),
                () -> assertTrue(generatedSource.contains("gdcc_prepare_lookup_diagnostics(get_proc_address);")),
                () -> assertTrue(generatedSource.contains("return true;")),
                () -> assertTrue(generatedSource.contains("return false;")),
                () -> assertTrue(generatedSource.contains("lookup_name,\n                0,\n                true);")),
                () -> assertFalse(generatedSource.contains("(GDExtensionBool)1")),
                () -> assertFalse(generatedSource.contains("(GDExtensionBool)0")),
                () -> assertFalse(generatedSource.contains("gdcc_get_proc_address")),
                () -> assertFalse(generatedSource.contains("gdcc_reset_interface_cache")),
                () -> assertFalse(generatedSource.contains("if (gdcc_interface_mem_alloc == NULL)")),
                () -> assertFalse(generatedSource.contains("""
                        void *godot_mem_alloc(size_t p_bytes) {
                            return gdcc_interface_mem_alloc(p_bytes);
                        }
                        """)),
                () -> assertFalse(generatedSource.contains("""
                        void godot_variant_new_nil(GDExtensionUninitializedVariantPtr r_dest) {
                            gdcc_interface_variant_new_nil(r_dest);
                        }
                        """))
        );
    }

    @Test
    void generateInterfaceSupportShouldWriteStableFiles(@TempDir Path tempDir) throws IOException {
        GodotInterfaceGenerator.generateInterfaceSupport(INTERFACE_HEADER, tempDir);
        var firstSnapshot = snapshot(tempDir);
        GodotInterfaceGenerator.generateInterfaceSupport(INTERFACE_HEADER, tempDir);
        var secondSnapshot = snapshot(tempDir);

        assertEquals(firstSnapshot, secondSnapshot);
        assertEquals(
                Map.of(
                        "godot_interface.h", firstSnapshot.get("godot_interface.h"),
                        "godot_interface.c", firstSnapshot.get("godot_interface.c")
                ),
                firstSnapshot
        );
    }

    @Test
    void parserShouldUseNameForWrapperAndTypedefForSignature() {
        var files = GodotInterfaceGenerator.renderInterfaceSupport("""
                typedef void (*GDExtensionInterfaceFunctionPtr)();
                typedef GDExtensionInterfaceFunctionPtr (*GDExtensionInterfaceGetProcAddress)(const char *p_function_name);
                /**
                 * @name strange_lookup_name
                 * @since 4.5
                 */
                typedef void (*GDExtensionsInterfaceUnexpectedTypedefName)(GDExtensionUninitializedVariantPtr r_dest);
                """);

        var generatedHeader = files.get("godot_interface.h");
        var generatedSource = files.get("godot_interface.c");

        assertAll(
                () -> assertTrue(generatedHeader.contains(
                        "GDCC_GODOT_DECL extern GDExtensionsInterfaceUnexpectedTypedefName "
                                + "gdcc_interface_strange_lookup_name;"
                )),
                () -> assertTrue(generatedHeader.contains(
                        "GDCC_GODOT_INLINE void godot_strange_lookup_name("
                                + "GDExtensionUninitializedVariantPtr r_dest)"
                )),
                () -> assertFalse(generatedHeader.contains("godot_unexpected_typedef_name")),
                () -> assertTrue(generatedSource.contains(
                        "GDCC_GODOT_DECL GDExtensionsInterfaceUnexpectedTypedefName "
                                + "gdcc_interface_strange_lookup_name = NULL;"
                )),
                () -> assertTrue(generatedSource.contains(
                        "GDCC_RESOLVE_INTERFACE(gdcc_interface_strange_lookup_name, "
                                + "GDExtensionsInterfaceUnexpectedTypedefName, "
                                + "\"godot_strange_lookup_name\", \"strange_lookup_name\");"
                )),
                () -> assertFalse(generatedSource.contains("gdcc_get_proc_address")),
                () -> assertFalse(generatedSource.contains("unexpected_typedef_name"))
        );
    }

    @Test
    void generatedWrappersShouldUseEagerResolvedInterfacePointersOnly() {
        var files = GodotInterfaceGenerator.renderInterfaceSupport("""
                typedef void (*GDExtensionInterfaceFunctionPtr)();
                typedef GDExtensionInterfaceFunctionPtr (*GDExtensionInterfaceGetProcAddress)(const char *p_function_name);
                typedef uint8_t GDExtensionBool;
                /**
                 * @name print_error
                 */
                typedef void (*GDExtensionInterfacePrintError)(
                        const char *p_description,
                        const char *p_function,
                        const char *p_file,
                        int32_t p_line,
                        GDExtensionBool p_editor_notify);
                /**
                 * @name print_error_with_message
                 */
                typedef void (*GDExtensionInterfacePrintErrorWithMessage)(
                        const char *p_description,
                        const char *p_message,
                        const char *p_function,
                        const char *p_file,
                        int32_t p_line,
                        GDExtensionBool p_editor_notify);
                /**
                 * @name eager_void
                 */
                typedef void (*GDExtensionInterfaceEagerVoid)(void);
                /**
                 * @name eager_value
                 */
                typedef int (*GDExtensionInterfaceEagerValue)(int p_value);
                """);

        var generatedHeader = files.get("godot_interface.h");
        var generatedSource = files.get("godot_interface.c");

        assertAll(
                () -> assertTrue(generatedSource.contains(
                        "GDCC_RESOLVE_INTERFACE(gdcc_interface_eager_void, "
                                + "GDExtensionInterfaceEagerVoid, \"godot_eager_void\", \"eager_void\");"
                )),
                () -> assertTrue(generatedSource.contains(
                        "GDCC_RESOLVE_INTERFACE(gdcc_interface_eager_value, "
                                + "GDExtensionInterfaceEagerValue, \"godot_eager_value\", \"eager_value\");"
                )),
                () -> assertTrue(generatedHeader.contains("""
                        GDCC_GODOT_INLINE void godot_eager_void(void) {
                            gdcc_interface_eager_void();
                        }
                        """)),
                () -> assertTrue(generatedHeader.contains("""
                        GDCC_GODOT_INLINE int godot_eager_value(int p_value) {
                            return gdcc_interface_eager_value(p_value);
                        }
                        """)),
                () -> assertFalse(generatedHeader.contains("get_proc_address(")),
                () -> assertFalse(generatedSource.contains("gdcc_get_proc_address")),
                () -> assertFalse(generatedSource.contains("if (gdcc_interface_eager_void == NULL)")),
                () -> assertFalse(generatedSource.contains("if (gdcc_interface_eager_value == NULL)")),
                () -> assertFalse(generatedSource.contains("""
                        void godot_eager_void(void) {
                            gdcc_interface_eager_void();
                        }
                        """)),
                () -> assertFalse(generatedSource.contains("""
                        int godot_eager_value(int p_value) {
                            return gdcc_interface_eager_value(p_value);
                        }
                        """))
        );
    }

    @Test
    void parserShouldRejectNameWithoutFollowingFunctionPointerTypedef() {
        var failure = assertThrows(IllegalStateException.class, () -> GodotInterfaceGenerator.renderInterfaceSupport("""
                /**
                 * @name missing_typedef
                 */
                typedef int NotAFunctionPointer;
                """));

        assertTrue(failure.getMessage().contains("missing_typedef"));
    }

    @Test
    void parserShouldRejectDuplicateLookupName() {
        var failure = assertThrows(IllegalStateException.class, () -> GodotInterfaceGenerator.renderInterfaceSupport("""
                /**
                 * @name duplicate_name
                 */
                typedef void (*GDExtensionInterfaceDuplicateName)(void);
                /**
                 * @name duplicate_name
                 */
                typedef int (*GDExtensionInterfaceDuplicateName2)(void);
                """));

        assertTrue(failure.getMessage().contains("duplicate_name"));
    }

    @Test
    void renderBindingSupportShouldAggregateStage2RuntimeSupport() {
        var files = GodotInterfaceGenerator.renderBindingSupport();
        var generatedHeader = files.get("godot_binding.h");
        var generatedSource = files.get("godot_binding.c");

        assertAll(
                () -> assertEquals(2, files.size()),
                () -> assertTrue(generatedHeader.contains("#include <godot_abi.h>")),
                () -> assertTrue(generatedHeader.contains("#include <godot_interface.h>")),
                () -> assertTrue(generatedHeader.contains("#include <godot_builtin.h>")),
                () -> assertTrue(generatedHeader.contains("#include <godot_utility.h>")),
                () -> assertTrue(generatedHeader.contains("#include <godot_fixed_binding.h>")),
                () -> assertTrue(generatedSource.contains("#include \"godot_binding.h\"")),
                () -> assertTrue(generatedSource.contains("#include \"godot_interface.c\"")),
                () -> assertTrue(generatedSource.contains("#include \"godot_builtin.c\"")),
                () -> assertTrue(generatedSource.contains("#include \"godot_utility.c\"")),
                () -> assertTrue(generatedSource.contains("#include \"godot_fixed_binding.c\""))
        );
    }

    private static int countNames(@NotNull String header) {
        var matcher = Pattern.compile("\\*\\s*@name\\s+([A-Za-z0-9_]+)\\b").matcher(header);
        var count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
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
