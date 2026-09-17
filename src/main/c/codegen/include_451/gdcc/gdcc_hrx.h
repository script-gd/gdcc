#ifndef GDCC_HRX_H
#define GDCC_HRX_H

/// Hot-reload exchange (HRX): heap-resident thunk indirection for custom Callables.
/// Contract: doc/module_impl/backend/hot_reload_implementation_plan.md §5.
///
/// In editor processes every custom Callable handed to Godot carries function pointers into
/// thunks emitted in executable heap memory (owned by no library image, so they survive
/// dlclose). A thunk only reads its pinned `gdcc_hrx_spec` (Godot heap), marks state, and
/// tail-calls the current implementation pointer; the new library generation rebinds surviving
/// specs by identity key. Non-editor processes keep the direct path and never touch this module
/// beyond one frozen mode check.

#include <godot_binding.h>

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

/// Layout version of spec/hub/thunk templates. The layouts below are ABI-frozen: fields may
/// only be appended together with a version bump, because old-generation thunks read fixed
/// offsets (asserted against the templates in gdcc_hrx.c).
/// v2 (§5.11): appended `callsite_context` to gdcc_hrx_spec (tail-only, old v1 blocks lack it
/// and are rejected by the abi_version guard before the field is ever read) and to
/// gdcc_hrx_identity (current-generation .rodata, always compiled with the matching header).
#define GDCC_HRX_ABI_VERSION 2u
#define GDCC_HRX_HUB_MAGIC UINT64_C(0x4744434348525855)
#define GDCC_HRX_HUB_VERSION 3u

/// Implementation entry of one custom Callable: identical to the per-lambda `call_func`
/// signature, except the first argument is the capture block (the thunk swaps it in).
typedef void (*gdcc_hrx_impl)(
        void *captures,
        const GDExtensionConstVariantPtr *args,
        GDExtensionInt argc,
        GDExtensionVariantPtr r_return,
        GDExtensionCallError *r_error
);
/// Capture destruction entry: identical to the per-lambda `free_func` signature.
typedef void (*gdcc_hrx_destroy_fn)(void *captures);
/// Optional per-lambda liveness predicate: identical to the per-lambda `is_valid_func`.
typedef GDExtensionBool (*gdcc_hrx_is_valid_fn)(void *captures);

/// Rebind verdict of a spec. Deliberately only two values: life/death is `dead` (written by the
/// free thunk), binding is written by main-library code only. `destroy_fn == NULL` must never
/// be used as an incompatibility signal (a compatible spec may have nothing to destroy).
typedef enum gdcc_hrx_binding_state {
    GDCC_HRX_BOUND_COMPATIBLE = 0,
    GDCC_HRX_UNBOUND_INCOMPATIBLE = 1
} gdcc_hrx_binding_state;

typedef struct gdcc_hrx_hub gdcc_hrx_hub;

/// Cross-generation Callable state. Godot-heap allocated, explicitly initialized
/// (`godot_mem_alloc` does not zero). Field offsets up to `hub` are read by machine-code
/// thunks and are therefore hard-frozen (static-asserted in gdcc_hrx.c).
typedef struct gdcc_hrx_spec {
    gdcc_hrx_impl impl_ptr;                 // offset 0:  current-generation implementation
    gdcc_hrx_destroy_fn destroy_fn;         // offset 8:  current-generation capture destroyer
    gdcc_hrx_is_valid_fn is_valid_fn;       // offset 16: current-generation liveness predicate
    void *captures;                         // offset 24: capture block (lambda) or standalone payload
    uint32_t dead;                          // offset 32: set by the free thunk once refcount hits 0
    uint32_t refcount;                      // offset 36: live Callable custom objects sharing this spec
    int32_t argument_count;                 // offset 40: data-driven get_argument_count answer
    uint32_t abi_version;                   // offset 44: GDCC_HRX_ABI_VERSION at creation
    gdcc_hrx_hub *hub;                      // offset 48: back-pointer (free thunk reads hub->sweeper)
    gdcc_hrx_binding_state binding_state;   // main-library-written rebind verdict
    uint32_t interned;                      // 1 = standalone (hub interning table member), 0 = lambda
    const char *impl_key;                   // heap-copied stable source identity (NULL: never rebinds)
    const unsigned char *schema_desc;       // heap-copied canonical schema descriptor
    uint32_t schema_desc_len;
    uint32_t reserved0;
    unsigned char schema_fingerprint[16];   // 128-bit fingerprint of schema_desc (lookup hint)
    struct gdcc_hrx_spec *prev;             // hub registry double-linked list
    struct gdcc_hrx_spec *next;
    struct gdcc_hrx_spec *intern_next;      // interning hash chain (standalone only)
    struct gdcc_hrx_spec *pending_next;     // sweep-pending queue link (sweep time only)
    // v2 append (ABI tail): heap-copied normalized call-site context; read/released ONLY
    // after an `abi_version >= 2` check — v1 blocks end right after `pending_next`.
    const char *callsite_context;
} gdcc_hrx_spec;

typedef void (*gdcc_hrx_sweep_fn)(gdcc_hrx_spec *spec);

/// Cross-generation anchor state. Reached through the Engine singleton's instance binding
/// under a per-extension constant token, so it survives library unload; every pointer the
/// engine may hold onto (binding callbacks free/reference) is NULL by contract.
struct gdcc_hrx_hub {
    uint64_t magic;                     // offset 0
    uint32_t version;                   // offset 8
    uint32_t sweep_depth;               // offset 12: reentrancy guard (>0: free thunks enqueue)
    gdcc_hrx_spec *registry;            // offset 16: non-owning roster of all live specs
    gdcc_hrx_spec **intern_table;       // offset 24: standalone identity hash buckets
    uint32_t intern_table_size;         // offset 32
    uint32_t intern_count;              // offset 36
    gdcc_hrx_sweep_fn sweeper;          // offset 40: current-generation sweeper (thunks tail-jump here)
    gdcc_hrx_spec *sweep_pending;       // offset 48: deferred sweep queue (via pending_next)
    void *thunk_page;                   // offset 56: shared RX thunk page (written ONCE at hub
                                        // creation, before any Callable exists; never written again)
    uint64_t leaked_capture_count;      // diagnostics: capture blocks intentionally leaked
};

/// Codegen-emitted stable identity of one lambda or standalone Callable. All pointers reference
/// generated `.rodata` (current-generation only); specs always store heap copies.
typedef struct gdcc_hrx_identity {
    const char *impl_key;                 // `<Class>::<func>#<ordinal>` or `standalone:<kind>:<owner>:<name>`
    const unsigned char *schema_desc;     // canonical schema encoding (layout + signature + abi)
    uint32_t schema_desc_len;
    int32_t argument_count;               // data-driven argument count (part of the schema surface)
    unsigned char schema_fingerprint[16]; // 128-bit fingerprint of schema_desc
    // §5.11 third rebind gate: normalized call-site context. Current-generation .rodata only
    // (never shared across generations), so appending here is layout-safe. NULL for standalone
    // identities; NULL-safe equality treats NULL==NULL as a match.
    const char *callsite_context;
} gdcc_hrx_identity;

/// Codegen-emitted module-level rebind table entry: identity plus this generation's functions.
typedef struct gdcc_hrx_rebind_entry {
    const gdcc_hrx_identity *identity;
    gdcc_hrx_impl impl;
    gdcc_hrx_destroy_fn destroy;
    gdcc_hrx_is_valid_fn is_valid;
} gdcc_hrx_rebind_entry;

/// Standalone Callable capture payload: fixed-ABI heap clone of the identity triple + call
/// metadata. Layout matches `gdcc_standalone_callable_spec` (gdcc_callable.h aliases it) so the
/// shared standalone `call` implementation can read it unchanged; released unconditionally as
/// shell metadata (never through schema matching).
typedef struct gdcc_hrx_standalone_payload {
    const char *kind;    // Godot-heap copies (library `.rodata` dies at dlclose)
    const char *owner;
    const char *name;
    godot_int utility_hash;
    int argument_count;
    godot_bool is_vararg;
    godot_bool returns_value;
} gdcc_hrx_standalone_payload;

/// Process-frozen dispatch mode (hot_reload_implementation_plan.md §5.4).
typedef enum gdcc_hrx_mode {
    GDCC_HRX_MODE_UNINITIALIZED = 0,
    GDCC_HRX_MODE_DIRECT_NON_RELOAD,  // non-editor process: legacy direct callables, zero HRX state
    GDCC_HRX_MODE_ACTIVE,             // editor + executable memory: thunk path
    GDCC_HRX_MODE_UNAVAILABLE         // editor without executable memory: fail-closed, never direct
} gdcc_hrx_mode;

gdcc_hrx_mode gdcc_hrx_get_mode(void);

/// Generated `initialize()` entry: resolves the Engine singleton + editor hint, probes
/// executable memory (editor only), then delegates to `gdcc_hrx_initialize_core`.
/// Called before any class registration / static init so no custom Callable can predate the mode.
gdcc_hrx_mode gdcc_hrx_initialize(
        GDExtensionClassLibraryPtr library,
        uint64_t anchor_token,
        const gdcc_hrx_rebind_entry *entries,
        uint32_t entry_count
);

/// Mode decision + hub takeover with explicit environment inputs (test-visible core).
/// Idempotent per generation: the first call freezes the mode; later calls (new generation in
/// the same process) skip the decision and only re-register the sweeper + run rebind/sweep.
gdcc_hrx_mode gdcc_hrx_initialize_core(
        GDExtensionClassLibraryPtr library,
        GDExtensionObjectPtr engine_object,
        bool is_editor,
        bool execmem_probe_ok,
        uint64_t anchor_token,
        const gdcc_hrx_rebind_entry *entries,
        uint32_t entry_count
);

/// Generated `deinitialize()` tail (D8, after the runtime registries): detaches this
/// generation's sweeper, then NULLs every spec's function pointers and marks them UNBOUND.
/// Afterwards no path needs old-library code anymore; `dead`/`refcount` stay untouched.
void gdcc_hrx_deinitialize(void);

/// Full executable-memory probe: RW map -> write -> RX -> execute a nop thunk -> unmap.
/// Architectures without a thunk template set always probe false (fail-closed).
bool gdcc_hrx_execmem_probe(void);

/// HRX lambda creation (mode ACTIVE only; the mode dispatch lives in gdcc_callable.h).
/// `identity == NULL` marks a non-rebindable spec (coroutine signal waiter): callable this
/// generation, permanently invalid after a reload. On success `out_spec` (nullable) receives
/// the spec; the spec is registered + refcounted BEFORE `callable_custom_create2` runs.
godot_Callable gdcc_hrx_create_lambda(
        void *captures,
        GDObjectInstanceID object_id,
        gdcc_hrx_impl impl,
        gdcc_hrx_is_valid_fn is_valid,
        gdcc_hrx_destroy_fn destroy,
        int32_t argument_count,
        const gdcc_hrx_identity *identity,
        gdcc_hrx_spec **out_spec
);

/// HRX standalone creation: interns by identity in the hub table (a hit shares `(thunk, spec)`
/// and keeps Godot's default `(call_func, userdata)` equality), otherwise clones the payload
/// onto the Godot heap and builds a fresh spec. Dead interned specs are never reused.
godot_Callable gdcc_hrx_create_standalone(
        const char *kind,
        const char *owner,
        const char *name,
        godot_int utility_hash,
        int argument_count,
        godot_bool is_vararg,
        godot_bool returns_value,
        gdcc_hrx_impl call_impl,
        gdcc_hrx_is_valid_fn is_valid_impl,
        const gdcc_hrx_identity *identity
);

/// Re-acquires a Callable for a live spec (refcount +1, same `(thunk, spec)` identity).
/// Used by the coroutine signal-detach path to rebuild an EQUAL lookup key under Godot's
/// default custom-Callable equality. Dead/NULL specs yield an invalid Callable.
godot_Callable gdcc_hrx_callable_retain(gdcc_hrx_spec *spec);

/// Zeroed (engine-invalid) Callable for the fail-closed paths.
godot_Callable gdcc_hrx_invalid_callable(void);

/// One-time error report for HRX_UNAVAILABLE creations.
void gdcc_hrx_report_unavailable(void);

/// Current-generation sweeper (registered into `hub->sweeper` at initialize). Single-spec
/// reclaim: interning detach before registry unlink before destruction, reentrancy deferred
/// through the pending queue; capture destruction only for BOUND specs with a live destroy_fn.
void gdcc_hrx_sweep(gdcc_hrx_spec *spec);

/// Currently resolved hub (this generation's image-local cache; NULL outside ACTIVE mode).
gdcc_hrx_hub *gdcc_hrx_current_hub(void);

/// Thunk template access. Machine-code templates are hand-assembled, position-independent,
/// ABI-frozen and need ZERO runtime patching: the spec arrives in each callback's first
/// argument (Godot always passes `callable_userdata`) and the free thunk reaches the hub
/// through `spec->hub`. All three architecture variants exist as data on every host (only
/// the active variant is ever executed) so tests can pin their bytes everywhere.
typedef enum gdcc_hrx_thunk_kind {
    GDCC_HRX_THUNK_IS_VALID = 0,
    GDCC_HRX_THUNK_FREE,
    GDCC_HRX_THUNK_CALL,
    GDCC_HRX_THUNK_GET_ARGUMENT_COUNT,
    GDCC_HRX_THUNK_KIND_COUNT
} gdcc_hrx_thunk_kind;

typedef enum gdcc_hrx_thunk_arch {
    GDCC_HRX_ARCH_X86_64_SYSV = 0,  // also macOS x86_64 (same System V AMD64 ABI)
    GDCC_HRX_ARCH_X86_64_WIN64,
    GDCC_HRX_ARCH_AARCH64,          // Linux + macOS arm64 (icache flushed after writes)
    GDCC_HRX_ARCH_COUNT
} gdcc_hrx_thunk_arch;

typedef struct gdcc_hrx_thunk_template {
    const uint8_t *bytes;
    uint32_t size;
} gdcc_hrx_thunk_template;

const gdcc_hrx_thunk_template *gdcc_hrx_thunk_template_get_for_arch(gdcc_hrx_thunk_arch arch, gdcc_hrx_thunk_kind kind);
uint32_t gdcc_hrx_thunk_bundle_offset_for_arch(gdcc_hrx_thunk_arch arch, gdcc_hrx_thunk_kind kind);

/// Host-architecture shortcuts used by the thunk-page publisher and Callable factory.
const gdcc_hrx_thunk_template *gdcc_hrx_thunk_template_get(gdcc_hrx_thunk_kind kind);
uint32_t gdcc_hrx_thunk_bundle_offset(gdcc_hrx_thunk_kind kind);

#endif //GDCC_HRX_H
