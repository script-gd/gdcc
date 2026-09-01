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

/// Zig-gated pure-C smoke tests for the coroutine runtime
/// (`gdcc/minicoro.c` + `gdcc/gdcc_coroutine.c`; contract:
/// doc/gdcc_runtime_lib.md §Coroutine Runtime). Skipped via assumption when no zig is on
/// the machine. The fixtures fake the Godot interface at the GDExtension function-pointer
/// level, so finalize/cancel/waiter logic runs against the real production C code paths;
/// branches that need real Godot objects (signal connect, dynamic external-object layer)
/// are deferred to the Godot e2e suite.
class GdccCoroutineRuntimeSmokeTest {
    private static final Path GODOT_INCLUDE_DIR = Path.of("src/main/c/codegen/include_451/godot").toAbsolutePath().normalize();
    private static final Path GDCC_INCLUDE_DIR = Path.of("src/main/c/codegen/include_451/gdcc").toAbsolutePath().normalize();

    @TempDir
    private static Path sharedDir;

    private static Path zig;
    private static List<Path> runtimeObjects;

    @BeforeAll
    static void compileRuntimeObjects() throws IOException, InterruptedException {
        zig = ZigUtil.findZig();
        Assumptions.assumeTrue(zig != null, "Zig executable is required for coroutine runtime C smoke tests");
        runtimeObjects = List.of(
                compileObject(zig, GODOT_INCLUDE_DIR.resolve("godot_binding.c"), sharedDir.resolve("godot_binding.o")),
                compileObject(zig, GDCC_INCLUDE_DIR.resolve("minicoro.c"), sharedDir.resolve("minicoro.o")),
                compileObject(zig, GDCC_INCLUDE_DIR.resolve("gdcc_coroutine.c"), sharedDir.resolve("gdcc_coroutine.o"))
        );
    }

    @Test
    void minicoroRoundTripShouldSuspendResumeAndDie() throws IOException, InterruptedException {
        // Anchors the vendored minicoro under the locked ASM + vmem configuration:
        // status sequence SUSPENDED -> SUSPENDED -> DEAD and user_data round-trip.
        var source = """
                #include <gdcc_coroutine.h>
                #include <stdio.h>
                
                typedef struct ProbeCtx {
                    int value;
                    int yields;
                } ProbeCtx;
                
                static void probe_body(mco_coro *co) {
                    ProbeCtx *ctx = mco_get_user_data(co);
                    ctx->value = 41;
                    ctx->yields++;
                    mco_yield(co);
                    ctx->value = 42;
                    ctx->yields++;
                }
                
                int main(void) {
                    ProbeCtx ctx = {0, 0};
                    mco_desc desc = mco_desc_init(probe_body, GDCC_CORO_STACK_SIZE);
                    desc.user_data = &ctx;
                    mco_coro *co = NULL;
                    if (mco_create(&co, &desc) != MCO_SUCCESS) return 10;
                    if (mco_status(co) != MCO_SUSPENDED) return 11;
                    if (mco_resume(co) != MCO_SUCCESS) return 12;
                    if (mco_status(co) != MCO_SUSPENDED) return 13;
                    if (ctx.value != 41 || ctx.yields != 1) return 14;
                    if (mco_resume(co) != MCO_SUCCESS) return 15;
                    if (mco_status(co) != MCO_DEAD) return 16;
                    if (ctx.value != 42 || ctx.yields != 2) return 17;
                    if (mco_destroy(co) != MCO_SUCCESS) return 18;
                    printf("OK roundtrip\\n");
                    return 0;
                }
                """;
        var execution = compileLinkAndRun("roundtrip_probe", source, runtimeObjects);
        assertEquals(0, execution.exitCode(), execution::diagnostic);
        assertTrue(execution.output().contains("OK roundtrip"), execution::diagnostic);
    }

    @Test
    void mcoCreateFailureShouldReportOutOfMemory() throws IOException, InterruptedException {
        // Anchors the OOM semantics the entry-thunk contract relies on: a failing
        // allocator makes mco_create report failure and leave no half-created coroutine.
        var source = """
                #include <gdcc_coroutine.h>
                #include <stdio.h>
                
                static void probe_body(mco_coro *co) {
                    (void)co;
                }
                
                static void *failing_alloc(size_t size, void *allocator_data) {
                    (void)size;
                    (void)allocator_data;
                    return NULL;
                }
                
                int main(void) {
                    mco_desc desc = mco_desc_init(probe_body, GDCC_CORO_STACK_SIZE);
                    desc.alloc_cb = failing_alloc;
                    mco_coro *co = NULL;
                    mco_result res = mco_create(&co, &desc);
                    if (res == MCO_SUCCESS) return 10;
                    if (co != NULL) return 11;
                    printf("OK create_failure res=%d\\n", (int)res);
                    return 0;
                }
                """;
        var execution = compileLinkAndRun("create_failure_probe", source, runtimeObjects);
        assertEquals(0, execution.exitCode(), execution::diagnostic);
        assertTrue(execution.output().contains("OK create_failure"), execution::diagnostic);
    }

    @Test
    void coroStateSlotHelpersShouldHonorNullContract() throws IOException, InterruptedException {
        // Anchors the compiler-local slot helper contract used by `compiler::GdccCoroState`
        // codegen: init is the nullary call-and-assign shape yielding NULL, and destroy on a
        // NULL (moved-from / never-written) slot is a no-op.
        var source = """
                #include <gdcc_coroutine.h>
                #include <stdio.h>
                
                int main(void) {
                    godot_Object *slot = gdcc_coro_state_slot_init();
                    if (slot != NULL) return 10;
                    // Moved-from / never-written slot: destroy must be a no-op.
                    gdcc_coro_state_slot_destroy(&slot);
                    if (slot != NULL) return 11;
                    printf("OK slot_helpers\\n");
                    return 0;
                }
                """;
        var execution = compileLinkAndRun("slot_helpers_probe", source, runtimeObjects);
        assertEquals(0, execution.exitCode(), execution::diagnostic);
        assertTrue(execution.output().contains("OK slot_helpers"), execution::diagnostic);
    }

    @Test
    void awaitStateFinalizeCascadeShouldHonorOrderingInvariants() throws IOException, InterruptedException {
        // Anchors: done fast path through `copy_ret_slot`; finalize invariant order
        // (pack -> done -> waiters -> emit); waiter-kind dispatch (typed via copy_ret_slot,
        // Variant via one private result_cache copy each); pack_result PRESERVES the typed
        // return slot; resume BEFORE edge release; nested finalize cascade (S -> W1 -> X);
        // waiters resumed before the external emit.
        var source = FAKE_ENGINE + """
                
                static FakeState g_S, g_W1, g_W2;
                static mco_coro *g_s_co, *g_w1_co, *g_w2_co, *g_x_co;
                static int64_t g_w1_out, g_x_out, g_late_out;
                static godot_Variant g_w2_op, g_w2_out;
                
                static void s_body(mco_coro *co) {
                    log_event("s_yield");
                    mco_yield(co);
                    log_event("s_end");
                    fake_state_write_ret(&g_S, 42);
                }
                
                static void w1_body(mco_coro *co) {
                    log_event("w1_await");
                    // Typed channel: the awaiter's int64 result slot is handed over directly.
                    gdcc_coro_await_state(&g_S.header, &g_w1_out, co, &g_W1.header);
                    CHECK(!g_W1.header.cancel, "w1 must not be cancelled");
                    log_event("w1_resumed");
                    CHECK(g_S.header.done, "w1 resumed before done was published");
                    CHECK(g_w1_out == 42, "w1 out must be a typed copy of the result");
                    CHECK(fake_refs_of((GDExtensionObjectPtr)&g_W1) == 1, "w1 edge must still be held during its resume");
                    fake_state_write_ret(&g_W1, 7);
                }
                
                static void w2_body(mco_coro *co) {
                    log_event("w2_await");
                    // Variant channel through the dynamic dispatch: mixed with the typed
                    // waiters above to anchor finalize's per-kind dispatch.
                    fake_variant_set_object(&g_w2_op, g_S.fake_id);
                    fake_add_ref((GDExtensionObjectPtr)&g_S); // the operand Variant's retain
                    gdcc_coro_await_dynamic(&g_w2_op, &g_w2_out, co, &g_W2.header);
                    CHECK(!g_W2.header.cancel, "w2 must not be cancelled");
                    log_event("w2_resumed");
                    CHECK(fake_variant_as_int(&g_w2_out) == 42, "w2 out must be a private Variant copy of the result");
                    CHECK(fake_refs_of((GDExtensionObjectPtr)&g_W2) == 1, "w2 edge must still be held during its resume");
                    fake_state_write_ret(&g_W2, 100);
                }
                
                static void x_body(mco_coro *co) {
                    log_event("x_await");
                    // Raw coroutine awaiter without a state object: no edge, no cascade.
                    gdcc_coro_await_state(&g_W1.header, &g_x_out, co, NULL);
                    log_event("x_resumed");
                    CHECK(g_x_out == 7, "x must receive W1's cascaded result through the typed channel");
                }
                
                int main(void) {
                    if (!godot_initialize_interface(fake_get_proc_address)) fail("interface init");
                    fake_state_init(&g_S, "S");
                    fake_state_init(&g_W1, "W1");
                    fake_state_init(&g_W2, "W2");
                    g_s_co = fake_make_coro(s_body, &g_S);
                    g_w1_co = fake_make_coro(w1_body, &g_W1);
                    g_w2_co = fake_make_coro(w2_body, &g_W2);
                    g_x_co = fake_make_coro(x_body, NULL);
                
                    mco_resume(g_s_co);   // S reaches its suspension point
                    CHECK(mco_status(g_s_co) == MCO_SUSPENDED, "S must be suspended");
                    // Each awaiter's call site holds an OWNED callee reference (the thunk
                    // out_state); await_state must consume it before yielding.
                    fake_add_ref((GDExtensionObjectPtr)&g_S);
                    mco_resume(g_w1_co);  // W1 registers a typed waiter on S and suspends
                    CHECK(fake_refs_of((GDExtensionObjectPtr)&g_S) == 1,
                            "await_state must cut the call-site callee edge before yielding");
                    fake_add_ref((GDExtensionObjectPtr)&g_W1); // X's call-site ref on W1
                    mco_resume(g_x_co);   // X registers on W1 and suspends
                    CHECK(fake_refs_of((GDExtensionObjectPtr)&g_W1) == 2,
                            "X's call-site ref must be consumed even without an awaiter edge");
                    mco_resume(g_w2_co);  // W2 registers a Variant waiter on S and suspends
                    CHECK(fake_refs_of((GDExtensionObjectPtr)&g_S) == 1,
                            "dynamic own-state must cut the operand callee edge before yielding");
                    fake_drop_ref((GDExtensionObjectPtr)&g_W1); // main detaches the awaiter creator refs
                    fake_drop_ref((GDExtensionObjectPtr)&g_W2);
                
                    mco_resume(g_s_co);   // S runs to completion -> MCO_DEAD
                    CHECK(mco_status(g_s_co) == MCO_DEAD, "S must be dead after its second resume");
                    gdcc_coro_finalize(&g_S.header); // entry-thunk role: DEAD at a resume return point
                    CHECK(g_S.ret_initialized && g_S.ret_slot == 42,
                            "pack_result must preserve the typed return slot for typed waiters");
                
                    // Connect-after-done fast path: immediate typed copy, no suspend.
                    fake_add_ref((GDExtensionObjectPtr)&g_S); // the call site's OWNED ref
                    gdcc_coro_await_state(&g_S.header, &g_late_out, g_s_co, NULL);
                    log_event("late_fast_path");
                    CHECK(g_late_out == 42, "late await must read the preserved typed slot");
                
                    CHECK(mco_status(g_x_co) == MCO_DEAD, "X must have completed through the cascade");
                    mco_destroy(g_x_co); // X is a raw coroutine: no state object owns its stack
                    fake_drop_ref((GDExtensionObjectPtr)&g_S); // main releases the last S reference
                
                    CHECK(g_print_error_count == 0, "no runtime errors expected");
                    CHECK(g_mem_balance == 0, "waiter nodes must all be freed");
                    CHECK(g_variant_copy_count == 1, "only W2's Variant waiter copies from result_cache");
                    CHECK(g_variant_destroy_count == 7,
                            "three destroy-then-write packs + three state frees + one dynamic operand reset");
                    CHECK(g_S.copy_ret_calls == 2 && g_W1.copy_ret_calls == 1 && g_W2.copy_ret_calls == 0,
                            "typed channel usage: w1 + late await read S, x reads W1, Variant waiter never does");
                    printf("OK await_state_finalize\\n");
                    return 0;
                }
                """;
        var execution = compileLinkAndRun("await_state_probe", source, runtimeObjects);
        assertEquals(0, execution.exitCode(), execution::diagnostic);
        assertEvents(execution, List.of(
                "s_yield",
                "w1_await",
                "x_await",
                "w2_await",
                "s_end",
                "pack_result:S",
                "w2_resumed",
                "pack_result:W2",
                "emit:W2",
                "destroy_slot:W2",
                "free:W2",
                "copy_ret:S",
                "w1_resumed",
                "pack_result:W1",
                "copy_ret:W1",
                "x_resumed",
                "emit:W1",
                "destroy_slot:W1",
                "free:W1",
                "emit:S",
                "copy_ret:S",
                "late_fast_path",
                "destroy_slot:S",
                "free:S"
        ));
        assertTrue(execution.output().contains("OK await_state_finalize"), execution::diagnostic);
    }

    @Test
    void cancelShouldCascadeAbandonmentWithoutFinalizeOrLeaks() throws IOException, InterruptedException {
        // Chain-abandonment anchor (A awaits B awaits emitter-held C; emitter dies):
        // every state is cancel-resumed into its cleanup path; never finalized, never
        // emitted; awaiter typed `out` slots stay unwritten; waiter nodes never leak.
        // Cancel NEVER destroys the typed return slot - the generated free_instance does
        // (exactly once). Also covers cancel/finalize mutual exclusion, idempotent cancel,
        // and co == NULL.
        var source = FAKE_ENGINE + """
                
                static FakeState g_A, g_B, g_C, g_D;
                static mco_coro *g_a_co, *g_b_co, *g_c_co;
                static int64_t g_a_out, g_b_out;
                
                static void c_body(mco_coro *co) {
                    log_event("c_start");
                    mco_yield(co); // suspended "on the emitter signal" (modeled by the emitter edge)
                    CHECK(g_C.header.cancel, "c must only resume through cancel");
                    log_event("c_cleanup");
                    fake_state_write_ret(&g_C, 0); // __prepare__ default consumed by the __finally__ analog
                }
                
                static void b_body(mco_coro *co) {
                    log_event("b_await");
                    gdcc_coro_await_state(&g_C.header, &g_b_out, co, &g_B.header);
                    CHECK(g_B.header.cancel, "b must only resume through cancel");
                    log_event("b_cleanup");
                    fake_state_write_ret(&g_B, 0);
                }
                
                static void a_body(mco_coro *co) {
                    log_event("a_await");
                    gdcc_coro_await_state(&g_B.header, &g_a_out, co, &g_A.header);
                    CHECK(g_A.header.cancel, "a must only resume through cancel");
                    log_event("a_cleanup");
                    fake_state_write_ret(&g_A, 0);
                }
                
                int main(void) {
                    if (!godot_initialize_interface(fake_get_proc_address)) fail("interface init");
                    fake_state_init(&g_A, "A");
                    fake_state_init(&g_B, "B");
                    fake_state_init(&g_C, "C");
                    fake_state_init(&g_D, "D"); // never gets a coroutine: co == NULL tolerance
                    g_a_co = fake_make_coro(a_body, &g_A);
                    g_b_co = fake_make_coro(b_body, &g_B);
                    g_c_co = fake_make_coro(c_body, &g_C);
                    g_a_out = -7; // sentinels: abandoned awaiter slots must stay untouched
                    g_b_out = -7;
                
                    mco_resume(g_c_co);                       // C suspends
                    fake_add_ref((GDExtensionObjectPtr)&g_C); // emitter connection edge
                    // B's call site holds the thunk's OWNED ref on C (the init ref), and
                    // await_state must consume it before yielding.
                    mco_resume(g_b_co);                       // B registers on C and suspends
                    CHECK(fake_refs_of((GDExtensionObjectPtr)&g_C) == 1,
                            "C must be held only by its emitter edge after B's registration");
                    mco_resume(g_a_co);                       // A registers on B, consuming A's thunk ref on B
                    CHECK(fake_refs_of((GDExtensionObjectPtr)&g_B) == 1,
                            "B must be held only by its wait edge after A's registration");
                    fake_drop_ref((GDExtensionObjectPtr)&g_A); // fire-and-forget root drop
                    CHECK(fake_refs_of((GDExtensionObjectPtr)&g_A) == 1,
                            "A must be held only by its wait edge");
                
                    // Emitter dies: the last C reference drops and the abandonment cascade runs.
                    fake_drop_ref((GDExtensionObjectPtr)&g_C);
                
                    CHECK(g_A.header.cancel && g_B.header.cancel && g_C.header.cancel, "every state must be cancelled");
                    CHECK(g_A.pack_calls == 0 && g_B.pack_calls == 0 && g_C.pack_calls == 0, "cancel must never pack");
                    CHECK(g_A.emit_calls == 0 && g_B.emit_calls == 0 && g_C.emit_calls == 0, "cancel must never emit");
                    CHECK(g_a_out == -7 && g_b_out == -7, "abandoned awaiter out slots must stay unwritten");
                    CHECK(g_A.destroy_slot_calls == 1 && g_B.destroy_slot_calls == 1 && g_C.destroy_slot_calls == 1,
                            "the return slot is destroyed exactly once, by free_instance");
                
                    gdcc_coro_cancel(&g_A.header);   // idempotent no-op after cancel+free
                    gdcc_coro_finalize(&g_C.header); // no-op: finalize is locked out after cancel
                    gdcc_coro_finalize(&g_A.header);
                    CHECK(g_A.pack_calls == 0 && g_C.pack_calls == 0, "post-cancel finalize must stay a no-op");
                
                    // Phase-split anchor: PREDELETE (cancel) must not touch the return slot;
                    // free_instance destroys it exactly once - even with co == NULL.
                    fake_fire_predelete((GDExtensionObjectPtr)&g_D);
                    CHECK(g_D.header.cancel, "PREDELETE must run the cancel path");
                    CHECK(g_D.destroy_slot_calls == 0, "cancel must never destroy the return slot");
                    fake_free_instance((GDExtensionObjectPtr)&g_D);
                    CHECK(g_D.destroy_slot_calls == 1, "free_instance destroys the return slot exactly once");
                
                    CHECK(g_print_error_count == 0, "no runtime errors expected");
                    CHECK(g_mem_balance == 0, "waiter nodes must all be freed");
                    CHECK(g_variant_copy_count == 0, "no result copies on the abandonment path");
                    CHECK(g_variant_destroy_count == 4, "only the four result caches are destroyed");
                    printf("OK cancel_cascade\\n");
                    return 0;
                }
                """;
        var execution = compileLinkAndRun("cancel_cascade_probe", source, runtimeObjects);
        assertEquals(0, execution.exitCode(), execution::diagnostic);
        assertEvents(execution, List.of(
                "c_start",
                "b_await",
                "a_await",
                "c_cleanup",
                "b_cleanup",
                "a_cleanup",
                "destroy_slot:A",
                "free:A",
                "destroy_slot:B",
                "free:B",
                "destroy_slot:C",
                "free:C",
                "destroy_slot:D",
                "free:D"
        ));
        assertTrue(execution.output().contains("OK cancel_cascade"), execution::diagnostic);
    }

    @Test
    void identifyShouldRejectNonStateObjects() throws IOException, InterruptedException {
        // Anchors `gdcc_coro_state_identify`: valid token+magic round-trip; rejection of
        // objects without the dedicated token binding, of bindings under a foreign token,
        // of bindings with a corrupted magic, and of NULL.
        var source = FAKE_ENGINE + """
                
                static FakeState g_S1, g_S2;
                static char g_foreign_storage;
                static char g_foreign_token;
                
                int main(void) {
                    if (!godot_initialize_interface(fake_get_proc_address)) fail("interface init");
                    fake_state_init(&g_S1, "S1");
                    CHECK(gdcc_coro_state_identify((GDExtensionObjectPtr)&g_S1) == &g_S1.header,
                            "a live state object must identify back to its header");
                    int reject_count = g_binding_set_reject_count;
                    fake_set_binding((GDExtensionObjectPtr)&g_S1, &g_foreign_token, &g_S2.header, NULL);
                    CHECK(g_binding_set_reject_count == reject_count + 1,
                            "set_instance_binding must reject a second slot-zero write");
                    CHECK(gdcc_coro_state_identify((GDExtensionObjectPtr)&g_S1) == &g_S1.header,
                            "rejected slot-zero overwrite must preserve the coroutine token binding");
                    CHECK(gdcc_coro_state_identify((GDExtensionObjectPtr)&g_foreign_storage) == NULL,
                            "object without the token binding must be rejected");
                    fake_set_binding((GDExtensionObjectPtr)&g_foreign_storage, &g_foreign_token, &g_S1.header, NULL);
                    CHECK(gdcc_coro_state_identify((GDExtensionObjectPtr)&g_foreign_storage) == NULL,
                            "a binding under a foreign token must be rejected");
                    fake_state_init(&g_S2, "S2");
                    g_S2.header.magic = UINT64_C(0xBAD); // corrupted binding payload
                    CHECK(gdcc_coro_state_identify((GDExtensionObjectPtr)&g_S2) == NULL,
                            "magic mismatch must be rejected");
                    CHECK(gdcc_coro_state_identify(NULL) == NULL, "NULL must be rejected");
                
                    fake_drop_ref((GDExtensionObjectPtr)&g_S1); // tidy teardown
                    g_S2.header.magic = GDCC_CORO_STATE_MAGIC;
                    fake_drop_ref((GDExtensionObjectPtr)&g_S2);
                    CHECK(g_print_error_count == 0, "no runtime errors expected");
                    printf("OK identify\\n");
                    return 0;
                }
                """;
        var execution = compileLinkAndRun("identify_probe", source, runtimeObjects);
        assertEquals(0, execution.exitCode(), execution::diagnostic);
        assertTrue(execution.output().contains("OK identify"), execution::diagnostic);
    }

    @Test
    void awaitDynamicShouldDispatchWithoutEngineBranches() throws IOException, InterruptedException {
        // Anchors the pure-C layers of `gdcc_coro_await_dynamic`: non-object pass-through,
        // nil/null-object pass-through, freed-object runtime error, and the own-state-object
        // channel (done fast path + suspend/register/resume delegation). The external-object
        // duck-type and TYPE_SIGNAL extraction need real engine objects and stay with the
        // Godot e2e suite.
        var source = FAKE_ENGINE + """
                
                static FakeState g_S5, g_W5;
                static mco_coro *g_w5_co;
                static godot_Variant g_op, g_out, g_w5_out, g_op5;
                
                static void w5_body(mco_coro *co) {
                    log_event("w5_await_dynamic");
                    gdcc_coro_await_dynamic(&g_op5, &g_w5_out, co, &g_W5.header);
                    CHECK(!g_W5.header.cancel, "w5 must not be cancelled");
                    log_event("w5_resumed");
                    CHECK(fake_variant_as_int(&g_w5_out) == 55, "dynamic own-state resume value mismatch");
                    fake_state_write_ret(&g_W5, 0);
                }
                
                int main(void) {
                    if (!godot_initialize_interface(fake_get_proc_address)) fail("interface init");
                
                    // Non-object operand: pass-through copy, no suspend.
                    fake_variant_set_int(&g_op, 42);
                    memset(&g_out, 0xAA, sizeof(g_out));
                    gdcc_coro_await_dynamic(&g_op, &g_out, NULL, NULL);
                    CHECK(fake_variant_as_int(&g_out) == 42, "int operand must pass through");
                
                    // Nil operand: pass-through.
                    memset(&g_op, 0, sizeof(g_op));
                    memset(&g_out, 0xAA, sizeof(g_out));
                    gdcc_coro_await_dynamic(&g_op, &g_out, NULL, NULL);
                    CHECK(fake_variant_type_of(&g_out) == GDEXTENSION_VARIANT_TYPE_NIL, "nil operand must pass through");
                
                    // Null object payload: pass-through.
                    fake_variant_set_object(&g_op, 0);
                    memset(&g_out, 0xAA, sizeof(g_out));
                    gdcc_coro_await_dynamic(&g_op, &g_out, NULL, NULL);
                    CHECK(fake_variant_type_of(&g_out) == GDEXTENSION_VARIANT_TYPE_OBJECT
                                    && fake_variant_object_id_of(&g_out) == 0,
                            "null object payload must pass through");
                
                    // Freed object: Godot-aligned runtime error, nil out, no suspend.
                    fake_register_freed_object(77);
                    fake_variant_set_object(&g_op, 77);
                    memset(&g_out, 0xAA, sizeof(g_out));
                    gdcc_coro_await_dynamic(&g_op, &g_out, NULL, NULL);
                    CHECK(g_print_error_count == 1, "freed object must report exactly one error");
                    CHECK(strstr(g_last_error, "Trying to await on a freed object.") != NULL,
                            "freed object error message must match Godot");
                    CHECK(fake_variant_type_of(&g_out) == GDEXTENSION_VARIANT_TYPE_NIL, "freed object must resume with nil");
                
                    // Static-path NULL callee: runtime error, and the typed out slot keeps
                    // its caller-side default (there is no typed nil to write).
                    int64_t typed_out = -7;
                    gdcc_coro_await_state(NULL, &typed_out, NULL, NULL);
                    CHECK(g_print_error_count == 2, "null callee must report exactly one error");
                    CHECK(typed_out == -7, "the typed failure path must leave the out slot untouched");
                
                    // Own state object, still running: dynamic dispatch delegates to the
                    // direct C-level waiter channel.
                    fake_state_init(&g_S5, "S5");
                    fake_state_init(&g_W5, "W5");
                    fake_variant_set_object(&g_op5, g_S5.fake_id);
                    fake_add_ref((GDExtensionObjectPtr)&g_S5); // the operand Variant's retain
                    g_w5_co = fake_make_coro(w5_body, &g_W5);
                    mco_resume(g_w5_co);
                    CHECK(mco_status(g_w5_co) == MCO_SUSPENDED, "w5 must suspend on the live state object");
                    CHECK(fake_refs_of((GDExtensionObjectPtr)&g_S5) == 1,
                            "dynamic own-state must cut the operand callee edge before yielding");
                    CHECK(fake_variant_type_of(&g_op5) == GDEXTENSION_VARIANT_TYPE_NIL,
                            "the operand must be reset to nil on the suspend path");
                    fake_drop_ref((GDExtensionObjectPtr)&g_W5);
                    fake_state_write_ret(&g_S5, 55);
                    gdcc_coro_finalize(&g_S5.header);
                    // w5 completed through the cascade (its coroutine stack is already
                    // destroyed by W5's state_free, so no mco_status check here).
                    CHECK(g_S5.ret_initialized && g_S5.ret_slot == 55,
                            "pack_result must preserve the typed return slot after finalize");
                
                    // Own state object after completion: done fast path through the dynamic layer.
                    fake_variant_set_object(&g_op5, g_S5.fake_id);
                    fake_add_ref((GDExtensionObjectPtr)&g_S5); // the operand Variant's retain
                    memset(&g_out, 0xAA, sizeof(g_out));
                    gdcc_coro_await_dynamic(&g_op5, &g_out, NULL, NULL);
                    CHECK(fake_variant_as_int(&g_out) == 55, "done fast path must read the cached result");
                    CHECK(fake_variant_type_of(&g_op5) == GDEXTENSION_VARIANT_TYPE_OBJECT,
                            "the done fast path must leave the operand untouched");
                    godot_variant_destroy(&g_op5); // the frame eventually destructs the temp
                
                    fake_drop_ref((GDExtensionObjectPtr)&g_S5); // main releases the last S5 reference
                    CHECK(g_mem_balance == 0, "waiter nodes must all be freed");
                    printf("OK await_dynamic\\n");
                    return 0;
                }
                """;
        var execution = compileLinkAndRun("await_dynamic_probe", source, runtimeObjects);
        assertEquals(0, execution.exitCode(), execution::diagnostic);
        assertEvents(execution, List.of(
                "w5_await_dynamic",
                "pack_result:S5",
                "w5_resumed",
                "pack_result:W5",
                "emit:W5",
                "destroy_slot:W5",
                "free:W5",
                "emit:S5",
                "destroy_slot:S5",
                "free:S5"
        ));
        assertTrue(execution.output().contains("OK await_dynamic"), execution::diagnostic);
    }

    /// Compiles a fixture source (already containing the fake engine layer), links it with
    /// the prebuilt runtime objects, runs the produced executable and returns its result.
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
        command.add("-D_DEFAULT_SOURCE");
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

    /// Extracts the fixture's `EV <name>` event lines in order.
    private static List<String> eventsOf(CompileResult result) {
        return result.output().lines()
                .filter(line -> line.startsWith("EV "))
                .map(line -> line.substring(3))
                .toList();
    }

    private static void assertEvents(CompileResult result, List<String> expected) {
        assertEquals(expected, eventsOf(result), result::diagnostic);
    }

    private record CompileResult(List<String> command, int exitCode, String output, Path outputPath) {
        String diagnostic() {
            return String.join(" ", command) + "\n" + output;
        }
    }

    /// Shared C fixture layer: a fake Godot engine behind the GDExtension interface
    /// function-pointer table. Fake Variant layout inside `godot_Variant` storage:
    /// bytes [0..4) type tag, bytes [8..16) payload (int64 / object instance id).
    private static final String FAKE_ENGINE = """
            #include <godot_binding.h>
            #include <gdcc_coroutine.h>
            
            #include <stddef.h>
            #include <stdio.h>
            #include <stdlib.h>
            #include <string.h>
            
            #define FAKE_VARIANT_TYPE_OFF 0
            #define FAKE_VARIANT_PAYLOAD_OFF 8
            #define FAKE_MAX_OBJECTS 16
            #define FAKE_MAX_BINDINGS 32
            
            static int g_mem_balance = 0;
            static int g_variant_copy_count = 0;
            static int g_variant_destroy_count = 0;
            static int g_print_error_count = 0;
            static int g_binding_set_reject_count = 0;
            static char g_last_error[256] = {0};
            
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
            static void log_eventf(const char *fmt, const char *arg) {
                char buf[64];
                snprintf(buf, sizeof(buf), fmt, arg);
                log_event(buf);
            }
            
            // ---------- fake Variant helpers ----------
            static void fake_variant_set_int(godot_Variant *v, int64_t value) {
                memset(v, 0, sizeof(*v));
                int32_t type = GDEXTENSION_VARIANT_TYPE_INT;
                memcpy((char *)v + FAKE_VARIANT_TYPE_OFF, &type, 4);
                memcpy((char *)v + FAKE_VARIANT_PAYLOAD_OFF, &value, 8);
            }
            static void fake_variant_set_object(godot_Variant *v, GDObjectInstanceID id) {
                memset(v, 0, sizeof(*v));
                int32_t type = GDEXTENSION_VARIANT_TYPE_OBJECT;
                memcpy((char *)v + FAKE_VARIANT_TYPE_OFF, &type, 4);
                memcpy((char *)v + FAKE_VARIANT_PAYLOAD_OFF, &id, 8);
            }
            static int32_t fake_variant_type_of(const godot_Variant *v) {
                int32_t type;
                memcpy(&type, (const char *)v + FAKE_VARIANT_TYPE_OFF, 4);
                return type;
            }
            static int64_t fake_variant_as_int(const godot_Variant *v) {
                int64_t value;
                memcpy(&value, (const char *)v + FAKE_VARIANT_PAYLOAD_OFF, 8);
                return value;
            }
            static GDObjectInstanceID fake_variant_object_id_of(const godot_Variant *v) {
                GDObjectInstanceID id;
                memcpy(&id, (const char *)v + FAKE_VARIANT_PAYLOAD_OFF, 8);
                return id;
            }
            
            // ---------- fake object / binding / refcount tables ----------
            static struct { GDObjectInstanceID id; GDExtensionObjectPtr ptr; int freed; } g_objects[FAKE_MAX_OBJECTS];
            static int g_object_count = 0;
            static struct { GDExtensionObjectPtr obj; void *token; void *binding; } g_bindings[FAKE_MAX_BINDINGS];
            static int g_binding_count = 0;
            static struct { GDExtensionObjectPtr obj; int refs; } g_refs[FAKE_MAX_OBJECTS];
            static int g_ref_count = 0;
            
            static void fake_append_binding(GDExtensionObjectPtr obj, void *token, void *binding) {
                if (g_binding_count >= FAKE_MAX_BINDINGS) fail("binding table overflow");
                g_bindings[g_binding_count].obj = obj;
                g_bindings[g_binding_count].token = token;
                g_bindings[g_binding_count].binding = binding;
                g_binding_count++;
            }
            static void *fake_get_binding(GDExtensionObjectPtr obj, void *token, const GDExtensionInstanceBindingCallbacks *callbacks) {
                for (int i = 0; i < g_binding_count; i++) {
                    if (g_bindings[i].obj == obj && g_bindings[i].token == token) return g_bindings[i].binding;
                }
                if (callbacks == NULL || callbacks->create_callback == NULL) return NULL;
                void *binding = callbacks->create_callback(token, obj);
                if (binding != NULL) fake_append_binding(obj, token, binding);
                return binding;
            }
            static void fake_set_binding(GDExtensionObjectPtr obj, void *token, void *binding, const GDExtensionInstanceBindingCallbacks *callbacks) {
                (void)callbacks;
                for (int i = 0; i < g_binding_count; i++) {
                    if (g_bindings[i].obj == obj && g_bindings[i].binding != NULL) {
                        g_binding_set_reject_count++;
                        return;
                    }
                }
                fake_append_binding(obj, token, binding);
            }
            
            static int fake_refs_index(GDExtensionObjectPtr obj) {
                for (int i = 0; i < g_ref_count; i++) {
                    if (g_refs[i].obj == obj) return i;
                }
                fail("unknown refcounted object");
                return -1;
            }
            static int fake_refs_of(GDExtensionObjectPtr obj) {
                return g_refs[fake_refs_index(obj)].refs;
            }
            static void fake_add_ref(GDExtensionObjectPtr obj) {
                g_refs[fake_refs_index(obj)].refs++;
            }
            
            // ---------- fake coroutine state class (models one generated hidden state class) ----------
            typedef struct FakeState {
                GDExtensionObjectPtr _object;  // wrapper root field; fake engine object = self address
                gdcc_coro_state_header header; // common frame header
                int64_t ret_slot;              // typed return slot model (an int64-returning coroutine)
                int ret_initialized;
                const char *name;
                GDObjectInstanceID fake_id;
                int pack_calls;
                int copy_ret_calls;
                int destroy_slot_calls;
                int emit_calls;
            } FakeState;
            #define FAKE_STATE_OF(h) ((FakeState *)((char *)(h) - offsetof(FakeState, header)))
            
            static struct { GDExtensionObjectPtr obj; FakeState *state; int freed; } g_state_reg[FAKE_MAX_OBJECTS];
            static int g_state_reg_count = 0;
            static GDObjectInstanceID g_next_fake_id = 1000;
            
            static void fake_pack_result(gdcc_coro_state_header *state) {
                FakeState *fs = FAKE_STATE_OF(state);
                log_eventf("pack_result:%s", fs->name);
                fs->pack_calls++;
                CHECK(fs->ret_initialized, "pack_result requires a written return slot");
                // New contract: COPY the typed slot into result_cache (destroy-then-write
                // discipline, the storage is always constructed) and KEEP the typed slot
                // alive - typed waiters and the done fast path read it afterwards.
                godot_variant_destroy(&state->result_cache);
                fake_variant_set_int(&state->result_cache, fs->ret_slot);
            }
            static void fake_copy_ret_slot(gdcc_coro_state_header *state, void *out_typed) {
                FakeState *fs = FAKE_STATE_OF(state);
                log_eventf("copy_ret:%s", fs->name);
                fs->copy_ret_calls++;
                CHECK(fs->ret_initialized, "copy_ret_slot requires a written return slot");
                *(int64_t *)out_typed = fs->ret_slot; // int64: plain value copy, no destroy of the awaiter slot needed
            }
            static void fake_destroy_ret_slot(gdcc_coro_state_header *state) {
                FakeState *fs = FAKE_STATE_OF(state);
                log_eventf("destroy_slot:%s", fs->name);
                fs->destroy_slot_calls++;
                fs->ret_initialized = 0; // tolerates a never-written slot (idempotent for int64)
            }
            static void fake_emit_completed(gdcc_coro_state_header *state) {
                FakeState *fs = FAKE_STATE_OF(state);
                log_eventf("emit:%s", fs->name);
                fs->emit_calls++;
                // Finalize ordering invariants at emit time.
                CHECK(state->done, "emit must happen after done is published");
                CHECK(state->waiters == NULL, "emit must happen after all waiters are drained");
                CHECK(fake_variant_type_of(&state->result_cache) == GDEXTENSION_VARIANT_TYPE_INT,
                        "emit must happen after the result is packed");
            }
            static const gdcc_coro_state_desc g_fake_desc = {
                    .pack_result = fake_pack_result,
                    .copy_ret_slot = fake_copy_ret_slot,
                    .destroy_ret_slot = fake_destroy_ret_slot,
                    .emit_completed = fake_emit_completed,
            };
            
            static void fake_state_write_ret(FakeState *fs, int64_t value) {
                fs->ret_slot = value;
                fs->ret_initialized = 1;
            }
            
            // Engine PREDELETE + instance-free emulation. The two phases stay separate,
            // mirroring production: NOTIFICATION_PREDELETE only runs `gdcc_coro_cancel`,
            // and the instance destructor (`free_instance`) runs the typed-slot destroy and
            // `gdcc_coro_state_free` later - the MCO_DEAD coroutine stack exists in between.
            static void fake_fire_predelete(GDExtensionObjectPtr obj) {
                for (int i = 0; i < g_state_reg_count; i++) {
                    if (g_state_reg[i].obj == obj && !g_state_reg[i].freed) {
                        g_state_reg[i].freed = 1;
                        gdcc_coro_cancel(&g_state_reg[i].state->header);
                        return;
                    }
                }
            }
            static void fake_free_instance(GDExtensionObjectPtr obj) {
                for (int i = 0; i < g_state_reg_count; i++) {
                    if (g_state_reg[i].obj == obj) {
                        // Generated-code shape: the typed return slot is destroyed exactly
                        // once here (never from cancel), then the generic frame teardown.
                        fake_destroy_ret_slot(&g_state_reg[i].state->header);
                        gdcc_coro_state_free(&g_state_reg[i].state->header);
                        log_eventf("free:%s", g_state_reg[i].state->name);
                        return;
                    }
                }
            }
            static int fake_drop_ref(GDExtensionObjectPtr obj) {
                int index = fake_refs_index(obj);
                g_refs[index].refs--;
                if (g_refs[index].refs == 0) {
                    fake_fire_predelete(obj);
                    fake_free_instance(obj);
                    return 1;
                }
                return 0;
            }
            
            static void fake_state_init(FakeState *fs, const char *name) {
                memset(fs, 0, sizeof(*fs));
                fs->_object = (GDExtensionObjectPtr)fs;
                fs->name = name;
                fs->fake_id = g_next_fake_id++;
                gdcc_coro_state_header_init(&fs->header, &g_fake_desc, fs->_object);
                if (g_object_count >= FAKE_MAX_OBJECTS || g_ref_count >= FAKE_MAX_OBJECTS
                        || g_state_reg_count >= FAKE_MAX_OBJECTS) {
                    fail("fake table overflow");
                }
                g_objects[g_object_count].id = fs->fake_id;
                g_objects[g_object_count].ptr = fs->_object;
                g_objects[g_object_count].freed = 0;
                g_object_count++;
                g_refs[g_ref_count].obj = fs->_object;
                g_refs[g_ref_count].refs = 1; // creator reference
                g_ref_count++;
                g_state_reg[g_state_reg_count].obj = fs->_object;
                g_state_reg[g_state_reg_count].state = fs;
                g_state_reg[g_state_reg_count].freed = 0;
                g_state_reg_count++;
                // Match production: the dedicated token owns the only slot-zero binding.
                fake_set_binding(fs->_object, gdcc_coro_binding_token(), &fs->header, NULL);
            }
            
            static void fake_register_freed_object(GDObjectInstanceID id) {
                if (g_object_count >= FAKE_MAX_OBJECTS) fail("fake table overflow");
                static char g_freed_storage[FAKE_MAX_OBJECTS];
                g_objects[g_object_count].id = id;
                g_objects[g_object_count].ptr = (GDExtensionObjectPtr)&g_freed_storage[g_object_count];
                g_objects[g_object_count].freed = 1;
                g_object_count++;
            }
            
            static mco_coro *fake_make_coro(void (*body)(mco_coro *), FakeState *owner) {
                mco_desc desc = mco_desc_init(body, GDCC_CORO_STACK_SIZE);
                desc.user_data = owner;
                mco_coro *co = NULL;
                if (mco_create(&co, &desc) != MCO_SUCCESS) fail("mco_create failed");
                if (owner != NULL) {
                    owner->header.co = co; // production: the entry thunk attaches the coroutine
                }
                return co;
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
            static void fake_variant_new_copy_iface(GDExtensionUninitializedVariantPtr dest, GDExtensionConstVariantPtr src) {
                memcpy(dest, src, GDCC_GODOT_SIZE_Variant);
                g_variant_copy_count++;
            }
            static GDExtensionVariantType fake_variant_get_type_iface(GDExtensionConstVariantPtr self) {
                int32_t type;
                memcpy(&type, (const char *)self + FAKE_VARIANT_TYPE_OFF, 4);
                return (GDExtensionVariantType)type;
            }
            static GDObjectInstanceID fake_variant_get_object_instance_id_iface(GDExtensionConstVariantPtr self) {
                GDObjectInstanceID id;
                memcpy(&id, (const char *)self + FAKE_VARIANT_PAYLOAD_OFF, 8);
                return id;
            }
            static GDExtensionObjectPtr fake_object_get_instance_from_id(GDObjectInstanceID id) {
                for (int i = 0; i < g_object_count; i++) {
                    if (g_objects[i].id == id) return g_objects[i].freed ? NULL : g_objects[i].ptr;
                }
                return NULL;
            }
            static void fake_variant_destroy_iface(GDExtensionVariantPtr self) {
                g_variant_destroy_count++;
                // Faithful Variant semantics: destroying an OBJECT payload releases its
                // reference (freed payloads have nothing left to release).
                const godot_Variant *v = (const godot_Variant *)self;
                if (fake_variant_type_of(v) == GDEXTENSION_VARIANT_TYPE_OBJECT) {
                    GDObjectInstanceID id = fake_variant_object_id_of(v);
                    if (id != 0) {
                        GDExtensionObjectPtr obj = fake_object_get_instance_from_id(id);
                        if (obj != NULL) fake_drop_ref(obj);
                    }
                }
            }
            static void fake_object_destroy(GDExtensionObjectPtr obj) {
                for (int i = 0; i < g_object_count; i++) {
                    if (g_objects[i].ptr == obj) g_objects[i].freed = 1;
                }
            }
            static void fake_string_name_new_with_utf8_chars(GDExtensionUninitializedStringNamePtr out, const char *text) {
                // All call sites pass stable string literals; keep the pointer for later comparison.
                memcpy(out, &text, sizeof(text));
            }
            static void fake_ptr_destructor_noop(GDExtensionTypePtr ptr) {
                (void)ptr;
            }
            static GDExtensionPtrDestructor fake_variant_get_ptr_destructor(GDExtensionVariantType type) {
                (void)type;
                return fake_ptr_destructor_noop;
            }
            static int g_mb_reference_sentinel;
            static int g_mb_unreference_sentinel;
            static GDExtensionMethodBindPtr fake_classdb_get_method_bind(GDExtensionConstStringNamePtr cls, GDExtensionConstStringNamePtr method, GDExtensionInt hash) {
                (void)hash;
                const char *class_name;
                const char *method_name;
                memcpy(&class_name, cls, sizeof(class_name));
                memcpy(&method_name, method, sizeof(method_name));
                if (strcmp(class_name, "RefCounted") != 0) return NULL;
                if (strcmp(method_name, "reference") == 0) return (GDExtensionMethodBindPtr)&g_mb_reference_sentinel;
                if (strcmp(method_name, "unreference") == 0) return (GDExtensionMethodBindPtr)&g_mb_unreference_sentinel;
                return NULL;
            }
            static void fake_object_method_bind_ptrcall(GDExtensionMethodBindPtr bind, GDExtensionObjectPtr instance, const GDExtensionConstTypePtr *args, GDExtensionTypePtr ret) {
                (void)args;
                if (bind == (GDExtensionMethodBindPtr)&g_mb_reference_sentinel) {
                    fake_add_ref(instance);
                    if (ret != NULL) *(GDExtensionBool *)ret = 1;
                    return;
                }
                if (bind == (GDExtensionMethodBindPtr)&g_mb_unreference_sentinel) {
                    int reached_zero = fake_drop_ref(instance);
                    if (ret != NULL) *(GDExtensionBool *)ret = reached_zero;
                    return;
                }
                fail("unexpected method bind ptrcall");
            }
            
            static void fake_unused_interface(void) {
            }
            static GDExtensionInterfaceFunctionPtr fake_get_proc_address(const char *name) {
                if (strcmp(name, "mem_alloc") == 0) return (GDExtensionInterfaceFunctionPtr)fake_mem_alloc;
                if (strcmp(name, "mem_realloc") == 0) return (GDExtensionInterfaceFunctionPtr)fake_mem_realloc;
                if (strcmp(name, "mem_free") == 0) return (GDExtensionInterfaceFunctionPtr)fake_mem_free;
                if (strcmp(name, "print_error") == 0) return (GDExtensionInterfaceFunctionPtr)fake_print_error;
                if (strcmp(name, "variant_new_copy") == 0) return (GDExtensionInterfaceFunctionPtr)fake_variant_new_copy_iface;
                if (strcmp(name, "variant_destroy") == 0) return (GDExtensionInterfaceFunctionPtr)fake_variant_destroy_iface;
                if (strcmp(name, "variant_get_type") == 0) return (GDExtensionInterfaceFunctionPtr)fake_variant_get_type_iface;
                if (strcmp(name, "variant_get_object_instance_id") == 0) return (GDExtensionInterfaceFunctionPtr)fake_variant_get_object_instance_id_iface;
                if (strcmp(name, "object_get_instance_from_id") == 0) return (GDExtensionInterfaceFunctionPtr)fake_object_get_instance_from_id;
                if (strcmp(name, "object_get_instance_binding") == 0) return (GDExtensionInterfaceFunctionPtr)fake_get_binding;
                if (strcmp(name, "object_set_instance_binding") == 0) return (GDExtensionInterfaceFunctionPtr)fake_set_binding;
                if (strcmp(name, "object_destroy") == 0) return (GDExtensionInterfaceFunctionPtr)fake_object_destroy;
                if (strcmp(name, "string_name_new_with_utf8_chars") == 0) return (GDExtensionInterfaceFunctionPtr)fake_string_name_new_with_utf8_chars;
                if (strcmp(name, "variant_get_ptr_destructor") == 0) return (GDExtensionInterfaceFunctionPtr)fake_variant_get_ptr_destructor;
                if (strcmp(name, "classdb_get_method_bind") == 0) return (GDExtensionInterfaceFunctionPtr)fake_classdb_get_method_bind;
                if (strcmp(name, "object_method_bind_ptrcall") == 0) return (GDExtensionInterfaceFunctionPtr)fake_object_method_bind_ptrcall;
                return (GDExtensionInterfaceFunctionPtr)fake_unused_interface;
            }
            """;
}
