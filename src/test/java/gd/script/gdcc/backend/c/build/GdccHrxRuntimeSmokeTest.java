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

/// Zig-gated pure-C smoke tests for the HRX hot-reload thunk runtime
/// (`gdcc/gdcc_hrx.c`; contract: doc/module_impl/backend/hot_reload_implementation_plan.md §5).
/// The fixtures fake the Godot interface at the GDExtension function-pointer level while the
/// shared thunk page uses REAL executable memory on the host, so on x86_64 the emitted machine
/// code actually executes: call/is_valid/free/get_argument_count all run through real thunks,
/// and "reload" is simulated by deinitialize -> initialize_core cycles with new rebind tables.
/// Skipped via assumption when no zig is on the machine.
class GdccHrxRuntimeSmokeTest {
    private static final Path GODOT_INCLUDE_DIR = Path.of("src/main/c/codegen/include_451/godot").toAbsolutePath().normalize();
    private static final Path GDCC_INCLUDE_DIR = Path.of("src/main/c/codegen/include_451/gdcc").toAbsolutePath().normalize();

    @TempDir
    private static Path sharedDir;

    private static Path zig;
    private static List<Path> runtimeObjects;

    @BeforeAll
    static void compileRuntimeObjects() throws IOException, InterruptedException {
        zig = ZigUtil.findZig();
        Assumptions.assumeTrue(zig != null, "Zig executable is required for HRX runtime C smoke tests");
        runtimeObjects = List.of(
                compileObject(zig, GODOT_INCLUDE_DIR.resolve("godot_binding.c"), sharedDir.resolve("godot_binding.o")),
                // Test hooks enable the image-state reset seam used to simulate fresh DSO images.
                compileObject(zig, GDCC_INCLUDE_DIR.resolve("gdcc_hrx.c"), sharedDir.resolve("gdcc_hrx.o"),
                        "-DGDCC_HRX_TEST_HOOKS")
        );
    }

    @Test
    void thunkTemplatesShouldMatchFrozenBytes() throws IOException, InterruptedException {
        // ABI freeze anchor: the hand-assembled templates need ZERO runtime patching (the
        // spec arrives via the callback's first argument, the hub via `spec->hub`); every
        // byte is pinned exactly, so any silent drift fails here. All three architecture
        // variants are data on every host, so they are pinned everywhere (execution of the
        // host variant is covered by the other probes).
        var source = """
                #include <gdcc_hrx.h>
                #include <stdio.h>
                
                typedef struct ExpectedTemplate {
                    gdcc_hrx_thunk_arch arch;
                    gdcc_hrx_thunk_kind kind;
                    const unsigned char *bytes;
                    unsigned int size;
                } ExpectedTemplate;
                
                static const unsigned char EXP_SYSV_IV[] = {
                    0x53, 0x48, 0x89, 0xFB, 0x48, 0x83, 0x3B, 0x00, 0x74, 0x1E, 
                    0x83, 0x7B, 0x20, 0x00, 0x75, 0x18, 0x48, 0x8B, 0x43, 0x10, 
                    0x48, 0x85, 0xC0, 0x74, 0x08, 0x48, 0x8B, 0x7B, 0x18, 0xFF, 
                    0xD0, 0x5B, 0xC3, 0xB8, 0x01, 0x00, 0x00, 0x00, 0x5B, 0xC3, 
                    0x31, 0xC0, 0x5B, 0xC3, 
                };

                static const unsigned char EXP_SYSV_FR[] = {
                    0x49, 0x89, 0xFA, 0x41, 0x8B, 0x4A, 0x24, 0x85, 0xC9, 0x74, 
                    0x24, 0xFF, 0xC9, 0x41, 0x89, 0x4A, 0x24, 0x85, 0xC9, 0x75, 
                    0x1A, 0x41, 0xC7, 0x42, 0x20, 0x01, 0x00, 0x00, 0x00, 0x49, 
                    0x8B, 0x42, 0x30, 0x48, 0x8B, 0x40, 0x28, 0x48, 0x85, 0xC0, 
                    0x74, 0x05, 0x4C, 0x89, 0xD7, 0xFF, 0xE0, 0xC3, 
                };

                static const unsigned char EXP_SYSV_CA[] = {
                    0x49, 0x89, 0xFA, 0x49, 0x83, 0x3A, 0x00, 0x74, 0x10, 0x41, 
                    0x83, 0x7A, 0x20, 0x00, 0x75, 0x09, 0x49, 0x8B, 0x02, 0x49, 
                    0x8B, 0x7A, 0x18, 0xFF, 0xE0, 0x4D, 0x85, 0xC0, 0x74, 0x17, 
                    0x41, 0xC7, 0x00, 0x01, 0x00, 0x00, 0x00, 0x41, 0xC7, 0x40, 
                    0x04, 0x00, 0x00, 0x00, 0x00, 0x41, 0xC7, 0x40, 0x08, 0x00, 
                    0x00, 0x00, 0x00, 0xC3, 
                };

                static const unsigned char EXP_SYSV_AC[] = {
                    0x49, 0x89, 0xFA, 0x48, 0x85, 0xF6, 0x74, 0x15, 0x49, 0x83, 
                    0x3A, 0x00, 0x74, 0x0C, 0x41, 0x83, 0x7A, 0x20, 0x00, 0x75, 
                    0x05, 0xC6, 0x06, 0x01, 0xEB, 0x03, 0xC6, 0x06, 0x00, 0x49, 
                    0x63, 0x42, 0x28, 0xC3, 
                };

                static const unsigned char EXP_WIN_IV[] = {
                    0x53, 0x48, 0x89, 0xCB, 0x48, 0x83, 0x3B, 0x00, 0x74, 0x26, 
                    0x83, 0x7B, 0x20, 0x00, 0x75, 0x20, 0x48, 0x8B, 0x43, 0x10, 
                    0x48, 0x85, 0xC0, 0x74, 0x10, 0x48, 0x8B, 0x4B, 0x18, 0x48, 
                    0x83, 0xEC, 0x20, 0xFF, 0xD0, 0x48, 0x83, 0xC4, 0x20, 0x5B, 
                    0xC3, 0xB8, 0x01, 0x00, 0x00, 0x00, 0x5B, 0xC3, 0x31, 0xC0, 
                    0x5B, 0xC3, 
                };

                static const unsigned char EXP_WIN_FR[] = {
                    0x49, 0x89, 0xCA, 0x41, 0x8B, 0x4A, 0x24, 0x85, 0xC9, 0x74, 
                    0x24, 0xFF, 0xC9, 0x41, 0x89, 0x4A, 0x24, 0x85, 0xC9, 0x75, 
                    0x1A, 0x41, 0xC7, 0x42, 0x20, 0x01, 0x00, 0x00, 0x00, 0x49, 
                    0x8B, 0x42, 0x30, 0x48, 0x8B, 0x40, 0x28, 0x48, 0x85, 0xC0, 
                    0x74, 0x05, 0x4C, 0x89, 0xD1, 0xFF, 0xE0, 0xC3, 
                };

                static const unsigned char EXP_WIN_CA[] = {
                    0x49, 0x89, 0xCA, 0x49, 0x83, 0x3A, 0x00, 0x74, 0x10, 0x41, 
                    0x83, 0x7A, 0x20, 0x00, 0x75, 0x09, 0x49, 0x8B, 0x02, 0x49, 
                    0x8B, 0x4A, 0x18, 0xFF, 0xE0, 0x4C, 0x8B, 0x5C, 0x24, 0x28, 
                    0x4D, 0x85, 0xDB, 0x74, 0x17, 0x41, 0xC7, 0x03, 0x01, 0x00, 
                    0x00, 0x00, 0x41, 0xC7, 0x43, 0x04, 0x00, 0x00, 0x00, 0x00, 
                    0x41, 0xC7, 0x43, 0x08, 0x00, 0x00, 0x00, 0x00, 0xC3, 
                };

                static const unsigned char EXP_WIN_AC[] = {
                    0x49, 0x89, 0xCA, 0x48, 0x85, 0xD2, 0x74, 0x15, 0x49, 0x83, 
                    0x3A, 0x00, 0x74, 0x0C, 0x41, 0x83, 0x7A, 0x20, 0x00, 0x75, 
                    0x05, 0xC6, 0x02, 0x01, 0xEB, 0x03, 0xC6, 0x02, 0x00, 0x49, 
                    0x63, 0x42, 0x28, 0xC3, 
                };

                static const unsigned char EXP_ARM_IV[] = {
                    0xE9, 0x03, 0x00, 0xAA, 0x2A, 0x01, 0x40, 0xF9, 0x8A, 0x01, 
                    0x00, 0xB4, 0x2B, 0x21, 0x40, 0xB9, 0x4B, 0x01, 0x00, 0x35, 
                    0x2A, 0x09, 0x40, 0xF9, 0xCA, 0x00, 0x00, 0xB4, 0xFD, 0x7B, 
                    0xBF, 0xA9, 0x20, 0x0D, 0x40, 0xF9, 0x40, 0x01, 0x3F, 0xD6, 
                    0xFD, 0x7B, 0xC1, 0xA8, 0xC0, 0x03, 0x5F, 0xD6, 0x20, 0x00, 
                    0x80, 0x52, 0xC0, 0x03, 0x5F, 0xD6, 0xE0, 0x03, 0x1F, 0x2A, 
                    0xC0, 0x03, 0x5F, 0xD6, 
                };

                static const unsigned char EXP_ARM_FR[] = {
                    0xE9, 0x03, 0x00, 0xAA, 0x2A, 0x25, 0x40, 0xB9, 0x6A, 0x01, 
                    0x00, 0x34, 0x4A, 0x05, 0x00, 0x51, 0x2A, 0x25, 0x00, 0xB9, 
                    0x0A, 0x01, 0x00, 0x35, 0x2B, 0x00, 0x80, 0x52, 0x2B, 0x21, 
                    0x00, 0xB9, 0x2A, 0x19, 0x40, 0xF9, 0x4A, 0x15, 0x40, 0xF9, 
                    0x6A, 0x00, 0x00, 0xB4, 0xE0, 0x03, 0x09, 0xAA, 0x40, 0x01, 
                    0x1F, 0xD6, 0xC0, 0x03, 0x5F, 0xD6, 
                };

                static const unsigned char EXP_ARM_CA[] = {
                    0xE9, 0x03, 0x00, 0xAA, 0x2A, 0x01, 0x40, 0xF9, 0xAA, 0x00, 
                    0x00, 0xB4, 0x2B, 0x21, 0x40, 0xB9, 0x6B, 0x00, 0x00, 0x35, 
                    0x20, 0x0D, 0x40, 0xF9, 0x40, 0x01, 0x1F, 0xD6, 0xA4, 0x00, 
                    0x00, 0xB4, 0x2C, 0x00, 0x80, 0x52, 0x8C, 0x00, 0x00, 0xB9, 
                    0x9F, 0x04, 0x00, 0xB9, 0x9F, 0x08, 0x00, 0xB9, 0xC0, 0x03, 
                    0x5F, 0xD6, 0x1F, 0x20, 0x03, 0xD5, 
                };

                static const unsigned char EXP_ARM_AC[] = {
                    0xE9, 0x03, 0x00, 0xAA, 0x21, 0x01, 0x00, 0xB4, 0x2A, 0x01, 
                    0x40, 0xF9, 0xCA, 0x00, 0x00, 0xB4, 0x2B, 0x21, 0x40, 0xB9, 
                    0x8B, 0x00, 0x00, 0x35, 0x2C, 0x00, 0x80, 0x52, 0x2C, 0x00, 
                    0x00, 0x39, 0x02, 0x00, 0x00, 0x14, 0x3F, 0x00, 0x00, 0x39, 
                    0x20, 0x29, 0x80, 0xB9, 0xC0, 0x03, 0x5F, 0xD6, 
                };
                
                int main(void) {
                    const ExpectedTemplate expected[] = {
                        { GDCC_HRX_ARCH_X86_64_SYSV, GDCC_HRX_THUNK_IS_VALID, EXP_SYSV_IV, sizeof(EXP_SYSV_IV) },
                        { GDCC_HRX_ARCH_X86_64_SYSV, GDCC_HRX_THUNK_FREE, EXP_SYSV_FR, sizeof(EXP_SYSV_FR) },
                        { GDCC_HRX_ARCH_X86_64_SYSV, GDCC_HRX_THUNK_CALL, EXP_SYSV_CA, sizeof(EXP_SYSV_CA) },
                        { GDCC_HRX_ARCH_X86_64_SYSV, GDCC_HRX_THUNK_GET_ARGUMENT_COUNT, EXP_SYSV_AC, sizeof(EXP_SYSV_AC) },
                        { GDCC_HRX_ARCH_X86_64_WIN64, GDCC_HRX_THUNK_IS_VALID, EXP_WIN_IV, sizeof(EXP_WIN_IV) },
                        { GDCC_HRX_ARCH_X86_64_WIN64, GDCC_HRX_THUNK_FREE, EXP_WIN_FR, sizeof(EXP_WIN_FR) },
                        { GDCC_HRX_ARCH_X86_64_WIN64, GDCC_HRX_THUNK_CALL, EXP_WIN_CA, sizeof(EXP_WIN_CA) },
                        { GDCC_HRX_ARCH_X86_64_WIN64, GDCC_HRX_THUNK_GET_ARGUMENT_COUNT, EXP_WIN_AC, sizeof(EXP_WIN_AC) },
                        { GDCC_HRX_ARCH_AARCH64, GDCC_HRX_THUNK_IS_VALID, EXP_ARM_IV, sizeof(EXP_ARM_IV) },
                        { GDCC_HRX_ARCH_AARCH64, GDCC_HRX_THUNK_FREE, EXP_ARM_FR, sizeof(EXP_ARM_FR) },
                        { GDCC_HRX_ARCH_AARCH64, GDCC_HRX_THUNK_CALL, EXP_ARM_CA, sizeof(EXP_ARM_CA) },
                        { GDCC_HRX_ARCH_AARCH64, GDCC_HRX_THUNK_GET_ARGUMENT_COUNT, EXP_ARM_AC, sizeof(EXP_ARM_AC) },
                    };
                    for (unsigned i = 0; i < sizeof(expected) / sizeof(expected[0]); i++) {
                        const ExpectedTemplate *exp = &expected[i];
                        const gdcc_hrx_thunk_template *tpl = gdcc_hrx_thunk_template_get_for_arch(exp->arch, exp->kind);
                        if (tpl == NULL) { printf("FAIL null template %u/%u\\n", exp->arch, exp->kind); return 1; }
                        if (tpl->size != exp->size) {
                            printf("FAIL template %u/%u size %u != %u\\n", exp->arch, exp->kind, tpl->size, exp->size);
                            return 1;
                        }
                        for (unsigned b = 0; b < exp->size; b++) {
                            if (exp->bytes[b] != tpl->bytes[b]) {
                                printf("FAIL template %u/%u byte %u: %02X != %02X\\n",
                                        exp->arch, exp->kind, b, exp->bytes[b], tpl->bytes[b]);
                                return 1;
                            }
                        }
                    }
                    // Shared thunk-page role layout: SysV {0,44,92,146,180}, Win64 {0,52,100,159,193},
                    // arm64 {0,64,120,176,224} (arm64 bundle starts stay 8-aligned). Each offset
                    // must ALSO equal the cumulative template sizes (no silent role overlap).
                    const unsigned bundle[GDCC_HRX_ARCH_COUNT][GDCC_HRX_THUNK_KIND_COUNT + 1] = {
                        { 0, 44, 92, 146, 180 }, { 0, 52, 100, 159, 193 }, { 0, 64, 120, 176, 224 }
                    };
                    for (int a = 0; a < GDCC_HRX_ARCH_COUNT; a++) {
                        unsigned acc = 0;
                        for (int k = 0; k < GDCC_HRX_THUNK_KIND_COUNT; k++) {
                            if (gdcc_hrx_thunk_bundle_offset_for_arch(a, k) != bundle[a][k]
                                    || gdcc_hrx_thunk_bundle_offset_for_arch(a, k) != acc) {
                                printf("FAIL bundle offset %d/%d\\n", a, k);
                                return 1;
                            }
                            acc += gdcc_hrx_thunk_template_get_for_arch(a, k)->size;
                        }
                        if (acc != bundle[a][GDCC_HRX_THUNK_KIND_COUNT]) {
                            printf("FAIL bundle end %d\\n", a);
                            return 1;
                        }
                    }
                    printf("OK templates\\n");
                    return 0;
                }
                """;
        var execution = compileLinkAndRun("templates_probe", FAKE_ENGINE + source, runtimeObjects);
        assertEquals(0, execution.exitCode(), execution::diagnostic);
        assertTrue(execution.output().contains("OK templates"), execution::diagnostic);
    }

    @Test
    void execmemProbeShouldSucceedOnHost() throws IOException, InterruptedException {
        // The real RW->write->RX->execute round trip must work on the dev host (Linux x86_64);
        // HRX_UNAVAILABLE fail-closed behavior is anchored by the unavailable-mode probe below.
        var source = """
                int main(void) {
                    if (!gdcc_hrx_execmem_probe()) return 10;
                    printf("OK execmem\\n");
                    return 0;
                }
                """;
        var execution = compileLinkAndRun("execmem_probe", FAKE_ENGINE + source, runtimeObjects);
        assertEquals(0, execution.exitCode(), execution::diagnostic);
        assertTrue(execution.output().contains("OK execmem"), execution::diagnostic);
    }

    @Test
    void lambdaCallableShouldRoundTripThroughRealThunks() throws IOException, InterruptedException {
        // Creation -> registry/refcount publication -> thunk execution (arg0 swap, pass-through),
        // is_valid / get_argument_count data answers, then deinitialize invalidation and the
        // engine-standard error path, and finally free-thunk -> live sweeper reclaim.
        var source = """
                static int g_impl_calls = 0;
                static void *g_impl_captures = NULL;
                static const GDExtensionConstVariantPtr *g_impl_args = NULL;
                static GDExtensionInt g_impl_argc = -1;
                static GDExtensionVariantPtr g_impl_ret = NULL;
                static GDExtensionCallError *g_impl_err = NULL;
                static void recording_impl(void *captures, const GDExtensionConstVariantPtr *args, GDExtensionInt argc,
                        GDExtensionVariantPtr r_return, GDExtensionCallError *r_error) {
                    g_impl_calls++;
                    g_impl_captures = captures;
                    g_impl_args = args;
                    g_impl_argc = argc;
                    g_impl_ret = r_return;
                    g_impl_err = r_error;
                    if (r_error != NULL) r_error->error = GDEXTENSION_CALL_OK;
                }
                static GDExtensionBool always_valid(void *captures) { (void)captures; return true; }
                static void noop_destroy(void *captures) { (void)captures; }
                
                static const unsigned char DESC_A[] = { 1, 2, 3, 4 };
                static const gdcc_hrx_identity IDENTITY_A = {
                    .impl_key = "Worker::run@+2:5",
                    .schema_desc = DESC_A,
                    .schema_desc_len = sizeof(DESC_A),
                    .argument_count = 2,
                    .schema_fingerprint = { 0xA0, 0xA1, 0xA2, 0xA3, 0xA4, 0xA5, 0xA6, 0xA7,
                                            0xA8, 0xA9, 0xAA, 0xAB, 0xAC, 0xAD, 0xAE, 0xAF },
                };
                
                int main(void) {
                    godot_initialize_interface(fake_get_proc_address);
                    gdcc_hrx_test_reset_image_state(); // simulate a fresh DSO image arriving
                    gdcc_hrx_mode mode = gdcc_hrx_initialize_core(
                            NULL, (GDExtensionObjectPtr)&g_engine_marker, true, true, 0x1234u, NULL, 0);
                    CHECK(mode == GDCC_HRX_MODE_ACTIVE, "editor + probe must resolve to ACTIVE");
                    const int balance_base = g_mem_balance;
                
                    int captures = 42;
                    gdcc_hrx_spec *spec = NULL;
                    godot_Callable cb = gdcc_hrx_create_lambda(
                            &captures, 0, recording_impl, always_valid, noop_destroy, 2, &IDENTITY_A, &spec);
                    FakeCallableCustom *custom = fake_callable_custom_of(&cb);
                    CHECK(custom != NULL, "callable must be non-empty");
                    CHECK(spec != NULL && custom->userdata == spec, "userdata must be the pinned spec");
                    CHECK(custom->call_func != (GDExtensionCallableCustomCall)recording_impl,
                            "call_func must be a heap thunk, never the library address");
                    CHECK(spec->refcount == 1 && !spec->dead
                            && spec->binding_state == GDCC_HRX_BOUND_COMPATIBLE, "spec must be live and bound");
                    CHECK(spec->impl_key != IDENTITY_A.impl_key
                            && strcmp(spec->impl_key, IDENTITY_A.impl_key) == 0,
                            "impl_key must be a heap copy (library rodata dies at dlclose)");
                
                    godot_Variant ret;
                    GDExtensionCallError err = { 0 };
                    GDExtensionConstVariantPtr args[2] = { NULL, NULL };
                    custom->call_func(custom->userdata, args, 2, &ret, &err);
                    CHECK(g_impl_calls == 1 && g_impl_captures == &captures,
                            "thunk must swap arg0 to the capture block and run the impl");
                    CHECK(g_impl_args == args && g_impl_argc == 2, "args/argc must pass through untouched");
                    CHECK(g_impl_ret == &ret && g_impl_err == &err, "return/error pointers must pass through");
                    CHECK(err.error == GDEXTENSION_CALL_OK, "impl result must propagate");
                
                    CHECK(custom->is_valid_func(custom->userdata) == true, "bound spec must be valid");
                    GDExtensionBool argc_valid = false;
                    CHECK(custom->argc_func(custom->userdata, &argc_valid) == 2 && argc_valid,
                            "argument count must come from spec data with the two-parameter ABI");
                    CHECK(custom->argc_func(custom->userdata, NULL) == 2, "NULL r_is_valid must be tolerated");
                
                    gdcc_hrx_deinitialize();
                                        CHECK(custom->is_valid_func(custom->userdata) == false, "deinitialize must invalidate");
                    g_impl_calls = 0;
                    err.error = (GDExtensionCallErrorType)777;
                    custom->call_func(custom->userdata, args, 2, &ret, &err);
                    CHECK(g_impl_calls == 0, "invalidated spec must never run the impl");
                    CHECK(err.error == GDEXTENSION_CALL_ERROR_INVALID_METHOD && err.argument == 0 && err.expected == 0,
                            "the invalid path must write the full GDExtensionCallError");
                    argc_valid = true;
                    CHECK(custom->argc_func(custom->userdata, &argc_valid) == 2 && !argc_valid,
                            "argument count validity flag must flip with binding state");
                
                    // The sweeper is detached now: the free only marks dead, and the NEXT
                    // generation's two-phase sweep reclaims the shell.
                    godot_Callable_destroy(&cb);
                    CHECK(spec->dead, "refcount zero crossing must mark dead without a sweeper");
                    gdcc_hrx_test_reset_image_state(); // simulate a fresh DSO image arriving
                    gdcc_hrx_initialize_core(NULL, (GDExtensionObjectPtr)&g_engine_marker, true, true, 0x1234u, NULL, 0);
                    CHECK(g_mem_balance == balance_base, "the two-phase sweep must reclaim everything");
                    printf("OK lambda_roundtrip\\n");
                    return 0;
                }
                """;
        var execution = compileLinkAndRun("lambda_roundtrip_probe", FAKE_ENGINE + source, runtimeObjects);
        assertEquals(0, execution.exitCode(), execution::diagnostic);
        assertTrue(execution.output().contains("OK lambda_roundtrip"), execution::diagnostic);
    }

    @Test
    void rebindShouldUpgradeSurvivorsAndFailClosedOnSchemaMismatch() throws IOException, InterruptedException {
        // Simulated reload: generation 1 creates two lambdas; deinitialize invalidates;
        // generation 2 arrives with a rebind table whose entry for A is schema-compatible
        // (rebound to the NEW implementation, argument count updated) and whose entry for B
        // has a different descriptor (stays UNBOUND: is_valid false, impl never runs, engine
        // error path on call).
        var source = """
                static int g_impl1_calls = 0;
                static int g_impl2_calls = 0;
                static void impl1(void *c, const GDExtensionConstVariantPtr *a, GDExtensionInt n,
                        GDExtensionVariantPtr r, GDExtensionCallError *e) { g_impl1_calls++; }
                static void impl2(void *c, const GDExtensionConstVariantPtr *a, GDExtensionInt n,
                        GDExtensionVariantPtr r, GDExtensionCallError *e) { g_impl2_calls++; }
                static GDExtensionBool valid1(void *c) { return true; }
                static void noop_destroy(void *c) { (void)c; }
                
                static const unsigned char DESC[] = { 9, 8, 7 };
                static const unsigned char DESC_OTHER[] = { 1, 1, 1, 1 };
                static const gdcc_hrx_identity IDENTITY_A = {
                    .impl_key = "Worker::run@+2:5", .schema_desc = DESC, .schema_desc_len = sizeof(DESC),
                    .argument_count = 2, .schema_fingerprint = { 1 },
                };
                // Same key + same descriptor, only a different argument count: still compatible.
                static const gdcc_hrx_identity IDENTITY_A_NEW = {
                    .impl_key = "Worker::run@+2:5", .schema_desc = DESC, .schema_desc_len = sizeof(DESC),
                    .argument_count = 3, .schema_fingerprint = { 1 },
                };
                static const gdcc_hrx_identity IDENTITY_B = {
                    .impl_key = "Worker::hide@+4:9", .schema_desc = DESC, .schema_desc_len = sizeof(DESC),
                    .argument_count = 1, .schema_fingerprint = { 2 },
                };
                static const gdcc_hrx_identity IDENTITY_B_CHANGED = {
                    .impl_key = "Worker::hide@+4:9", .schema_desc = DESC_OTHER, .schema_desc_len = sizeof(DESC_OTHER),
                    .argument_count = 1, .schema_fingerprint = { 2 },
                };
                static const gdcc_hrx_rebind_entry GEN2_TABLE[] = {
                    { &IDENTITY_A_NEW, impl2, noop_destroy, valid1 },
                    { &IDENTITY_B_CHANGED, impl2, noop_destroy, valid1 },
                };
                
                int main(void) {
                    godot_initialize_interface(fake_get_proc_address);
                    gdcc_hrx_test_reset_image_state(); // simulate a fresh DSO image arriving
                    gdcc_hrx_initialize_core(NULL, (GDExtensionObjectPtr)&g_engine_marker, true, true, 0x55u, NULL, 0);
                    const int balance_base = g_mem_balance;
                    int captures_a = 1;
                    int captures_b = 2;
                    godot_Callable cb_a = gdcc_hrx_create_lambda(&captures_a, 0, impl1, valid1, noop_destroy, 2, &IDENTITY_A, NULL);
                    godot_Callable cb_b = gdcc_hrx_create_lambda(&captures_b, 0, impl1, valid1, noop_destroy, 1, &IDENTITY_B, NULL);
                    FakeCallableCustom *custom_a = fake_callable_custom_of(&cb_a);
                    FakeCallableCustom *custom_b = fake_callable_custom_of(&cb_b);
                    gdcc_hrx_deinitialize();
                                        CHECK(custom_a->is_valid_func(custom_a->userdata) == false, "pre-reload must be invalid");
                
                    gdcc_hrx_test_reset_image_state(); // simulate a fresh DSO image arriving
                    gdcc_hrx_mode mode = gdcc_hrx_initialize_core(
                            NULL, (GDExtensionObjectPtr)&g_engine_marker, true, true, 0x55u, GEN2_TABLE, 2);
                    CHECK(mode == GDCC_HRX_MODE_ACTIVE, "second generation must stay ACTIVE");
                
                    // A: rebound in place to the new implementation.
                    CHECK(custom_a->is_valid_func(custom_a->userdata) == true, "compatible spec must rebind");
                    custom_a->call_func(custom_a->userdata, NULL, 0, NULL, NULL);
                    CHECK(g_impl2_calls == 1 && g_impl1_calls == 0, "rebound spec must run the NEW implementation");
                    GDExtensionBool argc_valid = false;
                    CHECK(custom_a->argc_func(custom_a->userdata, &argc_valid) == 3 && argc_valid,
                            "rebind must refresh the argument count");
                
                    // B: descriptor mismatch -> fail-closed.
                    CHECK(custom_b->is_valid_func(custom_b->userdata) == false, "mismatched spec must stay invalid");
                    GDExtensionCallError err = { 0 };
                    godot_Variant ret;
                    custom_b->call_func(custom_b->userdata, NULL, 0, &ret, &err);
                    CHECK(g_impl1_calls == 0 && g_impl2_calls == 1, "mismatched spec must never run any impl");
                    CHECK(err.error == GDEXTENSION_CALL_ERROR_INVALID_METHOD, "mismatch must take the engine error path");
                
                    godot_Callable_destroy(&cb_a);
                    godot_Callable_destroy(&cb_b);
                    CHECK(g_mem_balance == balance_base, "both specs must be reclaimed by the live sweeper");
                    printf("OK rebind\\n");
                    return 0;
                }
                """;
        var execution = compileLinkAndRun("rebind_probe", FAKE_ENGINE + source, runtimeObjects);
        assertEquals(0, execution.exitCode(), execution::diagnostic);
        assertTrue(execution.output().contains("OK rebind"), execution::diagnostic);
    }

    @Test
    void schemaMismatchedStandaloneShouldYieldFreshSpecOnRecreate() throws IOException, InterruptedException {
        // Simulated reload with a CHANGED standalone schema: the surviving old spec cannot
        // rebind and stays a fail-closed zombie. The new generation's same-key create must
        // detach that zombie from the intern table and build a FRESH spec instead of
        // inheriting the NULLed implementation (fail-closed must only isolate OLD Callables).
        var source = """
                static int g_calls1 = 0;
                static int g_calls2 = 0;
                static void sa_call1(void *c, const GDExtensionConstVariantPtr *a, GDExtensionInt n,
                        GDExtensionVariantPtr r, GDExtensionCallError *e) { g_calls1++; }
                static void sa_call2(void *c, const GDExtensionConstVariantPtr *a, GDExtensionInt n,
                        GDExtensionVariantPtr r, GDExtensionCallError *e) { g_calls2++; }
                static GDExtensionBool sa_valid(void *c) { return true; }
                static const unsigned char DESC_V1[] = { 5, 5 };
                static const unsigned char DESC_V2[] = { 9, 9, 9 };
                static const gdcc_hrx_identity IDENTITY_V1 = {
                    .impl_key = "standalone:utility::print", .schema_desc = DESC_V1,
                    .schema_desc_len = sizeof(DESC_V1), .argument_count = 1, .schema_fingerprint = { 7 },
                };
                static const gdcc_hrx_identity IDENTITY_V2 = {
                    .impl_key = "standalone:utility::print", .schema_desc = DESC_V2,
                    .schema_desc_len = sizeof(DESC_V2), .argument_count = 2, .schema_fingerprint = { 8 },
                };
                static const gdcc_hrx_rebind_entry GEN2_TABLE[] = {
                    { &IDENTITY_V2, sa_call2, NULL, sa_valid },
                };
                
                int main(void) {
                    godot_initialize_interface(fake_get_proc_address);
                    gdcc_hrx_test_reset_image_state(); // simulate a fresh DSO image arriving
                    gdcc_hrx_initialize_core(NULL, (GDExtensionObjectPtr)&g_engine_marker, true, true, 0x88u, NULL, 0);
                    const int balance_base = g_mem_balance;
                
                    godot_Callable cb_old = gdcc_hrx_create_standalone(
                            "utility", "", "print", 123LL, 1, false, true, sa_call1, sa_valid, &IDENTITY_V1);
                    FakeCallableCustom *custom_old = fake_callable_custom_of(&cb_old);
                    CHECK(custom_old != NULL, "gen1 creation must succeed");
                
                    gdcc_hrx_deinitialize();
                                        gdcc_hrx_test_reset_image_state(); // simulate a fresh DSO image arriving
                                        gdcc_hrx_mode mode = gdcc_hrx_initialize_core(
                            NULL, (GDExtensionObjectPtr)&g_engine_marker, true, true, 0x88u, GEN2_TABLE, 1);
                    CHECK(mode == GDCC_HRX_MODE_ACTIVE, "second generation must stay ACTIVE");
                    CHECK(custom_old->is_valid_func(custom_old->userdata) == false,
                            "schema-mismatched standalone must stay invalid after reload");
                
                    godot_Callable cb_new = gdcc_hrx_create_standalone(
                            "utility", "", "print", 123LL, 2, false, true, sa_call2, sa_valid, &IDENTITY_V2);
                    FakeCallableCustom *custom_new = fake_callable_custom_of(&cb_new);
                    CHECK(custom_new != NULL, "new-generation create must succeed");
                    CHECK(custom_new->userdata != custom_old->userdata,
                            "a schema-incompatible zombie must never father new Callables");
                    CHECK(custom_new->is_valid_func(custom_new->userdata) == true,
                            "the fresh spec must be bound to the new implementation");
                    custom_new->call_func(custom_new->userdata, NULL, 0, NULL, NULL);
                    CHECK(g_calls2 == 1 && g_calls1 == 0, "the fresh spec must run the NEW implementation");
                
                    godot_Callable cb_new2 = gdcc_hrx_create_standalone(
                            "utility", "", "print", 123LL, 2, false, true, sa_call2, sa_valid, &IDENTITY_V2);
                    CHECK(fake_callable_custom_of(&cb_new2)->userdata == custom_new->userdata,
                            "interning after the mismatch must resolve to the fresh spec");
                
                    GDExtensionCallError err = { 0 };
                    godot_Variant ret;
                    custom_old->call_func(custom_old->userdata, NULL, 0, &ret, &err);
                    CHECK(g_calls1 == 0 && err.error == GDEXTENSION_CALL_ERROR_INVALID_METHOD,
                            "the zombie must keep its fail-closed behavior");
                
                    godot_Callable_destroy(&cb_old);
                    godot_Callable_destroy(&cb_new);
                    godot_Callable_destroy(&cb_new2);
                    CHECK(g_mem_balance == balance_base, "zombie + fresh spec must both be reclaimed");
                    printf("OK standalone-schema-recreate\\n");
                    return 0;
                }
                """;
        var execution = compileLinkAndRun("standalone_schema_recreate_probe", FAKE_ENGINE + source, runtimeObjects);
        assertEquals(0, execution.exitCode(), execution::diagnostic);
        assertTrue(execution.output().contains("OK standalone-schema-recreate"), execution::diagnostic);
    }

    @Test
    void sharedStandaloneShouldRefcountInOrderAndSweepWithInternDetach() throws IOException, InterruptedException {
        // Interning shares one (thunk, spec) per identity: two creations are equal under
        // Godot's default identity; the FIRST free must NOT invalidate (refcount order:
        // decrement first, only the zero crossing marks dead and sweeps); after the sweep the
        // identity is detached from the interning table so a recreate gets a FRESH spec
        // instead of a corpse.
        var source = """
                static void sa_call(void *c, const GDExtensionConstVariantPtr *a, GDExtensionInt n,
                        GDExtensionVariantPtr r, GDExtensionCallError *e) { (void)c; }
                static GDExtensionBool sa_valid(void *c) { return true; }
                static const unsigned char DESC_SA[] = { 5, 5 };
                static const gdcc_hrx_identity IDENTITY_SA = {
                    .impl_key = "standalone:utility::print", .schema_desc = DESC_SA,
                    .schema_desc_len = sizeof(DESC_SA), .argument_count = 1, .schema_fingerprint = { 7 },
                };
                
                int main(void) {
                    godot_initialize_interface(fake_get_proc_address);
                    gdcc_hrx_test_reset_image_state(); // simulate a fresh DSO image arriving
                    gdcc_hrx_initialize_core(NULL, (GDExtensionObjectPtr)&g_engine_marker, true, true, 0x77u, NULL, 0);
                    const int balance_base = g_mem_balance;
                
                    godot_Callable cb1 = gdcc_hrx_create_standalone(
                            "utility", "", "print", 123LL, 1, false, true, sa_call, sa_valid, &IDENTITY_SA);
                    godot_Callable cb2 = gdcc_hrx_create_standalone(
                            "utility", "", "print", 123LL, 1, false, true, sa_call, sa_valid, &IDENTITY_SA);
                    FakeCallableCustom *custom1 = fake_callable_custom_of(&cb1);
                    FakeCallableCustom *custom2 = fake_callable_custom_of(&cb2);
                    CHECK(custom1 != NULL && custom2 != NULL, "both creations must succeed");
                    CHECK(fake_callable_equal(custom1, custom2),
                            "interned standalone must share (thunk, spec) so default identity stays equal");
                    gdcc_hrx_spec *spec = (gdcc_hrx_spec *)custom1->userdata;
                    CHECK(spec->interned == 1, "standalone spec must be interned");
                    CHECK(spec->refcount == 2, "two custom objects must share the refcount");
                    CHECK(spec->destroy_fn == NULL, "standalone destroy_fn is always NULL (fixed-ABI payload)");
                
                    godot_Callable_destroy(&cb1);
                    CHECK(spec->refcount == 1 && !spec->dead,
                            "the first free must only decrement: a shared spec must not die early");
                    CHECK(custom2->is_valid_func(custom2->userdata) == true, "surviving copy must stay callable");
                
                    godot_Callable_destroy(&cb2);
                    // Zero crossing: dead + immediate live-generation sweep. The sweeper must
                    // detach the identity from the interning table FIRST (a same-identity
                    // recreate must never hit a corpse).
                    CHECK(gdcc_hrx_current_hub()->intern_count == 0,
                            "the sweeper must detach the identity from the interning table");
                    godot_Callable cb3 = gdcc_hrx_create_standalone(
                            "utility", "", "print", 123LL, 1, false, true, sa_call, sa_valid, &IDENTITY_SA);
                    CHECK(fake_callable_custom_of(&cb3) != NULL, "recreate must succeed");
                    CHECK(gdcc_hrx_current_hub()->intern_count == 1, "the fresh spec must intern again");
                    godot_Callable_destroy(&cb3);
                    CHECK(g_mem_balance == balance_base,
                            "payload strings + shell metadata must all be reclaimed (no standalone leak)");
                    printf("OK standalone_shared\\n");
                    return 0;
                }
                """;
        var execution = compileLinkAndRun("standalone_shared_probe", FAKE_ENGINE + source, runtimeObjects);
        assertEquals(0, execution.exitCode(), execution::diagnostic);
        assertTrue(execution.output().contains("OK standalone_shared"), execution::diagnostic);
    }

    @Test
    void deadInternedSpecShouldNeverBeReusedBeforeSweep() throws IOException, InterruptedException {
        // In the sweeper-less window after deinitialize, a freed standalone is only marked
        // dead. A same-identity recreation in that window must NOT resurrect the corpse
        // (interning lookup rejects dead specs); the old shell is reclaimed by the next
        // generation's two-phase sweep.
        var source = """
                static void sa_call(void *c, const GDExtensionConstVariantPtr *a, GDExtensionInt n,
                        GDExtensionVariantPtr r, GDExtensionCallError *e) { (void)c; }
                static GDExtensionBool sa_valid(void *c) { return true; }
                static const unsigned char DESC_SA[] = { 3 };
                static const gdcc_hrx_identity IDENTITY_SA = {
                    .impl_key = "standalone:utility::str", .schema_desc = DESC_SA,
                    .schema_desc_len = sizeof(DESC_SA), .argument_count = 0, .schema_fingerprint = { 9 },
                };
                
                int main(void) {
                    godot_initialize_interface(fake_get_proc_address);
                    gdcc_hrx_test_reset_image_state(); // simulate a fresh DSO image arriving
                    gdcc_hrx_initialize_core(NULL, (GDExtensionObjectPtr)&g_engine_marker, true, true, 0x88u, NULL, 0);
                    const int balance_base = g_mem_balance;
                    godot_Callable cb1 = gdcc_hrx_create_standalone(
                            "utility", "", "str", 0LL, 0, false, true, sa_call, sa_valid, &IDENTITY_SA);
                    gdcc_hrx_spec *spec1 = (gdcc_hrx_spec *)fake_callable_custom_of(&cb1)->userdata;
                    gdcc_hrx_deinitialize(); // sweeper detached: frees below only mark dead
                                        godot_Callable_destroy(&cb1);
                    CHECK(spec1->dead, "refcount zero crossing must mark dead even without a sweeper");
                
                    godot_Callable cb2 = gdcc_hrx_create_standalone(
                            "utility", "", "str", 0LL, 0, false, true, sa_call, sa_valid, &IDENTITY_SA);
                    gdcc_hrx_spec *spec2 = (gdcc_hrx_spec *)fake_callable_custom_of(&cb2)->userdata;
                    CHECK(spec2 != spec1, "interning lookup must reject the dead spec and build a new one");
                    CHECK(spec2->refcount == 1 && !spec2->dead, "the new spec must be the live interned entry");
                
                    // Next generation: the dead corpse is swept (payload + shell reclaimed),
                    // the live spec2 survives the rebind pass untouched.
                    gdcc_hrx_test_reset_image_state(); // simulate a fresh DSO image arriving
                    gdcc_hrx_initialize_core(NULL, (GDExtensionObjectPtr)&g_engine_marker, true, true, 0x88u, NULL, 0);
                    godot_Callable_destroy(&cb2);
                    CHECK(g_mem_balance == balance_base, "corpse and live spec must both end reclaimed");
                    printf("OK dead_interned\\n");
                    return 0;
                }
                """;
        var execution = compileLinkAndRun("dead_interned_probe", FAKE_ENGINE + source, runtimeObjects);
        assertEquals(0, execution.exitCode(), execution::diagnostic);
        assertTrue(execution.output().contains("OK dead_interned"), execution::diagnostic);
    }

    @Test
    void freeThunkShouldGuardRefcountUnderflow() throws IOException, InterruptedException {
        // A duplicated free (engine anomaly: the same thunk entered twice with the same
        // correct userdata) must be a no-op: the guard reads refcount==0 and returns without
        // touching state; the shell survives until the next generation sweeps.
        var source = """
                static void impl1(void *c, const GDExtensionConstVariantPtr *a, GDExtensionInt n,
                        GDExtensionVariantPtr r, GDExtensionCallError *e) { (void)c; }
                static GDExtensionBool valid1(void *c) { return true; }
                static void noop_destroy(void *c) { (void)c; }
                static const gdcc_hrx_identity IDENTITY_U = {
                    .impl_key = "Worker::guard@+1:1", .schema_desc = NULL, .schema_desc_len = 0,
                    .argument_count = 0, .schema_fingerprint = { 0 },
                };
                
                int main(void) {
                    godot_initialize_interface(fake_get_proc_address);
                    gdcc_hrx_test_reset_image_state(); // simulate a fresh DSO image arriving
                    gdcc_hrx_initialize_core(NULL, (GDExtensionObjectPtr)&g_engine_marker, true, true, 0x99u, NULL, 0);
                    const int balance_base = g_mem_balance;
                    godot_Callable cb = gdcc_hrx_create_lambda(NULL, 0, impl1, valid1, noop_destroy, 0, &IDENTITY_U, NULL);
                    FakeCallableCustom *custom = fake_callable_custom_of(&cb);
                    gdcc_hrx_spec *spec = (gdcc_hrx_spec *)custom->userdata;
                    // Saved before destroy: the engine frees the custom wrapper after free_func.
                    GDExtensionCallableCustomFree free_fn = custom->free_func;
                    gdcc_hrx_deinitialize(); // no sweeper: frees only mark dead
                                        godot_Callable_destroy(&cb);
                    CHECK(spec->dead && spec->refcount == 0, "first free must mark dead exactly once");
                    // Erroneous second free with the same (correct) userdata, invoked directly:
                    free_fn(spec);
                    CHECK(spec->dead && spec->refcount == 0, "underflow guard must keep state unchanged");
                    // The next generation reclaims the shell through the two-phase sweep.
                    gdcc_hrx_test_reset_image_state(); // simulate a fresh DSO image arriving
                    gdcc_hrx_initialize_core(NULL, (GDExtensionObjectPtr)&g_engine_marker, true, true, 0x99u, NULL, 0);
                    CHECK(g_mem_balance == balance_base, "shell must be reclaimed by the sweep");
                    printf("OK underflow\\n");
                    return 0;
                }
                """;
        var execution = compileLinkAndRun("underflow_probe", FAKE_ENGINE + source, runtimeObjects);
        assertEquals(0, execution.exitCode(), execution::diagnostic);
        assertTrue(execution.output().contains("OK underflow"), execution::diagnostic);
    }

    @Test
    void sweepShouldUseRebindTableDestroyWithFingerprintAndDescriptorGates() throws IOException, InterruptedException {
        // Phase-2 destruction contract: the destroy_fn comes from the REBIND TABLE (the spec's
        // own pointers were NULLed by deinitialize), and only when BOTH the 128-bit
        // fingerprint AND the byte-exact descriptor match. Spec A matches: table destroy runs.
        // Spec B has its fingerprint hand-tampered to equal the table entry's while its
        // descriptor differs: the byte-compare gate must still refuse destruction and leak the
        // capture block intentionally (shell metadata is still freed).
        var source = """
                static int g_destroy1_calls = 0;
                static int g_destroy2_calls = 0;
                static void *g_destroy2_captures = NULL;
                static void impl1(void *c, const GDExtensionConstVariantPtr *a, GDExtensionInt n,
                        GDExtensionVariantPtr r, GDExtensionCallError *e) { (void)c; }
                static GDExtensionBool valid1(void *c) { return true; }
                static void destroy1(void *c) { g_destroy1_calls++; }
                static void destroy2(void *c) { g_destroy2_calls++; g_destroy2_captures = c; }
                
                static const unsigned char DESC_A[] = { 1, 2 };
                static const unsigned char DESC_B[] = { 3, 4 };
                static const unsigned char DESC_B_ENTRY[] = { 9, 9, 9 };
                static const gdcc_hrx_identity IDENTITY_A = {
                    .impl_key = "Worker::a@+1:1", .schema_desc = DESC_A, .schema_desc_len = sizeof(DESC_A),
                    .argument_count = 0, .schema_fingerprint = { 0xAA },
                };
                static const gdcc_hrx_identity IDENTITY_B = {
                    .impl_key = "Worker::b@+2:2", .schema_desc = DESC_B, .schema_desc_len = sizeof(DESC_B),
                    .argument_count = 0, .schema_fingerprint = { 0xBB },
                };
                // Table entry for B: same key, DIFFERENT descriptor and fingerprint 0xCC.
                static const gdcc_hrx_identity IDENTITY_B_ENTRY = {
                    .impl_key = "Worker::b@+2:2", .schema_desc = DESC_B_ENTRY, .schema_desc_len = sizeof(DESC_B_ENTRY),
                    .argument_count = 0, .schema_fingerprint = { 0xCC },
                };
                static const gdcc_hrx_rebind_entry GEN2_TABLE[] = {
                    { &IDENTITY_A, impl1, destroy2, valid1 },
                    { &IDENTITY_B_ENTRY, impl1, destroy2, valid1 },
                };
                
                int main(void) {
                    godot_initialize_interface(fake_get_proc_address);
                    gdcc_hrx_test_reset_image_state(); // simulate a fresh DSO image arriving
                    gdcc_hrx_initialize_core(NULL, (GDExtensionObjectPtr)&g_engine_marker, true, true, 0xABu, NULL, 0);
                    const int balance_base = g_mem_balance;
                    int captures_a = 11;
                    int captures_b = 22;
                    godot_Callable cb_a = gdcc_hrx_create_lambda(&captures_a, 0, impl1, valid1, destroy1, 0, &IDENTITY_A, NULL);
                    godot_Callable cb_b = gdcc_hrx_create_lambda(&captures_b, 0, impl1, valid1, destroy1, 0, &IDENTITY_B, NULL);
                    gdcc_hrx_spec *spec_b = (gdcc_hrx_spec *)fake_callable_custom_of(&cb_b)->userdata;
                    gdcc_hrx_deinitialize();
                                        godot_Callable_destroy(&cb_a);
                    godot_Callable_destroy(&cb_b);
                    CHECK(g_destroy1_calls == 0 && g_destroy2_calls == 0,
                            "deinitialize must detach the sweeper before any destruction");
                    // Fingerprint-collision craft: B's fingerprint now equals the table entry's
                    // while its descriptor still differs.
                    memcpy(spec_b->schema_fingerprint, IDENTITY_B_ENTRY.schema_fingerprint,
                            sizeof(spec_b->schema_fingerprint));
                
                    gdcc_hrx_test_reset_image_state(); // simulate a fresh DSO image arriving
                    gdcc_hrx_initialize_core(NULL, (GDExtensionObjectPtr)&g_engine_marker, true, true, 0xABu,
                            GEN2_TABLE, 2);
                    CHECK(g_destroy2_calls == 1 && g_destroy2_captures == &captures_a,
                            "matching spec must be destroyed through the REBIND TABLE destroy_fn");
                    CHECK(g_destroy1_calls == 0, "the NULLed spec destroy_fn must never be consulted");
                    CHECK(gdcc_hrx_current_hub()->leaked_capture_count == 1,
                            "descriptor mismatch must leak the capture block intentionally");
                    printf("OK sweep_destroy_gates\\n");
                    return 0;
                }
                """;
        var execution = compileLinkAndRun("sweep_destroy_gates_probe", FAKE_ENGINE + source, runtimeObjects);
        assertEquals(0, execution.exitCode(), execution::diagnostic);
        assertTrue(execution.output().contains("OK sweep_destroy_gates"), execution::diagnostic);
    }

    @Test
    void sweepReentrancyShouldQueuePendingAndDrainAtDepthZero() throws IOException, InterruptedException {
        // A destroy_fn that drops ANOTHER Callable reenters the free thunk mid-sweep: the
        // nested spec must queue on the pending list (pending_next) and be drained only after
        // the outer sweep unwinds, in order, with both capture blocks destroyed exactly once.
        var source = """
                static void impl1(void *c, const GDExtensionConstVariantPtr *a, GDExtensionInt n,
                        GDExtensionVariantPtr r, GDExtensionCallError *e) { (void)c; }
                static GDExtensionBool valid1(void *c) { return true; }
                static void destroy_b(void *c) { log_event("destroy:B"); }
                static void destroy_a(void *c) {
                    log_event("destroy:A");
                    // Reentrant release of B's only Callable while A's sweep is in flight.
                    godot_Callable *sibling = (godot_Callable *)c;
                    godot_Callable_destroy(sibling);
                }
                static const gdcc_hrx_identity IDENTITY_A = {
                    .impl_key = "Worker::a@+1:1", .schema_desc = NULL, .schema_desc_len = 0,
                    .argument_count = 0, .schema_fingerprint = { 1 },
                };
                static const gdcc_hrx_identity IDENTITY_B = {
                    .impl_key = "Worker::b@+2:2", .schema_desc = NULL, .schema_desc_len = 0,
                    .argument_count = 0, .schema_fingerprint = { 2 },
                };
                
                int main(void) {
                    godot_initialize_interface(fake_get_proc_address);
                    gdcc_hrx_test_reset_image_state(); // simulate a fresh DSO image arriving
                    gdcc_hrx_initialize_core(NULL, (GDExtensionObjectPtr)&g_engine_marker, true, true, 0xCDu, NULL, 0);
                    const int balance_base = g_mem_balance;
                    godot_Callable cb_b = gdcc_hrx_create_lambda(NULL, 0, impl1, valid1, destroy_b, 0, &IDENTITY_B, NULL);
                    // A's capture block IS the sibling Callable handle (plain pointer copy).
                    godot_Callable cb_a = gdcc_hrx_create_lambda(&cb_b, 0, impl1, valid1, destroy_a, 0, &IDENTITY_A, NULL);
                    (void)cb_a;
                    // Keep B alive through A's own reference only: drop no handle manually;
                    // destroying A's Callable starts the cascade.
                    godot_Callable cb_a_handle = cb_a;
                    godot_Callable_destroy(&cb_a_handle);
                    printf("EV done\\n");
                    CHECK(g_mem_balance == balance_base, "reentrant sweep must reclaim both specs");
                    return 0;
                }
                """;
        var execution = compileLinkAndRun("sweep_reentrancy_probe", FAKE_ENGINE + source, runtimeObjects);
        assertEquals(0, execution.exitCode(), execution::diagnostic);
        var events = execution.output().lines().filter(line -> line.startsWith("EV ")).map(line -> line.substring(3)).toList();
        assertEquals(List.of("destroy:A", "destroy:B", "done"), events, execution::diagnostic);
    }

    @Test
    void sharedThunkPageShouldServeAllSpecsAndStayConstantAcrossGenerations() throws IOException, InterruptedException {
        // Every spec's four function pointers reference the hub's single shared thunk page at
        // frozen role offsets. The page is written once at hub creation — before any Callable
        // exists — and never again: a simulated reload keeps the OLD Callable's pointers
        // unchanged, and new-generation specs reuse the same addresses.
        var source = """
                static int g_impl_calls = 0;
                static void impl1(void *c, const GDExtensionConstVariantPtr *a, GDExtensionInt n,
                        GDExtensionVariantPtr r, GDExtensionCallError *e) { g_impl_calls++; }
                static GDExtensionBool valid1(void *c) { return true; }
                static void noop_destroy(void *c) { (void)c; }
                static const gdcc_hrx_identity IDENTITY_A = {
                    .impl_key = "Worker::a@+1:1", .schema_desc = NULL, .schema_desc_len = 0,
                    .argument_count = 0, .schema_fingerprint = { 1 },
                };
                static const gdcc_hrx_identity IDENTITY_B = {
                    .impl_key = "Worker::b@+2:2", .schema_desc = NULL, .schema_desc_len = 0,
                    .argument_count = 0, .schema_fingerprint = { 2 },
                };
                static const gdcc_hrx_identity IDENTITY_D = {
                    .impl_key = "Worker::d@+4:4", .schema_desc = NULL, .schema_desc_len = 0,
                    .argument_count = 0, .schema_fingerprint = { 4 },
                };
                static const gdcc_hrx_rebind_entry GEN2_TABLE[] = {
                    { &IDENTITY_B, impl1, noop_destroy, valid1 },
                };
                
                int main(void) {
                    godot_initialize_interface(fake_get_proc_address);
                    gdcc_hrx_test_reset_image_state(); // simulate a fresh DSO image arriving
                    gdcc_hrx_initialize_core(NULL, (GDExtensionObjectPtr)&g_engine_marker, true, true, 0xEFu, NULL, 0);
                    const int balance_base = g_mem_balance;
                    godot_Callable cb_a = gdcc_hrx_create_lambda(NULL, 0, impl1, valid1, noop_destroy, 0, &IDENTITY_A, NULL);
                    godot_Callable cb_b = gdcc_hrx_create_lambda(NULL, 0, impl1, valid1, noop_destroy, 0, &IDENTITY_B, NULL);
                    FakeCallableCustom *custom_a = fake_callable_custom_of(&cb_a);
                    FakeCallableCustom *custom_b = fake_callable_custom_of(&cb_b);
                    gdcc_hrx_hub *hub = gdcc_hrx_current_hub();
                    CHECK(hub != NULL && hub->thunk_page != NULL, "the hub must own the shared thunk page");
                    const uint8_t *page = (const uint8_t *)hub->thunk_page;
                    CHECK((const uint8_t *)custom_a->call_func == page + gdcc_hrx_thunk_bundle_offset(GDCC_HRX_THUNK_CALL),
                            "call_func must point into the shared page at the frozen role offset");
                    CHECK(custom_a->call_func == custom_b->call_func
                            && custom_a->is_valid_func == custom_b->is_valid_func
                            && custom_a->free_func == custom_b->free_func
                            && custom_a->argc_func == custom_b->argc_func,
                            "all specs share the same role thunk addresses");
                    CHECK(fake_callable_equal(custom_a, custom_b) == 0,
                            "shared call_func must not collapse identity (userdata differs)");
                    // Byte snapshot of the whole role bundle: after the reload below the page
                    // must still hold the exact same bytes (never demoted, never rewritten).
                    unsigned char snapshot[256];
                    const unsigned bundle_bytes = gdcc_hrx_thunk_bundle_offset(GDCC_HRX_THUNK_GET_ARGUMENT_COUNT)
                            + gdcc_hrx_thunk_template_get(GDCC_HRX_THUNK_GET_ARGUMENT_COUNT)->size;
                    memcpy(snapshot, page, bundle_bytes);
                
                    void *call_b = (void *)custom_b->call_func;
                    gdcc_hrx_deinitialize();
                                        gdcc_hrx_test_reset_image_state(); // simulate a fresh DSO image arriving
                                        gdcc_hrx_initialize_core(NULL, (GDExtensionObjectPtr)&g_engine_marker, true, true, 0xEFu, GEN2_TABLE, 1);
                    CHECK((void *)custom_b->call_func == call_b,
                            "a rebound Callable must keep its function pointers (page never rewritten)");
                    CHECK(gdcc_hrx_current_hub()->thunk_page == (void *)page,
                            "the page is published once per hub, not per generation");
                    CHECK(memcmp(snapshot, page, bundle_bytes) == 0,
                            "the shared page bytes must be identical after a reload (never rewritten)");
                    CHECK(custom_b->is_valid_func(custom_b->userdata) == true, "rebound spec must be valid");
                    custom_b->call_func(custom_b->userdata, NULL, 0, NULL, NULL);
                    CHECK(g_impl_calls == 1, "rebound spec must run through the shared thunk");
                
                    godot_Callable cb_d = gdcc_hrx_create_lambda(NULL, 0, impl1, valid1, noop_destroy, 0, &IDENTITY_D, NULL);
                    FakeCallableCustom *custom_d = fake_callable_custom_of(&cb_d);
                    CHECK((void *)custom_d->call_func == call_b,
                            "new-generation specs reuse the same shared page addresses");
                
                    godot_Callable_destroy(&cb_a);
                    godot_Callable_destroy(&cb_b);
                    godot_Callable_destroy(&cb_d);
                    CHECK(g_mem_balance == balance_base, "all specs must be reclaimed");
                    printf("OK thunkpage\\n");
                    return 0;
                }
                """;
        var execution = compileLinkAndRun("thunkpage_probe", FAKE_ENGINE + source, runtimeObjects);
        assertEquals(0, execution.exitCode(), execution::diagnostic);
        assertTrue(execution.output().contains("OK thunkpage"), execution::diagnostic);
    }

    @Test
    void failedHubCreationTombstoneMustBeCleanedForLaterTakeover() throws IOException, InterruptedException {
        // A NULL binding slot (tombstone) is left behind when a hub creation fails, because
        // the engine keeps the freshly appended slot even when create_callback returns NULL
        // (object.cpp:2116-2147). If it is not dropped before creating, the tombstone shadows
        // every later lookup of the token (first match wins) and NO generation could ever
        // take over the hub — old Callables would never rebind.
        var source = """
                int main(void) {
                    godot_initialize_interface(fake_get_proc_address);
                    // Simulate the tombstone left by a failed first hub creation.
                    fake_append_binding((GDExtensionObjectPtr)&g_engine_marker, (void *)0xABu, NULL);
                    gdcc_hrx_test_reset_image_state(); // simulate a fresh DSO image arriving
                    gdcc_hrx_mode mode = gdcc_hrx_initialize_core(
                            NULL, (GDExtensionObjectPtr)&g_engine_marker, true, true, 0xABu, NULL, 0);
                    CHECK(mode == GDCC_HRX_MODE_ACTIVE, "the tombstone must not block hub creation");
                    CHECK(g_free_binding_calls == 1, "the tombstone must be dropped exactly once");
                    gdcc_hrx_hub *hub = gdcc_hrx_current_hub();
                    CHECK(hub != NULL && hub->magic == GDCC_HRX_HUB_MAGIC, "a fresh hub must be mounted");
                    CHECK(fake_get_binding((GDExtensionObjectPtr)&g_engine_marker, (void *)0xABu, NULL) == hub,
                            "the only slot for the token must be the fresh hub");
                    CHECK(g_binding_count == 1, "the tombstone must not leave duplicate slots");
                    gdcc_hrx_deinitialize();
                                        gdcc_hrx_test_reset_image_state(); // simulate a fresh DSO image arriving
                                        gdcc_hrx_initialize_core(NULL, (GDExtensionObjectPtr)&g_engine_marker, true, true, 0xABu, NULL, 0);
                    CHECK(gdcc_hrx_current_hub() == hub, "the next generation must take over the same hub");
                    CHECK(g_create_callback_calls == 1, "no second hub may be created");
                    printf("OK tombstone\\n");
                    return 0;
                }
                """;
        var execution = compileLinkAndRun("tombstone_probe", FAKE_ENGINE + source, runtimeObjects);
        assertEquals(0, execution.exitCode(), execution::diagnostic);
        assertTrue(execution.output().contains("OK tombstone"), execution::diagnostic);
    }

    @Test
    void stackedTombstonesShouldAllBeSweptBeforeHubCreation() throws IOException, InterruptedException {
        // Repeated failed creations (or an unfixed runtime build) can leave a STACK of NULL
        // tombstones ahead of a valid hub slot. The bounded sweep in initialize_core frees
        // them one per iteration until the real hub surfaces: it must be taken over (never
        // shadowed by a fresh duplicate hub).
        var source = """
                static gdcc_hrx_hub g_legacy_hub;
                
                int main(void) {
                    godot_initialize_interface(fake_get_proc_address);
                    // A legacy (pre-fix) process leaves: [T, T, T] ahead of a valid hub H1.
                    fake_append_binding((GDExtensionObjectPtr)&g_engine_marker, (void *)0xACu, NULL);
                    fake_append_binding((GDExtensionObjectPtr)&g_engine_marker, (void *)0xACu, NULL);
                    fake_append_binding((GDExtensionObjectPtr)&g_engine_marker, (void *)0xACu, NULL);
                    memset(&g_legacy_hub, 0, sizeof(g_legacy_hub));
                    g_legacy_hub.magic = GDCC_HRX_HUB_MAGIC;
                    g_legacy_hub.version = GDCC_HRX_HUB_VERSION;
                    g_legacy_hub.intern_table_size = 64; // GDCC_HRX_INTERN_BUCKETS_INIT (private to gdcc_hrx.c)
                    g_legacy_hub.intern_table = (gdcc_hrx_spec **)godot_mem_alloc(64 * sizeof(gdcc_hrx_spec *));
                    for (unsigned i = 0; i < 64; i++) g_legacy_hub.intern_table[i] = NULL;
                    // H1's thunk page is irrelevant for takeover (specs are never created here).
                    fake_append_binding((GDExtensionObjectPtr)&g_engine_marker, (void *)0xACu, &g_legacy_hub);
                
                    gdcc_hrx_test_reset_image_state(); // simulate a fresh DSO image arriving
                    gdcc_hrx_mode mode = gdcc_hrx_initialize_core(
                            NULL, (GDExtensionObjectPtr)&g_engine_marker, true, true, 0xACu, NULL, 0);
                    CHECK(mode == GDCC_HRX_MODE_ACTIVE, "stacked tombstones must not block takeover");
                    CHECK(g_free_binding_calls == 3, "every stacked tombstone must be swept exactly once");
                    CHECK(gdcc_hrx_current_hub() == &g_legacy_hub,
                            "the valid hub buried under the tombstones must be taken over");
                    CHECK(g_binding_count == 1, "only the valid hub slot may remain");
                    CHECK(g_create_callback_calls == 0, "no fresh hub may be created when a valid one surfaced");
                    gdcc_hrx_deinitialize();
                                        gdcc_hrx_test_reset_image_state(); // simulate a fresh DSO image arriving
                                        gdcc_hrx_initialize_core(NULL, (GDExtensionObjectPtr)&g_engine_marker, true, true, 0xACu, NULL, 0);
                    CHECK(gdcc_hrx_current_hub() == &g_legacy_hub, "the next generation must keep the same hub");
                    printf("OK stacked-tombstone\\n");
                    return 0;
                }
                """;
        var execution = compileLinkAndRun("stacked_tombstone_probe", FAKE_ENGINE + source, runtimeObjects);
        assertEquals(0, execution.exitCode(), execution::diagnostic);
        assertTrue(execution.output().contains("OK stacked-tombstone"), execution::diagnostic);
    }

    @Test
    void anchorShouldCoexistWithOccupiedSlotZeroAndKeepCallbacksNull() throws IOException, InterruptedException {
        // The hub anchor must mount through get_instance_binding even when a foreign binding
        // already occupies the object (set_instance_binding would hard-fail on slot 0). The
        // callbacks handed to the engine must have free/reference == NULL (they are copied
        // into the slot and would dangle across a reload), and a generation restart must not
        // recreate the hub.
        var source = """
                static int g_foreign_binding = 0;
                
                int main(void) {
                    godot_initialize_interface(fake_get_proc_address);
                    fake_append_binding((GDExtensionObjectPtr)&g_engine_marker, (void *)0xDEADu, &g_foreign_binding);
                    gdcc_hrx_test_reset_image_state(); // simulate a fresh DSO image arriving
                    gdcc_hrx_mode mode = gdcc_hrx_initialize_core(
                            NULL, (GDExtensionObjectPtr)&g_engine_marker, true, true, 0x1234u, NULL, 0);
                    CHECK(mode == GDCC_HRX_MODE_ACTIVE, "anchor creation must succeed");
                    CHECK(g_binding_count == 2, "the hub must be appended alongside the foreign binding");
                    CHECK(g_create_callback_calls == 1, "create_callback must run exactly once");
                    CHECK(g_anchor_free_cb_was_null == 1 && g_anchor_reference_cb_was_null == 1,
                            "anchor free/reference callbacks must be NULL (engine copies them)");
                    gdcc_hrx_hub *hub = gdcc_hrx_current_hub();
                    CHECK(hub != NULL && hub->magic == GDCC_HRX_HUB_MAGIC && hub->version == GDCC_HRX_HUB_VERSION,
                            "the mounted hub must carry magic/version");
                    CHECK(fake_get_binding((GDExtensionObjectPtr)&g_engine_marker, (void *)0x1234u, NULL) == hub,
                            "the hub must be reachable through the anchor token");
                    // Generation restart reuses the hub (no recreation, sweeper re-registered).
                    gdcc_hrx_deinitialize();
                                        gdcc_hrx_test_reset_image_state(); // simulate a fresh DSO image arriving
                                        gdcc_hrx_initialize_core(NULL, (GDExtensionObjectPtr)&g_engine_marker, true, true, 0x1234u, NULL, 0);
                    CHECK(g_create_callback_calls == 1, "restart must not recreate the hub");
                    CHECK(gdcc_hrx_current_hub() == hub, "restart must keep the same hub");
                    CHECK(fake_get_binding((GDExtensionObjectPtr)&g_engine_marker, (void *)0xDEADu, NULL) == &g_foreign_binding,
                            "the foreign slot-0 binding must be untouched");
                    printf("OK anchor\\n");
                    return 0;
                }
                """;
        var execution = compileLinkAndRun("anchor_probe", FAKE_ENGINE + source, runtimeObjects);
        assertEquals(0, execution.exitCode(), execution::diagnostic);
        assertTrue(execution.output().contains("OK anchor"), execution::diagnostic);
    }

    @Test
    void mismatchedHubShouldBeOrphanedAndReplaced() throws IOException, InterruptedException {
        // A foreign/corrupt binding under our token (bad magic): never traverse it, free the
        // binding, mount a fresh hub, and leave the old one an unreachable island.
        var source = """
                static gdcc_hrx_hub g_bad_hub;
                
                int main(void) {
                    godot_initialize_interface(fake_get_proc_address);
                    memset(&g_bad_hub, 0, sizeof(g_bad_hub));
                    g_bad_hub.magic = 0xBADBADu; // wrong magic
                    fake_append_binding((GDExtensionObjectPtr)&g_engine_marker, (void *)0x2468u, &g_bad_hub);
                    gdcc_hrx_test_reset_image_state(); // simulate a fresh DSO image arriving
                    gdcc_hrx_mode mode = gdcc_hrx_initialize_core(
                            NULL, (GDExtensionObjectPtr)&g_engine_marker, true, true, 0x2468u, NULL, 0);
                    CHECK(mode == GDCC_HRX_MODE_ACTIVE, "mismatch recovery must still activate");
                    CHECK(g_free_binding_calls == 1, "the foreign binding must be dropped exactly once");
                    gdcc_hrx_hub *hub = gdcc_hrx_current_hub();
                    CHECK(hub != NULL && hub != &g_bad_hub && hub->magic == GDCC_HRX_HUB_MAGIC,
                            "a fresh hub must replace the foreign one");
                    CHECK(fake_get_binding((GDExtensionObjectPtr)&g_engine_marker, (void *)0x2468u, NULL) == hub,
                            "the fresh hub must be mounted under the token");
                    printf("OK mismatch\\n");
                    return 0;
                }
                """;
        var execution = compileLinkAndRun("mismatch_probe", FAKE_ENGINE + source, runtimeObjects);
        assertEquals(0, execution.exitCode(), execution::diagnostic);
        assertTrue(execution.output().contains("OK mismatch"), execution::diagnostic);
    }

    @Test
    void mismatchedHubWithFailedProbeShouldFreezeUnavailable() throws IOException, InterruptedException {
        // A foreign/corrupt anchor is NOT proof of a working thunk-page environment: even
        // after orphaning it, a failed execmem probe must freeze the process into
        // HRX_UNAVAILABLE instead of mounting a fresh hub and activating.
        var source = """
                static gdcc_hrx_hub g_bad_hub;
                
                int main(void) {
                    godot_initialize_interface(fake_get_proc_address);
                    memset(&g_bad_hub, 0, sizeof(g_bad_hub));
                    g_bad_hub.magic = 0xBADBADu; // wrong magic
                    fake_append_binding((GDExtensionObjectPtr)&g_engine_marker, (void *)0x2469u, &g_bad_hub);
                    gdcc_hrx_test_reset_image_state(); // simulate a fresh DSO image arriving
                    gdcc_hrx_mode mode = gdcc_hrx_initialize_core(
                            NULL, (GDExtensionObjectPtr)&g_engine_marker, true, false, 0x2469u, NULL, 0);
                    CHECK(mode == GDCC_HRX_MODE_UNAVAILABLE,
                            "a corrupt anchor must not bypass the probe fail-closed decision");
                    CHECK(g_free_binding_calls == 1, "the foreign binding must still be dropped exactly once");
                    CHECK(gdcc_hrx_current_hub() == NULL, "no fresh hub may be mounted without a passing probe");
                    CHECK(g_print_error_count == 1, "the unavailable decision is reported once");
                    printf("OK mismatch-probe\\n");
                    return 0;
                }
                """;
        var execution = compileLinkAndRun("mismatch_probe_false", FAKE_ENGINE + source, runtimeObjects);
        assertEquals(0, execution.exitCode(), execution::diagnostic);
        assertTrue(execution.output().contains("OK mismatch-probe"), execution::diagnostic);
    }

    @Test
    void unavailableModeShouldFailClosedAndConsumeCaptures() throws IOException, InterruptedException {
        // Editor process without executable memory: creation is refused (never a silent
        // fallback to the direct path), the error is reported exactly once, and the caller's
        // capture block is consumed with its free_func (the "failed creation drops the last
        // reference" semantics), so no half-built state escapes.
        var source = """
                static int g_free_calls = 0;
                static void counting_free(void *c) { g_free_calls++; }
                static void impl1(void *c, const GDExtensionConstVariantPtr *a, GDExtensionInt n,
                        GDExtensionVariantPtr r, GDExtensionCallError *e) { (void)c; }
                static GDExtensionBool valid1(void *c) { return true; }
                static const gdcc_hrx_identity IDENTITY_U = {
                    .impl_key = "Worker::u@+1:1", .schema_desc = NULL, .schema_desc_len = 0,
                    .argument_count = 0, .schema_fingerprint = { 1 },
                };
                
                int main(void) {
                    godot_initialize_interface(fake_get_proc_address);
                    gdcc_hrx_test_reset_image_state(); // simulate a fresh DSO image arriving
                    gdcc_hrx_mode mode = gdcc_hrx_initialize_core(
                            NULL, (GDExtensionObjectPtr)&g_engine_marker, true, false, 0x33u, NULL, 0);
                    CHECK(mode == GDCC_HRX_MODE_UNAVAILABLE, "failed probe must resolve to UNAVAILABLE");
                    CHECK(g_print_error_count == 1, "the probe failure is reported once at initialize");
                
                    int captures = 7;
                    godot_Callable cb = gdcc_new_lambda_callable(&captures, 0, impl1, valid1, counting_free, NULL, &IDENTITY_U);
                    CHECK(fake_callable_custom_of(&cb) == NULL, "creation must be refused (invalid Callable)");
                    CHECK(g_free_calls == 1, "captures must be consumed exactly once");
                    CHECK(g_print_error_count == 1, "no per-creation error spam on the first refusal");
                
                    godot_Callable cb2 = gdcc_new_lambda_callable(&captures, 0, impl1, valid1, counting_free, NULL, &IDENTITY_U);
                    CHECK(fake_callable_custom_of(&cb2) == NULL && g_free_calls == 2,
                            "every refusal consumes its captures");
                    CHECK(g_print_error_count == 1, "the unavailable error is one-time");
                    godot_Callable cb3 = gdcc_new_standalone_callable(
                            "utility", "", "print", 0LL, 1, false, true, &IDENTITY_U);
                    CHECK(fake_callable_custom_of(&cb3) == NULL, "standalone creation must also fail closed");
                    printf("OK unavailable\\n");
                    return 0;
                }
                """;
        var execution = compileLinkAndRun("unavailable_probe", FAKE_ENGINE + source, runtimeObjects);
        assertEquals(0, execution.exitCode(), execution::diagnostic);
        assertTrue(execution.output().contains("OK unavailable"), execution::diagnostic);
    }

    @Test
    void directModeShouldKeepTheLegacyDispatchIntact() throws IOException, InterruptedException {
        // Non-editor process: no hub, no anchor, no executable memory; the two creation
        // entries behave exactly as before HR-8 (library function pointers + raw userdata),
        // and the HRX creation APIs stay defensively inert.
        var source = """
                static int g_free_calls = 0;
                static void impl1(void *c, const GDExtensionConstVariantPtr *a, GDExtensionInt n,
                        GDExtensionVariantPtr r, GDExtensionCallError *e) { (void)c; }
                static GDExtensionBool valid1(void *c) { return true; }
                static void free1(void *c) { g_free_calls++; }
                static GDExtensionInt argc1(void *c, GDExtensionBool *ok) { if (ok != NULL) *ok = true; return 5; }
                static const gdcc_hrx_identity IDENTITY_D = {
                    .impl_key = "Worker::d@+1:1", .schema_desc = NULL, .schema_desc_len = 0,
                    .argument_count = 5, .schema_fingerprint = { 1 },
                };
                
                int main(void) {
                    godot_initialize_interface(fake_get_proc_address);
                    gdcc_hrx_test_reset_image_state(); // simulate a fresh DSO image arriving
                    gdcc_hrx_mode mode = gdcc_hrx_initialize_core(
                            NULL, (GDExtensionObjectPtr)&g_engine_marker, false, false, 0x44u, NULL, 0);
                    CHECK(mode == GDCC_HRX_MODE_DIRECT_NON_RELOAD, "non-editor must resolve to DIRECT");
                    CHECK(g_binding_count == 0 && g_create_callback_calls == 0,
                            "direct mode must never touch the anchor");
                    CHECK(gdcc_hrx_current_hub() == NULL, "direct mode must not build a hub");
                
                    int captures = 3;
                    godot_Callable cb = gdcc_new_lambda_callable(&captures, 0, impl1, valid1, free1, argc1, &IDENTITY_D);
                    FakeCallableCustom *custom = fake_callable_custom_of(&cb);
                    CHECK(custom != NULL, "direct creation must succeed");
                    CHECK(custom->call_func == (GDExtensionCallableCustomCall)impl1 && custom->userdata == &captures,
                            "legacy (call_func, userdata) identity must be preserved");
                    CHECK(custom->is_valid_func == valid1 && custom->argc_func == argc1,
                            "legacy callbacks must pass through untouched");
                
                    // The HRX creation APIs stay inert outside ACTIVE mode.
                    godot_Callable refused = gdcc_hrx_create_lambda(&captures, 0, impl1, valid1, free1, 0, &IDENTITY_D, NULL);
                    CHECK(fake_callable_custom_of(&refused) == NULL, "HRX creation must refuse outside ACTIVE");
                    gdcc_hrx_deinitialize(); // must be a harmless no-op
                    CHECK(g_free_calls == 0, "deinitialize must not touch direct callables");
                
                    godot_Callable_destroy(&cb);
                    CHECK(g_free_calls == 1, "the legacy free_func runs exactly once");
                
                    // Standalone direct path: legacy registry interning + destroy_all.
                    godot_Callable sa1 = gdcc_new_standalone_callable("utility", "", "print", 0LL, 1, false, true, &IDENTITY_D);
                    godot_Callable sa2 = gdcc_new_standalone_callable("utility", "", "print", 0LL, 1, false, true, &IDENTITY_D);
                    CHECK(fake_callable_custom_of(&sa1)->userdata == fake_callable_custom_of(&sa2)->userdata,
                            "legacy interning must share the spec");
                    godot_Callable_destroy(&sa1);
                    godot_Callable_destroy(&sa2);
                    gdcc_standalone_callable_registry_destroy_all();
                    CHECK(g_mem_balance == 0, "legacy registry teardown must reclaim everything");
                    printf("OK direct\\n");
                    return 0;
                }
                """;
        var execution = compileLinkAndRun("direct_probe", FAKE_ENGINE + source, runtimeObjects);
        assertEquals(0, execution.exitCode(), execution::diagnostic);
        assertTrue(execution.output().contains("OK direct"), execution::diagnostic);
    }

    @Test
    void identitylessWaiterSpecShouldNeverRebindAcrossReload() throws IOException, InterruptedException {
        // Coroutine signal waiters carry no rebind identity (HR-8 policy): callable this
        // generation, permanently invalid after a reload — a stray connection can never jump
        // into unloaded code. Its post-reload destruction is intentionally skipped (unknown
        // layout) while the shell is reclaimed.
        var source = """
                static int g_impl_calls = 0;
                static void impl1(void *c, const GDExtensionConstVariantPtr *a, GDExtensionInt n,
                        GDExtensionVariantPtr r, GDExtensionCallError *e) { g_impl_calls++; }
                static GDExtensionBool valid1(void *c) { return true; }
                static void noop_destroy(void *c) { (void)c; }
                
                int main(void) {
                    godot_initialize_interface(fake_get_proc_address);
                    gdcc_hrx_test_reset_image_state(); // simulate a fresh DSO image arriving
                    gdcc_hrx_initialize_core(NULL, (GDExtensionObjectPtr)&g_engine_marker, true, true, 0x66u, NULL, 0);
                    int captures = 9;
                    godot_Callable cb = gdcc_hrx_create_lambda(&captures, 0, impl1, valid1, noop_destroy, -1, NULL, NULL);
                    FakeCallableCustom *custom = fake_callable_custom_of(&cb);
                    custom->call_func(custom->userdata, NULL, 0, NULL, NULL);
                    CHECK(g_impl_calls == 1, "waiter must be callable in its own generation");
                
                    gdcc_hrx_deinitialize();
                                        // New generation arrives with an EMPTY rebind table: nothing can rebind the waiter.
                    gdcc_hrx_test_reset_image_state(); // simulate a fresh DSO image arriving
                    gdcc_hrx_initialize_core(NULL, (GDExtensionObjectPtr)&g_engine_marker, true, true, 0x66u, NULL, 0);
                    CHECK(custom->is_valid_func(custom->userdata) == false,
                            "waiter must be permanently invalid after a reload");
                    GDExtensionCallError err = { 0 };
                    godot_Variant ret;
                    custom->call_func(custom->userdata, NULL, 0, &ret, &err);
                    CHECK(g_impl_calls == 1 && err.error == GDEXTENSION_CALL_ERROR_INVALID_METHOD,
                            "a stray waiter must take the engine error path, never old code");
                    godot_Callable_destroy(&cb);
                    CHECK(gdcc_hrx_current_hub()->leaked_capture_count == 1,
                            "post-reload waiter captures are intentionally leaked, not mis-destroyed");
                    printf("OK waiter_no_rebind\\n");
                    return 0;
                }
                """;
        var execution = compileLinkAndRun("waiter_no_rebind_probe", FAKE_ENGINE + source, runtimeObjects);
        assertEquals(0, execution.exitCode(), execution::diagnostic);
        assertTrue(execution.output().contains("OK waiter_no_rebind"), execution::diagnostic);
    }

    @Test
    void retainShouldRebuildAnEqualLookupKeyForSignalDetach() throws IOException, InterruptedException {
        // The coroutine bulk-cancel detach needs a Callable EQUAL to the connection's under
        // Godot's default identity. In thunk mode that is only possible by retaining the same
        // spec: the retained key shares (thunk, spec), drops only the refcount on destroy,
        // and refuses dead/NULL specs.
        var source = """
                static int g_destroy_calls = 0;
                static void impl1(void *c, const GDExtensionConstVariantPtr *a, GDExtensionInt n,
                        GDExtensionVariantPtr r, GDExtensionCallError *e) { (void)c; }
                static GDExtensionBool valid1(void *c) { return true; }
                static void destroy1(void *c) { g_destroy_calls++; }
                
                int main(void) {
                    godot_initialize_interface(fake_get_proc_address);
                    gdcc_hrx_test_reset_image_state(); // simulate a fresh DSO image arriving
                    gdcc_hrx_initialize_core(NULL, (GDExtensionObjectPtr)&g_engine_marker, true, true, 0x77u, NULL, 0);
                    const int balance_base = g_mem_balance;
                    int wait = 1;
                    gdcc_hrx_spec *spec = NULL;
                    godot_Callable cb = gdcc_hrx_create_lambda(&wait, 0, impl1, valid1, destroy1, -1, NULL, &spec);
                    // The fake "connection" retains the Callable; the local handle is dropped.
                    FakeCallableCustom *connection = fake_callable_custom_of(&cb);
                    connection->refs++;
                    godot_Callable_destroy(&cb);
                    CHECK(spec->refcount == 1, "the connection must be the last owner");
                
                    godot_Callable key = gdcc_hrx_callable_retain(spec);
                    CHECK(fake_callable_custom_of(&key) != NULL && spec->refcount == 2,
                            "retain must add a custom object on the same spec");
                    CHECK(fake_callable_equal(connection, fake_callable_custom_of(&key)),
                            "the retained key must compare EQUAL to the connection's callable");
                    godot_Callable_destroy(&key);
                    CHECK(spec->refcount == 1 && !spec->dead, "destroying the key must only decrement");
                
                    fake_callable_custom_release(connection); // connection removal
                    CHECK(g_destroy_calls == 1, "the wait must be released exactly once by the last owner");
                    CHECK(g_mem_balance == balance_base, "everything must be reclaimed");
                
                    // Negative: NULL and dead specs refuse retention.
                    CHECK(fake_callable_custom_of(&(godot_Callable){0}) == NULL, "sanity: empty callable");
                    godot_Callable invalid = gdcc_hrx_callable_retain(NULL);
                    CHECK(fake_callable_custom_of(&invalid) == NULL, "NULL spec must refuse");
                    gdcc_hrx_spec *spec2 = NULL;
                    godot_Callable cb2 = gdcc_hrx_create_lambda(NULL, 0, impl1, valid1, destroy1, -1, NULL, &spec2);
                    gdcc_hrx_deinitialize(); // no sweeper: destroy below only marks dead
                                        godot_Callable_destroy(&cb2);
                    CHECK(spec2->dead, "spec must be dead before the negative retain check");
                    godot_Callable refused = gdcc_hrx_callable_retain(spec2);
                    CHECK(fake_callable_custom_of(&refused) == NULL, "dead spec must refuse retention");
                    gdcc_hrx_test_reset_image_state(); // simulate a fresh DSO image arriving
                    gdcc_hrx_initialize_core(NULL, (GDExtensionObjectPtr)&g_engine_marker, true, true, 0x77u, NULL, 0);
                    printf("OK retain\\n");
                    return 0;
                }
                """;
        var execution = compileLinkAndRun("retain_probe", FAKE_ENGINE + source, runtimeObjects);
        assertEquals(0, execution.exitCode(), execution::diagnostic);
        assertTrue(execution.output().contains("OK retain"), execution::diagnostic);
    }

    @Test
    void gdccHrxRuntimeShouldCompileForEveryBuildableTargetIsa() throws IOException, InterruptedException {
        // Buildability regression pin: LINUX_RISCV64 (no thunk template set) must still
        // COMPILE the runtime — the probe fails closed there instead of breaking the build.
        // The aarch64 and Win64 variants pin the ISA-specific branches (icache flush,
        // VirtualAlloc) that the x86_64 host build never compiles.
        for (var target : List.of("riscv64-linux-gnu", "aarch64-linux-gnu", "x86_64-windows-gnu")) {
            var output = sharedDir.resolve("gdcc_hrx_" + target + ".o");
            var command = new ArrayList<String>();
            command.add(zig.toString());
            command.add("cc");
            command.add("-std=c23");
            command.add("-D_DEFAULT_SOURCE");
            command.add("-target");
            command.add(target);
            command.add("-I" + GODOT_INCLUDE_DIR);
            command.add("-I" + GDCC_INCLUDE_DIR);
            command.add("-c");
            command.add(GDCC_INCLUDE_DIR.resolve("gdcc_hrx.c").toString());
            command.add("-o");
            command.add(output.toString());
            var process = new ProcessBuilder(command).redirectErrorStream(true).start();
            var processOutput = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.waitFor(), () -> String.join(" ", command) + "\n" + processOutput);
        }
    }

    // ---------------------------------------------------------------------------
    // Harness (mirrors GdccCoroutineRuntimeSmokeTest conventions).
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

    /// Shared C fixture layer: a fake Godot engine behind the GDExtension interface
    /// function-pointer table, plus `gdcc_callable.h` itself (the three-mode dispatcher under
    /// test). Fake Callable custom objects keep every callback so probes can invoke the real
    /// thunks; the binding table follows the engine's get_instance_binding append semantics.
    private static final String FAKE_ENGINE = """
            #include <godot_binding.h>
            
            static GDExtensionClassLibraryPtr class_library = NULL;
            
            #include <gdcc_callable.h>
            
            // Test-only seam from gdcc_hrx.c (compiled with -DGDCC_HRX_TEST_HOOKS):
            // simulate a NEW library image between generations.
            void gdcc_hrx_test_reset_image_state(void);
            
            #include <stddef.h>
            #include <stdio.h>
            #include <stdlib.h>
            #include <string.h>
            
            #define FAKE_MAX_BINDINGS 16
            
            static int g_mem_balance = 0;
            static int g_print_error_count = 0;
            static char g_last_error[256] = {0};
            static int g_engine_marker = 0;
            
            static void fail(const char *msg) {
                printf("FAIL %s\\n", msg);
                fflush(stdout);
                exit(1);
            }
            #define CHECK(cond, msg) do { if (!(cond)) fail(msg); } while (0)
            
            static void log_event(const char *event) {
                printf("EV %s\\n", event);
                fflush(stdout);
            }
            
            // ---------- fake GDExtension interface entry points ----------
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
                g_print_error_count++;
                snprintf(g_last_error, sizeof(g_last_error), "%s", desc != NULL ? desc : "");
            }
            
            // ---------- fake Callable custom object layer ----------
            // godot_Callable layout: bytes [0..8) FakeCallableCustom pointer (NULL = invalid).
            typedef struct FakeCallableCustom {
                void *userdata;
                GDExtensionCallableCustomCall call_func;
                GDExtensionCallableCustomIsValid is_valid_func;
                GDExtensionCallableCustomFree free_func;
                GDExtensionCallableCustomGetArgumentCount argc_func;
                int refs;
            } FakeCallableCustom;
            
            static FakeCallableCustom *fake_callable_custom_of(const godot_Callable *callable) {
                FakeCallableCustom *custom;
                memcpy(&custom, callable, sizeof(custom));
                return custom;
            }
            static void fake_callable_custom_write(godot_Callable *callable, FakeCallableCustom *custom) {
                memset(callable, 0, sizeof(*callable));
                memcpy(callable, &custom, sizeof(custom));
            }
            static void fake_callable_custom_release(FakeCallableCustom *custom) {
                if (custom == NULL) return;
                custom->refs--;
                if (custom->refs == 0) {
                    if (custom->free_func != NULL) custom->free_func(custom->userdata);
                    fake_mem_free(custom);
                }
            }
            // Godot's default custom-Callable equality: (call_func, userdata).
            static int fake_callable_equal(const FakeCallableCustom *a, const FakeCallableCustom *b) {
                if (a == b) return 1;
                if (a == NULL || b == NULL) return 0;
                return a->call_func == b->call_func && a->userdata == b->userdata;
            }
            static void fake_callable_custom_create2(GDExtensionUninitializedTypePtr r_callable, GDExtensionCallableCustomInfo2 *info) {
                FakeCallableCustom *custom = fake_mem_alloc(sizeof(FakeCallableCustom));
                if (custom == NULL) fail("callable custom allocation failed");
                custom->userdata = info->callable_userdata;
                custom->call_func = info->call_func;
                custom->is_valid_func = info->is_valid_func;
                custom->free_func = info->free_func;
                custom->argc_func = info->get_argument_count_func;
                custom->refs = 1;
                fake_callable_custom_write((godot_Callable *)r_callable, custom);
            }
            static void fake_callable_ptr_destructor(GDExtensionTypePtr ptr) {
                fake_callable_custom_release(fake_callable_custom_of((const godot_Callable *)ptr));
            }
            static void fake_ptr_destructor_noop(GDExtensionTypePtr ptr) {
                (void)ptr;
            }
            static GDExtensionPtrDestructor fake_variant_get_ptr_destructor(GDExtensionVariantType type) {
                if (type == GDEXTENSION_VARIANT_TYPE_CALLABLE) return fake_callable_ptr_destructor;
                return fake_ptr_destructor_noop;
            }
            
            // ---------- fake instance-binding table (engine get_instance_binding semantics) ----------
            static struct { GDExtensionObjectPtr obj; void *token; void *binding; } g_bindings[FAKE_MAX_BINDINGS];
            static int g_binding_count = 0;
            static int g_create_callback_calls = 0;
            static int g_free_binding_calls = 0;
            static int g_anchor_free_cb_was_null = -1;
            static int g_anchor_reference_cb_was_null = -1;
            
            static void fake_append_binding(GDExtensionObjectPtr obj, void *token, void *binding) {
                if (g_binding_count >= FAKE_MAX_BINDINGS) fail("binding table overflow");
                g_bindings[g_binding_count].obj = obj;
                g_bindings[g_binding_count].token = token;
                g_bindings[g_binding_count].binding = binding;
                g_binding_count++;
            }
            static void *fake_get_binding(GDExtensionObjectPtr obj, void *token, const GDExtensionInstanceBindingCallbacks *callbacks) {
                // Real engine semantics (object.cpp:2116-2147): the first token match wins;
                // when its binding is NULL (miss OR tombstone) and callbacks are given, a NEW
                // slot is appended and kept even if create_callback returns NULL.
                void *binding = NULL;
                for (int i = 0; i < g_binding_count; i++) {
                    if (g_bindings[i].obj == obj && g_bindings[i].token == token) {
                        binding = g_bindings[i].binding;
                        break;
                    }
                }
                if (binding != NULL || callbacks == NULL) return binding;
                // The engine copies free/reference into the slot; HRX requires both to be NULL.
                g_anchor_free_cb_was_null = callbacks->free_callback == NULL ? 1 : 0;
                g_anchor_reference_cb_was_null = callbacks->reference_callback == NULL ? 1 : 0;
                if (callbacks->create_callback == NULL) return NULL;
                g_create_callback_calls++;
                binding = callbacks->create_callback(token, obj);
                fake_append_binding(obj, token, binding);
                return binding;
            }
            static void fake_free_binding(GDExtensionObjectPtr obj, void *token) {
                // Real engine semantics (object.cpp:2165-2185): remove the FIRST token match
                // and shift the remaining slots down.
                for (int i = 0; i < g_binding_count; i++) {
                    if (g_bindings[i].obj == obj && g_bindings[i].token == token) {
                        for (int j = i; j + 1 < g_binding_count; j++) {
                            g_bindings[j] = g_bindings[j + 1];
                        }
                        g_binding_count--;
                        g_free_binding_calls++;
                        return;
                    }
                }
            }
            
            static void fake_unused_interface(void) {
            }
            static GDExtensionInterfaceFunctionPtr fake_get_proc_address(const char *name) {
                if (strcmp(name, "mem_alloc") == 0) return (GDExtensionInterfaceFunctionPtr)fake_mem_alloc;
                if (strcmp(name, "mem_realloc") == 0) return (GDExtensionInterfaceFunctionPtr)fake_mem_realloc;
                if (strcmp(name, "mem_free") == 0) return (GDExtensionInterfaceFunctionPtr)fake_mem_free;
                if (strcmp(name, "print_error") == 0) return (GDExtensionInterfaceFunctionPtr)fake_print_error;
                if (strcmp(name, "callable_custom_create2") == 0) return (GDExtensionInterfaceFunctionPtr)fake_callable_custom_create2;
                if (strcmp(name, "variant_get_ptr_destructor") == 0) return (GDExtensionInterfaceFunctionPtr)fake_variant_get_ptr_destructor;
                if (strcmp(name, "object_get_instance_binding") == 0) return (GDExtensionInterfaceFunctionPtr)fake_get_binding;
                if (strcmp(name, "object_free_instance_binding") == 0) return (GDExtensionInterfaceFunctionPtr)fake_free_binding;
                return (GDExtensionInterfaceFunctionPtr)fake_unused_interface;
            }
            """;
}
