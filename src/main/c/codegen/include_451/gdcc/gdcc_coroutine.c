/// GDCC coroutine runtime helpers — generic translation unit.
/// Contract: doc/gdcc_runtime_lib.md §Coroutine Runtime and
/// doc/gdcc_ownership_lifecycle_spec.md §3.10.
///
/// Keep-alive edge directions (no cycles):
/// - signal wait: the one-shot connection's custom Callable holds the awaiter's own state
///   object (root edge, aligned with Godot's `bind(retvalue)`);
/// - coroutine-chain wait: the callee's waiter node holds the awaiter state object
///   (callee -> awaiter); `gdcc_coro_await_state` consumes the call site's OWNED callee
///   reference and releases it before the yield (dynamic own-state path: the operand's
///   callee reference is released and the operand reset to nil), so the callee stays alive
///   through its own wait edges and the awaiter never holds the callee across a suspension;
/// - dynamic external-object wait: the awaiter frame keeps the operand Variant (emitter)
///   retained for the whole suspension, because Signal connections do not keep the emitter
///   alive (Godot-aligned).

#include <godot_binding.h>

// `class_library` is referenced by static helpers pulled in through gdcc_helper.h. This TU
// never registers classes, so the local NULL definition is only there to satisfy those
// references; the coroutine await Callable deliberately passes `token = NULL` (verified
// against Godot 4.5.1 `CallableCustomExtension`: the token is only read by the
// `callable_custom_get_userdata` API, never for validity).
static GDExtensionClassLibraryPtr class_library = NULL;

#include <gdcc_helper.h>
#include <gdcc_coroutine.h>

#include <string.h>

void *gdcc_coro_binding_token(void) {
    /// The address of this TU-local object is the dedicated instance-binding token. It is
    /// deliberately distinct from `class_library` bindings so `identify` never confuses a
    /// plain GDCC wrapper with a coroutine state object.
    static char gdcc_coro_binding_token_storage = 0;
    return &gdcc_coro_binding_token_storage;
}

/// Module-local editor-only tracking gate: set by the generated `initialize()` from
/// `is_editor_hint()`. Active-state tracking, signal-wait registration, and bulk cancel
/// only exist so the editor can safely reload; release exports and editor-launched game
/// processes never reload, so the gate keeps that runtime cost out of those processes.
/// Defaults to false: a module whose `initialize()` never ran (pure-C fixtures) keeps
/// the ordinary coroutine lifecycle.
static bool gdcc_hot_reload_active = false;

void gdcc_coro_set_hot_reload_active(bool active) {
    gdcc_hot_reload_active = active;
}

/// Module-local intrusive list of every in-flight coroutine state. Non-owning: the
/// list never keeps a state object alive by itself - the ordinary keep-alive edges do.
/// A state is linked by the generated start thunk right after a successful `mco_create`
/// (OOM states and RELOADED_SHELLs are never linked) and unlinked by `gdcc_coro_finalize`
/// / `gdcc_coro_cancel` / `gdcc_coro_state_free`, whichever comes first.
static gdcc_coro_state_header *gdcc_active_head = NULL;

static bool gdcc_coro_active_is_linked(const gdcc_coro_state_header *state) {
    return state->active_prev != NULL || state->active_next != NULL || gdcc_active_head == state;
}

/// Idempotent unlink; safe on never-linked states.
static void gdcc_coro_active_unlink(gdcc_coro_state_header *state) {
    if (!gdcc_coro_active_is_linked(state)) {
        return;
    }
    if (state->active_prev != NULL) {
        state->active_prev->active_next = state->active_next;
    } else {
        gdcc_active_head = state->active_next;
    }
    if (state->active_next != NULL) {
        state->active_next->active_prev = state->active_prev;
    }
    state->active_prev = NULL;
    state->active_next = NULL;
}

void gdcc_coro_active_link(gdcc_coro_state_header *state) {
    if (state == NULL || !gdcc_hot_reload_active || gdcc_coro_active_is_linked(state)) {
        // Tracking is editor-only. With the gate disabled, the coroutine follows the
        // ordinary lifecycle and unlink operations remain safe no-ops.
        return;
    }
    state->active_prev = NULL;
    state->active_next = gdcc_active_head;
    if (gdcc_active_head != NULL) {
        gdcc_active_head->active_prev = state;
    }
    gdcc_active_head = state;
}

void gdcc_coro_state_header_init(gdcc_coro_state_header *state, const gdcc_coro_state_desc *desc, GDExtensionObjectPtr obj) {
    memset(state, 0, sizeof(*state)); // zeroed Variant storage is a constructed nil Variant
    state->magic = GDCC_CORO_STATE_MAGIC;
    state->desc = desc;
    state->obj = obj;
}

gdcc_coro_state_header *gdcc_coro_state_identify(GDExtensionObjectPtr obj) {
    if (obj == NULL) {
        return NULL;
    }
    // NULL callbacks: never lazily create a binding here, only recognize existing ones.
    void *binding = godot_object_get_instance_binding(obj, gdcc_coro_binding_token(), NULL);
    if (binding == NULL) {
        return NULL;
    }
    gdcc_coro_state_header *header = binding;
    if (header->magic != GDCC_CORO_STATE_MAGIC) {
        return NULL;
    }
    return header;
}

void gdcc_coro_state_free(gdcc_coro_state_header *state) {
    if (state == NULL) {
        return;
    }
    // Defensive unlink: finalize/cancel normally already did this; a state that never ran
    // either path (e.g. freed while still linked after a contract violation) must not leave
    // a dangling node behind for `gdcc_coro_cancel_all`.
    gdcc_coro_active_unlink(state);
    // A RELOADED_SHELL passes through safely: co == NULL (no stack was ever created) and
    // result_cache is still the constructed nil Variant from header init.
    if (state->co != NULL) {
        if (mco_status(state->co) != MCO_DEAD) {
            // Contract violation: cancel-resume at PREDELETE must have driven the coroutine
            // to MCO_DEAD before free_instance. Report, then still destroy to avoid leaking
            // the stack (mco_destroy also accepts MCO_SUSPENDED).
            GDCC_PRINT_RUNTIME_ERROR("gdcc: coroutine state freed before reaching MCO_DEAD",
                    "gdcc_coro_state_free", NULL, 0);
        }
        mco_destroy(state->co);
        state->co = NULL;
    }
    // result_cache is always constructed (nil when never finalized).
    godot_variant_destroy(&state->result_cache);
    // Revoke identity before the wrapper storage is released.
    state->magic = 0;
}

godot_Object *gdcc_coro_state_slot_init(void) {
    // Call-and-assign init shape ($slot = gdcc_coro_state_slot_init();): the initial value of
    // an OWNED state reference slot is simply NULL.
    return NULL;
}

void gdcc_coro_state_slot_destroy(godot_Object **slot) {
    if (*slot == NULL) {
        // Moved-from (consumed by await) or never written: nothing to release.
        return;
    }
    // State objects are RefCounted; releasing the last reference destroys the object and
    // drives the PREDELETE cancel-resume cleanup path (ownership spec §3.10).
    release_object(*slot);
    *slot = NULL;
}

/// One-shot signal wait userdata. Owns the strong reference to the awaiter's own state
/// object for as long as the connection's Callable is alive; released by the free callback
/// (one-shot fired, emitter died, connect failure dropped the last Callable reference, or
/// the bulk-cancel disconnect below).
typedef struct gdcc_coro_signal_wait {
    mco_coro *co;
    godot_Variant *out;
    GDExtensionObjectPtr self_obj; // strong edge: connection -> own state object (NULL in pure-C fixtures)
    gdcc_coro_state_header *self;  // own frame header, for the MCO_DEAD -> finalize cascade
    /// Registration allocated (with its Signal copy constructed) BEFORE the connect so
    /// every failure path below has exactly one owner for it - this wait; published to
    /// `self->signal_reg` only after a successful connect. NULL on the early-OOM path.
    struct gdcc_coro_signal_reg *reg;
} gdcc_coro_signal_wait;

/// Registration of an in-flight one-shot signal wait, referenced from the state
/// header (`signal_reg`, when the awaiter has a state object) and from the wait userdata.
/// Carries everything the bulk-cancel path needs to disconnect the pending signal: the
/// awaited Signal copy and the wait userdata whose (call_func, userdata) pair identifies
/// the connection's Callable under Godot's default custom-Callable equality
/// (gdextension_interface.cpp `default_compare_equal`). Freed by the wait free callback.
typedef struct gdcc_coro_signal_reg {
    godot_Signal sig;            // constructed copy; a Signal never retains its emitter
    gdcc_coro_signal_wait *wait; // non-owning back-pointer (the wait owns this reg)
    /// Backing spec of the connection's Callable in thunk mode (non-owning; the spec's
    /// captures ARE the wait above, so both die together). The bulk-cancel detach rebuilds
    /// its lookup key from this handle because equality under thunks is the (thunk, spec)
    /// pair, not the raw (call_func, wait) pair. NULL in direct mode.
    void *hrx_spec;
} gdcc_coro_signal_reg;

static godot_bool gdcc_coro_signal_wait_is_valid(void *userdata) {
    // The Callable holds a strong reference to the state object, so it is valid for as
    // long as the connection keeps the Callable alive.
    (void)userdata;
    return true;
}

static void gdcc_coro_signal_wait_free(void *userdata) {
    gdcc_coro_signal_wait *wait = userdata;
    if (wait == NULL) {
        return;
    }
    // Clear the registration first: the connection is gone by the time this callback
    // runs (fired, emitter died, connect failure, or bulk-cancel disconnect), so no later
    // code path may disconnect a stale pair. The state back-pointer is cleared only when
    // it still names this registration (defensive: exactly one reg exists per suspension).
    if (wait->reg != NULL) {
        if (wait->self != NULL && wait->self->signal_reg == wait->reg) {
            wait->self->signal_reg = NULL;
        }
        godot_Signal_destroy(&wait->reg->sig);
        godot_mem_free(wait->reg);
        wait->reg = NULL;
    }
    if (wait->self_obj != NULL) {
        release_object(wait->self_obj);
    }
    godot_mem_free(wait);
}

static void gdcc_coro_signal_resume_call(
        void *userdata,
        const GDExtensionConstVariantPtr *args,
        GDExtensionInt argc,
        GDExtensionVariantPtr r_return,
        GDExtensionCallError *r_error
) {
    gdcc_coro_signal_wait *wait = userdata;
    // Resume-value rule (Godot baseline): 0 args -> nil, 1 arg -> that value,
    // N args -> Array of all arguments. Engine-owned argv is only copied, never consumed.
    if (argc == 0) {
        memset(wait->out, 0, sizeof(godot_Variant)); // zeroed storage is a nil Variant
    } else if (argc == 1) {
        godot_variant_new_copy(wait->out, args[0]);
    } else {
        godot_Array packed_args = godot_new_Array();
        for (GDExtensionInt i = 0; i < argc; i++) {
            godot_Array_push_back(&packed_args, (const godot_Variant *)args[i]);
        }
        *wait->out = godot_new_Variant_with_Array(&packed_args);
        godot_Array_destroy(&packed_args);
    }
    // Custom callables must leave a valid nil return behind.
    if (r_return != NULL) {
        memset(r_return, 0, sizeof(godot_Variant));
    }
    if (r_error != NULL) {
        r_error->error = GDEXTENSION_CALL_OK;
    }
    mco_resume(wait->co);
    // Resume return point contract: a coroutine that returned MCO_DEAD must go through
    // finalize exactly once (waiter cascade). `wait` stays valid because the Callable
    // still holds the state object reference until free_func runs.
    if (wait->self != NULL && mco_status(wait->co) == MCO_DEAD) {
        gdcc_coro_finalize(wait->self);
    }
}

/// Disconnects a pending one-shot signal wait before bulk-cancelling its coroutine.
/// Rebuilds a Callable that is EQUAL to the connection's under Godot's default
/// custom-Callable equality (same call_func + same userdata, gdextension_interface.cpp
/// `default_compare_equal`), so `Signal_disconnect` locates and removes the connection;
/// removing it drops the connection's own Callable reference, whose free callback runs
/// synchronously - clearing the registration, releasing the keep-alive edge and freeing
/// the wait userdata. The rebuilt handle is ONLY a lookup key: its free callback is NULL
/// because the wait must be released exactly once, by the connection's handle. Without
/// this a later emission would resume a coroutine whose stack was destroyed after the
/// cancel, or write into a freed frame.
static void gdcc_coro_signal_detach(gdcc_coro_state_header *state) {
    gdcc_coro_signal_reg *reg = state->signal_reg;
    if (reg == NULL) {
        return;
    }
    godot_Callable probe;
    if (reg->hrx_spec != NULL) {
        // In thunk mode the connection's Callable identity is the (thunk, spec) pair, so
        // the EQUAL lookup key must be a fresh reference to the SAME spec - never a rebuilt
        // lambda Callable (which would get a new spec and silently fail to match). The
        // connection's own handle still releases the wait exactly once; this probe's free
        // thunk only decrements the shared refcount.
        probe = gdcc_hrx_callable_retain((gdcc_hrx_spec *)reg->hrx_spec);
    } else {
        probe = gdcc_new_lambda_callable(
                reg->wait,
                0,
                gdcc_coro_signal_resume_call,
                gdcc_coro_signal_wait_is_valid,
                NULL, // free_func: the connection's own handle releases the wait (exactly once)
                NULL,
                NULL  // no HRX identity: direct mode never reads it
        );
    }
    // Disconnect through a LOCAL Signal copy, never `&reg->sig`: removing the connection
    // runs the wait free callback synchronously, which destroys `reg->sig` and frees `reg`
    // itself - while `Signal::disconnect` keeps reading the signal name afterwards
    // (`Object::_disconnect` erases the slot-map entry, then still consults `p_signal`).
    // A local copy keeps that name alive across the call.
    godot_Signal sig = godot_new_Signal_with_Signal(&reg->sig);
    godot_Signal_disconnect(&sig, &probe);
    godot_Callable_destroy(&probe);
    godot_Signal_destroy(&sig);
}

/// Shared connect-and-yield core for the signal await paths.
/// Returns godot_OK when the coroutine suspended and resumed normally (`out` written by the
/// signal callback). Returns the connect error code without suspending and without touching
/// `out` otherwise; the caller decides the failure policy (static path: runtime error + nil;
/// dynamic duck-type path: pass-through).
static godot_int gdcc_coro_signal_connect_wait(godot_Signal *sig, godot_Variant *out, mco_coro *co, gdcc_coro_state_header *self) {
    gdcc_coro_signal_wait *wait = godot_mem_alloc(sizeof(gdcc_coro_signal_wait));
    if (wait == NULL) {
        GDCC_PRINT_RUNTIME_ERROR("gdcc: out of memory allocating coroutine signal waiter",
                "gdcc_coro_signal_connect_wait", NULL, 0);
        return godot_ERR_OUT_OF_MEMORY;
    }
    // The registration is allocated and its Signal copy constructed BEFORE the connect, so
    // every path below (including a connect failure, whose Callable destroy already runs
    // the free callback) leaves exactly one owner for it - the wait. Skipped entirely when
    // the hot-reload gate is off: tracking exists only for the editor bulk cancel, so
    // non-editor processes pay no per-await allocation.
    gdcc_coro_signal_reg *reg = NULL;
    if (gdcc_hot_reload_active) {
        reg = godot_mem_alloc(sizeof(gdcc_coro_signal_reg));
        if (reg == NULL) {
            godot_mem_free(wait);
            GDCC_PRINT_RUNTIME_ERROR("gdcc: out of memory allocating coroutine signal wait registration",
                    "gdcc_coro_signal_connect_wait", NULL, 0);
            return godot_ERR_OUT_OF_MEMORY;
        }
        reg->sig = godot_new_Signal_with_Signal(sig);
        reg->wait = wait;
        reg->hrx_spec = NULL;
    }
    wait->reg = reg;
    wait->co = co;
    wait->out = out;
    wait->self = self;
    wait->self_obj = (self != NULL) ? self->obj : NULL;
    if (wait->self_obj != NULL) {
        own_object(wait->self_obj);
    }
    // In thunk mode the backing spec is handed out so the bulk-cancel detach can later
    // rebuild an EQUAL lookup key from it (gdcc_coro_signal_detach). Waiter specs carry no
    // rebind identity: they are callable this generation and permanently invalid after a
    // reload (never a dangling jump into unloaded code).
    void *hrx_spec = NULL;
    godot_Callable callable = gdcc_new_lambda_callable_ex(
            wait,
            0,
            gdcc_coro_signal_resume_call,
            gdcc_coro_signal_wait_is_valid,
            gdcc_coro_signal_wait_free,
            NULL,
            NULL,
            -1,
            &hrx_spec
    );
    const godot_int connect_result = godot_Signal_connect(sig, &callable, godot_Object_CONNECT_ONE_SHOT);
    // Dropping the local Callable reference: on success the one-shot connection retains it
    // (free_func runs when the connection is removed); on failure this destroy is the last
    // reference, so free_func already released `wait`, the registration and the self edge -
    // never free twice.
    godot_Callable_destroy(&callable);
    if (connect_result != godot_OK) {
        return connect_result;
    }
    // Publish the registration only after a successful connect: from here on the wait is
    // "signal-suspended" and a bulk cancel may disconnect it through the state header.
    if (reg != NULL) {
        reg->hrx_spec = hrx_spec;
    }
    if (self != NULL) {
        self->signal_reg = reg;
    }
    mco_yield(co);
    // Resumed: `out` was written by the signal callback. A cancel-resume returns here with
    // `out` unwritten - the generated cancel check right after the await consumes that.
    return godot_OK;
}

void gdcc_coro_await_signal(godot_Signal *sig, godot_Variant *out, mco_coro *co, gdcc_coro_state_header *self) {
    const godot_int connect_result = gdcc_coro_signal_connect_wait(sig, out, co, self);
    if (connect_result != godot_OK) {
        // Deliberate deviation from Godot's hang-forever-after-failed-connect.
        GDCC_PRINT_RUNTIME_ERROR("gdcc: await failed to connect one-shot signal; resuming with nil without suspending",
                "gdcc_coro_await_signal", NULL, 0);
        memset(out, 0, sizeof(godot_Variant)); // nil
    }
}

/// Registration outcome of the own-state waiter channel.
typedef enum gdcc_coro_wait_reg {
    GDCC_CORO_WAIT_DONE,       // result copied (typed slot or result_cache), no suspend
    GDCC_CORO_WAIT_REGISTERED, // waiter registered; caller must cut its callee edge + yield
    GDCC_CORO_WAIT_FAILED      // runtime error reported, no suspend
} gdcc_coro_wait_reg;

/// Failure-path output policy: Variant channels resume with nil, while typed channels leave
/// the awaiter's slot untouched so it keeps its `__prepare__` default (there is no typed
/// nil to write). The caller reports the actual runtime error.
static void gdcc_coro_wait_fail_out(gdcc_coro_waiter_kind kind, void *out) {
    if (kind == GDCC_CORO_WAITER_VARIANT) {
        memset(out, 0, sizeof(godot_Variant)); // nil
    }
}

/// Shared core of the own-state waiter channel. Single-threaded: the check-then-register
/// sequence is atomic, so there is no connect-after-done window.
static gdcc_coro_wait_reg gdcc_coro_register_waiter(gdcc_coro_state_header *callee, gdcc_coro_waiter_kind kind, void *out, mco_coro *co, gdcc_coro_state_header *self) {
    if (callee->done) {
        // Connect-after-done fast path: copy while the caller's reference keeps the callee
        // alive; the caller releases that reference right after. Typed waiters read the
        // still-alive typed return slot; Variant waiters read the cached result Variant.
        if (kind == GDCC_CORO_WAITER_TYPED) {
            callee->desc->copy_ret_slot(callee, out);
        } else {
            godot_variant_new_copy(out, &callee->result_cache);
        }
        return GDCC_CORO_WAIT_DONE;
    }
    if (callee->cancel || callee->reloaded_shell) {
        // Cancelled callee: unreachable by construction (the caller holds a callee
        // reference, so PREDELETE cannot have run); kept as a defensive guard because a
        // cancelled callee never resumes its waiters - suspending here would hang the
        // awaiter forever. A reloaded shell represents a coroutine silently cancelled
        // during recreation, so awaiting it must return the determined cancellation
        // result immediately without ever suspending.
        GDCC_PRINT_RUNTIME_ERROR("gdcc: await on an abandoned coroutine state; resuming without suspending",
                "gdcc_coro_register_waiter", NULL, 0);
        gdcc_coro_wait_fail_out(kind, out);
        return GDCC_CORO_WAIT_FAILED;
    }
    gdcc_coro_waiter *waiter = godot_mem_alloc(sizeof(gdcc_coro_waiter));
    if (waiter == NULL) {
        GDCC_PRINT_RUNTIME_ERROR("gdcc: out of memory allocating coroutine waiter",
                "gdcc_coro_register_waiter", NULL, 0);
        gdcc_coro_wait_fail_out(kind, out);
        return GDCC_CORO_WAIT_FAILED;
    }
    waiter->kind = kind;
    waiter->co = co;
    waiter->out = out;
    waiter->awaiter = (self != NULL) ? self->obj : NULL;
    waiter->awaiter_state = self;
    if (waiter->awaiter != NULL) {
        own_object(waiter->awaiter);
    }
    // Push front; multiple waiters resume in LIFO order.
    waiter->next = callee->waiters;
    callee->waiters = waiter;
    return GDCC_CORO_WAIT_REGISTERED;
}

void gdcc_coro_await_state(gdcc_coro_state_header *callee, void *out_typed, mco_coro *co, gdcc_coro_state_header *self) {
    if (callee == NULL) {
        // Compiler bug by the single-consumer LIR contract (only state references or NULL
        // may reach the slot, and await rejects a moved-from NULL). Report and keep the
        // awaiter's default-initialized slot untouched.
        GDCC_PRINT_RUNTIME_ERROR("gdcc: await on a null coroutine state; resuming without suspending",
                "gdcc_coro_await_state", NULL, 0);
        return;
    }
    const gdcc_coro_wait_reg reg = gdcc_coro_register_waiter(callee, GDCC_CORO_WAITER_TYPED, out_typed, co, self);
    // Consume contract: the call site's OWNED callee reference is always released here.
    // On the registered path it happens BEFORE the yield, so the awaiter never holds the
    // callee across a suspension - the keep-alive graph stays a DAG (callee -> awaiter
    // only) and the abandonment cascade stays reachable. The callee itself stays alive
    // through its own wait edges.
    release_object(callee->obj);
    if (reg == GDCC_CORO_WAIT_REGISTERED) {
        mco_yield(co);
        // Resumed only via `gdcc_coro_finalize` (out already written) or via the awaiter's
        // own cancel-resume (out unwritten - the generated cancel check consumes that).
    }
}

void gdcc_coro_await_dynamic(godot_Variant *operand, godot_Variant *out, mco_coro *co, gdcc_coro_state_header *self) {
    switch (godot_variant_get_type(operand)) {
        case GDEXTENSION_VARIANT_TYPE_SIGNAL: {
            godot_Signal sig = godot_new_Signal_with_Variant(operand);
            gdcc_coro_await_signal(&sig, out, co, self);
            godot_Signal_destroy(&sig);
            return;
        }
        case GDEXTENSION_VARIANT_TYPE_OBJECT: {
            const GDObjectInstanceID id = godot_variant_get_object_instance_id(operand);
            if (id == 0) {
                // Null object payload: redundant-await pass-through.
                godot_variant_new_copy(out, operand);
                return;
            }
            const GDExtensionObjectPtr obj = gdcc_object_live_ptr(id);
            if (obj == NULL) {
                // Freed object: Godot-aligned runtime error, no suspend.
                GDCC_PRINT_RUNTIME_ERROR("Trying to await on a freed object.",
                        "gdcc_coro_await_dynamic", NULL, 0);
                memset(out, 0, sizeof(godot_Variant)); // nil
                return;
            }
            gdcc_coro_state_header *callee = gdcc_coro_state_identify(obj);
            if (callee != NULL) {
                // Own state object: direct C-level waiter channel, no Signal mechanism. The
                // dynamic path always awaits through the Variant channel.
                const gdcc_coro_wait_reg reg = gdcc_coro_register_waiter(callee, GDCC_CORO_WAITER_VARIANT, out, co, self);
                if (reg == GDCC_CORO_WAIT_REGISTERED) {
                    // Cut the operand's callee reference and reset it to nil BEFORE yielding
                    // (the destroy performs the release): the awaiter frame must not hold
                    // the callee across the suspension - same no-cycle rule as await_state.
                    godot_variant_destroy(operand);
                    memset(operand, 0, sizeof(godot_Variant)); // reset to a constructed nil
                    mco_yield(co);
                }
                // GDCC_CORO_WAIT_DONE: no suspension, the operand keep-alive edge is
                // harmless and stays untouched. GDCC_CORO_WAIT_FAILED: already reported.
                return;
            }
            // External object: duck-type on the `completed` signal; the connect error code
            // IS the existence check (deliberately wider than Godot's strict class check).
            // The operand Variant stays retained in the awaiter frame for the whole
            // suspension: Signal connections do not keep the emitter alive (Godot-aligned).
            godot_StringName completed_name = godot_new_StringName_with_utf8_chars("completed");
            godot_Signal sig = godot_new_Signal_with_Object_StringName((godot_Object *)obj, &completed_name);
            godot_StringName_destroy(&completed_name);
            const godot_int connect_result = gdcc_coro_signal_connect_wait(&sig, out, co, self);
            godot_Signal_destroy(&sig);
            if (connect_result == godot_ERR_OUT_OF_MEMORY) {
                // Pre-connect allocation failure is not a missing signal: never pass through.
                GDCC_PRINT_RUNTIME_ERROR("gdcc: out of memory preparing coroutine await; resuming with nil without suspending",
                        "gdcc_coro_await_dynamic", NULL, 0);
                memset(out, 0, sizeof(godot_Variant)); // nil
                return;
            }
            if (connect_result != godot_OK) {
                // No `completed` signal: redundant-await pass-through, no error.
                godot_variant_new_copy(out, operand);
            }
            return;
        }
        default: {
            // Any other type (including nil): pass through, no suspend.
            godot_variant_new_copy(out, operand);
            return;
        }
    }
}

void gdcc_coro_finalize(gdcc_coro_state_header *state) {
    if (state == NULL || state->done || state->cancel || state->reloaded_shell) {
        // The cancel path must never finalize; the `done` guard also makes nested re-entry
        // on the same state a no-op (finalize is re-entrant by design). A RELOADED_SHELL
        // never finalizes either: its body, waiters and `completed` emission belong to the
        // unloaded library generation.
        return;
    }
    // No longer in flight: leave the active list before any resume/emit below.
    gdcc_coro_active_unlink(state);
    // (1) Copy the typed return slot into result_cache (the slot itself stays alive for
    // typed waiters and the done fast path).
    state->desc->pack_result(state);
    // (2) Publish done: done/result_cache must be visible before any resume or emit.
    state->done = true;
    // (3) Pop waiters: one private result copy each, dispatched by waiter kind, resume
    // FIRST, then release the edge (releasing before the resume could drop the awaiter's
    // last keep-alive edge mid-flight). The node is already unlinked, so waiter resumes
    // may safely cascade nested finalizes on their own states.
    while (state->waiters != NULL) {
        gdcc_coro_waiter *waiter = state->waiters;
        state->waiters = waiter->next;
        if (waiter->kind == GDCC_CORO_WAITER_TYPED) {
            state->desc->copy_ret_slot(state, waiter->out);
        } else {
            godot_variant_new_copy((godot_Variant *)waiter->out, &state->result_cache);
        }
        mco_resume(waiter->co);
        // Resume return point contract: cascade finalize when the waiter's coroutine
        // returned MCO_DEAD. The strong edge is only released afterwards, so the awaiter
        // state object stays alive through its own finalize.
        if (waiter->awaiter_state != NULL && mco_status(waiter->co) == MCO_DEAD) {
            gdcc_coro_finalize(waiter->awaiter_state);
        }
        if (waiter->awaiter != NULL) {
            release_object(waiter->awaiter);
        }
        godot_mem_free(waiter);
    }
    // (4) External listeners last.
    if (state->desc->emit_completed != NULL) {
        state->desc->emit_completed(state);
    }
}

void gdcc_coro_cancel(gdcc_coro_state_header *state) {
    if (state == NULL || state->done || state->cancel || state->reloaded_shell) {
        // A RELOADED_SHELL is already terminal: never resume its (NULL) coroutine body and
        // never touch its (empty) waiter list - PREDELETE on a shell is a pure no-op here,
        // and the generated free_instance still runs its exactly-once field cleanup.
        return;
    }
    state->cancel = true;
    // No longer in flight: leave the active list (idempotent, also covers the
    // nested-PREDELETE cascades triggered by the waiter-edge releases below).
    gdcc_coro_active_unlink(state);
    if (state->co != NULL && mco_status(state->co) == MCO_SUSPENDED) {
        // The body checks the cancel flag right after every await resume point, jumps
        // straight to `__finally__` (the default `_return_val` from `__prepare__` is in
        // place), cleans up stack-owned values and returns MCO_DEAD. The cancel path must
        // not call any user code.
        mco_resume(state->co);
    }
    // Never finalize: no done, no pack, no emit, no waiter resume. Waiter nodes are only
    // popped to release the awaiter reference edges; abandoned awaiters stay suspended
    // forever (aligned with Godot cancel_pending_functions).
    while (state->waiters != NULL) {
        gdcc_coro_waiter *waiter = state->waiters;
        state->waiters = waiter->next;
        if (waiter->awaiter != NULL) {
            release_object(waiter->awaiter);
        }
        godot_mem_free(waiter);
    }
    // The typed return slot is deliberately NOT destroyed here: whatever the cancel-path
    // `__finally__` wrote into it is destroyed by the generated `free_instance` (exactly
    // one `desc->destroy_ret_slot` per state, tolerating never-written slots).
}

void gdcc_coro_cancel_all(void) {
    if (!gdcc_hot_reload_active) {
        // Gate-off (release exports, editor-launched game processes): never bulk-cancel.
        // In-flight coroutines keep their ordinary lifecycle - the keep-alive edges are
        // released as emitters/owners die, driving the usual PREDELETE cancel path.
        return;
    }
    // Head-pop loop: cancelling a state releases its waiter reference edges, which may
    // cascade PREDELETE -> cancel -> unlink on OTHER listed states (and free them), so the
    // head is re-read every round and `next` is never cached.
    while (gdcc_active_head != NULL) {
        gdcc_coro_state_header *state = gdcc_active_head;
        gdcc_coro_active_unlink(state);
        // Temporary strong reference: the signal detach releases the connection's
        // keep-alive edge and the cancel below may release this state's last waiter edge -
        // either could otherwise destroy the state object mid-operation.
        if (state->obj != NULL) {
            own_object(state->obj);
        }
        gdcc_coro_signal_detach(state);
        gdcc_coro_cancel(state);
        if (state->obj != NULL) {
            release_object(state->obj);
        }
    }
}
