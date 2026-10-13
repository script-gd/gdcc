static int g_impl1_calls = 0;
static int g_impl2_calls = 0;
static void impl1(void *c, const GDExtensionConstVariantPtr *a, GDExtensionInt n,
        GDExtensionVariantPtr r, GDExtensionCallError *e) { g_impl1_calls++; }
static void impl2(void *c, const GDExtensionConstVariantPtr *a, GDExtensionInt n,
        GDExtensionVariantPtr r, GDExtensionCallError *e) { g_impl2_calls++; }
static GDExtensionBool valid1(void *c) { return true; }
static void noop_destroy(void *c) { (void)c; }

static const unsigned char DESC[] = { 9, 8, 7 };
static const gdcc_hrx_identity IDENTITY_A = {
    .impl_key = "Worker::run#0", .schema_desc = DESC, .schema_desc_len = sizeof(DESC),
    .argument_count = 1, .schema_fingerprint = { 1 },
    .callsite_context = "call(base=sig_a, method=connect, arg=0)",
};
static const gdcc_hrx_identity IDENTITY_A_SWAPPED = {
    .impl_key = "Worker::run#0", .schema_desc = DESC, .schema_desc_len = sizeof(DESC),
    .argument_count = 1, .schema_fingerprint = { 1 },
    .callsite_context = "call(base=sig_b, method=connect, arg=0)",
};
static const gdcc_hrx_rebind_entry GEN2_TABLE[] = {
    { &IDENTITY_A_SWAPPED, impl2, noop_destroy, valid1 },
};

int main(void) {
    godot_initialize_interface(fake_get_proc_address);
    gdcc_hrx_test_reset_image_state();
    gdcc_hrx_initialize_core(NULL, (GDExtensionObjectPtr)&g_engine_marker, true, true, 0x78u, NULL, 0);
    const int balance_base = g_mem_balance;
    int captures = 1;
    godot_Callable cb_a = gdcc_hrx_create_lambda(&captures, 0, impl1, valid1, noop_destroy, 1, &IDENTITY_A, NULL);
    FakeCallableCustom *custom_a = fake_callable_custom_of(&cb_a);
    gdcc_hrx_deinitialize();

    gdcc_hrx_test_reset_image_state();
    gdcc_hrx_initialize_core(NULL, (GDExtensionObjectPtr)&g_engine_marker, true, true, 0x78u, GEN2_TABLE, 1);

    // Context mismatch with identical key+schema: fail closed.
    CHECK(custom_a->is_valid_func(custom_a->userdata) == false, "mismatched context must stay invalid");
    GDExtensionCallError err = { 0 };
    godot_Variant ret;
    custom_a->call_func(custom_a->userdata, NULL, 0, &ret, &err);
    CHECK(g_impl1_calls == 0 && g_impl2_calls == 0, "a mismatched spec must never run any impl");
    CHECK(err.error == GDEXTENSION_CALL_ERROR_INVALID_METHOD, "mismatch must take the engine error path");

    godot_Callable_destroy(&cb_a);
    CHECK(g_mem_balance == balance_base, "the mismatched spec must be reclaimed incl. its context copy");
    printf("OK rebind_context_mismatch\n");
    return 0;
}
