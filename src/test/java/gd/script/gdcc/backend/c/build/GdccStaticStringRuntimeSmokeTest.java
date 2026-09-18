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
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Zig-gated pure-C smoke tests for generation-gated String/StringName reconstruction after
/// registry `destroy_all` (`gdcc/gdcc_string.h`, `gdcc/gdcc_string_name.h`; contracts:
/// `gdcc_runtime_lib.md` §Static String/StringName Registry and
/// `hot_reload_implementation.md` §4.1).
/// Each probe TU includes the two headers directly, making the probe itself the registry-owning TU
/// (mirroring the generated entry TU), and runs initialize -> destroy_all -> initialize within one
/// process — the same-image reload shape where function-local statics survive while the registry is
/// emptied. A fake Godot engine behind the GDExtension function-pointer table provides counting
/// String/StringName storage, so construction/destruction/hash invocations are asserted exactly;
/// destructors also poison the dead storage so a stuck generation gate would fail loudly instead of
/// silently passing. Skipped via assumption when no zig is on the machine.
class GdccStaticStringRuntimeSmokeTest {
    private static final Path GODOT_INCLUDE_DIR = Path.of("src/main/c/codegen/include_451/godot").toAbsolutePath().normalize();
    private static final Path GDCC_INCLUDE_DIR = Path.of("src/main/c/codegen/include_451/gdcc").toAbsolutePath().normalize();

    @TempDir
    private static Path sharedDir;

    private static Path zig;
    private static List<Path> runtimeObjects;

    @BeforeAll
    static void compileRuntimeObjects() throws IOException, InterruptedException {
        zig = ZigUtil.findZig();
        Assumptions.assumeTrue(zig != null, "Zig executable is required for static-string runtime C smoke tests");
        runtimeObjects = List.of(
                compileObject(zig, GODOT_INCLUDE_DIR.resolve("godot_binding.c"), sharedDir.resolve("godot_binding.o"))
        );
    }

    @Test
    void sameImageReinitShouldRebuildAndReregisterStatics() throws IOException, InterruptedException {
        var execution = compileLinkAndRun("same_image_reinit_probe", FAKE_ENGINE + MAIN_PROBE, runtimeObjects);
        assertEquals(0, execution.exitCode(), execution::diagnostic);
        assertTrue(execution.output().contains("OK same-image reinit"), execution::diagnostic);
    }

    @Test
    void destroyAllOnEmptyRegistryShouldStaySafeAndRearmStatics() throws IOException, InterruptedException {
        // Edge case of the generation design: destroy_all on a virgin registry must be a safe no-op
        // that still bumps the generation, and NEVER-stamped statics must initialize against ANY
        // generation afterwards (not just the fresh-image 0).
        var execution = compileLinkAndRun("empty_destroy_probe", FAKE_ENGINE + EMPTY_DESTROY_PROBE, runtimeObjects);
        assertEquals(0, execution.exitCode(), execution::diagnostic);
        assertTrue(execution.output().contains("OK empty-destroy rearm"), execution::diagnostic);
    }

    // ---------------------------------------------------------------------------
    // Harness (mirrors GdccHrxRuntimeSmokeTest conventions).
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

    private static Path compileObject(Path zig, Path source, Path output, String... extraFlags) throws IOException, InterruptedException {
        var command = new ArrayList<String>();
        command.add(zig.toString());
        command.add("cc");
        command.add("-std=c23");
        command.add("-D_DEFAULT_SOURCE");
        command.add("-I" + GODOT_INCLUDE_DIR);
        command.add("-I" + GDCC_INCLUDE_DIR);
        command.addAll(List.of(extraFlags));
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

    /// Shared C fixture layer: a fake Godot engine with counting String/StringName storage.
    /// godot_String / godot_StringName are 8-byte opaque payloads; the fake stores an owning
    /// FakeString pointer there. Destructors free the copy AND scribble a poison pattern into the
    /// caller storage, so any generation gate that wrongly hands a destroyed value to the new
    /// generation is caught by an explicit check instead of a flaky use-after-free read.
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

            #define FAKE_POISON_UINT 0xEEEEEEEEEEEEEEEEull

            typedef struct FakeString {
                char *utf8;
            } FakeString;

            static int g_mem_balance = 0;
            static uint64_t g_s_utf8_construct = 0;      // interface string_new_with_utf8_chars
            static uint64_t g_s_destruct = 0;            // String destructor
            static uint64_t g_sn_from_string_construct = 0; // StringName ctor #2 (from String): one per GD_STATIC_SN rebuild
            static uint64_t g_sn_raw_utf8_construct = 0;    // raw string_name_new_with_utf8_chars (builtin-method name lookups)
            static uint64_t g_sn_destruct = 0;           // StringName destructor
            static uint64_t g_sn_hash_invoke = 0;        // StringName.hash builtin invocations

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
                printf("FAIL engine print_error: %s\\n", desc != NULL ? desc : "");
                fflush(stdout);
                exit(1);
            }

            static FakeString *fake_string_new_owned(const char *text) {
                FakeString *fs = fake_mem_alloc(sizeof(FakeString));
                size_t len = strlen(text);
                fs->utf8 = fake_mem_alloc(len + 1);
                memcpy(fs->utf8, text, len + 1);
                return fs;
            }
            static void fake_string_release(FakeString *fs) {
                fake_mem_free(fs->utf8);
                fake_mem_free(fs);
            }
            static FakeString *fake_string_read(GDExtensionConstTypePtr storage, const char *what) {
                FakeString *fs;
                memcpy(&fs, storage, sizeof(fs));
                if ((uintptr_t)fs == FAKE_POISON_UINT) fail(what);
                return fs;
            }

            static void fake_string_new_with_utf8_chars(GDExtensionUninitializedStringPtr out, const char *text) {
                g_s_utf8_construct++;
                FakeString *fs = fake_string_new_owned(text);
                memcpy(out, &fs, sizeof(fs));
            }
            static void fake_string_name_new_with_utf8_chars(GDExtensionUninitializedStringNamePtr out, const char *text) {
                g_sn_raw_utf8_construct++;
                FakeString *fs = fake_string_new_owned(text);
                memcpy(out, &fs, sizeof(fs));
            }
            static void fake_string_ptr_destructor(GDExtensionTypePtr ptr) {
                g_s_destruct++;
                fake_string_release(fake_string_read(ptr, "double-destroyed String"));
                memset(ptr, 0xEE, sizeof(FakeString *));
            }
            static void fake_string_name_ptr_destructor(GDExtensionTypePtr ptr) {
                g_sn_destruct++;
                fake_string_release(fake_string_read(ptr, "double-destroyed StringName"));
                memset(ptr, 0xEE, sizeof(FakeString *));
            }

            static void fake_string_name_from_string_ctor(GDExtensionUninitializedTypePtr p_base, const GDExtensionConstTypePtr *p_args) {
                g_sn_from_string_construct++;
                FakeString *source = fake_string_read(p_args[0], "StringName ctor got destroyed String");
                FakeString *fs = fake_string_new_owned(source->utf8);
                memcpy(p_base, &fs, sizeof(fs));
            }
            static GDExtensionPtrConstructor fake_variant_get_ptr_constructor(GDExtensionVariantType type, int32_t ctor) {
                if (type == GDEXTENSION_VARIANT_TYPE_STRING_NAME && ctor == 2) return fake_string_name_from_string_ctor;
                fail("unexpected constructor lookup");
                return NULL;
            }
            static GDExtensionPtrDestructor fake_variant_get_ptr_destructor(GDExtensionVariantType type) {
                if (type == GDEXTENSION_VARIANT_TYPE_STRING) return fake_string_ptr_destructor;
                if (type == GDEXTENSION_VARIANT_TYPE_STRING_NAME) return fake_string_name_ptr_destructor;
                fail("unexpected destructor lookup");
                return NULL;
            }

            // Deterministic stand-in for StringName.hash so gen1/gen2 values are comparable.
            static godot_int fake_fnv1a(const char *text) {
                uint64_t hash = 1469598103934665603ull;
                for (const unsigned char *p = (const unsigned char *)text; *p != 0; p++) {
                    hash = (hash ^ *p) * 1099511628211ull;
                }
                return (godot_int)hash;
            }
            static void fake_string_name_hash_method(GDExtensionTypePtr p_base, const GDExtensionConstTypePtr *p_args, GDExtensionTypePtr r_return, int p_argument_count) {
                (void)p_args;
                if (p_argument_count != 0) fail("StringName.hash called with arguments");
                g_sn_hash_invoke++;
                FakeString *self = fake_string_read(p_base, "hash of destroyed StringName");
                *(godot_int *)r_return = fake_fnv1a(self->utf8);
            }
            static GDExtensionPtrBuiltInMethod fake_variant_get_ptr_builtin_method(GDExtensionVariantType type, GDExtensionConstStringNamePtr method, GDExtensionInt hash) {
                FakeString *name = fake_string_read(method, "builtin method lookup got destroyed StringName");
                if (type == GDEXTENSION_VARIANT_TYPE_STRING_NAME && strcmp(name->utf8, "hash") == 0 && hash == 3173160232LL) {
                    return fake_string_name_hash_method;
                }
                fail("unexpected builtin method lookup");
                return NULL;
            }

            static void fake_unused_interface(void) {
            }
            static GDExtensionInterfaceFunctionPtr fake_get_proc_address(const char *name) {
                if (strcmp(name, "mem_alloc") == 0) return (GDExtensionInterfaceFunctionPtr)fake_mem_alloc;
                if (strcmp(name, "mem_realloc") == 0) return (GDExtensionInterfaceFunctionPtr)fake_mem_realloc;
                if (strcmp(name, "mem_free") == 0) return (GDExtensionInterfaceFunctionPtr)fake_mem_free;
                if (strcmp(name, "print_error") == 0) return (GDExtensionInterfaceFunctionPtr)fake_print_error;
                if (strcmp(name, "string_new_with_utf8_chars") == 0) return (GDExtensionInterfaceFunctionPtr)fake_string_new_with_utf8_chars;
                if (strcmp(name, "string_name_new_with_utf8_chars") == 0) return (GDExtensionInterfaceFunctionPtr)fake_string_name_new_with_utf8_chars;
                if (strcmp(name, "variant_get_ptr_constructor") == 0) return (GDExtensionInterfaceFunctionPtr)fake_variant_get_ptr_constructor;
                if (strcmp(name, "variant_get_ptr_destructor") == 0) return (GDExtensionInterfaceFunctionPtr)fake_variant_get_ptr_destructor;
                if (strcmp(name, "variant_get_ptr_builtin_method") == 0) return (GDExtensionInterfaceFunctionPtr)fake_variant_get_ptr_builtin_method;
                return (GDExtensionInterfaceFunctionPtr)fake_unused_interface;
            }

            // The probes below act as the registry-owning TU (like the generated entry TU).
            #include <gdcc_string.h>
            #include <gdcc_string_name.h>

            static const char *fake_utf8_of_sn(const godot_StringName *sn) {
                return fake_string_read(sn, "stale destroyed StringName handed to new generation")->utf8;
            }
            static const char *fake_utf8_of_s(const godot_String *s) {
                return fake_string_read(s, "stale destroyed String handed to new generation")->utf8;
            }
            """;

    /// Full same-image reload cycle: generation 1 registration, in-generation re-entry (must NOT
    /// rebuild), destroy_all twice (idempotent, no double-free), then generation 2 registration,
    /// which must rebuild + re-register every static exactly once and hand back valid values.
    private static final String MAIN_PROBE = """
            typedef struct GenResult {
                const char *cls_utf8;
                const char *method_utf8;
                const char *msg_utf8;
                godot_int method_hash;
            } GenResult;

            // Mirrors the generated entry TU's registration path: each expansion site owns its
            // function-local statics, so calling this twice within one process reproduces the
            // same-image reload shape (statics keep their generation stamps across destroy_all).
            static GenResult run_registration_generation(void) {
                godot_StringName *cls = GD_STATIC_SN(u8"GDCCProbeClass");
                godot_StringName *method = GD_STATIC_SN(u8"_ready");
                godot_String *msg = GD_STATIC_S(u8"Loading probe...");
                gdcc_StringNameWithHash method_hashed = GD_STATIC_SN_HASH(u8"_ready");
                GenResult r;
                r.cls_utf8 = fake_utf8_of_sn(cls);
                r.method_utf8 = fake_utf8_of_sn(method);
                r.msg_utf8 = fake_utf8_of_s(msg);
                r.method_hash = method_hashed.hash;
                return r;
            }

            int main(void) {
                CHECK(godot_initialize_interface(fake_get_proc_address) != 0, "interface init failed");

                // ---------- generation 1 ----------
                GenResult g1 = run_registration_generation();
                CHECK(g_sn_registry.count == 3 && g_n_registry.count == 1, "gen1 registry population wrong");
                CHECK(g_sn_registry.generation == 0 && g_n_registry.generation == 0, "fresh image generation must be 0");
                CHECK(strcmp(g1.cls_utf8, "GDCCProbeClass") == 0, "gen1 class name wrong");
                CHECK(strcmp(g1.method_utf8, "_ready") == 0, "gen1 method name wrong");
                CHECK(strcmp(g1.msg_utf8, "Loading probe...") == 0, "gen1 message wrong");
                CHECK(g1.method_hash == fake_fnv1a("_ready"), "gen1 hash value wrong");
                CHECK(g_sn_hash_invoke == 1, "gen1 hash must be computed exactly once");
                const uint64_t gen1_sn_constructs = g_sn_from_string_construct;
                const uint64_t gen1_s_constructs = g_s_utf8_construct;
                CHECK(gen1_sn_constructs == 3, "gen1 StringName construction count wrong");
                // One GD_STATIC_S plus one temporary String per GD_STATIC_SN construction.
                CHECK(gen1_s_constructs == 4, "gen1 String construction count wrong");

                // Same-generation re-entry: the gate must hit the fast path, rebuilding nothing.
                GenResult g1b = run_registration_generation();
                CHECK(g_sn_from_string_construct == gen1_sn_constructs, "same-generation re-entry rebuilt StringName");
                CHECK(g_s_utf8_construct == gen1_s_constructs, "same-generation re-entry rebuilt String");
                CHECK(g_sn_hash_invoke == 1, "same-generation re-entry recomputed hash");
                CHECK(g_sn_registry.count == 3 && g_n_registry.count == 1, "same-generation re-entry double-registered");
                CHECK(g1b.method_hash == g1.method_hash, "same-generation hash changed");

                // ---------- deinitialize(): destroy_all, twice for idempotency ----------
                gdcc_sn_registry_destroy_all();
                gdcc_s_registry_destroy_all();
                CHECK(g_sn_registry.count == 0 && g_sn_registry.capacity == 0 && g_sn_registry.items == NULL,
                        "destroy_all must empty the StringName registry");
                CHECK(g_n_registry.count == 0 && g_n_registry.capacity == 0 && g_n_registry.items == NULL,
                        "destroy_all must empty the String registry");
                CHECK(g_sn_registry.generation == 1 && g_n_registry.generation == 1, "destroy_all must bump generation");
                // Every registered value destroyed exactly once. The resolved-once "hash" lookup
                // name was already destroyed by the resolver itself during gen1, hence the +raw term.
                CHECK(g_sn_destruct == gen1_sn_constructs + g_sn_raw_utf8_construct,
                        "gen1 StringNames not destroyed exactly once");
                const uint64_t destructs_after_first_destroy = g_sn_destruct + g_s_destruct;
                const int balance_after_first_destroy = g_mem_balance;
                gdcc_sn_registry_destroy_all();
                gdcc_s_registry_destroy_all();
                CHECK(g_sn_destruct + g_s_destruct == destructs_after_first_destroy, "second destroy_all double-freed");
                CHECK(g_mem_balance == balance_after_first_destroy, "second destroy_all moved the memory balance");
                CHECK(g_sn_registry.generation == 2 && g_n_registry.generation == 2, "second destroy_all must still bump generation");

                // ---------- generation 2 (same image: stamps survived) ----------
                GenResult g2 = run_registration_generation();
                CHECK(g_sn_from_string_construct == 2 * gen1_sn_constructs, "gen2 StringName rebuild count wrong");
                CHECK(g_s_utf8_construct == 2 * gen1_s_constructs, "gen2 String rebuild count wrong");
                CHECK(g_sn_registry.count == 3 && g_n_registry.count == 1, "gen2 registry re-registration incomplete");
                 // Generation 2 must expose valid content after generation 1 destroyed its storage.
                CHECK(strcmp(g2.cls_utf8, "GDCCProbeClass") == 0, "gen2 class name invalid");
                CHECK(strcmp(g2.method_utf8, "_ready") == 0, "gen2 method name invalid");
                CHECK(strcmp(g2.msg_utf8, "Loading probe...") == 0, "gen2 message invalid");
                CHECK(g_sn_hash_invoke == 2, "gen2 hash must be recomputed, not served from the stale cache");
                CHECK(g2.method_hash == g1.method_hash, "gen2 hash diverged from gen1");

                // ---------- final teardown: exactly-once destruction, zero leaks ----------
                gdcc_sn_registry_destroy_all();
                gdcc_s_registry_destroy_all();
                CHECK(g_sn_destruct == g_sn_from_string_construct + g_sn_raw_utf8_construct,
                        "every StringName must be destroyed exactly once");
                CHECK(g_s_destruct == g_s_utf8_construct, "every String must be destroyed exactly once");
                CHECK(g_mem_balance == 0, "leaked allocations");
                printf("OK same-image reinit\\n");
                return 0;
            }
            """;

    /// destroy_all on a virgin registry must be a safe no-op that still bumps the generation, and
    /// NEVER-stamped expansion sites must initialize against ANY current generation afterwards.
    private static final String EMPTY_DESTROY_PROBE = """
            int main(void) {
                CHECK(godot_initialize_interface(fake_get_proc_address) != 0, "interface init failed");
                gdcc_sn_registry_destroy_all();
                gdcc_s_registry_destroy_all();
                gdcc_sn_registry_destroy_all();
                gdcc_s_registry_destroy_all();
                CHECK(g_sn_destruct == 0 && g_s_destruct == 0, "empty destroy_all destroyed something");
                CHECK(g_mem_balance == 0, "empty destroy_all moved the memory balance");
                CHECK(g_sn_registry.generation == 2 && g_n_registry.generation == 2, "empty destroy_all must bump generation");

                godot_StringName *name = GD_STATIC_SN(u8"LateClass");
                godot_String *text = GD_STATIC_S(u8"late");
                CHECK(g_sn_registry.count == 1 && g_n_registry.count == 1, "late registration failed");
                CHECK(g_sn_from_string_construct == 1 && g_s_utf8_construct == 2, "late construction count wrong");
                CHECK(strcmp(fake_utf8_of_sn(name), "LateClass") == 0, "late StringName invalid");
                CHECK(strcmp(fake_utf8_of_s(text), "late") == 0, "late String invalid");

                gdcc_sn_registry_destroy_all();
                gdcc_s_registry_destroy_all();
                CHECK(g_sn_destruct == g_sn_from_string_construct && g_s_destruct == g_s_utf8_construct,
                        "late values not destroyed exactly once");
                CHECK(g_mem_balance == 0, "leaked allocations");
                printf("OK empty-destroy rearm\\n");
                return 0;
            }
            """;
}
