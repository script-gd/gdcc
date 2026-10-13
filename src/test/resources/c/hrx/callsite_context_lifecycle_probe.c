static int g_impl1_calls = 0;
static void impl1(void *c, const GDExtensionConstVariantPtr *a, GDExtensionInt n,
        GDExtensionVariantPtr r, GDExtensionCallError *e) { g_impl1_calls++; }
static GDExtensionBool valid1(void *c) { return true; }
static void noop_destroy(void *c) { (void)c; }

static const unsigned char DESC[] = { 4, 2 };
static const gdcc_hrx_identity IDENTITY_CTX = {
    .impl_key = "Worker::run#0", .schema_desc = DESC, .schema_desc_len = sizeof(DESC),
    .argument_count = 1, .schema_fingerprint = { 1 },
    .callsite_context = "call(base=sig_a, method=connect, arg=0)",
};
static const gdcc_hrx_identity IDENTITY_CTX_NEW = {
    .impl_key = "Worker::run#0", .schema_desc = DESC, .schema_desc_len = sizeof(DESC),
    .argument_count = 1, .schema_fingerprint = { 1 },
    .callsite_context = "call(base=sig_a, method=connect, arg=0)",
};
static const gdcc_hrx_identity IDENTITY_PLAIN = {
    .impl_key = "Worker::run#1", .schema_desc = DESC, .schema_desc_len = sizeof(DESC),
    .argument_count = 1, .schema_fingerprint = { 1 },
    .callsite_context = NULL,
};
static const gdcc_hrx_rebind_entry GEN2_TABLE[] = {
    { &IDENTITY_CTX_NEW, impl1, noop_destroy, valid1 },
};

int main(void) {
    godot_initialize_interface(fake_get_proc_address);
    gdcc_hrx_test_reset_image_state();
    gdcc_hrx_initialize_core(NULL, (GDExtensionObjectPtr)&g_engine_marker, true, true, 0x79u, NULL, 0);
    const int balance_base = g_mem_balance;
    int captures = 1;

    // (1) heap copy: equal content, different storage.
    gdcc_hrx_spec *spec_ctx = NULL;
    godot_Callable cb_ctx = gdcc_hrx_create_lambda(&captures, 0, impl1, valid1, noop_destroy, 1, &IDENTITY_CTX, &spec_ctx);
    CHECK(spec_ctx != NULL, "spec must be exposed");
    CHECK(spec_ctx->callsite_context != NULL
            && spec_ctx->callsite_context != IDENTITY_CTX.callsite_context
            && strcmp(spec_ctx->callsite_context, IDENTITY_CTX.callsite_context) == 0,
            "callsite_context must be a heap copy of the identity's");
    CHECK(spec_ctx->abi_version == GDCC_HRX_ABI_VERSION, "spec records the current ABI version");
    // Watermark for step (3): everything allocated after this line belongs to
    // cb_plain alone, so destroying cb_plain must return the balance exactly here.
    const int balance_after_ctx = g_mem_balance;
    gdcc_hrx_spec *spec_plain = NULL;
    godot_Callable cb_plain = gdcc_hrx_create_lambda(&captures, 0, impl1, valid1, noop_destroy, 1, &IDENTITY_PLAIN, &spec_plain);
    CHECK(spec_plain != NULL && spec_plain->callsite_context == NULL,
            "a NULL identity context must stay NULL (no strdup(NULL))");

    gdcc_hrx_deinitialize();

    // (2) version guard: mark the context spec as a v1 relic — the rebind must
    // reject it even though key+schema+context would match the new table.
    spec_ctx->abi_version = 1u;
    gdcc_hrx_test_reset_image_state();
    gdcc_hrx_initialize_core(NULL, (GDExtensionObjectPtr)&g_engine_marker, true, true, 0x79u, GEN2_TABLE, 1);
    CHECK(spec_ctx->binding_state == GDCC_HRX_UNBOUND_INCOMPATIBLE,
            "a v1 spec must be rejected by the abi_version guard even when everything else matches");
    // Restore the version so shell_free may release the real context copy below.
    spec_ctx->abi_version = GDCC_HRX_ABI_VERSION;

    // (3) shell_free guard: a v1 block must never have its (never-present) tail
    // field read or freed. Poison the tail of the plain spec and mark it v1:
    // sweeping it must not touch the poison pointer (a free of 0x1 would crash).
    spec_plain->callsite_context = (const char *)(uintptr_t)0x1;
    spec_plain->abi_version = 1u;
    godot_Callable_destroy(&cb_plain);
    CHECK(g_mem_balance == balance_after_ctx,
            "the v1 plain spec must be reclaimed without touching the tail field");
    godot_Callable_destroy(&cb_ctx);
    CHECK(g_mem_balance == balance_base, "the context spec must be reclaimed incl. its context copy");
    printf("OK callsite_context_lifecycle\n");
    return 0;
}
