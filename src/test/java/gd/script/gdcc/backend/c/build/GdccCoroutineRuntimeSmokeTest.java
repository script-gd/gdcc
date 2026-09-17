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
                compileObject(zig, GDCC_INCLUDE_DIR.resolve("gdcc_coroutine.c"), sharedDir.resolve("gdcc_coroutine.o")),
                // gdcc_coroutine.c dispatches lambda-Callable creation through gdcc_hrx;
                // the mode stays UNINITIALIZED in these fixtures, i.e. the direct path.
                compileObject(zig, GDCC_INCLUDE_DIR.resolve("gdcc_hrx.c"), sharedDir.resolve("gdcc_hrx.o"))
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
    void reloadedShellShouldShortCircuitFinalizeCancelAndAwait() throws IOException, InterruptedException {
        // Reloaded-shell terminal-state anchors: a shell is produced by the recreate path
        // after the in-flight coroutine was silently cancelled at reload. finalize on a
        // shell never packs/resumes/emits; cancel stays a pure no-op; awaiting a shell
        // returns the determined cancellation result IMMEDIATELY (no suspend, typed out
        // slot keeps its default, one diagnostic) while the await_state consume contract
        // still releases the callee reference — which here drives the shell's own
        // PREDELETE + free_instance, proving that path idempotent.
        var source = FAKE_ENGINE + """
                
                static FakeState g_SH, g_W;
                static mco_coro *g_w_co;
                static int64_t g_w_out;
                static int g_w_resumed;
                
                static void w_body(mco_coro *co) {
                    log_event("w_await");
                    gdcc_coro_await_state(&g_SH.header, &g_w_out, co, &g_W.header);
                    // Immediate-return contract: control reaches here in the SAME resume
                    // slice (no mco_yield happened), with the awaiter itself untouched.
                    g_w_resumed++;
                    CHECK(!g_W.header.cancel, "the shell await path must not cancel the awaiter");
                    log_event("w_after_await");
                    fake_state_write_ret(&g_W, 0); // lets the main line finalize W normally (control group)
                }
                
                int main(void) {
                    if (!godot_initialize_interface(fake_get_proc_address)) fail("interface init");
                    fake_state_init(&g_SH, "SH");
                    g_SH.header.reloaded_shell = true; // recreate-path terminal state
                    fake_state_init(&g_W, "W");
                    g_w_out = -9; // sentinel: the typed out slot must keep its default
                    g_w_co = fake_make_coro(w_body, &g_W);
                
                    // finalize on a shell: no pack, no copy, no waiter resume, no emit, no done.
                    gdcc_coro_finalize(&g_SH.header);
                    CHECK(g_SH.pack_calls == 0 && g_SH.copy_ret_calls == 0 && g_SH.emit_calls == 0,
                            "finalize on a shell must be a no-op");
                    CHECK(!g_SH.header.done, "a shell never publishes done");
                
                    // cancel on a shell: pure no-op — the flag is never set and the (NULL)
                    // coroutine body is never resumed.
                    gdcc_coro_cancel(&g_SH.header);
                    CHECK(!g_SH.header.cancel, "cancel must stay a no-op on a shell");
                    CHECK(g_SH.header.waiters == NULL, "a shell never registers waiters");
                
                    // Await the shell from a live coroutine. The shell's only reference is the
                    // caller's init ref; the consume contract releases it, driving the shell's
                    // PREDELETE (cancel no-op) and free_instance (destroy slot + state_free).
                    mco_resume(g_w_co);
                    CHECK(g_w_resumed == 1, "awaiting a shell must return in the same resume slice");
                    CHECK(mco_status(g_w_co) == MCO_DEAD, "the awaiter must run to completion without suspending");
                    CHECK(g_w_out == -9, "the typed out slot must keep its default on the shell await path");
                    CHECK(g_SH.destroy_slot_calls == 1, "the shell return slot is destroyed exactly once");
                    CHECK(g_SH.header.magic == 0, "the shell identity must be revoked by state_free");
                    CHECK(g_print_error_count == 1, "the shell await reports exactly one diagnostic");
                
                    // Control group: the awaiter itself finalizes normally afterwards.
                    gdcc_coro_finalize(&g_W.header);
                    CHECK(g_W.pack_calls == 1 && g_W.emit_calls == 1, "the awaiter must finalize normally");
                    fake_drop_ref((GDExtensionObjectPtr)&g_W);
                    CHECK(g_W.destroy_slot_calls == 1, "the awaiter return slot is destroyed exactly once");
                
                    CHECK(g_mem_balance == 0, "no waiter node may leak on the shell path");
                    CHECK(g_variant_destroy_count == 3, "result caches: SH free + W pack + W free");
                    printf("OK reloaded_shell\\n");
                    return 0;
                }
                """;
        var execution = compileLinkAndRun("reloaded_shell_probe", source, runtimeObjects);
        assertEquals(0, execution.exitCode(), execution::diagnostic);
        assertEvents(execution, List.of(
                "w_await",
                "destroy_slot:SH",
                "free:SH",
                "w_after_await",
                "pack_result:W",
                "emit:W",
                "destroy_slot:W",
                "free:W"
        ));
        assertTrue(execution.output().contains("OK reloaded_shell"), execution::diagnostic);
    }

    @Test
    void cancelAllShouldDetachSignalsAndCancelEveryActiveState() throws IOException, InterruptedException {
        // Bulk-abandonment anchors: states linked by the start thunk are all cancelled - a
        // signal-suspended one is DISCONNECTED first (a later emission must never resume a
        // dead coroutine or write into a freed frame), a waiter-suspended one through the
        // ordinary cancel path, a plain-yield one likewise; a finalized state is already
        // unlinked and stays untouched (no re-pack, no re-emit, no cancel flag). Reference
        // discipline: the temporary strong reference lets detach + cancel run while edges
        // drop; creator references released afterwards drive the idempotent PREDELETE +
        // exactly-once free_instance. The second cancel_all call must be a pure no-op, and
        // nothing may leak.
        var source = FAKE_ENGINE + """
                
                static FakeState g_SIG, g_WR, g_IDLE, g_DONE;
                static mco_coro *g_sig_co, *g_wr_co, *g_idle_co, *g_done_co;
                static int64_t g_wr_out;
                static godot_Variant g_sig_out;
                static char g_emitter_storage;
                #define FAKE_EMITTER_ID 4000
                
                static void sig_body(mco_coro *co) {
                    log_event("sig_await");
                    godot_Signal sig;
                    fake_signal_write(&sig, FAKE_EMITTER_ID, "tick");
                    gdcc_coro_await_signal(&sig, &g_sig_out, co, &g_SIG.header);
                    CHECK(g_SIG.header.cancel, "sig must resume only through cancel");
                    log_event("sig_cleanup");
                    fake_state_write_ret(&g_SIG, 0); // __prepare__ default consumed by the __finally__ analog
                }
                
                static void wr_body(mco_coro *co) {
                    log_event("wr_await");
                    gdcc_coro_await_state(&g_SIG.header, &g_wr_out, co, &g_WR.header);
                    CHECK(g_WR.header.cancel, "wr must resume only through cancel");
                    log_event("wr_cleanup");
                    fake_state_write_ret(&g_WR, 0);
                }
                
                static void idle_body(mco_coro *co) {
                    log_event("idle_yield");
                    mco_yield(co);
                    CHECK(g_IDLE.header.cancel, "idle must resume only through cancel");
                    log_event("idle_cleanup");
                    fake_state_write_ret(&g_IDLE, 0);
                }
                
                static void done_body(mco_coro *co) {
                    (void)co;
                    fake_state_write_ret(&g_DONE, 5);
                }
                
                int main(void) {
                    if (!godot_initialize_interface(fake_get_proc_address)) fail("interface init");
                    gdcc_coro_set_hot_reload_active(true); // enable editor-only coroutine tracking
                    // Live emitter object (not a state): registered in the fake object table.
                    g_objects[g_object_count].id = FAKE_EMITTER_ID;
                    g_objects[g_object_count].ptr = (GDExtensionObjectPtr)&g_emitter_storage;
                    g_objects[g_object_count].freed = 0;
                    g_object_count++;
                
                    fake_state_init(&g_SIG, "SIG");
                    fake_state_init(&g_WR, "WR");
                    fake_state_init(&g_IDLE, "IDLE");
                    fake_state_init(&g_DONE, "DONE");
                    g_wr_out = -7; // sentinel: abandoned awaiter slots must stay untouched
                    g_sig_co = fake_make_coro(sig_body, &g_SIG);
                    g_wr_co = fake_make_coro(wr_body, &g_WR);
                    g_idle_co = fake_make_coro(idle_body, &g_IDLE);
                    g_done_co = fake_make_coro(done_body, &g_DONE);
                    // Start-thunk role: link right after mco_create, before the first resume.
                    gdcc_coro_active_link(&g_SIG.header);
                    gdcc_coro_active_link(&g_WR.header);
                    gdcc_coro_active_link(&g_IDLE.header);
                    gdcc_coro_active_link(&g_DONE.header);
                
                    mco_resume(g_done_co); // DONE completes synchronously
                    gdcc_coro_finalize(&g_DONE.header); // entry-thunk role; must unlink DONE
                    CHECK(g_DONE.pack_calls == 1 && g_DONE.emit_calls == 1, "done state must finalize exactly once");
                
                    fake_add_ref((GDExtensionObjectPtr)&g_SIG); // wr's call-site OWNED ref (thunk semantics)
                    mco_resume(g_sig_co); // SIG connects and suspends on the signal
                    CHECK(mco_status(g_sig_co) == MCO_SUSPENDED, "sig must be signal-suspended");
                    CHECK(g_SIG.header.signal_reg != NULL, "the signal wait must be registered on the state");
                    CHECK(g_connection_count == 1, "exactly one live connection expected");
                    CHECK(fake_refs_of((GDExtensionObjectPtr)&g_SIG) == 3,
                            "main ref + wr call-site ref + connection keep-alive edge");
                    mco_resume(g_wr_co); // WR registers a typed waiter on SIG and suspends
                    CHECK(mco_status(g_wr_co) == MCO_SUSPENDED, "wr must be waiter-suspended");
                    mco_resume(g_idle_co); // IDLE suspends on a plain yield
                
                    gdcc_coro_cancel_all();
                
                    CHECK(g_SIG.header.cancel && g_WR.header.cancel && g_IDLE.header.cancel,
                            "every active state must be cancelled");
                    CHECK(!g_DONE.header.cancel, "a finalized state must not be re-cancelled");
                    CHECK(g_SIG.header.signal_reg == NULL, "bulk cancel must clear the signal registration");
                    CHECK(g_connection_count == 0, "bulk cancel must disconnect the pending one-shot signal");
                    CHECK(g_SIG.pack_calls == 0 && g_WR.pack_calls == 0 && g_IDLE.pack_calls == 0,
                            "bulk cancel never packs");
                    CHECK(g_SIG.emit_calls == 0 && g_WR.emit_calls == 0 && g_IDLE.emit_calls == 0,
                            "bulk cancel never emits");
                    CHECK(g_DONE.pack_calls == 1 && g_DONE.emit_calls == 1,
                            "the finalized state must stay untouched by bulk cancel");
                    CHECK(g_wr_out == -7, "the abandoned awaiter out slot stays unwritten");
                
                    // UAF guard anchor: emitting after the bulk cancel must not reach any
                    // callback (the connection was disconnected above; a live connection
                    // here would resume a dead coroutine and write into a freed frame).
                    fake_emit_signal(FAKE_EMITTER_ID, "tick", NULL, 0);
                
                    gdcc_coro_cancel_all(); // idempotent: the list is already empty
                
                    // Creator references released: idempotent PREDELETE (cancel already
                    // terminal) + exactly-once free_instance per state.
                    fake_drop_ref((GDExtensionObjectPtr)&g_SIG);
                    fake_drop_ref((GDExtensionObjectPtr)&g_WR);
                    fake_drop_ref((GDExtensionObjectPtr)&g_IDLE);
                    fake_drop_ref((GDExtensionObjectPtr)&g_DONE);
                    CHECK(g_SIG.destroy_slot_calls == 1 && g_WR.destroy_slot_calls == 1
                                    && g_IDLE.destroy_slot_calls == 1 && g_DONE.destroy_slot_calls == 1,
                            "the return slot is destroyed exactly once per state, by free_instance");
                    CHECK(g_print_error_count == 0, "no runtime errors expected");
                    CHECK(g_mem_balance == 0,
                            "waiter nodes, signal registrations and callable customs must all be freed");
                    CHECK(g_variant_destroy_count == 5,
                            "one destroy-then-write pack (DONE) + four result caches at state_free");
                    printf("OK cancel_all\\n");
                    return 0;
                }
                """;
        var execution = compileLinkAndRun("cancel_all_probe", source, runtimeObjects);
        assertEquals(0, execution.exitCode(), execution::diagnostic);
        assertEvents(execution, List.of(
                "pack_result:DONE",
                "emit:DONE",
                "sig_await",
                "wr_await",
                "idle_yield",
                "idle_cleanup",
                "wr_cleanup",
                "disconnect:tick",
                "sig_cleanup",
                "destroy_slot:SIG",
                "free:SIG",
                "destroy_slot:WR",
                "free:WR",
                "destroy_slot:IDLE",
                "free:IDLE",
                "destroy_slot:DONE",
                "free:DONE"
        ));
        assertTrue(execution.output().contains("OK cancel_all"), execution::diagnostic);
    }

    @Test
    void hrxModeSignalAwaitShouldDetachViaRetainAndFreeExactlyOnce() throws IOException, InterruptedException {
        // In thunk mode the connection's Callable identity is the (thunk, spec) pair, so
        // the bulk-cancel detach must rebuild its EQUAL lookup key through
        // `gdcc_hrx_callable_retain` on the registration's stored spec - never a fresh
        // lambda Callable (which would miss the connection). The rest of the contract is
        // mode-independent: disconnect first, wait released exactly once, a later emission
        // never resumes the cancelled coroutine.
        var source = FAKE_ENGINE + """
                #include <gdcc_hrx.h>

                static FakeState g_SIG;
                static mco_coro *g_sig_co;
                static godot_Variant g_sig_out;
                static char g_emitter_storage;
                static char g_engine_storage;
                #define FAKE_EMITTER_ID 4000

                static void sig_body(mco_coro *co) {
                    log_event("sig_await");
                    godot_Signal sig;
                    fake_signal_write(&sig, FAKE_EMITTER_ID, "tick");
                    gdcc_coro_await_signal(&sig, &g_sig_out, co, &g_SIG.header);
                    CHECK(g_SIG.header.cancel, "sig must resume only through cancel");
                    log_event("sig_cleanup");
                    fake_state_write_ret(&g_SIG, 0);
                }

                int main(void) {
                    if (!godot_initialize_interface(fake_get_proc_address)) fail("interface init");
                    gdcc_coro_set_hot_reload_active(true);
                    // Editor process with working executable memory: the HRX thunk path.
                    gdcc_hrx_mode hrx_mode = gdcc_hrx_initialize_core(
                            NULL, (GDExtensionObjectPtr)&g_engine_storage, true, true, 0x55AAu, NULL, 0);
                    CHECK(hrx_mode == GDCC_HRX_MODE_ACTIVE, "hrx must activate in editor mode");
                    g_objects[g_object_count].id = FAKE_EMITTER_ID;
                    g_objects[g_object_count].ptr = (GDExtensionObjectPtr)&g_emitter_storage;
                    g_objects[g_object_count].freed = 0;
                    g_object_count++;

                    fake_state_init(&g_SIG, "SIG");
                    g_sig_co = fake_make_coro(sig_body, &g_SIG);
                    gdcc_coro_active_link(&g_SIG.header);
                    mco_resume(g_sig_co);
                    CHECK(mco_status(g_sig_co) == MCO_SUSPENDED, "sig must be signal-suspended");
                    CHECK(g_SIG.header.signal_reg != NULL, "the signal wait must be registered");
                    CHECK(g_connection_count == 1, "exactly one live connection expected");
                    // Thunk-mode marker: the connection must carry the HRX spec pinned in the
                    // hub registry (direct mode would carry the raw wait pointer instead).
                    gdcc_hrx_hub *hub = gdcc_hrx_current_hub();
                    CHECK(hub != NULL && hub->registry != NULL, "the waiter must be an HRX spec");
                    CHECK(g_connections[0].callable->userdata == hub->registry,
                            "the connection must carry the spec (thunk mode), not the raw wait pointer");

                    gdcc_coro_cancel_all();

                    CHECK(g_SIG.header.cancel, "the state must be cancelled");
                    CHECK(g_SIG.header.signal_reg == NULL, "the registration must be cleared");
                    CHECK(g_connection_count == 0,
                            "the retain-based detach must locate and drop the pending connection");
                    fake_emit_signal(FAKE_EMITTER_ID, "tick", NULL, 0); // must reach nothing

                    fake_drop_ref((GDExtensionObjectPtr)&g_SIG);
                    CHECK(g_SIG.destroy_slot_calls == 1, "the return slot is destroyed exactly once");
                    // Two allocations remain by design: the cross-generation hub + its intern
                    // table (anchor-owned, process-lifetime).
                    CHECK(g_mem_balance == 2,
                            "waiter, registration and spec shell must all be reclaimed (hub, intern table and the shared RX thunk page stay process-lifetime)");
                    printf("OK hrx_signal_detach\\n");
                    return 0;
                }
                """;
        var execution = compileLinkAndRun("hrx_signal_detach_probe", source, runtimeObjects);
        assertEquals(0, execution.exitCode(), execution::diagnostic);
        assertTrue(execution.output().contains("OK hrx_signal_detach"), execution::diagnostic);
    }

    @Test
    void cancelAllShouldFreeConnectionEdgeOnlyStateInsideTheLoop() throws IOException, InterruptedException {
        // Fire-and-forget anchor: the state is held ONLY by the one-shot connection's
        // keep-alive edge (creator reference dropped right after suspending - the void
        // engine-entry shape). Inside cancel_all the detach removes that edge, leaving the
        // temporary own as the last reference; releasing it must synchronously drive
        // PREDELETE (cancel already terminal, so a no-op) + exactly-once free_instance
        // BEFORE cancel_all returns. No double free, no second cancel, no leak.
        var source = FAKE_ENGINE + """
                
                static FakeState g_FF;
                static mco_coro *g_ff_co;
                static godot_Variant g_ff_out;
                static char g_emitter_storage2;
                #define FAKE_EMITTER2_ID 4001
                
                static void ff_body(mco_coro *co) {
                    log_event("ff_await");
                    godot_Signal sig;
                    fake_signal_write(&sig, FAKE_EMITTER2_ID, "tick");
                    gdcc_coro_await_signal(&sig, &g_ff_out, co, &g_FF.header);
                    CHECK(g_FF.header.cancel, "ff must resume only through cancel");
                    log_event("ff_cleanup");
                    fake_state_write_ret(&g_FF, 0);
                }
                
                int main(void) {
                    if (!godot_initialize_interface(fake_get_proc_address)) fail("interface init");
                    gdcc_coro_set_hot_reload_active(true); // enable editor-only coroutine tracking
                    g_objects[g_object_count].id = FAKE_EMITTER2_ID;
                    g_objects[g_object_count].ptr = (GDExtensionObjectPtr)&g_emitter_storage2;
                    g_objects[g_object_count].freed = 0;
                    g_object_count++;
                
                    fake_state_init(&g_FF, "FF");
                    g_ff_co = fake_make_coro(ff_body, &g_FF);
                    gdcc_coro_active_link(&g_FF.header);
                    mco_resume(g_ff_co);
                    CHECK(mco_status(g_ff_co) == MCO_SUSPENDED, "ff must be signal-suspended");
                    CHECK(g_connection_count == 1, "one live connection expected");
                    fake_drop_ref((GDExtensionObjectPtr)&g_FF); // fire-and-forget detach
                    CHECK(fake_refs_of((GDExtensionObjectPtr)&g_FF) == 1,
                            "only the connection keep-alive edge remains");
                
                    gdcc_coro_cancel_all();
                
                    CHECK(g_FF.header.cancel, "ff must be cancelled");
                    CHECK(g_FF.destroy_slot_calls == 1,
                            "free_instance must run INSIDE cancel_all once the last edge drops");
                    CHECK(g_FF.header.magic == 0, "state_free must revoke the identity inside cancel_all");
                    // The cancel-resume -> MCO_DEAD -> state_free contract is anchored by
                    // state_free's own violation report: zero print errors here proves the
                    // stack was DEAD at free time (state_free destroyed it; no probe-side
                    // mco_status/mco_destroy afterwards - that would be use-after-destroy).
                    CHECK(g_FF.pack_calls == 0 && g_FF.emit_calls == 0, "cancel never packs or emits");
                    CHECK(g_connection_count == 0, "the connection was disconnected by the bulk cancel");
                    gdcc_coro_cancel_all(); // idempotent: nothing linked anymore
                    CHECK(g_FF.destroy_slot_calls == 1, "still exactly one free_instance");
                    CHECK(g_print_error_count == 0, "no runtime errors expected");
                    CHECK(g_mem_balance == 0, "wait, registration and callable custom must all be freed");
                    CHECK(g_variant_destroy_count == 1, "only the result cache is destroyed");
                    printf("OK cancel_all_edge_only\\n");
                    return 0;
                }
                """;
        var execution = compileLinkAndRun("cancel_all_edge_only_probe", source, runtimeObjects);
        assertEquals(0, execution.exitCode(), execution::diagnostic);
        assertEvents(execution, List.of(
                "ff_await",
                "disconnect:tick",
                "ff_cleanup",
                "destroy_slot:FF",
                "free:FF"
        ));
        assertTrue(execution.output().contains("OK cancel_all_edge_only"), execution::diagnostic);
    }

    @Test
    void cancelAllShouldCascadeWaiterEdgeOnlyAwaiterWithoutDanglingHead() throws IOException, InterruptedException {
        // Nested-release anchor: the awaiter is held ONLY by the callee's waiter edge
        // (fire-and-forget chain). The callee is processed FIRST (linked last, so it heads
        // the intrusive list); its cancel pops the waiter node and releases the awaiter's
        // last reference, which must synchronously run PREDELETE -> cancel (resuming the
        // awaiter to MCO_DEAD and unlinking it from the active list) -> free_instance, all
        // INSIDE the cancel_all loop. The head-pop loop must then re-read a VALID head
        // (never the freed awaiter) and finish without processing it twice.
        var source = FAKE_ENGINE + """
                
                static FakeState g_CAL, g_AW;
                static mco_coro *g_cal_co, *g_aw_co;
                static int64_t g_aw_out;
                
                static void cal_body(mco_coro *co) {
                    log_event("cal_yield");
                    mco_yield(co);
                    CHECK(g_CAL.header.cancel, "cal must resume only through cancel");
                    log_event("cal_cleanup");
                    fake_state_write_ret(&g_CAL, 0);
                }
                
                static void aw_body(mco_coro *co) {
                    log_event("aw_await");
                    gdcc_coro_await_state(&g_CAL.header, &g_aw_out, co, &g_AW.header);
                    CHECK(g_AW.header.cancel, "aw must resume only through cancel");
                    log_event("aw_cleanup");
                    fake_state_write_ret(&g_AW, 0);
                }
                
                int main(void) {
                    if (!godot_initialize_interface(fake_get_proc_address)) fail("interface init");
                    gdcc_coro_set_hot_reload_active(true); // enable editor-only coroutine tracking
                    fake_state_init(&g_CAL, "CAL");
                    fake_state_init(&g_AW, "AW");
                    g_aw_out = -7;
                    g_cal_co = fake_make_coro(cal_body, &g_CAL);
                    g_aw_co = fake_make_coro(aw_body, &g_AW);
                    // Link the awaiter FIRST so the callee heads the list and is cancelled
                    // first: its waiter-edge release drives the whole awaiter teardown
                    // nested inside round one of the loop.
                    gdcc_coro_active_link(&g_AW.header);
                    gdcc_coro_active_link(&g_CAL.header);
                
                    mco_resume(g_cal_co); // CAL suspends on a plain yield
                    fake_add_ref((GDExtensionObjectPtr)&g_CAL); // aw's call-site OWNED ref
                    mco_resume(g_aw_co);  // AW registers on CAL and suspends
                    CHECK(fake_refs_of((GDExtensionObjectPtr)&g_CAL) == 1, "call-site ref consumed");
                    // Main keeps the callee (a suspended callee needs a keep-alive edge of
                    // its own) and detaches the awaiter: fire-and-forget, held only by the
                    // callee's waiter edge.
                    fake_drop_ref((GDExtensionObjectPtr)&g_AW);
                    CHECK(fake_refs_of((GDExtensionObjectPtr)&g_AW) == 1,
                            "the awaiter is held only by the callee's waiter edge");
                
                    gdcc_coro_cancel_all();
                
                    CHECK(g_CAL.header.cancel && g_AW.header.cancel, "both states must be cancelled");
                    CHECK(g_AW.destroy_slot_calls == 1,
                            "the awaiter must be freed INSIDE the nested cascade of round one");
                    CHECK(g_AW.header.magic == 0, "awaiter identity revoked by the nested free");
                    // MCO_DEAD-at-free for both stacks is anchored by state_free's own
                    // violation report (zero print errors below).
                    CHECK(g_aw_out == -7, "the abandoned awaiter out slot stays unwritten");
                    CHECK(g_CAL.pack_calls == 0 && g_AW.pack_calls == 0, "cancel never packs");
                    gdcc_coro_cancel_all(); // idempotent
                    CHECK(g_AW.destroy_slot_calls == 1,
                            "the nested-freed awaiter is never re-processed");
                    fake_drop_ref((GDExtensionObjectPtr)&g_CAL); // main releases the callee
                    CHECK(g_CAL.destroy_slot_calls == 1, "callee freed exactly once");
                    CHECK(g_print_error_count == 0, "no runtime errors expected");
                    CHECK(g_mem_balance == 0, "the waiter node must be freed");
                    CHECK(g_variant_destroy_count == 2, "only the two result caches are destroyed");
                    printf("OK cancel_all_waiter_cascade\\n");
                    return 0;
                }
                """;
        var execution = compileLinkAndRun("cancel_all_waiter_cascade_probe", source, runtimeObjects);
        assertEquals(0, execution.exitCode(), execution::diagnostic);
        assertEvents(execution, List.of(
                "cal_yield",
                "aw_await",
                "cal_cleanup",
                "aw_cleanup",
                "destroy_slot:AW",
                "free:AW",
                "destroy_slot:CAL",
                "free:CAL"
        ));
        assertTrue(execution.output().contains("OK cancel_all_waiter_cascade"), execution::diagnostic);
    }

    @Test
    void signalRegistrationShouldSurviveResuspensionAndEmitterDeath() throws IOException, InterruptedException {
        // Registration-lifecycle anchors: (1) when a coroutine is resumed by an
        // emission and RE-SUSPENDS on a second signal inside the same callback slice, the
        // old wait's free callback (running after the callback) must NOT clear the newer
        // registration (the identity guard); (2) when the emitter dies first, the
        // connection teardown runs the free callback which clears the registration, so a
        // later cancel_all never disconnects a stale pair.
        var source = FAKE_ENGINE + """
                
                static FakeState g_RS, g_ED;
                static mco_coro *g_rs_co, *g_ed_co;
                static godot_Variant g_rs_out1, g_rs_out2, g_ed_out;
                static char g_emitter_storage3, g_emitter_storage4;
                #define FAKE_EMITTER3_ID 4002
                #define FAKE_EMITTER4_ID 4003
                
                static void rs_body(mco_coro *co) {
                    log_event("rs_await1");
                    godot_Signal sig1;
                    fake_signal_write(&sig1, FAKE_EMITTER3_ID, "tick");
                    gdcc_coro_await_signal(&sig1, &g_rs_out1, co, &g_RS.header);
                    CHECK(!g_RS.header.cancel, "rs must first resume through the real emission");
                    log_event("rs_await2");
                    godot_Signal sig2;
                    fake_signal_write(&sig2, FAKE_EMITTER3_ID, "tock");
                    gdcc_coro_await_signal(&sig2, &g_rs_out2, co, &g_RS.header);
                    CHECK(g_RS.header.cancel, "rs must finish through the bulk cancel");
                    log_event("rs_cleanup");
                    fake_state_write_ret(&g_RS, 0);
                }
                
                static void ed_body(mco_coro *co) {
                    log_event("ed_await");
                    godot_Signal sig;
                    fake_signal_write(&sig, FAKE_EMITTER4_ID, "tick");
                    gdcc_coro_await_signal(&sig, &g_ed_out, co, &g_ED.header);
                    CHECK(g_ED.header.cancel, "ed must resume only through cancel");
                    log_event("ed_cleanup");
                    fake_state_write_ret(&g_ED, 0);
                }
                
                int main(void) {
                    if (!godot_initialize_interface(fake_get_proc_address)) fail("interface init");
                    gdcc_coro_set_hot_reload_active(true); // enable editor-only coroutine tracking
                    g_objects[g_object_count].id = FAKE_EMITTER3_ID;
                    g_objects[g_object_count].ptr = (GDExtensionObjectPtr)&g_emitter_storage3;
                    g_objects[g_object_count].freed = 0;
                    g_object_count++;
                    g_objects[g_object_count].id = FAKE_EMITTER4_ID;
                    g_objects[g_object_count].ptr = (GDExtensionObjectPtr)&g_emitter_storage4;
                    g_objects[g_object_count].freed = 0;
                    g_object_count++;
                
                    fake_state_init(&g_RS, "RS");
                    fake_state_init(&g_ED, "ED");
                    g_rs_co = fake_make_coro(rs_body, &g_RS);
                    g_ed_co = fake_make_coro(ed_body, &g_ED);
                    gdcc_coro_active_link(&g_RS.header);
                    gdcc_coro_active_link(&g_ED.header);
                    mco_resume(g_rs_co); // RS suspends on tick
                    mco_resume(g_ed_co); // ED suspends on tick (emitter4)
                    CHECK(g_connection_count == 2, "two live connections expected");
                    CHECK(g_RS.header.signal_reg != NULL && g_ED.header.signal_reg != NULL,
                            "both waits registered");
                
                    // (1) Emit tick: the callback resumes RS, which re-suspends on tock and
                    // publishes the NEW registration before the old wait's free callback
                    // runs at the end of the emission.
                    fake_emit_signal(FAKE_EMITTER3_ID, "tick", NULL, 0);
                    // The identity guard must have kept the NEW registration: still
                    // published, and the live connection is the tock one. (No pointer
                    // comparison against the old registration - its value is indeterminate
                    // once freed.)
                    CHECK(g_RS.header.signal_reg != NULL,
                            "the resuspension registration must survive the old free callback");
                    int tock_connections = 0;
                    for (int i = 0; i < g_connection_count; i++) {
                        if (strcmp(g_connections[i].signal_name, "tock") == 0) tock_connections++;
                    }
                    CHECK(tock_connections == 1, "the live RS connection must be the tock one");
                    CHECK(g_connection_count == 1 + 1, "tock plus ed's tick remain");
                    CHECK(fake_variant_type_of(&g_rs_out1) == GDEXTENSION_VARIANT_TYPE_NIL,
                            "0-arg emission resumes with nil");
                
                    // (2) Emitter death: the teardown removes ed's connection and the free
                    // callback clears ED's registration; ED stays alive on its creator ref.
                    fake_destroy_emitter(FAKE_EMITTER4_ID);
                    CHECK(g_ED.header.signal_reg == NULL, "emitter death must clear the registration");
                    CHECK(mco_status(g_ed_co) == MCO_SUSPENDED, "ed stays suspended after the emitter died");
                
                    gdcc_coro_cancel_all(); // detaches tock for RS; ED has nothing to detach
                    CHECK(g_RS.header.cancel && g_ED.header.cancel, "both states cancelled");
                    CHECK(g_RS.header.signal_reg == NULL, "the tock connection was disconnected");
                    CHECK(g_connection_count == 0, "no connection survives the bulk cancel");
                    CHECK(g_RS.pack_calls == 0 && g_ED.pack_calls == 0, "cancel never packs");
                
                    fake_drop_ref((GDExtensionObjectPtr)&g_RS);
                    fake_drop_ref((GDExtensionObjectPtr)&g_ED);
                    CHECK(g_RS.destroy_slot_calls == 1 && g_ED.destroy_slot_calls == 1,
                            "exactly-once free_instance");
                    CHECK(g_print_error_count == 0, "no runtime errors expected");
                    CHECK(g_mem_balance == 0, "waits, registrations and callable customs freed");
                    CHECK(g_variant_destroy_count == 2, "only the two result caches are destroyed");
                    printf("OK signal_reg_lifecycle\\n");
                    return 0;
                }
                """;
        var execution = compileLinkAndRun("signal_reg_lifecycle_probe", source, runtimeObjects);
        assertEquals(0, execution.exitCode(), execution::diagnostic);
        assertEvents(execution, List.of(
                "rs_await1",
                "ed_await",
                "emit_signal:tick",
                "rs_await2",
                "emitter_teardown:tick",
                "ed_cleanup",
                "disconnect:tock",
                "rs_cleanup",
                "destroy_slot:RS",
                "free:RS",
                "destroy_slot:ED",
                "free:ED"
        ));
        assertTrue(execution.output().contains("OK signal_reg_lifecycle"), execution::diagnostic);
    }

    @Test
    void hotReloadGateOffShouldDisableTrackingAndBulkCancel() throws IOException, InterruptedException {
        // Dual-mode gate anchors: with the gate OFF (the default - release exports and
        // editor-launched game processes never set it), active links are no-ops, no
        // per-await signal registration is allocated, and cancel_all must not touch
        // anything; the ordinary coroutine lifecycle (signal await, emission resume,
        // finalize, PREDELETE cancel) is completely unaffected. Flipping the gate ON
        // afterwards restores tracking + bulk cancel for subsequently linked states -
        // the two sides of the same runtime.
        var source = FAKE_ENGINE + """
                
                static FakeState g_G1, g_G2;
                static mco_coro *g_g1_co, *g_g2_co;
                static godot_Variant g_g1_out;
                static char g_emitter_storage5;
                #define FAKE_EMITTER5_ID 4004
                
                static void g1_body(mco_coro *co) {
                    log_event("g1_await");
                    godot_Signal sig;
                    fake_signal_write(&sig, FAKE_EMITTER5_ID, "tick");
                    gdcc_coro_await_signal(&sig, &g_g1_out, co, &g_G1.header);
                    CHECK(!g_G1.header.cancel, "g1 must resume through the real emission, never a gated-off bulk cancel");
                    log_event("g1_resumed");
                    fake_state_write_ret(&g_G1, 11);
                }
                
                static void g2_body(mco_coro *co) {
                    log_event("g2_yield");
                    mco_yield(co);
                    CHECK(g_G2.header.cancel, "g2 must resume only through cancel");
                    log_event("g2_cleanup");
                    fake_state_write_ret(&g_G2, 0);
                }
                
                int main(void) {
                    if (!godot_initialize_interface(fake_get_proc_address)) fail("interface init");
                    // The gate stays OFF (its default) for the first half of this probe.
                    g_objects[g_object_count].id = FAKE_EMITTER5_ID;
                    g_objects[g_object_count].ptr = (GDExtensionObjectPtr)&g_emitter_storage5;
                    g_objects[g_object_count].freed = 0;
                    g_object_count++;
                
                    fake_state_init(&g_G1, "G1");
                    fake_state_init(&g_G2, "G2");
                    g_g1_co = fake_make_coro(g1_body, &g_G1);
                    g_g2_co = fake_make_coro(g2_body, &g_G2);
                    gdcc_coro_active_link(&g_G1.header); // no-op under the gate
                    gdcc_coro_active_link(&g_G2.header);
                
                    mco_resume(g_g1_co); // G1 connects and suspends on the signal
                    CHECK(mco_status(g_g1_co) == MCO_SUSPENDED, "g1 must be signal-suspended");
                    CHECK(g_connection_count == 1, "the one-shot connection exists regardless of the gate");
                    CHECK(g_G1.header.signal_reg == NULL, "gate-off: no registration is allocated");
                    CHECK(fake_refs_of((GDExtensionObjectPtr)&g_G1) == 2,
                            "main ref + connection keep-alive edge");
                    mco_resume(g_g2_co);
                    CHECK(mco_status(g_g2_co) == MCO_SUSPENDED, "g2 must be suspended");
                
                    gdcc_coro_cancel_all(); // no-op under the gate
                    CHECK(!g_G1.header.cancel && !g_G2.header.cancel,
                            "gate-off: bulk cancel must not touch anything");
                    CHECK(mco_status(g_g1_co) == MCO_SUSPENDED && mco_status(g_g2_co) == MCO_SUSPENDED,
                            "both coroutines remain suspended");
                
                    // Ordinary lifecycle unaffected: the emission resumes G1 to completion;
                    // the signal callback's MCO_DEAD contract drives finalize inline.
                    fake_emit_signal(FAKE_EMITTER5_ID, "tick", NULL, 0);
                    CHECK(mco_status(g_g1_co) == MCO_DEAD, "g1 completed through the emission");
                    CHECK(g_G1.pack_calls == 1 && g_G1.emit_calls == 1, "g1 finalized normally");
                    CHECK(g_G1.header.signal_reg == NULL, "no registration ever existed");
                
                    // Flipping the gate on restores tracking + bulk cancel for states
                    // linked afterwards.
                    gdcc_coro_set_hot_reload_active(true);
                    // Pin down that the gate-off links above really never published: before
                    // re-linking, a bulk cancel must find the list EMPTY (field checks alone
                    // cannot catch the single-node case prev==next==NULL && head==state).
                    gdcc_coro_cancel_all();
                    CHECK(!g_G2.header.cancel && mco_status(g_g2_co) == MCO_SUSPENDED,
                            "the gate-off link must not have published onto the active list");
                    gdcc_coro_active_link(&g_G2.header); // now it actually links
                    gdcc_coro_cancel_all();
                    CHECK(g_G2.header.cancel, "gate-on: bulk cancel abandons g2");
                    CHECK(g_G2.pack_calls == 0 && g_G2.emit_calls == 0, "cancel never packs or emits");
                
                    fake_drop_ref((GDExtensionObjectPtr)&g_G1);
                    fake_drop_ref((GDExtensionObjectPtr)&g_G2);
                    CHECK(g_G1.destroy_slot_calls == 1 && g_G2.destroy_slot_calls == 1,
                            "exactly-once free_instance");
                    CHECK(g_print_error_count == 0, "no runtime errors expected");
                    CHECK(g_mem_balance == 0,
                            "no wait node leaks; g1's registration was never allocated under the gate");
                    CHECK(g_variant_destroy_count == 3, "g1 pack + two result caches");
                    printf("OK gate_off\\n");
                    return 0;
                }
                """;
        var execution = compileLinkAndRun("gate_off_probe", source, runtimeObjects);
        assertEquals(0, execution.exitCode(), execution::diagnostic);
        assertEvents(execution, List.of(
                "g1_await",
                "g2_yield",
                "emit_signal:tick",
                "g1_resumed",
                "pack_result:G1",
                "emit:G1",
                "g2_cleanup",
                "destroy_slot:G1",
                "free:G1",
                "destroy_slot:G2",
                "free:G2"
        ));
        assertTrue(execution.output().contains("OK gate_off"), execution::diagnostic);
    }

    @Test
    void gateOffEmitterDeathShouldDriveOrdinaryPredeleteCancel() throws IOException, InterruptedException {
        // Gate-off exit-path anchor (the release-export shape): a signal-suspended state
        // held ONLY by the connection keep-alive edge; when the emitter dies, the
        // connection teardown runs the free callback (no registration exists under the
        // gate), the self edge release drops the last reference, and the ordinary
        // PREDELETE cancel path drives the coroutine to MCO_DEAD with exactly-once
        // free_instance - identical to the ordinary non-editor behavior.
        var source = FAKE_ENGINE + """
                
                static FakeState g_ED2;
                static mco_coro *g_ed2_co;
                static godot_Variant g_ed2_out;
                static char g_emitter_storage6;
                #define FAKE_EMITTER6_ID 4005
                
                static void ed2_body(mco_coro *co) {
                    log_event("ed2_await");
                    godot_Signal sig;
                    fake_signal_write(&sig, FAKE_EMITTER6_ID, "tick");
                    gdcc_coro_await_signal(&sig, &g_ed2_out, co, &g_ED2.header);
                    CHECK(g_ED2.header.cancel, "ed2 must resume only through cancel");
                    log_event("ed2_cleanup");
                    fake_state_write_ret(&g_ED2, 0);
                }
                
                int main(void) {
                    if (!godot_initialize_interface(fake_get_proc_address)) fail("interface init");
                    // The gate stays OFF (its default) for the whole probe.
                    g_objects[g_object_count].id = FAKE_EMITTER6_ID;
                    g_objects[g_object_count].ptr = (GDExtensionObjectPtr)&g_emitter_storage6;
                    g_objects[g_object_count].freed = 0;
                    g_object_count++;
                
                    fake_state_init(&g_ED2, "ED2");
                    g_ed2_co = fake_make_coro(ed2_body, &g_ED2);
                    gdcc_coro_active_link(&g_ED2.header); // no-op under the gate
                    mco_resume(g_ed2_co);
                    CHECK(mco_status(g_ed2_co) == MCO_SUSPENDED, "ed2 must be signal-suspended");
                    CHECK(g_ED2.header.signal_reg == NULL, "gate-off: no registration");
                    fake_drop_ref((GDExtensionObjectPtr)&g_ED2); // only the connection edge remains
                    CHECK(fake_refs_of((GDExtensionObjectPtr)&g_ED2) == 1,
                            "held only by the connection keep-alive edge");
                
                    fake_destroy_emitter(FAKE_EMITTER6_ID);
                
                    CHECK(g_ED2.header.cancel, "the ordinary PREDELETE cancel path ran");
                    CHECK(g_ED2.destroy_slot_calls == 1, "exactly-once free_instance");
                    CHECK(g_ED2.header.magic == 0, "identity revoked by state_free");
                    CHECK(g_ED2.pack_calls == 0 && g_ED2.emit_calls == 0, "cancel never packs or emits");
                    CHECK(g_ED2.header.signal_reg == NULL, "still no registration");
                    CHECK(g_connection_count == 0, "the emitter teardown removed the connection");
                    CHECK(g_print_error_count == 0, "no runtime errors expected");
                    CHECK(g_mem_balance == 0, "wait node and callable custom freed");
                    CHECK(g_variant_destroy_count == 1, "only the result cache is destroyed");
                    printf("OK gate_off_emitter_death\\n");
                    return 0;
                }
                """;
        var execution = compileLinkAndRun("gate_off_emitter_death_probe", source, runtimeObjects);
        assertEquals(0, execution.exitCode(), execution::diagnostic);
        assertEvents(execution, List.of(
                "ed2_await",
                "emitter_teardown:tick",
                "ed2_cleanup",
                "destroy_slot:ED2",
                "free:ED2"
        ));
        assertTrue(execution.output().contains("OK gate_off_emitter_death"), execution::diagnostic);
    }

    @Test
    void signalRegAllocationFailureShouldFailTheAwaitCleanly() throws IOException, InterruptedException {
        // Gate-on OOM anchor for the registration allocation: the wait allocation
        // succeeds, the registration allocation fails - the connect must never happen, no
        // keep-alive edge is taken (it is only own_object'ed AFTER the registration
        // allocation), the wait is freed, and the static-path await applies its failure
        // policy: runtime error, nil out, NO suspension. Exactly two diagnostics (the OOM
        // report plus the await failure report).
        var source = FAKE_ENGINE + """
                
                static FakeState g_OOM;
                static godot_Variant g_oom_out;
                static char g_emitter_storage7;
                #define FAKE_EMITTER7_ID 4006
                
                int main(void) {
                    if (!godot_initialize_interface(fake_get_proc_address)) fail("interface init");
                    gdcc_coro_set_hot_reload_active(true); // registration allocation only exists gate-on
                    g_objects[g_object_count].id = FAKE_EMITTER7_ID;
                    g_objects[g_object_count].ptr = (GDExtensionObjectPtr)&g_emitter_storage7;
                    g_objects[g_object_count].freed = 0;
                    g_object_count++;
                
                    fake_state_init(&g_OOM, "OOM");
                    // First allocation (the wait) succeeds, second (the registration) fails.
                    g_mem_alloc_fail_at_call = 2;
                    godot_Signal sig;
                    fake_signal_write(&sig, FAKE_EMITTER7_ID, "tick");
                    memset(&g_oom_out, 0xAA, sizeof(g_oom_out));
                    gdcc_coro_await_signal(&sig, &g_oom_out, NULL, &g_OOM.header);
                
                    CHECK(g_print_error_count == 2, "OOM report + await failure report");
                    CHECK(strstr(g_last_error, "await failed to connect one-shot signal") != NULL,
                            "the await applies the static-path failure policy");
                    CHECK(fake_variant_type_of(&g_oom_out) == GDEXTENSION_VARIANT_TYPE_NIL,
                            "failure resumes with nil");
                    CHECK(g_connection_count == 0, "the connect never happened");
                    CHECK(g_OOM.header.signal_reg == NULL, "no registration was published");
                    CHECK(fake_refs_of((GDExtensionObjectPtr)&g_OOM) == 1,
                            "no keep-alive edge was taken (only the creator ref remains)");
                    CHECK(g_mem_balance == 0, "the wait was freed");
                
                    fake_drop_ref((GDExtensionObjectPtr)&g_OOM);
                    CHECK(g_OOM.destroy_slot_calls == 1, "exactly-once free_instance");
                    CHECK(g_print_error_count == 2, "no further errors");
                    printf("OK signal_reg_oom\\n");
                    return 0;
                }
                """;
        var execution = compileLinkAndRun("signal_reg_oom_probe", source, runtimeObjects);
        assertEquals(0, execution.exitCode(), execution::diagnostic);
        assertEvents(execution, List.of(
                "destroy_slot:OOM",
                "free:OOM"
        ));
        assertTrue(execution.output().contains("OK signal_reg_oom"), execution::diagnostic);
    }

    @Test
    void identifyShouldRejectNonStateObjects() throws IOException, InterruptedException {        // Anchors `gdcc_coro_state_identify`: valid token+magic round-trip; rejection of
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
                if (binding != NULL || callbacks == NULL || callbacks->create_callback == NULL) return binding;
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
                        return;
                    }
                }
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
            // Failure injection for allocation-path probes: 0 disables (default for every
            // other probe); N fails the Nth fake_mem_alloc call (1-based) with NULL.
            static int g_mem_alloc_call_count = 0;
            static int g_mem_alloc_fail_at_call = 0;
            static void *fake_mem_alloc(size_t bytes) {
                g_mem_alloc_call_count++;
                if (g_mem_alloc_fail_at_call != 0 && g_mem_alloc_call_count == g_mem_alloc_fail_at_call) {
                    return NULL;
                }
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

            // ---------- fake Signal / Callable / connection layer ----------
            // godot_Callable layout: bytes [0..8) FakeCallableCustom pointer (NULL = null
            // callable). godot_Signal layout: bytes [0..8) emitter ObjectID, [8..16) signal
            // name as a stable char* (same convention as the fake StringName).
            #define FAKE_SIGNAL_OBJECT_OFF 0
            #define FAKE_SIGNAL_NAME_OFF 8
            #define FAKE_MAX_CONNECTIONS 16

            typedef struct FakeCallableCustom {
                void *userdata;
                GDExtensionCallableCustomCall call_func;
                GDExtensionCallableCustomFree free_func;
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
            // Godot's default custom-Callable equality: call_func + userdata
            // (gdextension_interface.cpp `default_compare_equal`) - the bulk-cancel
            // detach rebuilds an equal Callable through exactly this rule.
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
                custom->free_func = info->free_func;
                custom->refs = 1;
                fake_callable_custom_write((godot_Callable *)r_callable, custom);
            }
            static void fake_callable_ptr_destructor(GDExtensionTypePtr ptr) {
                fake_callable_custom_release(fake_callable_custom_of((const godot_Callable *)ptr));
            }

            static GDObjectInstanceID fake_signal_object_id_of(const godot_Signal *sig) {
                GDObjectInstanceID id;
                memcpy(&id, (const char *)sig + FAKE_SIGNAL_OBJECT_OFF, 8);
                return id;
            }
            static const char *fake_signal_name_of(const godot_Signal *sig) {
                const char *name;
                memcpy(&name, (const char *)sig + FAKE_SIGNAL_NAME_OFF, sizeof(name));
                return name;
            }
            static void fake_signal_write(godot_Signal *sig, GDObjectInstanceID id, const char *name) {
                memset(sig, 0, sizeof(*sig));
                memcpy((char *)sig + FAKE_SIGNAL_OBJECT_OFF, &id, 8);
                memcpy((char *)sig + FAKE_SIGNAL_NAME_OFF, &name, sizeof(name));
            }
            static void fake_signal_ptr_destructor(GDExtensionTypePtr ptr) {
                // A destroyed Signal no longer exposes its name; combined with the read in
                // fake_builtin_signal_disconnect this pinpoints use-after-free of the
                // caller's Signal storage instead of letting it read stale bytes.
                const char *null_name = NULL;
                memcpy((char *)ptr + FAKE_SIGNAL_NAME_OFF, &null_name, sizeof(null_name));
            }
            static void fake_signal_copy_ctor(GDExtensionUninitializedTypePtr out, const GDExtensionConstTypePtr *args) {
                memcpy(out, args[0], GDCC_GODOT_SIZE_Signal); // pure value copy in the fake
            }
            static void fake_callable_copy_ctor(GDExtensionUninitializedTypePtr out, const GDExtensionConstTypePtr *args) {
                FakeCallableCustom *custom = fake_callable_custom_of((const godot_Callable *)args[0]);
                if (custom != NULL) custom->refs++;
                fake_callable_custom_write((godot_Callable *)out, custom);
            }
            static GDExtensionPtrConstructor fake_variant_get_ptr_constructor(GDExtensionVariantType type, int32_t ctor) {
                if (type == GDEXTENSION_VARIANT_TYPE_SIGNAL && ctor == 1) return fake_signal_copy_ctor;
                if (type == GDEXTENSION_VARIANT_TYPE_CALLABLE && ctor == 1) return fake_callable_copy_ctor;
                return NULL;
            }

            // Connection table: connect retains the Callable, disconnect / one-shot removal
            // releases it; lookup follows the engine's default custom equality above.
            static struct { GDObjectInstanceID emitter; const char *signal_name; FakeCallableCustom *callable; int one_shot; } g_connections[FAKE_MAX_CONNECTIONS];
            static int g_connection_count = 0;

            static void fake_builtin_signal_connect(GDExtensionTypePtr base, const GDExtensionConstTypePtr *args, GDExtensionTypePtr ret, int argcount) {
                (void)argcount;
                const godot_Signal *sig = (const godot_Signal *)base;
                FakeCallableCustom *custom = fake_callable_custom_of((const godot_Callable *)args[0]);
                godot_int flags;
                memcpy(&flags, args[1], sizeof(flags));
                godot_int result = godot_OK;
                if (fake_object_get_instance_from_id(fake_signal_object_id_of(sig)) == NULL || custom == NULL) {
                    result = godot_ERR_INVALID_PARAMETER;
                } else {
                    if (g_connection_count >= FAKE_MAX_CONNECTIONS) fail("connection table overflow");
                    g_connections[g_connection_count].emitter = fake_signal_object_id_of(sig);
                    g_connections[g_connection_count].signal_name = fake_signal_name_of(sig);
                    g_connections[g_connection_count].callable = custom;
                    g_connections[g_connection_count].one_shot = (flags & godot_Object_CONNECT_ONE_SHOT) != 0;
                    g_connection_count++;
                    custom->refs++;
                }
                if (ret != NULL) memcpy(ret, &result, sizeof(result));
            }
            static void fake_builtin_signal_disconnect(GDExtensionTypePtr base, const GDExtensionConstTypePtr *args, GDExtensionTypePtr ret, int argcount) {
                (void)ret;
                (void)argcount;
                const godot_Signal *sig = (const godot_Signal *)base;
                FakeCallableCustom *custom = fake_callable_custom_of((const godot_Callable *)args[0]);
                GDObjectInstanceID emitter = fake_signal_object_id_of(sig);
                const char *name = fake_signal_name_of(sig);
                for (int i = 0; i < g_connection_count; i++) {
                    if (g_connections[i].emitter == emitter && strcmp(g_connections[i].signal_name, name) == 0
                            && fake_callable_equal(g_connections[i].callable, custom)) {
                        log_eventf("disconnect:%s", name);
                        FakeCallableCustom *held = g_connections[i].callable;
                        g_connections[i] = g_connections[g_connection_count - 1];
                        g_connection_count--;
                        fake_callable_custom_release(held);
                        // Engine fidelity: Object::_disconnect keeps reading the signal name
                        // AFTER the slot-map erase (which ran the free callback above and
                        // may have destroyed the caller's Signal storage). Reading it here
                        // turns a use-after-free of the Signal into a deterministic failure.
                        CHECK(fake_signal_name_of(sig) != NULL,
                                "disconnect read the signal name after the free callback destroyed it");
                        return;
                    }
                }
            }
            static GDExtensionPtrBuiltInMethod fake_variant_get_ptr_builtin_method(GDExtensionVariantType type, GDExtensionConstStringNamePtr method, GDExtensionInt hash) {
                (void)hash;
                const char *name;
                memcpy(&name, method, sizeof(name));
                if (type == GDEXTENSION_VARIANT_TYPE_SIGNAL && strcmp(name, "connect") == 0) return fake_builtin_signal_connect;
                if (type == GDEXTENSION_VARIANT_TYPE_SIGNAL && strcmp(name, "disconnect") == 0) return fake_builtin_signal_disconnect;
                return NULL;
            }

            // Engine-death emulation: the emitter is destroyed and the engine removes all
            // its connections synchronously (each removal releases the connection's
            // Callable reference, running the free callback inline).
            static void fake_destroy_emitter(GDObjectInstanceID emitter) {
                GDExtensionObjectPtr ptr = NULL;
                for (int i = 0; i < g_object_count; i++) {
                    if (g_objects[i].id == emitter && !g_objects[i].freed) ptr = g_objects[i].ptr;
                }
                if (ptr == NULL) fail("destroy_emitter on an unknown/dead emitter");
                fake_object_destroy(ptr);
                for (int i = 0; i < g_connection_count; i++) {
                    if (g_connections[i].emitter != emitter) continue;
                    log_eventf("emitter_teardown:%s", g_connections[i].signal_name);
                    FakeCallableCustom *held = g_connections[i].callable;
                    g_connections[i] = g_connections[g_connection_count - 1];
                    g_connection_count--;
                    i--;
                    fake_callable_custom_release(held);
                }
            }

            // Engine-emission emulation for probes: a one-shot connection is removed BEFORE
            // its callback runs, while the emitter keeps a local Callable reference alive
            // across the call (the free callback therefore runs only after the emission).
            static void fake_emit_signal(GDObjectInstanceID emitter, const char *signal_name, const GDExtensionConstVariantPtr *args, GDExtensionInt argc) {
                for (int i = 0; i < g_connection_count; i++) {
                    if (g_connections[i].emitter != emitter || strcmp(g_connections[i].signal_name, signal_name) != 0) continue;
                    FakeCallableCustom *custom = g_connections[i].callable;
                    custom->refs++; // local emission reference
                    if (g_connections[i].one_shot) {
                        g_connections[i] = g_connections[g_connection_count - 1];
                        g_connection_count--;
                        i--;
                        fake_callable_custom_release(custom); // the connection's edge (local ref still held)
                    }
                    log_eventf("emit_signal:%s", signal_name);
                    godot_Variant ret;
                    GDExtensionCallError err;
                    custom->call_func(custom->userdata, args, argc, &ret, &err);
                    fake_callable_custom_release(custom); // drop the local reference
                }
            }

            static GDExtensionPtrDestructor fake_variant_get_ptr_destructor(GDExtensionVariantType type) {
                if (type == GDEXTENSION_VARIANT_TYPE_CALLABLE) return fake_callable_ptr_destructor;
                if (type == GDEXTENSION_VARIANT_TYPE_SIGNAL) return fake_signal_ptr_destructor;
                return fake_ptr_destructor_noop;
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
                if (strcmp(name, "object_free_instance_binding") == 0) return (GDExtensionInterfaceFunctionPtr)fake_free_binding;
                if (strcmp(name, "object_set_instance_binding") == 0) return (GDExtensionInterfaceFunctionPtr)fake_set_binding;
                if (strcmp(name, "object_destroy") == 0) return (GDExtensionInterfaceFunctionPtr)fake_object_destroy;
                if (strcmp(name, "string_name_new_with_utf8_chars") == 0) return (GDExtensionInterfaceFunctionPtr)fake_string_name_new_with_utf8_chars;
                if (strcmp(name, "variant_get_ptr_destructor") == 0) return (GDExtensionInterfaceFunctionPtr)fake_variant_get_ptr_destructor;
                if (strcmp(name, "variant_get_ptr_constructor") == 0) return (GDExtensionInterfaceFunctionPtr)fake_variant_get_ptr_constructor;
                if (strcmp(name, "variant_get_ptr_builtin_method") == 0) return (GDExtensionInterfaceFunctionPtr)fake_variant_get_ptr_builtin_method;
                if (strcmp(name, "callable_custom_create2") == 0) return (GDExtensionInterfaceFunctionPtr)fake_callable_custom_create2;
                if (strcmp(name, "classdb_get_method_bind") == 0) return (GDExtensionInterfaceFunctionPtr)fake_classdb_get_method_bind;
                if (strcmp(name, "object_method_bind_ptrcall") == 0) return (GDExtensionInterfaceFunctionPtr)fake_object_method_bind_ptrcall;
                return (GDExtensionInterfaceFunctionPtr)fake_unused_interface;
            }
            """;
}
