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
static const gdcc_hrx_identity IDENTITY_A_NEW = {
    .impl_key = "Worker::run#0", .schema_desc = DESC, .schema_desc_len = sizeof(DESC),
    .argument_count = 1, .schema_fingerprint = { 1 },
    .callsite_context = "call(base=sig_a, method=connect, arg=0)",
};
// Same key + same descriptor, different context: the swap form that must NOT rebind.
static const gdcc_hrx_identity IDENTITY_A_SWAPPED = {
    .impl_key = "Worker::run#0", .schema_desc = DESC, .schema_desc_len = sizeof(DESC),
    .argument_count = 1, .schema_fingerprint = { 1 },
    .callsite_context = "call(base=sig_b, method=connect, arg=0)",
};
static const gdcc_hrx_identity IDENTITY_C = {
    .impl_key = "Worker::run#2", .schema_desc = DESC, .schema_desc_len = sizeof(DESC),
    .argument_count = 1, .schema_fingerprint = { 1 },
    .callsite_context = NULL,
};
static const gdcc_hrx_identity IDENTITY_C_NEW = {
    .impl_key = "Worker::run#2", .schema_desc = DESC, .schema_desc_len = sizeof(DESC),
    .argument_count = 1, .schema_fingerprint = { 1 },
    .callsite_context = "call(base=sig_a, method=connect, arg=0)",
};
static const gdcc_hrx_identity IDENTITY_D = {
    .impl_key = "Worker::run#3", .schema_desc = DESC, .schema_desc_len = sizeof(DESC),
    .argument_count = 1, .schema_fingerprint = { 1 },
    .callsite_context = NULL,
};
static const gdcc_hrx_identity IDENTITY_D_NEW = {
    .impl_key = "Worker::run#3", .schema_desc = DESC, .schema_desc_len = sizeof(DESC),
    .argument_count = 1, .schema_fingerprint = { 1 },
    .callsite_context = NULL,
};
static const gdcc_hrx_rebind_entry GEN2_TABLE[] = {
    { &IDENTITY_A_NEW, impl2, noop_destroy, valid1 },
    { &IDENTITY_C_NEW, impl2, noop_destroy, valid1 },
    { &IDENTITY_D_NEW, impl2, noop_destroy, valid1 },
};

int main(void) {
    godot_initialize_interface(fake_get_proc_address);
    gdcc_hrx_test_reset_image_state();
    gdcc_hrx_initialize_core(NULL, (GDExtensionObjectPtr)&g_engine_marker, true, true, 0x77u, NULL, 0);
    const int balance_base = g_mem_balance;
    int captures = 1;
    godot_Callable cb_a = gdcc_hrx_create_lambda(&captures, 0, impl1, valid1, noop_destroy, 1, &IDENTITY_A, NULL);
    godot_Callable cb_c = gdcc_hrx_create_lambda(&captures, 0, impl1, valid1, noop_destroy, 1, &IDENTITY_C, NULL);
    godot_Callable cb_d = gdcc_hrx_create_lambda(&captures, 0, impl1, valid1, noop_destroy, 1, &IDENTITY_D, NULL);
    FakeCallableCustom *custom_a = fake_callable_custom_of(&cb_a);
    FakeCallableCustom *custom_c = fake_callable_custom_of(&cb_c);
    FakeCallableCustom *custom_d = fake_callable_custom_of(&cb_d);
    gdcc_hrx_deinitialize();

    gdcc_hrx_test_reset_image_state();
    gdcc_hrx_initialize_core(NULL, (GDExtensionObjectPtr)&g_engine_marker, true, true, 0x77u, GEN2_TABLE, 3);

    // A: identical context -> rebound to the new implementation.
    CHECK(custom_a->is_valid_func(custom_a->userdata) == true, "matching context must rebind");
    custom_a->call_func(custom_a->userdata, NULL, 0, NULL, NULL);
    CHECK(g_impl2_calls == 1 && g_impl1_calls == 0, "context-matched spec must run the new impl");
    // C: NULL context (old) vs non-NULL context (new) -> fail closed.
    CHECK(custom_c->is_valid_func(custom_c->userdata) == false, "NULL-vs-non-NULL context must not rebind");
    // D: NULL context on both sides -> matches (standalone shape).
    CHECK(custom_d->is_valid_func(custom_d->userdata) == true, "NULL==NULL context must rebind");
    custom_d->call_func(custom_d->userdata, NULL, 0, NULL, NULL);
    CHECK(g_impl2_calls == 2, "the NULL/NULL spec must run the new impl");

    godot_Callable_destroy(&cb_a);
    godot_Callable_destroy(&cb_c);
    godot_Callable_destroy(&cb_d);
    CHECK(g_mem_balance == balance_base, "all specs (incl. context copies) must be reclaimed");
    printf("OK rebind_context_gate\n");
    return 0;
}
