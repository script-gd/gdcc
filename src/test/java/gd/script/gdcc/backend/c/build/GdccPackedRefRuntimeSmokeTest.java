package gd.script.gdcc.backend.c.build;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Zig-gated tests for `gdcc/gdcc_packed_ref.h`, the Phase B runtime infrastructure of
/// `packed_array_reference_semantics_plan.md` (Variant-backed Packed*Array storage, plan §4.1).
///
/// Test shapes:
/// - a compile-only probe proving the header is self-contained (no other gdcc header needed);
/// - a happy-path runtime probe behind a fake Godot engine that models the engine identity
///   contract: Variant copy shares one heap array (holders counting), while every struct<->Variant
///   crossing produces a NEW array identity (the basis of the ptrcall exception, plan §1.3).
///   This lets the probes assert exactly which whitelisted conversion each helper performs —
///   e.g. aliasing must go through `variant_new_copy` and never through the struct copy ctor;
/// - two fail-fast probes (missing per-family getter at init, accessor used before init) that
///   must abort with an engine error message, run as separate processes so the abort is
///   observable as a non-zero exit code.
class GdccPackedRefRuntimeSmokeTest {
    private static final Path GODOT_INCLUDE_DIR = Path.of("src/main/c/codegen/include_451/godot").toAbsolutePath().normalize();
    private static final Path GDCC_INCLUDE_DIR = Path.of("src/main/c/codegen/include_451/gdcc").toAbsolutePath().normalize();

    @TempDir
    private static Path sharedDir;

    private static Path zig;
    private static List<Path> runtimeObjects;

    @BeforeAll
    static void compileRuntimeObjects() throws IOException, InterruptedException {
        zig = ZigUtil.findZig();
        Assumptions.assumeTrue(zig != null, "Zig executable is required for packed-ref runtime C smoke tests");
        runtimeObjects = List.of(
                compileObject(zig, GODOT_INCLUDE_DIR.resolve("godot_binding.c"), sharedDir.resolve("godot_binding.o"))
        );
    }

    @Test
    void headerShouldCompileStandalone() throws IOException, InterruptedException {
        // The header must be self-contained: only its own `#include <godot_binding.h>` is
        // allowed to pull in dependencies, so no class_library global or sibling gdcc header
        // may be required by the includer.
        var source = sharedDir.resolve("packed_ref_compile_probe.c");
        Files.writeString(source, """
                #include <gdcc_packed_ref.h>
                
                godot_Variant probe_compile(void) {
                    gdcc_packed_ref_init();
                    godot_Variant empty = gdcc_packed_int32_array_new_empty();
                    godot_Variant alias = gdcc_packed_ref_copy(&empty);
                    godot_bool same_family = gdcc_packed_ref_is(&alias, GDEXTENSION_VARIANT_TYPE_PACKED_INT32_ARRAY);
                    godot_PackedInt32Array *internal = gdcc_packed_int32_array_internal_ptr(&alias);
                    godot_Variant inbound = gdcc_packed_int32_array_variant_from_struct(internal);
                    godot_PackedInt32Array outbound = gdcc_packed_int32_array_struct_from_variant(&inbound);
                    godot_Variant wrapped = gdcc_packed_int32_array_wrap_temp(&outbound);
                    godot_Variant copied = gdcc_packed_int32_array_new_copy(&wrapped);
                    godot_Array arr = godot_new_Array();
                    godot_Variant from_array = gdcc_packed_vector4_array_new_from_array(&arr);
                    if (!same_family) {
                        return godot_new_Variant_nil();
                    }
                    gdcc_packed_ref_destroy(&from_array);
                    return copied;
                }
                """, StandardCharsets.UTF_8);
        // compileObject asserts a zero exit code internally, so a successful return proves the
        // header compiles standalone.
        compileObject(zig, source, sharedDir.resolve("packed_ref_compile_probe.o"));
    }

    @Test
    void helpersShouldProvideSharedIdentityAndWhitelistedConversions() throws IOException, InterruptedException {
        var execution = compileLinkAndRun("packed_ref_happy_probe", FAKE_ENGINE + HAPPY_PROBE, runtimeObjects);
        assertEquals(0, execution.exitCode(), execution::diagnostic);
        assertTrue(execution.output().contains("OK packed-ref happy path"), execution::diagnostic);
    }

    @Test
    void initShouldFailFastWhenInternalGetterMissing() throws IOException, InterruptedException {
        var execution = compileLinkAndRun("packed_ref_missing_getter_probe", FAKE_ENGINE + MISSING_GETTER_PROBE, runtimeObjects);
        assertNotEquals(0, execution.exitCode(), execution::diagnostic);
        assertTrue(execution.output().contains("ENGINE_ERROR"), execution::diagnostic);
        assertTrue(execution.output().contains("Vector4Array"), execution::diagnostic);
        // Death must happen inside the helper (abort), not by falling through to the probe tail:
        // a fail path that prints and merely returns would still reach the probe's own return 1.
        assertFalseMarker(execution, "FAIL init did not fail-fast");
    }

    @Test
    void internalPtrShouldFailFastWithoutInit() throws IOException, InterruptedException {
        var execution = compileLinkAndRun("packed_ref_no_init_probe", FAKE_ENGINE + NO_INIT_PROBE, runtimeObjects);
        assertNotEquals(0, execution.exitCode(), execution::diagnostic);
        assertTrue(execution.output().contains("before gdcc_packed_ref_init"), execution::diagnostic);
        assertFalseMarker(execution, "FAIL internal_ptr did not fail-fast");
    }

    @Test
    void internalPtrShouldFailFastOnNullVariant() throws IOException, InterruptedException {
        var execution = compileLinkAndRun("packed_ref_null_self_probe", FAKE_ENGINE + NULL_SELF_PROBE, runtimeObjects);
        assertNotEquals(0, execution.exitCode(), execution::diagnostic);
        assertTrue(execution.output().contains("NULL Variant"), execution::diagnostic);
        assertFalseMarker(execution, "FAIL null-self did not fail-fast");
    }

    @Test
    void internalPtrShouldFailFastWhenGetterReturnsNull() throws IOException, InterruptedException {
        var execution = compileLinkAndRun("packed_ref_null_internal_probe", FAKE_ENGINE + NULL_INTERNAL_PROBE, runtimeObjects);
        assertNotEquals(0, execution.exitCode(), execution::diagnostic);
        assertTrue(execution.output().contains("NULL internal pointer"), execution::diagnostic);
        assertFalseMarker(execution, "FAIL null-internal did not fail-fast");
    }

    @Test
    void initShouldFailFastBeforeInterfaceInit() throws IOException, InterruptedException {
        // Without godot_initialize_interface the interface pointer table is all NULL: init must
        // detect this and the fail path must fall back to stderr instead of calling a NULL
        // print_error pointer (stderr is merged into the captured output).
        var execution = compileLinkAndRun("packed_ref_no_interface_probe", FAKE_ENGINE + NO_INTERFACE_PROBE, runtimeObjects);
        assertNotEquals(0, execution.exitCode(), execution::diagnostic);
        assertTrue(execution.output().contains("variant_get_ptr_internal_getter interface unresolved"), execution::diagnostic);
        assertFalseMarker(execution, "FAIL init did not fail-fast");
    }

    @Test
    void internalPtrShouldRequireInitInEveryTranslationUnit() throws IOException, InterruptedException {
        // Getter caches are per-TU statics: a second TU that includes the header but never ran
        // gdcc_packed_ref_init must fail-fast even though the main TU initialized its own copy.
        var tu2Source = sharedDir.resolve("packed_ref_tu2.c");
        Files.writeString(tu2Source, """
                #include <gdcc_packed_ref.h>
                
                void tu2_use_internal_ptr(godot_Variant *value) {
                    (void)gdcc_packed_int32_array_internal_ptr(value);
                }
                """, StandardCharsets.UTF_8);
        var tu1Source = sharedDir.resolve("packed_ref_multi_tu_probe.c");
        Files.writeString(tu1Source, FAKE_ENGINE + MULTI_TU_PROBE, StandardCharsets.UTF_8);
        var tu1Object = compileObject(zig, tu1Source, sharedDir.resolve("packed_ref_multi_tu_probe.o"));
        var tu2Object = compileObject(zig, tu2Source, sharedDir.resolve("packed_ref_tu2.o"));
        var objects = new ArrayList<Path>();
        objects.add(tu1Object);
        objects.add(tu2Object);
        objects.addAll(runtimeObjects);
        var executable = sharedDir.resolve("packed_ref_multi_tu_probe");
        var linked = linkExecutable(zig, objects, executable);
        assertEquals(0, linked.exitCode(), linked::diagnostic);
        var execution = runExecutable(executable);
        assertNotEquals(0, execution.exitCode(), execution::diagnostic);
        assertTrue(execution.output().contains("before gdcc_packed_ref_init"), execution::diagnostic);
        assertFalseMarker(execution, "FAIL tu2 did not fail-fast");
    }

    private static void assertFalseMarker(CompileResult execution, String probeTailMarker) {
        assertFalse(execution.output().contains(probeTailMarker), execution::diagnostic);
    }

    // ---------------------------------------------------------------------------
    // Harness (mirrors GdccStaticStringRuntimeSmokeTest conventions).
    // ---------------------------------------------------------------------------

    private static CompileResult compileLinkAndRun(String probeName, String source, List<Path> extraObjects)
            throws IOException, InterruptedException {
        var probeSource = sharedDir.resolve(probeName + ".c");
        Files.writeString(probeSource, source, StandardCharsets.UTF_8);
        var probeObject = compileObject(zig, probeSource, sharedDir.resolve(probeName + ".o"));
        var objects = new ArrayList<Path>();
        objects.add(probeObject);
        objects.addAll(extraObjects);
        var executable = sharedDir.resolve(probeName);
        var linked = linkExecutable(zig, objects, executable);
        assertEquals(0, linked.exitCode(), linked::diagnostic);
        return runExecutable(executable);
    }

    private static Path compileObject(Path zig, Path source, Path output) throws IOException, InterruptedException {
        var command = new ArrayList<String>();
        command.add(zig.toString());
        command.add("cc");
        command.add("-std=c23");
        command.add("-I" + GODOT_INCLUDE_DIR);
        command.add("-I" + GDCC_INCLUDE_DIR);
        command.add("-c");
        command.add(source.toString());
        command.add("-o");
        command.add(output.toString());

        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        var processOutput = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        var exitCode = process.waitFor();
        assertEquals(0, exitCode, () -> String.join(" ", command) + "\n" + processOutput);
        return output;
    }

    private static CompileResult linkExecutable(Path zig, List<Path> objects, Path output) throws IOException, InterruptedException {
        var command = new ArrayList<String>();
        command.add(zig.toString());
        command.add("cc");
        for (var object : objects) {
            command.add(object.toString());
        }
        command.add("-o");
        command.add(output.toString());

        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        var processOutput = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        var exitCode = process.waitFor();
        return new CompileResult(command, exitCode, processOutput, output);
    }

    private static CompileResult runExecutable(Path executable) throws IOException, InterruptedException {
        var command = List.of(executable.toString());
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        var processOutput = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        var exitCode = process.waitFor();
        return new CompileResult(command, exitCode, processOutput, executable);
    }

    private record CompileResult(List<String> command, int exitCode, String output, Path outputPath) {
        String diagnostic() {
            return String.join(" ", command) + "\n" + output;
        }
    }

    /// Shared C fixture layer: a fake Godot engine modeling the Packed*Array identity contract.
    ///
    /// `godot_Variant` (24B) holds [0,8) type tag and [8,16) `FakePacked*` holder reference.
    /// Each `FakePacked` owns a 16-byte `self_slot` (the internal-pointer target, mirroring the
    /// engine's stable per-array storage) whose first 8 bytes hold the `FakePacked*` back-pointer
    /// every fake packed function uses to recover the object. Identity rules the probes rely on:
    /// - `variant_new_copy` shares the same FakePacked (holders++);
    /// - every struct<->Variant conversion (pack/unpack) and every packed copy ctor allocates a
    ///   NEW FakePacked with copied content, so mutation across that boundary stays invisible —
    ///   exactly what the plan's ptrcall exception and same-family copy semantics require.
    private static final String FAKE_ENGINE = """
            #include <godot_binding.h>
            #include <stddef.h>
            #include <stdint.h>
            #include <stdio.h>
            #include <stdlib.h>
            #include <string.h>
            
            static void fail(const char *msg) {
                printf("FAIL %s\\n", msg);
                fflush(stdout);
                exit(1);
            }
            #define CHECK(cond, msg) do { if (!(cond)) fail(msg); } while (0)
            
            static_assert(sizeof(godot_Variant) == 24, "fake Variant layout needs 24-byte Variant");
            static_assert(sizeof(godot_PackedInt32Array) == 16, "fake packed layout needs 16-byte struct");
            static_assert(sizeof(godot_PackedByteArray) == 16, "fake packed layout needs 16-byte struct");
            
            #define FAKE_POISON_UINT 0xEEEEEEEEEEEEEEEEull
            #define FAKE_MAX_TYPES 64
            
            typedef struct FakePacked {
                uint8_t self_slot[16];
                int64_t id;
                int64_t holders;
                int64_t elem_kind; // 0 = int32, 1 = byte
                int64_t elems[16];
                int64_t size;
            } FakePacked;
            
            typedef struct FakeArray {
                int64_t elems[8];
                int64_t size;
            } FakeArray;
            
            static int64_t g_next_packed_id = 1;
            static int64_t g_packed_live = 0;
            static int64_t g_mem_balance = 0;
            static int64_t g_struct_destroy_calls = 0;
            static int64_t g_ctor_calls[FAKE_MAX_TYPES][3];
            static int64_t g_getter_lookups[FAKE_MAX_TYPES];
            static int64_t g_pack_calls[FAKE_MAX_TYPES];
            static int64_t g_unpack_calls[FAKE_MAX_TYPES];
            static int64_t g_variant_copy_calls = 0;
            static int64_t g_variant_destroy_calls = 0;
            static int64_t g_print_error_calls = 0;
            static GDExtensionVariantType g_getter_disabled_for = GDEXTENSION_VARIANT_TYPE_NIL;
            
            static void *fake_mem_alloc(size_t bytes) {
                g_mem_balance++;
                return malloc(bytes == 0 ? 1 : bytes);
            }
            static void *fake_mem_realloc(void *ptr, size_t bytes) {
                if (ptr == NULL) g_mem_balance++;
                return realloc(ptr, bytes == 0 ? 1 : bytes);
            }
            static void fake_mem_free(void *ptr) {
                if (ptr != NULL) {
                    g_mem_balance--;
                    free(ptr);
                }
            }
            static void fake_print_error(const char *desc, const char *func, const char *file, int32_t line, GDExtensionBool notify) {
                (void)func; (void)file; (void)line; (void)notify;
                g_print_error_calls++;
                printf("ENGINE_ERROR %s\\n", desc != NULL ? desc : "");
                fflush(stdout);
            }
            
            static FakePacked *fake_packed_alloc(int64_t elem_kind) {
                FakePacked *fp = fake_mem_alloc(sizeof(FakePacked));
                memset(fp, 0, sizeof(*fp));
                fp->id = g_next_packed_id++;
                fp->holders = 1;
                fp->elem_kind = elem_kind;
                memcpy(fp->self_slot, &fp, sizeof(fp));
                g_packed_live++;
                return fp;
            }
            static void fake_packed_release(FakePacked *fp) {
                fp->holders--;
                if (fp->holders == 0) {
                    g_packed_live--;
                    fake_mem_free(fp);
                }
            }
            static FakePacked *fake_packed_read(GDExtensionConstTypePtr storage, const char *what) {
                FakePacked *fp;
                memcpy(&fp, storage, sizeof(fp));
                if ((uintptr_t)fp == FAKE_POISON_UINT) fail(what);
                return fp;
            }
            static void fake_packed_write(GDExtensionUninitializedTypePtr out, FakePacked *fp) {
                memset(out, 0, 16);
                memcpy(out, &fp, sizeof(fp));
            }
            static FakePacked *fake_packed_clone(FakePacked *src) {
                FakePacked *fp = fake_packed_alloc(src->elem_kind);
                fp->size = src->size;
                memcpy(fp->elems, src->elems, sizeof(fp->elems));
                return fp;
            }
            
            static void fake_variant_write(GDExtensionUninitializedVariantPtr out, int64_t type, FakePacked *ref) {
                memset(out, 0, sizeof(godot_Variant));
                memcpy(out, &type, 8);
                memcpy((char *)out + 8, &ref, 8);
            }
            static int64_t fake_variant_read_type(GDExtensionConstVariantPtr v) {
                int64_t type;
                memcpy(&type, v, 8);
                return type;
            }
            static FakePacked *fake_variant_read_ref(GDExtensionConstVariantPtr v) {
                FakePacked *ref;
                memcpy(&ref, (const char *)v + 8, 8);
                return ref;
            }
            
            static GDExtensionVariantType fake_variant_get_type(GDExtensionConstVariantPtr v) {
                return (GDExtensionVariantType)fake_variant_read_type(v);
            }
            static void fake_variant_new_copy(GDExtensionUninitializedVariantPtr out, GDExtensionConstVariantPtr src) {
                g_variant_copy_calls++;
                FakePacked *ref = fake_variant_read_ref(src);
                if (ref != NULL) ref->holders++;
                fake_variant_write(out, fake_variant_read_type(src), ref);
            }
            static void fake_variant_destroy(GDExtensionVariantPtr v) {
                g_variant_destroy_calls++;
                FakePacked *ref = fake_variant_read_ref(v);
                if (ref != NULL) fake_packed_release(ref);
                memset(v, 0xEE, sizeof(godot_Variant));
            }
            
            static void *fake_internal_ptr(GDExtensionVariantPtr v) {
                FakePacked *ref = fake_variant_read_ref(v);
                if (ref == NULL) return NULL;
                return ref->self_slot;
            }
            static GDExtensionVariantGetInternalPtrFunc fake_get_internal_getter(GDExtensionVariantType type) {
                g_getter_lookups[type]++;
                if (type == g_getter_disabled_for) return NULL;
                return fake_internal_ptr;
            }
            
            static void fake_packed_i32_empty_ctor(GDExtensionUninitializedTypePtr out, const GDExtensionConstTypePtr *args) {
                (void)args;
                fake_packed_write(out, fake_packed_alloc(0));
            }
            static void fake_packed_u8_empty_ctor(GDExtensionUninitializedTypePtr out, const GDExtensionConstTypePtr *args) {
                (void)args;
                fake_packed_write(out, fake_packed_alloc(1));
            }
            static void fake_packed_v4_empty_ctor(GDExtensionUninitializedTypePtr out, const GDExtensionConstTypePtr *args) {
                (void)args;
                fake_packed_write(out, fake_packed_alloc(2));
            }
            static void fake_packed_copy_ctor(GDExtensionUninitializedTypePtr out, const GDExtensionConstTypePtr *args) {
                fake_packed_write(out, fake_packed_clone(fake_packed_read(args[0], "copy ctor got destroyed packed")));
            }
            static void fake_packed_i32_from_array_ctor(GDExtensionUninitializedTypePtr out, const GDExtensionConstTypePtr *args) {
                FakeArray *arr;
                memcpy(&arr, args[0], sizeof(arr));
                FakePacked *fp = fake_packed_alloc(0);
                fp->size = arr->size;
                memcpy(fp->elems, arr->elems, sizeof(arr->elems));
                fake_packed_write(out, fp);
            }
            static GDExtensionPtrConstructor fake_get_ptr_constructor(GDExtensionVariantType type, int32_t index) {
                if (index < 0 || index > 2) fail("packed ctor index out of range");
                g_ctor_calls[type][index]++;
                if (type == GDEXTENSION_VARIANT_TYPE_PACKED_INT32_ARRAY) {
                    if (index == 0) return fake_packed_i32_empty_ctor;
                    if (index == 1) return fake_packed_copy_ctor;
                    return fake_packed_i32_from_array_ctor;
                }
                if (type == GDEXTENSION_VARIANT_TYPE_PACKED_BYTE_ARRAY && index <= 1) {
                    return index == 0 ? fake_packed_u8_empty_ctor : fake_packed_copy_ctor;
                }
                if (type == GDEXTENSION_VARIANT_TYPE_PACKED_VECTOR4_ARRAY && index == 0) {
                    return fake_packed_v4_empty_ctor;
                }
                fail("unexpected packed ctor lookup");
                return NULL;
            }
            static void fake_packed_dtor(GDExtensionTypePtr ptr) {
                g_struct_destroy_calls++;
                fake_packed_release(fake_packed_read(ptr, "double-destroyed packed struct"));
                memset(ptr, 0xEE, 16);
            }
            static GDExtensionPtrDestructor fake_get_ptr_destructor(GDExtensionVariantType type) {
                if (type == GDEXTENSION_VARIANT_TYPE_PACKED_INT32_ARRAY
                        || type == GDEXTENSION_VARIANT_TYPE_PACKED_BYTE_ARRAY
                        || type == GDEXTENSION_VARIANT_TYPE_PACKED_VECTOR4_ARRAY) {
                    return fake_packed_dtor;
                }
                fail("unexpected packed dtor lookup");
                return NULL;
            }
            
            static void fake_pack_i32(GDExtensionUninitializedVariantPtr out, GDExtensionTypePtr in) {
                FakePacked *src = fake_packed_read(in, "pack got destroyed packed");
                fake_variant_write(out, GDEXTENSION_VARIANT_TYPE_PACKED_INT32_ARRAY, fake_packed_clone(src));
            }
            static void fake_pack_u8(GDExtensionUninitializedVariantPtr out, GDExtensionTypePtr in) {
                FakePacked *src = fake_packed_read(in, "pack got destroyed packed");
                fake_variant_write(out, GDEXTENSION_VARIANT_TYPE_PACKED_BYTE_ARRAY, fake_packed_clone(src));
            }
            static void fake_pack_v4(GDExtensionUninitializedVariantPtr out, GDExtensionTypePtr in) {
                FakePacked *src = fake_packed_read(in, "pack got destroyed packed");
                fake_variant_write(out, GDEXTENSION_VARIANT_TYPE_PACKED_VECTOR4_ARRAY, fake_packed_clone(src));
            }
            static GDExtensionVariantFromTypeConstructorFunc fake_get_from_type(GDExtensionVariantType type) {
                g_pack_calls[type]++;
                if (type == GDEXTENSION_VARIANT_TYPE_PACKED_INT32_ARRAY) return fake_pack_i32;
                if (type == GDEXTENSION_VARIANT_TYPE_PACKED_BYTE_ARRAY) return fake_pack_u8;
                if (type == GDEXTENSION_VARIANT_TYPE_PACKED_VECTOR4_ARRAY) return fake_pack_v4;
                fail("unexpected from-type lookup");
                return NULL;
            }
            static void fake_unpack_i32(GDExtensionUninitializedTypePtr out, GDExtensionVariantPtr v) {
                if (fake_variant_read_type(v) != GDEXTENSION_VARIANT_TYPE_PACKED_INT32_ARRAY) {
                    fail("unpack type mismatch");
                }
                fake_packed_write(out, fake_packed_clone(fake_variant_read_ref(v)));
            }
            static GDExtensionTypeFromVariantConstructorFunc fake_get_to_type(GDExtensionVariantType type) {
                g_unpack_calls[type]++;
                if (type == GDEXTENSION_VARIANT_TYPE_PACKED_INT32_ARRAY) return fake_unpack_i32;
                fail("unexpected to-type lookup");
                return NULL;
            }
            
            static void fake_unused_interface(void) {
            }
            static GDExtensionInterfaceFunctionPtr fake_get_proc_address(const char *name) {
                if (strcmp(name, "mem_alloc") == 0) return (GDExtensionInterfaceFunctionPtr)fake_mem_alloc;
                if (strcmp(name, "mem_realloc") == 0) return (GDExtensionInterfaceFunctionPtr)fake_mem_realloc;
                if (strcmp(name, "mem_free") == 0) return (GDExtensionInterfaceFunctionPtr)fake_mem_free;
                if (strcmp(name, "print_error") == 0) return (GDExtensionInterfaceFunctionPtr)fake_print_error;
                if (strcmp(name, "variant_get_type") == 0) return (GDExtensionInterfaceFunctionPtr)fake_variant_get_type;
                if (strcmp(name, "variant_new_copy") == 0) return (GDExtensionInterfaceFunctionPtr)fake_variant_new_copy;
                if (strcmp(name, "variant_destroy") == 0) return (GDExtensionInterfaceFunctionPtr)fake_variant_destroy;
                if (strcmp(name, "variant_get_ptr_internal_getter") == 0) return (GDExtensionInterfaceFunctionPtr)fake_get_internal_getter;
                if (strcmp(name, "variant_get_ptr_constructor") == 0) return (GDExtensionInterfaceFunctionPtr)fake_get_ptr_constructor;
                if (strcmp(name, "variant_get_ptr_destructor") == 0) return (GDExtensionInterfaceFunctionPtr)fake_get_ptr_destructor;
                if (strcmp(name, "get_variant_from_type_constructor") == 0) return (GDExtensionInterfaceFunctionPtr)fake_get_from_type;
                if (strcmp(name, "get_variant_to_type_constructor") == 0) return (GDExtensionInterfaceFunctionPtr)fake_get_to_type;
                return (GDExtensionInterfaceFunctionPtr)fake_unused_interface;
            }
            
            #include <gdcc_packed_ref.h>
            
            // Probe-side accessors over internal pointers, standing in for generated code that
            // calls the typed builtin method wrappers (push_back/size/operator_index).
            static void probe_push(godot_PackedInt32Array *arr, int32_t value) {
                FakePacked *fp = fake_packed_read(arr, "push on destroyed packed");
                CHECK(fp->elem_kind == 0, "push on wrong elem kind");
                CHECK(fp->size < 16, "probe capacity exceeded");
                fp->elems[fp->size++] = value;
            }
            static godot_int probe_size_i32(const godot_PackedInt32Array *arr) {
                return fake_packed_read(arr, "size of destroyed packed")->size;
            }
            static godot_int probe_size_u8(const godot_PackedByteArray *arr) {
                return fake_packed_read(arr, "size of destroyed packed")->size;
            }
            static godot_int probe_size_v4(const godot_PackedVector4Array *arr) {
                return fake_packed_read(arr, "size of destroyed packed")->size;
            }
            static int32_t probe_get(const godot_PackedInt32Array *arr, godot_int index) {
                FakePacked *fp = fake_packed_read(arr, "get on destroyed packed");
                CHECK(index >= 0 && index < fp->size, "probe index out of range");
                return (int32_t)fp->elems[index];
            }
            static int64_t probe_id_of_struct(const godot_PackedInt32Array *arr) {
                return fake_packed_read(arr, "id of destroyed packed")->id;
            }
            static int64_t probe_id_of_variant(const godot_Variant *v) {
                return fake_variant_read_ref(v)->id;
            }
            """;

    /// Happy path: every Phase B helper exercised against the fake engine's identity contract,
    /// with exact invocation accounting (which ctor/copy/pack ran how many times) so each helper
    /// is pinned to its whitelisted conversion shape.
    private static final String HAPPY_PROBE = """
            static const GDExtensionVariantType ALL_PACKED_KINDS[10] = {
                GDEXTENSION_VARIANT_TYPE_PACKED_BYTE_ARRAY,
                GDEXTENSION_VARIANT_TYPE_PACKED_INT32_ARRAY,
                GDEXTENSION_VARIANT_TYPE_PACKED_INT64_ARRAY,
                GDEXTENSION_VARIANT_TYPE_PACKED_FLOAT32_ARRAY,
                GDEXTENSION_VARIANT_TYPE_PACKED_FLOAT64_ARRAY,
                GDEXTENSION_VARIANT_TYPE_PACKED_STRING_ARRAY,
                GDEXTENSION_VARIANT_TYPE_PACKED_VECTOR2_ARRAY,
                GDEXTENSION_VARIANT_TYPE_PACKED_VECTOR3_ARRAY,
                GDEXTENSION_VARIANT_TYPE_PACKED_COLOR_ARRAY,
                GDEXTENSION_VARIANT_TYPE_PACKED_VECTOR4_ARRAY,
            };
            
            int main(void) {
                CHECK(godot_initialize_interface(fake_get_proc_address) != 0, "interface init failed");
                gdcc_packed_ref_init();
                for (int i = 0; i < 10; i++) {
                    CHECK(g_getter_lookups[ALL_PACKED_KINDS[i]] == 1, "each family getter resolved exactly once");
                }
            
                // ---- new_empty (whitelist b): empty array Variant, temp struct destroyed ----
                godot_Variant empty = gdcc_packed_int32_array_new_empty();
                CHECK(g_ctor_calls[GDEXTENSION_VARIANT_TYPE_PACKED_INT32_ARRAY][0] == 1, "empty ctor not used");
                CHECK(g_pack_calls[GDEXTENSION_VARIANT_TYPE_PACKED_INT32_ARRAY] == 1, "empty not wrapped into Variant");
                CHECK(g_struct_destroy_calls == 1, "empty temp struct not destroyed exactly once");
                CHECK(g_packed_live == 1, "empty Variant must own exactly one packed storage");
                CHECK(gdcc_packed_ref_is(&empty, GDEXTENSION_VARIANT_TYPE_PACKED_INT32_ARRAY), "empty type mismatch");
                CHECK(!gdcc_packed_ref_is(&empty, GDEXTENSION_VARIANT_TYPE_PACKED_BYTE_ARRAY), "empty accepted wrong family");
                CHECK(probe_size_i32(gdcc_packed_int32_array_internal_ptr(&empty)) == 0, "empty not empty");
            
                // negative `is` cases: nil-typed storage and NULL pointer must both be false
                godot_Variant nil_v;
                memset(&nil_v, 0, sizeof(nil_v));
                CHECK(!gdcc_packed_ref_is(&nil_v, GDEXTENSION_VARIANT_TYPE_PACKED_INT32_ARRAY), "nil passed family test");
                CHECK(!gdcc_packed_ref_is(NULL, GDEXTENSION_VARIANT_TYPE_PACKED_INT32_ARRAY), "NULL passed family test");
            
                // ---- copy alias: identity shared via variant_new_copy ONLY (core invariant) ----
                godot_PackedInt32Array *empty_internal = gdcc_packed_int32_array_internal_ptr(&empty);
                probe_push(empty_internal, 11);
                probe_push(empty_internal, 22);
                godot_Variant alias = gdcc_packed_ref_copy(&empty);
                CHECK(g_variant_copy_calls == 1, "alias did not go through variant copy");
                CHECK(g_ctor_calls[GDEXTENSION_VARIANT_TYPE_PACKED_INT32_ARRAY][1] == 0,
                        "alias must NOT use the struct copy ctor");
                CHECK(probe_id_of_variant(&alias) == probe_id_of_variant(&empty), "alias identity not shared");
                probe_push(gdcc_packed_int32_array_internal_ptr(&alias), 33);
                CHECK(probe_size_i32(empty_internal) == 3 && probe_get(empty_internal, 2) == 33,
                        "mutation through alias not visible on original");
            
                // ---- variant_from_struct (whitelist a, ptrcall inbound): new identity, isolated ----
                godot_PackedInt32Array raw;
                fake_packed_write(&raw, fake_packed_alloc(0));
                probe_push(&raw, 5);
                probe_push(&raw, 6);
                godot_Variant materialized = gdcc_packed_int32_array_variant_from_struct(&raw);
                CHECK(probe_id_of_variant(&materialized) != probe_id_of_struct(&raw),
                        "inbound materialization must not share identity");
                CHECK(probe_size_i32(gdcc_packed_int32_array_internal_ptr(&materialized)) == 2, "inbound size wrong");
                probe_push(&raw, 7);
                CHECK(probe_size_i32(gdcc_packed_int32_array_internal_ptr(&materialized)) == 2,
                        "struct mutation leaked into materialized Variant");
                probe_push(gdcc_packed_int32_array_internal_ptr(&materialized), 8);
                CHECK(probe_size_i32(&raw) == 3, "materialized Variant mutation leaked into struct");
            
                // ---- struct_from_variant (whitelist a, ptrcall outbound): copy out, isolated ----
                godot_PackedInt32Array out = gdcc_packed_int32_array_struct_from_variant(&alias);
                CHECK(g_unpack_calls[GDEXTENSION_VARIANT_TYPE_PACKED_INT32_ARRAY] == 1, "outbound not via unpack");
                CHECK(probe_size_i32(&out) == 3 && probe_get(&out, 0) == 11 && probe_get(&out, 2) == 33,
                        "outbound content wrong");
                CHECK(probe_id_of_struct(&out) != probe_id_of_variant(&alias), "outbound copy must not share identity");
                probe_push(gdcc_packed_int32_array_internal_ptr(&alias), 44);
                CHECK(probe_size_i32(&out) == 3, "Variant mutation leaked into outbound struct");
                probe_push(&out, 55);
                CHECK(probe_size_i32(empty_internal) == 4, "outbound struct mutation leaked into Variant");
                godot_PackedInt32Array_destroy(&out);
            
                // ---- wrap_temp (whitelist c): builtin temp wrapped, temp destroyed once ----
                godot_PackedInt32Array temp;
                fake_packed_write(&temp, fake_packed_alloc(0));
                probe_push(&temp, 100);
                int64_t destroys_before_wrap = g_struct_destroy_calls;
                godot_Variant wrapped = gdcc_packed_int32_array_wrap_temp(&temp);
                CHECK(g_struct_destroy_calls == destroys_before_wrap + 1, "wrap_temp must destroy the temp exactly once");
                CHECK(gdcc_packed_ref_is(&wrapped, GDEXTENSION_VARIANT_TYPE_PACKED_INT32_ARRAY), "wrapped type mismatch");
                godot_PackedInt32Array *wrapped_internal = gdcc_packed_int32_array_internal_ptr(&wrapped);
                CHECK(probe_size_i32(wrapped_internal) == 1 && probe_get(wrapped_internal, 0) == 100, "wrapped content wrong");
            
                // ---- new_copy (whitelist d same-type; `as` same-family shape, plan §4.3.7) ----
                godot_Variant copied = gdcc_packed_int32_array_new_copy(&alias);
                CHECK(g_ctor_calls[GDEXTENSION_VARIANT_TYPE_PACKED_INT32_ARRAY][1] == 1,
                        "same-type copy must use the struct copy ctor on the internal pointer");
                CHECK(probe_id_of_variant(&copied) != probe_id_of_variant(&alias),
                        "same-type copy must produce a new identity");
                godot_PackedInt32Array *copied_internal = gdcc_packed_int32_array_internal_ptr(&copied);
                CHECK(probe_size_i32(copied_internal) == 4 && probe_get(copied_internal, 3) == 44, "copy content wrong");
                probe_push(gdcc_packed_int32_array_internal_ptr(&alias), 66);
                CHECK(probe_size_i32(copied_internal) == 4, "same-type copy not independent from source mutation");
            
                // ---- new_from_array (whitelist d cross-type) ----
                FakeArray fa = { .elems = { 7, 8, 9 }, .size = 3 };
                FakeArray *fa_ptr = &fa;
                godot_Array arr;
                memset(&arr, 0, sizeof(arr));
                memcpy(&arr, &fa_ptr, sizeof(fa_ptr));
                godot_Variant from_array = gdcc_packed_int32_array_new_from_array(&arr);
                CHECK(g_ctor_calls[GDEXTENSION_VARIANT_TYPE_PACKED_INT32_ARRAY][2] == 1, "from-Array ctor not used");
                godot_PackedInt32Array *from_array_internal = gdcc_packed_int32_array_internal_ptr(&from_array);
                CHECK(probe_size_i32(from_array_internal) == 3 && probe_get(from_array_internal, 0) == 7
                        && probe_get(from_array_internal, 2) == 9, "from-Array content wrong");
            
                // ---- second family smoke: macro instantiations stay independent ----
                godot_Variant bytes = gdcc_packed_byte_array_new_empty();
                CHECK(gdcc_packed_ref_is(&bytes, GDEXTENSION_VARIANT_TYPE_PACKED_BYTE_ARRAY), "byte empty type mismatch");
                CHECK(!gdcc_packed_ref_is(&bytes, GDEXTENSION_VARIANT_TYPE_PACKED_INT32_ARRAY), "byte accepted wrong family");
                CHECK(probe_size_u8(gdcc_packed_byte_array_internal_ptr(&bytes)) == 0, "byte empty not empty");
                godot_Variant bytes_alias = gdcc_packed_ref_copy(&bytes);
                CHECK(probe_id_of_variant(&bytes_alias) == probe_id_of_variant(&bytes), "byte alias identity not shared");
            
                // ---- third family (Vector4): non-integer family exercises its own instantiation ----
                godot_Variant v4 = gdcc_packed_vector4_array_new_empty();
                CHECK(gdcc_packed_ref_is(&v4, GDEXTENSION_VARIANT_TYPE_PACKED_VECTOR4_ARRAY), "vector4 empty type mismatch");
                CHECK(!gdcc_packed_ref_is(&v4, GDEXTENSION_VARIANT_TYPE_PACKED_INT32_ARRAY), "vector4 accepted wrong family");
                CHECK(probe_size_v4(gdcc_packed_vector4_array_internal_ptr(&v4)) == 0, "vector4 empty not empty");
                godot_Variant v4_alias = gdcc_packed_ref_copy(&v4);
                CHECK(probe_id_of_variant(&v4_alias) == probe_id_of_variant(&v4), "vector4 alias identity not shared");
            
                // ---- teardown: every holder released exactly once, nothing leaked, no errors ----
                gdcc_packed_ref_destroy(&empty);
                gdcc_packed_ref_destroy(&alias);
                gdcc_packed_ref_destroy(&materialized);
                gdcc_packed_ref_destroy(&wrapped);
                gdcc_packed_ref_destroy(&copied);
                gdcc_packed_ref_destroy(&from_array);
                gdcc_packed_ref_destroy(&bytes);
                gdcc_packed_ref_destroy(&bytes_alias);
                gdcc_packed_ref_destroy(&v4);
                gdcc_packed_ref_destroy(&v4_alias);
                godot_PackedInt32Array_destroy(&raw);
                CHECK(g_variant_destroy_calls == 10, "Variant destroy count wrong");
                CHECK(g_packed_live == 0, "packed storage leaked or double-released");
                CHECK(g_mem_balance == 0, "fake heap not balanced");
                CHECK(g_print_error_calls == 0, "happy path must never report engine errors");
                printf("OK packed-ref happy path\\n");
                return 0;
            }
            """;

    /// Missing-getter fail-fast: the fake engine refuses to expose the Vector4Array internal
    /// getter, so `gdcc_packed_ref_init` must print an engine error naming the family and abort.
    private static final String MISSING_GETTER_PROBE = """
            int main(void) {
                CHECK(godot_initialize_interface(fake_get_proc_address) != 0, "interface init failed");
                g_getter_disabled_for = GDEXTENSION_VARIANT_TYPE_PACKED_VECTOR4_ARRAY;
                gdcc_packed_ref_init();
                printf("FAIL init did not fail-fast\\n");
                return 1;
            }
            """;

    /// Uninitialized-access fail-fast: using an internal-pointer accessor before
    /// `gdcc_packed_ref_init()` ran must abort with a clear error instead of calling a NULL getter.
    private static final String NO_INIT_PROBE = """
            int main(void) {
                CHECK(godot_initialize_interface(fake_get_proc_address) != 0, "interface init failed");
                godot_Variant value = gdcc_packed_int32_array_new_empty();
                (void)gdcc_packed_int32_array_internal_ptr(&value);
                printf("FAIL internal_ptr did not fail-fast\\n");
                return 1;
            }
            """;

    /// NULL-self fail-fast: the accessor must reject a NULL Variant pointer outright.
    private static final String NULL_SELF_PROBE = """
            int main(void) {
                CHECK(godot_initialize_interface(fake_get_proc_address) != 0, "interface init failed");
                gdcc_packed_ref_init();
                (void)gdcc_packed_int32_array_internal_ptr(NULL);
                printf("FAIL null-self did not fail-fast\\n");
                return 1;
            }
            """;

    /// NULL-result backstop: against an engine whose getter does return NULL (the fake models a
    /// nil-backed Variant this way), the accessor must fail-fast instead of propagating NULL.
    /// A type-mismatched Variant remains caller-side UB and is deliberately NOT probed (plan §7.5).
    private static final String NULL_INTERNAL_PROBE = """
            int main(void) {
                CHECK(godot_initialize_interface(fake_get_proc_address) != 0, "interface init failed");
                gdcc_packed_ref_init();
                godot_Variant nil_v;
                memset(&nil_v, 0, sizeof(nil_v));
                (void)gdcc_packed_int32_array_internal_ptr(&nil_v);
                printf("FAIL null-internal did not fail-fast\\n");
                return 1;
            }
            """;

    /// Interface-not-ready fail-fast: init before `godot_initialize_interface` must detect the
    /// unresolved getter interface and report via the stderr fallback (print_error is NULL here).
    private static final String NO_INTERFACE_PROBE = """
            int main(void) {
                gdcc_packed_ref_init();
                printf("FAIL init did not fail-fast\\n");
                return 1;
            }
            """;

    /// Per-TU init contract: the main TU initializes its own getter caches, but the second TU
    /// (packed_ref_tu2.c) never does, so its accessor call must fail-fast inside that TU.
    private static final String MULTI_TU_PROBE = """
            void tu2_use_internal_ptr(godot_Variant *value);
            
            int main(void) {
                CHECK(godot_initialize_interface(fake_get_proc_address) != 0, "interface init failed");
                gdcc_packed_ref_init();
                godot_Variant value = gdcc_packed_int32_array_new_empty();
                tu2_use_internal_ptr(&value);
                printf("FAIL tu2 did not fail-fast\\n");
                return 1;
            }
            """;
}
