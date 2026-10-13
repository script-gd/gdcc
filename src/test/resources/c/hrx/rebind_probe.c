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
    .impl_key = "Worker::run#0", .schema_desc = DESC, .schema_desc_len = sizeof(DESC),
    .argument_count = 2, .schema_fingerprint = { 1 },
};
// Same key + same descriptor, only a different argument count: still compatible.
static const gdcc_hrx_identity IDENTITY_A_NEW = {
    .impl_key = "Worker::run#0", .schema_desc = DESC, .schema_desc_len = sizeof(DESC),
    .argument_count = 3, .schema_fingerprint = { 1 },
};
static const gdcc_hrx_identity IDENTITY_B = {
    .impl_key = "Worker::hide#1", .schema_desc = DESC, .schema_desc_len = sizeof(DESC),
    .argument_count = 1, .schema_fingerprint = { 2 },
};
static const gdcc_hrx_identity IDENTITY_B_CHANGED = {
    .impl_key = "Worker::hide#1", .schema_desc = DESC_OTHER, .schema_desc_len = sizeof(DESC_OTHER),
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
    printf("OK rebind\n");
    return 0;
}
