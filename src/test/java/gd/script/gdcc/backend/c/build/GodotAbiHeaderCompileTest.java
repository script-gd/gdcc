package gd.script.gdcc.backend.c.build;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GodotAbiHeaderCompileTest {
    @Test
    void godotAbiHeaderShouldCompileOnlyForSupportedFloat64Build(@TempDir Path tempDir)
            throws IOException, InterruptedException {
        var zig = ZigUtil.findZig();
        Assumptions.assumeTrue(zig != null, "Zig executable is required for godot_abi.h compile smoke");
        var source = tempDir.resolve("godot_abi_probe.c");
        Files.writeString(source, """
                #include <godot_abi.h>
                #include <stddef.h>
                
                static_assert(GDEXTENSION_VARIANT_TYPE_VECTOR3 == 9, "variant enum should come from gdextension_interface.h");
                static_assert(GDEXTENSION_VARIANT_OP_ADD == 6, "operator enum should come from gdextension_interface.h");
                static_assert(GDEXTENSION_CALL_ERROR_TOO_FEW_ARGUMENTS == 4, "call error enum should be visible");
                static_assert(GDEXTENSION_METHOD_FLAG_STATIC == 32, "method flags should be visible");
                static_assert(GDEXTENSION_INITIALIZATION_SCENE == 2, "initialization level should be visible");
                static_assert(godot_PROPERTY_HINT_NONE == 0, "global enum values should be visible");
                static_assert(godot_PROPERTY_USAGE_DEFAULT == 6, "global enum aliases should be visible");
                static_assert(godot_METHOD_FLAG_STATIC == 32, "method flag aliases should be visible");
                static_assert(sizeof(godot_Variant) == GDCC_GODOT_SIZE_Variant, "Variant size should match generated ABI size");
                static_assert(sizeof(godot_real_t) == sizeof(float), "GDCC supports only single-precision real_t");
                static_assert(GDCC_GODOT_SIZE_Vector3 == 12, "Vector3 float_64 size should be preserved");
                static_assert(offsetof(godot_Vector3, z) == GDCC_GODOT_OFFSET_Vector3_z, "Vector3.z offset should match metadata");
                static_assert(sizeof(((godot_Vector3 *)0)->z) == sizeof(GDCC_GODOT_META_Vector3_z), "Vector3.z meta should match metadata");
                static_assert(sizeof(((godot_Color *)0)->r) == sizeof(float), "Color.r should stay float-backed");
                static_assert(sizeof(GDCC_GODOT_META_Color_r) == sizeof(float), "Color.r meta should stay float");
                static_assert(sizeof(GDCC_GODOT_META_Vector3_z) == sizeof(float), "Vector3.z should be float-backed");
                static_assert(GDCC_GODOT_OFFSET_Vector3_z == 8, "Vector3.z float_64 offset should be preserved");
                
                static godot_AudioFrame audio_frame;
                static godot_Glyph glyph;
                static godot_ObjectID object_id;
                static godot_CaretInfo caret_info;
                
                int gdcc_probe(void) {
                    return (int)(audio_frame.left + glyph.advance + (float)object_id.id)
                            + (int)caret_info.leading_direction;
                }
                """);

        var supported = compileObject(zig, source, List.of(), tempDir.resolve("godot_abi_probe_float64.o"));
        assertEquals(0, supported.exitCode(), supported::diagnostic);

        var realDouble = compileObject(
                zig,
                source,
                List.of("-DREAL_T_IS_DOUBLE"),
                tempDir.resolve("godot_abi_probe_double64.o")
        );
        assertUnsupported(realDouble, "single-precision Godot real_t");

        var target32Bit = compileObject(
                zig,
                source,
                List.of("-target", "x86-linux-gnu"),
                tempDir.resolve("godot_abi_probe_float32.o")
        );
        assertUnsupported(target32Bit, "does not support 32-bit Godot builtin ABI");
    }

    @Test
    void godotBindingHeaderAndAggregateSourceShouldCompile(@TempDir Path tempDir)
            throws IOException, InterruptedException {
        var zig = ZigUtil.findZig();
        Assumptions.assumeTrue(zig != null, "Zig executable is required for godot_binding compile smoke");
        var source = tempDir.resolve("godot_binding_probe.c");
        Files.writeString(source, """
                #include <godot_binding.h>
                static GDExtensionClassLibraryPtr class_library = NULL;
                #include <gdcc_bind.h>
                
                GDCC_DEFINE_ENGINE_METHOD_BIND_ACCESSOR(
                        gdcc_probe_method_bind_with_fallbacks,
                        u8"Node",
                        u8"queue_free",
                        "Node",
                        "queue_free",
                        (GDExtensionInt)321,
                        (GDExtensionInt)2,
                        (GDExtensionInt)654,
                        (GDExtensionInt)987
                )
                
                GDCC_DEFINE_ENGINE_METHOD_BIND_ACCESSOR(
                        gdcc_probe_method_bind_without_fallbacks,
                        u8"Object",
                        u8"get_instance_id",
                        "Object",
                        "get_instance_id",
                        (GDExtensionInt)123,
                        (GDExtensionInt)0,
                        (GDExtensionInt)0
                )
                
                static GDExtensionInterfaceFunctionPtr gdcc_fake_get_proc_address(const char *p_function_name) {
                    (void)p_function_name;
                    return NULL;
                }
                int gdcc_probe_lookup_fail_context(void) {
                    const GDExtensionInt compatibility_hashes[] = {456, 789};
                    gdcc_binding_lookup_context context = {
                            .kind = "class_method_bind",
                            .function_name = "godot_Node_add_child",
                            .lookup_name = "add_child",
                            .owner = "Node",
                            .type = NULL,
                            .has_primary_hash = true,
                            .primary_hash = 123,
                            .compatibility_hashes = compatibility_hashes,
                            .compatibility_hash_count = 2,
                            .suppress_internal_error = true,
                    };
                    return !gdcc_binding_lookup_fail(&context);
                }
                int gdcc_probe(void) {
                    GDExtensionBool initialized = godot_initialize_interface(gdcc_fake_get_proc_address);
                    GDExtensionVariantType type = godot_variant_get_type((GDExtensionConstVariantPtr)0);
                    godot_Node *node = NULL;
                    godot_Node2D *node2d = NULL;
                    godot_Engine *engine = godot_Engine_singleton();
                    godot_Node_InternalMode mode = godot_Node_INTERNAL_MODE_BACK;
                    if (0) {
                        GDExtensionMethodBindPtr bind = NULL;
                        (void)gdcc_probe_method_bind_with_fallbacks(&bind);
                        (void)gdcc_probe_method_bind_without_fallbacks(&bind);
                    }
                    (void)type;
                    (void)node;
                    (void)node2d;
                    (void)engine;
                    (void)mode;
                    return initialized;
                }
                """);

        var gdccIncludeDir = Path.of("src/main/c/codegen/include_451/gdcc").toAbsolutePath().normalize();
        var headerProbe = compileObject(zig, source, List.of("-I" + gdccIncludeDir), tempDir.resolve("godot_binding_probe.o"));
        assertEquals(0, headerProbe.exitCode(), headerProbe::diagnostic);

        var cxxSource = tempDir.resolve("godot_binding_cpp_probe.cpp");
        Files.writeString(cxxSource, """
                #include <godot_binding.h>
                
                extern "C" int gdcc_probe_cpp(void) {
                    return !godot_initialize_interface(nullptr);
                }
                """);
        var cxxProbe = compileObject(zig, "c++", "-std=c++23", cxxSource, List.of(), tempDir.resolve("godot_binding_cpp_probe.o"));
        assertEquals(0, cxxProbe.exitCode(), cxxProbe::diagnostic);

        var oldSignatureSource = tempDir.resolve("godot_binding_old_lookup_probe.c");
        Files.writeString(oldSignatureSource, """
                #include <godot_binding.h>
                void gdcc_probe_old_lookup_fail(void) {
                    gdcc_binding_lookup_fail("interface", "mem_alloc");
                }
                """);
        var oldSignatureProbe = compileObject(
                zig,
                oldSignatureSource,
                List.of(),
                tempDir.resolve("godot_binding_old_lookup_probe.o")
        );
        assertNotEquals(0, oldSignatureProbe.exitCode(), oldSignatureProbe::diagnostic);

        var aggregateSource = Path.of("src/main/c/codegen/include_451/godot/godot_binding.c");
        var aggregate = compileObject(zig, aggregateSource, List.of(), tempDir.resolve("godot_binding.o"));
        assertEquals(0, aggregate.exitCode(), aggregate::diagnostic);
    }

    @Test
    void gdccBuiltinCtorHeaderShouldCompileAndExposeRuntimeHelpers(@TempDir Path tempDir)
            throws IOException, InterruptedException {
        var zig = ZigUtil.findZig();
        Assumptions.assumeTrue(zig != null, "Zig executable is required for gdcc_builtin_ctor compile smoke");
        var source = tempDir.resolve("gdcc_builtin_ctor_probe.c");
        Files.writeString(source, """
                #include <gdcc_builtin_ctor.h>
                
                int gdcc_probe_atomic_helpers(void) {
                    godot_Variant nil = godot_new_Nil();
                    godot_Variant nil_from_variant = godot_new_Nil_with_Variant(&nil);
                    godot_bool default_bool = godot_new_bool();
                    godot_bool copied_bool = godot_new_bool_with_bool(true);
                    godot_bool int_bool = godot_new_bool_with_int(2);
                    godot_bool float_bool = godot_new_bool_with_float(3.0);
                    godot_int default_int = godot_new_int();
                    godot_int copied_int = godot_new_int_with_int(4);
                    godot_int float_int = godot_new_int_with_float(5.0);
                    godot_int bool_int = godot_new_int_with_bool(true);
                    godot_float default_float = godot_new_float();
                    godot_float copied_float = godot_new_float_with_float(6.0);
                    godot_float int_float = godot_new_float_with_int(7);
                    godot_float bool_float = godot_new_float_with_bool(true);
                    (void)nil_from_variant;
                    godot_variant_destroy(&nil);
                    godot_variant_destroy(&nil_from_variant);
                    return default_bool + copied_bool + int_bool + float_bool
                            + (int)(default_int + copied_int + float_int + bool_int)
                            + (int)(default_float + copied_float + int_float + bool_float);
                }
                
                int gdcc_probe_flat_float_helpers(void) {
                    godot_Transform2D transform2d = godot_new_Transform2D_with_float_float_float_float_float_float(
                            1.0, 0.0, 0.0, 1.0, 2.0, 3.0
                    );
                    godot_Transform3D transform3d = godot_new_Transform3D_with_float_float_float_float_float_float_float_float_float_float_float_float(
                            1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 2.0, 3.0, 4.0
                    );
                    godot_Basis basis = godot_new_Basis_with_float_float_float_float_float_float_float_float_float(
                            1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0
                    );
                    godot_Projection projection = godot_new_Projection_with_float_float_float_float_float_float_float_float_float_float_float_float_float_float_float_float(
                            1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0,
                            9.0, 10.0, 11.0, 12.0, 13.0, 14.0, 15.0, 16.0
                    );
                    return (int)(transform2d.x.x + transform3d.basis.x.x + basis.x.x + projection.x.x);
                }
                """);

        var gdccIncludeDir = Path.of("src/main/c/codegen/include_451/gdcc").toAbsolutePath().normalize();
        var probe = compileObject(
                zig,
                source,
                List.of("-I" + gdccIncludeDir),
                tempDir.resolve("gdcc_builtin_ctor_probe.o")
        );
        assertEquals(0, probe.exitCode(), probe::diagnostic);

        var helperSource = tempDir.resolve("gdcc_helper_ctor_probe.c");
        Files.writeString(helperSource, """
                #include <gdcc_builtin_ctor.h>
                static GDExtensionClassLibraryPtr class_library = NULL;
                #include <gdcc_helper.h>
                
                int gdcc_probe_helper_reexport(void) {
                    return (int)godot_new_int_with_int(42);
                }
                """);
        var helperProbe = compileObject(
                zig,
                helperSource,
                List.of("-I" + gdccIncludeDir),
                tempDir.resolve("gdcc_helper_ctor_probe.o")
        );
        assertEquals(0, helperProbe.exitCode(), helperProbe::diagnostic);
    }

    @Test
    void bindingLookupFailShouldReturnFalseAndRespectInternalReportFlag(@TempDir Path tempDir)
            throws IOException, InterruptedException {
        var zig = ZigUtil.findZig();
        Assumptions.assumeTrue(zig != null, "Zig executable is required for lookup failure runtime smoke");
        var source = tempDir.resolve("lookup_fail_probe.c");
        Files.writeString(source, """
                #include <godot_binding.h>
                
                int main(void) {
                    gdcc_binding_lookup_context default_context = {
                            .kind = "utility",
                            .function_name = "godot_utility_default",
                            .lookup_name = "default_lookup",
                            .owner = "Global",
                            .type = "void",
                    };
                    if (gdcc_binding_lookup_fail(&default_context)) {
                        return 10;
                    }
                
                    gdcc_binding_lookup_context suppressed_context = {
                            .kind = "utility",
                            .function_name = "godot_utility_suppressed",
                            .lookup_name = "suppressed_lookup",
                            .suppress_internal_error = true,
                    };
                    if (gdcc_binding_lookup_fail(&suppressed_context)) {
                        return 20;
                    }
                    return 0;
                }
                """);

        var probeObject = compileObject(zig, source, List.of(), tempDir.resolve("lookup_fail_probe.o"));
        assertEquals(0, probeObject.exitCode(), probeObject::diagnostic);
        var bindingObject = compileObject(
                zig,
                Path.of("src/main/c/codegen/include_451/godot/godot_binding.c"),
                List.of(),
                tempDir.resolve("godot_binding.o")
        );
        assertEquals(0, bindingObject.exitCode(), bindingObject::diagnostic);

        var executable = tempDir.resolve("lookup_fail_probe");
        var linked = linkExecutable(zig, List.of(probeObject.outputPath(), bindingObject.outputPath()), executable);
        assertEquals(0, linked.exitCode(), linked::diagnostic);

        var execution = runExecutable(executable);
        assertEquals(0, execution.exitCode(), execution::diagnostic);
        assertTrue(execution.output().contains("lookup=default_lookup"), execution::diagnostic);
        assertFalse(execution.output().contains("suppressed_lookup"), execution::diagnostic);
    }

    @Test
    void godotInitializeInterfaceShouldReturnFalseAndClearPartialPointersOnLookupMiss(@TempDir Path tempDir)
            throws IOException, InterruptedException {
        var zig = ZigUtil.findZig();
        Assumptions.assumeTrue(zig != null, "Zig executable is required for interface lookup failure runtime smoke");
        var source = tempDir.resolve("interface_lookup_fail_probe.c");
        Files.writeString(source, """
                #include <godot_binding.h>
                
                #include <string.h>
                
                static void gdcc_fake_print_error_with_message(
                        const char *p_description,
                        const char *p_message,
                        const char *p_function,
                        const char *p_file,
                        int32_t p_line,
                        GDExtensionBool p_editor_notify
                ) {
                    (void)p_description;
                    (void)p_message;
                    (void)p_function;
                    (void)p_file;
                    (void)p_line;
                    (void)p_editor_notify;
                }
                
                static void gdcc_fake_print_error(
                        const char *p_description,
                        const char *p_function,
                        const char *p_file,
                        int32_t p_line,
                        GDExtensionBool p_editor_notify
                ) {
                    (void)p_description;
                    (void)p_function;
                    (void)p_file;
                    (void)p_line;
                    (void)p_editor_notify;
                }
                
                static void gdcc_fake_unused_interface(void) {
                }
                
                static GDExtensionInterfaceFunctionPtr gdcc_fake_get_proc_address(const char *p_function_name) {
                    if (strcmp(p_function_name, "print_error_with_message") == 0) {
                        return (GDExtensionInterfaceFunctionPtr)gdcc_fake_print_error_with_message;
                    }
                    if (strcmp(p_function_name, "print_error") == 0) {
                        return (GDExtensionInterfaceFunctionPtr)gdcc_fake_print_error;
                    }
                    if (strcmp(p_function_name, "mem_realloc") == 0) {
                        return NULL;
                    }
                    return (GDExtensionInterfaceFunctionPtr)gdcc_fake_unused_interface;
                }
                
                int main(void) {
                    if (godot_initialize_interface(gdcc_fake_get_proc_address)) {
                        return 10;
                    }
                    if (gdcc_interface_get_godot_version != NULL || gdcc_interface_mem_alloc != NULL) {
                        return 20;
                    }
                    return 0;
                }
                """);

        var probeObject = compileObject(zig, source, List.of(), tempDir.resolve("interface_lookup_fail_probe.o"));
        assertEquals(0, probeObject.exitCode(), probeObject::diagnostic);
        var bindingObject = compileObject(
                zig,
                Path.of("src/main/c/codegen/include_451/godot/godot_binding.c"),
                List.of(),
                tempDir.resolve("godot_binding.o")
        );
        assertEquals(0, bindingObject.exitCode(), bindingObject::diagnostic);

        var executable = tempDir.resolve("interface_lookup_fail_probe");
        var linked = linkExecutable(zig, List.of(probeObject.outputPath(), bindingObject.outputPath()), executable);
        assertEquals(0, linked.exitCode(), linked::diagnostic);

        var execution = runExecutable(executable);
        assertEquals(0, execution.exitCode(), execution::diagnostic);
    }

    @Test
    void godotInterfaceInlineWrappersShouldShareOnePointerTableAcrossTranslationUnits(@TempDir Path tempDir)
            throws IOException, InterruptedException {
        var zig = ZigUtil.findZig();
        Assumptions.assumeTrue(zig != null, "Zig executable is required for godot_interface multi-TU smoke");
        var mainSource = tempDir.resolve("interface_probe_main.c");
        Files.writeString(mainSource, """
                #include <godot_binding.h>
                
                #include <stdint.h>
                #include <string.h>
                
                int gdcc_mem_alloc_calls = 0;
                int gdcc_variant_destroy_calls = 0;
                
                int gdcc_call_mem_alloc_from_other_tu(void);
                void gdcc_call_variant_destroy_from_other_tu(void);
                
                static void *gdcc_fake_mem_alloc(size_t p_bytes) {
                    gdcc_mem_alloc_calls += (int)p_bytes;
                    return (void *)(uintptr_t)0x1;
                }
                
                static void gdcc_fake_variant_destroy(GDExtensionVariantPtr p_self) {
                    if (p_self == NULL) {
                        gdcc_variant_destroy_calls++;
                    }
                }
                
                static void gdcc_fake_unused_interface(void) {
                }
                
                static GDExtensionInterfaceFunctionPtr gdcc_fake_get_proc_address(const char *p_function_name) {
                    if (strcmp(p_function_name, "mem_alloc") == 0) {
                        return (GDExtensionInterfaceFunctionPtr)gdcc_fake_mem_alloc;
                    }
                    if (strcmp(p_function_name, "variant_destroy") == 0) {
                        return (GDExtensionInterfaceFunctionPtr)gdcc_fake_variant_destroy;
                    }
                    return (GDExtensionInterfaceFunctionPtr)gdcc_fake_unused_interface;
                }
                
                int main(void) {
                    if (!godot_initialize_interface(gdcc_fake_get_proc_address)) {
                        return 10;
                    }
                    if (gdcc_call_mem_alloc_from_other_tu() != 8) {
                        return 20;
                    }
                    gdcc_call_variant_destroy_from_other_tu();
                    if (gdcc_mem_alloc_calls != 8 || gdcc_variant_destroy_calls != 1) {
                        return 30;
                    }
                    return 0;
                }
                """);
        var memAllocTu = tempDir.resolve("interface_probe_mem_alloc.c");
        Files.writeString(memAllocTu, """
                #include <godot_binding.h>
                
                extern int gdcc_mem_alloc_calls;
                
                int gdcc_call_mem_alloc_from_other_tu(void) {
                    void *result = godot_mem_alloc(8);
                    return result != NULL ? gdcc_mem_alloc_calls : -1;
                }
                """);
        var variantTu = tempDir.resolve("interface_probe_variant.c");
        Files.writeString(variantTu, """
                #include <godot_interface.h>
                
                void gdcc_call_variant_destroy_from_other_tu(void) {
                    godot_variant_destroy((GDExtensionVariantPtr)0);
                }
                """);

        var mainObject = compileObject(zig, mainSource, List.of(), tempDir.resolve("interface_probe_main.o"));
        assertEquals(0, mainObject.exitCode(), mainObject::diagnostic);
        var memAllocObject = compileObject(zig, memAllocTu, List.of(), tempDir.resolve("interface_probe_mem_alloc.o"));
        assertEquals(0, memAllocObject.exitCode(), memAllocObject::diagnostic);
        var variantObject = compileObject(zig, variantTu, List.of(), tempDir.resolve("interface_probe_variant.o"));
        assertEquals(0, variantObject.exitCode(), variantObject::diagnostic);
        var bindingObject = compileObject(
                zig,
                Path.of("src/main/c/codegen/include_451/godot/godot_binding.c"),
                List.of(),
                tempDir.resolve("godot_binding.o")
        );
        assertEquals(0, bindingObject.exitCode(), bindingObject::diagnostic);

        var executable = tempDir.resolve("interface_probe");
        var linked = linkExecutable(
                zig,
                List.of(mainObject.outputPath(), memAllocObject.outputPath(), variantObject.outputPath(), bindingObject.outputPath()),
                executable
        );
        assertEquals(0, linked.exitCode(), linked::diagnostic);

        var execution = runExecutable(executable);
        assertEquals(0, execution.exitCode(), execution::diagnostic);
    }

    @Test
    void gdccObjectIdHelpersShouldCompileAndFollowLivenessContract(@TempDir Path tempDir)
            throws IOException, InterruptedException {
        var zig = ZigUtil.findZig();
        Assumptions.assumeTrue(zig != null, "Zig executable is required for object ID helper behavior smoke");
        var source = tempDir.resolve("object_id_helper_probe.c");
        Files.writeString(source, """
                #include <godot_binding.h>
                static GDExtensionClassLibraryPtr class_library = NULL;
                #include <gdcc_helper.h>

                #include <stdint.h>
                #include <string.h>

                #define PROBE_LIVE_ID 42
                #define PROBE_FREED_ID 43
                #define PROBE_REF_ID (GDCC_OBJECT_ID_REFERENCE_BIT | 7)

                static int probe_object_db_calls = 0;

                static void probe_unused(void) {
                }

                static GDExtensionObjectPtr probe_object_get_instance_from_id(GDObjectInstanceID p_id) {
                    probe_object_db_calls++;
                    if (p_id == PROBE_FREED_ID) {
                        return NULL;
                    }
                    return (GDExtensionObjectPtr)(uintptr_t)0xa11ce;
                }

                static GDObjectInstanceID probe_object_get_instance_id(GDExtensionConstObjectPtr p_object) {
                    if (p_object == (GDExtensionConstObjectPtr)(uintptr_t)0xf4ee) {
                        return PROBE_FREED_ID;
                    }
                    return PROBE_LIVE_ID;
                }

                static GDExtensionInterfaceFunctionPtr probe_get_proc_address(const char *p_function_name) {
                    if (strcmp(p_function_name, "object_get_instance_from_id") == 0) {
                        return (GDExtensionInterfaceFunctionPtr)probe_object_get_instance_from_id;
                    }
                    if (strcmp(p_function_name, "object_get_instance_id") == 0) {
                        return (GDExtensionInterfaceFunctionPtr)probe_object_get_instance_id;
                    }
                    return (GDExtensionInterfaceFunctionPtr)probe_unused;
                }

                int main(void) {
                    if (!godot_initialize_interface(probe_get_proc_address)) {
                        return 10;
                    }

                    if (!gdcc_object_id_is_ref_counted(PROBE_REF_ID) || gdcc_object_id_is_ref_counted(PROBE_LIVE_ID)) {
                        return 20;
                    }

                    if (gdcc_object_live_ptr(0) != NULL) {
                        return 30;
                    }
                    if (gdcc_object_live_ptr(PROBE_FREED_ID) != NULL) {
                        return 31;
                    }
                    if (gdcc_object_live_ptr(PROBE_LIVE_ID) != (GDExtensionObjectPtr)(uintptr_t)0xa11ce) {
                        return 32;
                    }

                    const int db_calls_before_ref = probe_object_db_calls;
                    if (!gdcc_object_is_live(PROBE_REF_ID)) {
                        return 40;
                    }
                    if (probe_object_db_calls != db_calls_before_ref) {
                        return 41;
                    }
                    if (gdcc_object_is_live(0) || !gdcc_object_is_live(PROBE_LIVE_ID) || gdcc_object_is_live(PROBE_FREED_ID)) {
                        return 42;
                    }
                    if (!gdcc_object_is_null(0) || !gdcc_object_is_null(PROBE_FREED_ID) || gdcc_object_is_null(PROBE_LIVE_ID)) {
                        return 43;
                    }

                    if (!gdcc_object_live_ptrs_equal(NULL, NULL)) {
                        return 50;
                    }
                    if (gdcc_object_live_ptrs_equal((GDExtensionObjectPtr)(uintptr_t)0x1, NULL)) {
                        return 51;
                    }
                    if (!gdcc_object_live_ptrs_equal((GDExtensionObjectPtr)(uintptr_t)0x1, (GDExtensionObjectPtr)(uintptr_t)0x1)) {
                        return 52;
                    }

                    if (gdcc_object_id_from_raw(NULL) != 0) {
                        return 60;
                    }
                    if (gdcc_object_id_from_raw((GDExtensionObjectPtr)(uintptr_t)0x1) != PROBE_LIVE_ID) {
                        return 61;
                    }

                    if (!gdcc_object_is_null_raw_and_id(NULL, 0)) {
                        return 70;
                    }
                    if (!gdcc_object_is_null_raw_and_id((GDExtensionObjectPtr)(uintptr_t)0x1, 0)) {
                        return 71;
                    }
                    if (!gdcc_object_is_null_raw_and_id(NULL, PROBE_LIVE_ID)) {
                        return 72;
                    }
                    if (!gdcc_object_is_null_raw_and_id((GDExtensionObjectPtr)(uintptr_t)0xdead, PROBE_FREED_ID)) {
                        return 73;
                    }
                    if (gdcc_object_is_null_raw_and_id((GDExtensionObjectPtr)(uintptr_t)0xa11ce, PROBE_LIVE_ID)) {
                        return 74;
                    }
                    const int db_calls_before_ref_null = probe_object_db_calls;
                    if (gdcc_object_is_null_raw_and_id((GDExtensionObjectPtr)(uintptr_t)0x7efc, PROBE_REF_ID)) {
                        return 75;
                    }
                    if (probe_object_db_calls != db_calls_before_ref_null) {
                        return 76;
                    }

                    return 0;
                }
                """);

        var gdccIncludeDir = Path.of("src/main/c/codegen/include_451/gdcc").toAbsolutePath().normalize();
        var probeObject = compileObject(
                zig,
                source,
                List.of("-I" + gdccIncludeDir),
                tempDir.resolve("object_id_helper_probe.o")
        );
        assertEquals(0, probeObject.exitCode(), probeObject::diagnostic);
        var bindingObject = compileObject(
                zig,
                Path.of("src/main/c/codegen/include_451/godot/godot_binding.c"),
                List.of(),
                tempDir.resolve("godot_binding.o")
        );
        assertEquals(0, bindingObject.exitCode(), bindingObject::diagnostic);

        var executable = tempDir.resolve("object_id_helper_probe");
        var linked = linkExecutable(zig, List.of(probeObject.outputPath(), bindingObject.outputPath()), executable);
        assertEquals(0, linked.exitCode(), linked::diagnostic);

        var execution = runExecutable(executable);
        assertEquals(0, execution.exitCode(), execution::diagnostic);
    }

    private static CompileResult compileObject(
            Path zig,
            Path source,
            List<String> extraArgs,
            Path output
    ) throws IOException, InterruptedException {
        return compileObject(zig, "cc", "-std=c23", source, extraArgs, output);
    }

    private static CompileResult compileObject(
            Path zig,
            String compiler,
            String standard,
            Path source,
            List<String> extraArgs,
            Path output
    ) throws IOException, InterruptedException {
        var includeDir = Path.of("src/main/c/codegen/include_451/godot").toAbsolutePath().normalize();
        var command = new ArrayList<String>();
        command.add(zig.toString());
        command.add(compiler);
        command.add(standard);
        command.add("-I" + includeDir);
        command.addAll(extraArgs);
        command.add("-c");
        command.add(source.toString());
        command.add("-o");
        command.add(output.toString());

        var process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        var processOutput = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        var exitCode = process.waitFor();

        return new CompileResult(command, exitCode, processOutput, output);
    }

    private static CompileResult linkExecutable(
            Path zig,
            List<Path> objects,
            Path output
    ) throws IOException, InterruptedException {
        var command = new ArrayList<String>();
        command.add(zig.toString());
        command.add("cc");
        for (var object : objects) {
            command.add(object.toString());
        }
        command.add("-o");
        command.add(output.toString());

        var process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        var processOutput = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        var exitCode = process.waitFor();

        return new CompileResult(command, exitCode, processOutput, output);
    }

    private static CompileResult runExecutable(Path executable) throws IOException, InterruptedException {
        var command = List.of(executable.toString());
        var process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        var processOutput = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        var exitCode = process.waitFor();

        return new CompileResult(command, exitCode, processOutput, executable);
    }

    private static void assertUnsupported(CompileResult result, String expectedMessage) {
        assertNotEquals(0, result.exitCode(), result::diagnostic);
        assertTrue(result.output().contains(expectedMessage), result::diagnostic);
    }

    private record CompileResult(
            List<String> command,
            int exitCode,
            String output,
            Path outputPath
    ) {
        String diagnostic() {
            return String.join(" ", command) + "\n" + output;
        }
    }
}
