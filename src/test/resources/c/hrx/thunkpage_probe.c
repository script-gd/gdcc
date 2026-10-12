static int g_impl_calls = 0;
static void impl1(void *c, const GDExtensionConstVariantPtr *a, GDExtensionInt n,
        GDExtensionVariantPtr r, GDExtensionCallError *e) { g_impl_calls++; }
static GDExtensionBool valid1(void *c) { return true; }
static void noop_destroy(void *c) { (void)c; }
static const gdcc_hrx_identity IDENTITY_A = {
    .impl_key = "Worker::a#0", .schema_desc = NULL, .schema_desc_len = 0,
    .argument_count = 0, .schema_fingerprint = { 1 },
};
static const gdcc_hrx_identity IDENTITY_B = {
    .impl_key = "Worker::b#1", .schema_desc = NULL, .schema_desc_len = 0,
    .argument_count = 0, .schema_fingerprint = { 2 },
};
static const gdcc_hrx_identity IDENTITY_D = {
    .impl_key = "Worker::d#3", .schema_desc = NULL, .schema_desc_len = 0,
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
    printf("OK thunkpage\n");
    return 0;
}
