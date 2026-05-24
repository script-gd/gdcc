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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
                static GDExtensionInterfaceFunctionPtr gdcc_fake_get_proc_address(const char *p_function_name) {
                    (void)p_function_name;
                    return NULL;
                }
                void gdcc_probe_lookup_fail_context(void) {
                    const GDExtensionInt compatibility_hashes[] = {456, 789};
                    gdcc_binding_lookup_context context = {
                            .kind = "class_method_bind",
                            .function_name = "godot_Node_add_child",
                            .lookup_name = "add_child",
                            .owner = "Node",
                            .type = NULL,
                            .has_primary_hash = (GDExtensionBool)1,
                            .primary_hash = 123,
                            .compatibility_hashes = compatibility_hashes,
                            .compatibility_hash_count = 2,
                    };
                    gdcc_binding_lookup_fail(&context);
                }
                int gdcc_probe(void) {
                    GDExtensionBool initialized = godot_initialize_interface(gdcc_fake_get_proc_address);
                    GDExtensionVariantType type = godot_variant_get_type((GDExtensionConstVariantPtr)0);
                    (void)type;
                    return initialized != 0;
                }
                """);

        var headerProbe = compileObject(zig, source, List.of(), tempDir.resolve("godot_binding_probe.o"));
        assertEquals(0, headerProbe.exitCode(), headerProbe::diagnostic);

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

    private static CompileResult compileObject(
            Path zig,
            Path source,
            List<String> extraArgs,
            Path output
    ) throws IOException, InterruptedException {
        var includeDir = Path.of("src/main/c/codegen/include_451/godot").toAbsolutePath().normalize();
        var command = new ArrayList<String>();
        command.add(zig.toString());
        command.add("cc");
        command.add("-std=c23");
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

        return new CompileResult(command, exitCode, processOutput);
    }

    private static void assertUnsupported(CompileResult result, String expectedMessage) {
        assertNotEquals(0, result.exitCode(), result::diagnostic);
        assertTrue(result.output().contains(expectedMessage), result::diagnostic);
    }

    private record CompileResult(
            List<String> command,
            int exitCode,
            String output
    ) {
        String diagnostic() {
            return String.join(" ", command) + "\n" + output;
        }
    }
}
