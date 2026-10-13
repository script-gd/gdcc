static int g_free_calls = 0;
static void impl1(void *c, const GDExtensionConstVariantPtr *a, GDExtensionInt n,
        GDExtensionVariantPtr r, GDExtensionCallError *e) { (void)c; }
static GDExtensionBool valid1(void *c) { return true; }
static void free1(void *c) { g_free_calls++; }
static GDExtensionInt argc1(void *c, GDExtensionBool *ok) { if (ok != NULL) *ok = true; return 5; }
static const gdcc_hrx_identity IDENTITY_D = {
    .impl_key = "Worker::d#0", .schema_desc = NULL, .schema_desc_len = 0,
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
    printf("OK direct\n");
    return 0;
}
