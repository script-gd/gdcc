// Runtime probe for the production template_451/onready_lookup.ftl fragment. The composed
// TU = fake-engine prelude (StringName storage + counting destructors + TU-local SN
// registry) + this file with the RENDERED fragment injected at __GENERATED_LOOKUP__.
// Flag values mirror entry.h.ftl's GDCC_FLAG_* definitions.
enum { GDCC_FLAG_ONREADY_DONE = 2, GDCC_FLAG_CTOR_COMPLETE = 4, GDCC_FLAG_HAS_ONREADY = 8 };

static char g_library_sentinel;
static GDExtensionClassLibraryPtr class_library = (GDExtensionClassLibraryPtr)&g_library_sentinel;

static _Atomic(int) g_error_count = 0;
static _Atomic(int) g_get_class_name_calls = 0;
static _Atomic(int) g_op_equal_calls = 0;
static _Atomic(int) g_apply_calls = 0;
static int g_apply_last_row = 0;
static uint64_t g_destruct_seen_in_apply = 0;
static int g_query_calls_seen_in_apply = 0;

static void counting_print_error(const char *desc, const char *func, const char *file, int32_t line, GDExtensionBool notify) {
    (void)func; (void)file; (void)line; (void)notify;
    atomic_fetch_add_explicit(&g_error_count, 1, memory_order_relaxed);
    printf("DIAG %s\n", desc != NULL ? desc : "");
    fflush(stdout);
}

// The production macro lives in gdcc_helper.h (whole helper-header graph); the probe
// binds the same call shape to the counting diagnostic so error paths stay measurable.
#define GDCC_PRINT_RUNTIME_ERROR(desc, function_name, file_name, line_number) \
    counting_print_error((desc), (function_name), (file_name), (line_number), true)

typedef struct FakeObj {
    const char *cls;
    int query_fails;
} FakeObj;

static GDExtensionBool fake_object_get_class_name(
        GDExtensionConstObjectPtr p_object,
        GDExtensionClassLibraryPtr p_library,
        GDExtensionUninitializedStringNamePtr r_class_name) {
    CHECK(p_library == class_library, "class library pointer must pass through verbatim");
    atomic_fetch_add_explicit(&g_get_class_name_calls, 1, memory_order_relaxed);
    FakeObj *o = (FakeObj *)p_object;
    if (o->query_fails) return false;
    FakeString *fs = fake_string_new_owned(o->cls);
    memcpy(r_class_name, &fs, sizeof(fs));
    return true;
}

static void fake_op_equal(GDExtensionConstTypePtr left, GDExtensionConstTypePtr right, GDExtensionTypePtr result) {
    atomic_fetch_add_explicit(&g_op_equal_calls, 1, memory_order_relaxed);
    FakeString *a = fake_string_read(left, "lookup compared a destroyed temporary StringName");
    FakeString *b = fake_string_read(right, "lookup compared a destroyed key StringName");
    *(GDExtensionBool *)result = (strcmp(a->utf8, b->utf8) == 0);
}

static GDExtensionPtrOperatorEvaluator fake_operator_evaluator(
        GDExtensionVariantOperator op, GDExtensionVariantType type_a, GDExtensionVariantType type_b) {
    if (op == GDEXTENSION_VARIANT_OP_EQUAL
            && type_a == GDEXTENSION_VARIANT_TYPE_STRING_NAME
            && type_b == GDEXTENSION_VARIANT_TYPE_STRING_NAME) {
        return fake_op_equal;
    }
    return NULL;
}

static GDExtensionInterfaceFunctionPtr probe_get_proc_address(const char *name) {
    if (strcmp(name, "print_error") == 0) return (GDExtensionInterfaceFunctionPtr)counting_print_error;
    if (strcmp(name, "object_get_class_name") == 0) return (GDExtensionInterfaceFunctionPtr)fake_object_get_class_name;
    if (strcmp(name, "variant_get_ptr_operator_evaluator") == 0) return (GDExtensionInterfaceFunctionPtr)fake_operator_evaluator;
    return fake_get_proc_address(name);
}

// Wrapper shapes mirroring the generated root struct tail (_object + flags byte); the
// struct names must match the class names because the generated code uses them as types.
typedef struct LkRoot { GDExtensionObjectPtr _object; uint8_t _gdcc_flags; } LkRoot;
typedef struct LkRoot2 { GDExtensionObjectPtr _object; uint8_t _gdcc_flags; } LkRoot2;
static inline GDExtensionObjectPtr LkRoot_object_ptr(LkRoot *self) { return self->_object; }
static inline GDExtensionObjectPtr LkRoot2_object_ptr(LkRoot2 *self) { return self->_object; }

static void LkRoot__gdcc_onready_apply_root(LkRoot *root);
static void LkMid__gdcc_onready_apply_root(LkRoot *root);
static void LkLeaf__gdcc_onready_apply_root(LkRoot *root);
static void LkRoot2__gdcc_onready_apply_root(LkRoot2 *root);

/*__GENERATED_LOOKUP__*/

static void apply_common(int row, LkRoot *root) {
    atomic_fetch_add_explicit(&g_apply_calls, 1, memory_order_relaxed);
    g_apply_last_row = row;
    g_destruct_seen_in_apply = atomic_load(&g_sn_destruct);
    g_query_calls_seen_in_apply = atomic_load(&g_get_class_name_calls);
}

static void LkRoot__gdcc_onready_apply_root(LkRoot *root) { apply_common(1, root); }
static void LkMid__gdcc_onready_apply_root(LkRoot *root) { apply_common(2, root); }
static void LkLeaf__gdcc_onready_apply_root(LkRoot *root) {
    apply_common(3, root);
    // Re-entrant entry (a `_ready` body or super call reached during apply): DONE is
    // already set, so this must return without a second query or apply.
    LkRoot__gdcc_onready_initialize_if_needed(root);
}
static void LkRoot2__gdcc_onready_apply_root(LkRoot2 *root) {
    (void)root;
    atomic_fetch_add_explicit(&g_apply_calls, 1, memory_order_relaxed);
    g_apply_last_row = 4;
    g_destruct_seen_in_apply = atomic_load(&g_sn_destruct);
    g_query_calls_seen_in_apply = atomic_load(&g_get_class_name_calls);
}

int main(void) {
    CHECK(godot_initialize_interface(probe_get_proc_address) != 0, "interface init failed");

    FakeObj root_obj = { "LkRoot", 0 };
    FakeObj mid_obj = { "LkMid", 0 };
    FakeObj leaf_obj = { "LkLeaf", 0 };
    FakeObj second_obj = { "LkRoot2", 0 };
    FakeObj fail_obj = { "LkRoot", 1 };
    FakeObj foreign_obj = { "LkForeign", 0 };
    LkRoot w = { NULL, 0 };

    // HAS=0 (clean/no-init/branch instances) and incomplete construction both gate out:
    // zero queries, zero key builds, zero compares, zero applies, flags untouched.
    w._object = (GDExtensionObjectPtr)&root_obj;
    w._gdcc_flags = GDCC_FLAG_CTOR_COMPLETE;
    LkRoot__gdcc_onready_initialize_if_needed(&w);
    CHECK(w._gdcc_flags == GDCC_FLAG_CTOR_COMPLETE, "HAS=0 gate must keep flags unchanged");
    w._gdcc_flags = GDCC_FLAG_HAS_ONREADY;
    LkRoot__gdcc_onready_initialize_if_needed(&w);
    CHECK(w._gdcc_flags == GDCC_FLAG_HAS_ONREADY, "incomplete-ctor gate must keep flags unchanged");
    CHECK(g_get_class_name_calls == 0 && g_op_equal_calls == 0 && g_apply_calls == 0,
            "gated entries must not query");
    CHECK(g_sn_from_string_construct == 0 && g_sn_destruct == 0, "no key or temp touched");

    // Valid root instance: one query, row 1, DONE latched, destructor bit preserved.
    w._gdcc_flags = 1 | GDCC_FLAG_CTOR_COMPLETE | GDCC_FLAG_HAS_ONREADY;
    LkRoot__gdcc_onready_initialize_if_needed(&w);
    CHECK(g_get_class_name_calls == 1 && g_apply_calls == 1 && g_apply_last_row == 1,
            "root row did not apply");
    CHECK(w._gdcc_flags == (1 | GDCC_FLAG_ONREADY_DONE | GDCC_FLAG_CTOR_COMPLETE | GDCC_FLAG_HAS_ONREADY),
            "flags after apply wrong");
    CHECK(g_sn_destruct == 1 && g_destruct_seen_in_apply == 1,
            "temporary must be destroyed exactly once, before apply");
    CHECK(g_query_calls_seen_in_apply == 1, "exactly one query per attempt");

    // Repeat manual/super entries: DONE short-circuits, zero extra queries.
    LkRoot__gdcc_onready_initialize_if_needed(&w);
    LkRoot__gdcc_onready_initialize_if_needed(&w);
    CHECK(g_get_class_name_calls == 1 && g_apply_calls == 1, "DONE must suppress repeats");

    // Middle row (inherited-only class): exact second row.
    LkRoot wm = { (GDExtensionObjectPtr)&mid_obj, GDCC_FLAG_CTOR_COMPLETE | GDCC_FLAG_HAS_ONREADY };
    LkRoot__gdcc_onready_initialize_if_needed(&wm);
    CHECK(g_get_class_name_calls == 2 && g_apply_last_row == 2 && g_apply_calls == 2,
            "middle row did not apply");
    CHECK(g_sn_destruct == 2 && g_destruct_seen_in_apply == g_sn_destruct,
            "middle apply must observe the temp destroyed");

    // Leaf row with the multi-key table: a shared GD_STATIC_SN expansion site would
    // collapse every key onto the first literal, so the mid query above would have hit
    // row 0 (leaf tag 3) instead of the middle row.
    LkRoot wl = { (GDExtensionObjectPtr)&leaf_obj, GDCC_FLAG_CTOR_COMPLETE | GDCC_FLAG_HAS_ONREADY };
    LkRoot__gdcc_onready_initialize_if_needed(&wl);
    CHECK(g_get_class_name_calls == 3 && g_apply_calls == 3 && g_apply_last_row == 3,
            "leaf row did not apply");
    CHECK(g_sn_destruct == 3 && g_destruct_seen_in_apply == g_sn_destruct,
            "leaf apply must observe the temp destroyed");
    CHECK(g_get_class_name_calls == 3, "re-entrant apply must not re-query");

    // The second physical root has its own table and helper.
    LkRoot2 w2 = { (GDExtensionObjectPtr)&second_obj, GDCC_FLAG_CTOR_COMPLETE | GDCC_FLAG_HAS_ONREADY };
    LkRoot2__gdcc_onready_initialize_if_needed(&w2);
    CHECK(g_get_class_name_calls == 4 && g_apply_last_row == 4 && g_apply_calls == 4,
            "second root row did not apply");
    CHECK(g_sn_destruct == 4 && g_destruct_seen_in_apply == g_sn_destruct,
            "second-root apply must observe the temp destroyed");

    // NULL raw object: no query, one diagnostic, DONE latched.
    LkRoot wn = { NULL, GDCC_FLAG_CTOR_COMPLETE | GDCC_FLAG_HAS_ONREADY };
    uint64_t queries_before = g_get_class_name_calls;
    LkRoot__gdcc_onready_initialize_if_needed(&wn);
    CHECK(g_get_class_name_calls == queries_before && g_error_count == 1
            && (wn._gdcc_flags & GDCC_FLAG_ONREADY_DONE), "null raw must diagnose and latch DONE");
    LkRoot__gdcc_onready_initialize_if_needed(&wn);
    CHECK(g_get_class_name_calls == queries_before && g_error_count == 1 && g_apply_calls == 4,
            "null raw DONE must suppress any retry");

    // Query API failure: exactly one query, diagnostic, NO temp destroy, DONE latched,
    // and a later call does not retry.
    LkRoot wf = { (GDExtensionObjectPtr)&fail_obj, GDCC_FLAG_CTOR_COMPLETE | GDCC_FLAG_HAS_ONREADY };
    uint64_t destruct_before = g_sn_destruct;
    LkRoot__gdcc_onready_initialize_if_needed(&wf);
    CHECK(g_get_class_name_calls == queries_before + 1 && g_error_count == 2,
            "query failure must query once and diagnose");
    CHECK(g_sn_destruct == destruct_before, "unpublished temp must not be destroyed");
    CHECK(g_apply_calls == 4 && (wf._gdcc_flags & GDCC_FLAG_ONREADY_DONE), "failure must not apply");
    LkRoot__gdcc_onready_initialize_if_needed(&wf);
    CHECK(g_get_class_name_calls == queries_before + 1, "query failure must not retry");

    // Unknown class: one query, temp destroyed exactly once, diagnostic, no apply.
    LkRoot wu = { (GDExtensionObjectPtr)&foreign_obj, GDCC_FLAG_CTOR_COMPLETE | GDCC_FLAG_HAS_ONREADY };
    uint64_t ops_before = g_op_equal_calls;
    LkRoot__gdcc_onready_initialize_if_needed(&wu);
    CHECK(g_get_class_name_calls == queries_before + 2 && g_error_count == 3
            && g_sn_destruct == destruct_before + 1 && g_apply_calls == 4
            && (wu._gdcc_flags & GDCC_FLAG_ONREADY_DONE), "unknown class must destroy once and diagnose");
    CHECK(g_op_equal_calls - ops_before == 3, "no-match must scan the whole table");
    LkRoot__gdcc_onready_initialize_if_needed(&wu);
    CHECK(g_get_class_name_calls == queries_before + 2 && g_error_count == 3 && g_apply_calls == 4,
            "no-match DONE must suppress any retry");

    // Recreate-shaped flags (CTOR|DONE|HAS, destructor bit set): zero queries.
    LkRoot wr = { (GDExtensionObjectPtr)&root_obj,
            1 | GDCC_FLAG_CTOR_COMPLETE | GDCC_FLAG_ONREADY_DONE | GDCC_FLAG_HAS_ONREADY };
    LkRoot__gdcc_onready_initialize_if_needed(&wr);
    CHECK(g_get_class_name_calls == queries_before + 2 && g_apply_calls == 4,
            "recreate flags must skip");

    // Same-image reinit: destroy_all empties the registry and poisons the stored keys;
    // the generation stamp makes each getter rebuild its own literal on next use —
    // exactly once per key, re-registered without duplicates, never a stale name.
    uint32_t registered_before = g_sn_registry.count;
    uint64_t destruct_before_teardown = g_sn_destruct;
    gdcc_sn_registry_destroy_all();
    CHECK(g_sn_destruct - destruct_before_teardown == registered_before,
            "destroy_all must destroy exactly the registered keys");
    CHECK(registered_before == 4 && g_sn_registry.count == 0,
            "registry must hold exactly the four built keys and be empty afterwards");
    gdcc_sn_registry_destroy_all();
    CHECK(g_sn_destruct - destruct_before_teardown == registered_before,
            "repeated destroy_all on an empty registry must destroy nothing");
    uint64_t keys_before = g_sn_from_string_construct;
    w._gdcc_flags = 1 | GDCC_FLAG_CTOR_COMPLETE | GDCC_FLAG_HAS_ONREADY;
    LkRoot__gdcc_onready_initialize_if_needed(&w);
    CHECK(g_apply_calls == 5 && g_apply_last_row == 1, "same-image reuse must re-apply");
    // The scan walks all three rows before the match, so each visited getter rebuilds
    // its own literal exactly once (no expansion-site sharing, no stale poison).
    CHECK(g_sn_from_string_construct - keys_before == 3, "visited keys rebuild exactly once");
    wl._gdcc_flags = GDCC_FLAG_CTOR_COMPLETE | GDCC_FLAG_HAS_ONREADY;
    LkRoot__gdcc_onready_initialize_if_needed(&wl);
    CHECK(g_apply_last_row == 3 && g_sn_from_string_construct - keys_before == 3,
            "fresh-generation keys must not rebuild again");
    CHECK(g_sn_registry.count == 3, "every rebuilt key re-registered once");

    // Final teardown: exactly the three rebuilt keys are destroyed; the second-root
    // key was already released above and is never rebuilt; every fake-engine
    // allocation (keys, temps, registry storage) returns to zero.
    destruct_before_teardown = g_sn_destruct;
    gdcc_sn_registry_destroy_all();
    CHECK(g_sn_destruct - destruct_before_teardown == 3,
            "final teardown must destroy exactly the rebuilt keys");
    CHECK(g_sn_registry.count == 0, "registry must be empty after final teardown");
    CHECK(atomic_load(&g_mem_balance) == 0, "all fake allocations must be released");

    printf("OK onready lookup\n");
    return 0;
}
