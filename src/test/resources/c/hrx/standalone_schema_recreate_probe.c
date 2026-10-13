static int g_calls1 = 0;
static int g_calls2 = 0;
static void sa_call1(void *c, const GDExtensionConstVariantPtr *a, GDExtensionInt n,
        GDExtensionVariantPtr r, GDExtensionCallError *e) { g_calls1++; }
static void sa_call2(void *c, const GDExtensionConstVariantPtr *a, GDExtensionInt n,
        GDExtensionVariantPtr r, GDExtensionCallError *e) { g_calls2++; }
static GDExtensionBool sa_valid(void *c) { return true; }
static const unsigned char DESC_V1[] = { 5, 5 };
static const unsigned char DESC_V2[] = { 9, 9, 9 };
static const gdcc_hrx_identity IDENTITY_V1 = {
    .impl_key = "standalone:utility::print", .schema_desc = DESC_V1,
    .schema_desc_len = sizeof(DESC_V1), .argument_count = 1, .schema_fingerprint = { 7 },
};
static const gdcc_hrx_identity IDENTITY_V2 = {
    .impl_key = "standalone:utility::print", .schema_desc = DESC_V2,
    .schema_desc_len = sizeof(DESC_V2), .argument_count = 2, .schema_fingerprint = { 8 },
};
static const gdcc_hrx_rebind_entry GEN2_TABLE[] = {
    { &IDENTITY_V2, sa_call2, NULL, sa_valid },
};

int main(void) {
    godot_initialize_interface(fake_get_proc_address);
    gdcc_hrx_test_reset_image_state(); // simulate a fresh DSO image arriving
    gdcc_hrx_initialize_core(NULL, (GDExtensionObjectPtr)&g_engine_marker, true, true, 0x88u, NULL, 0);
    const int balance_base = g_mem_balance;

    godot_Callable cb_old = gdcc_hrx_create_standalone(
            "utility", "", "print", 123LL, 1, false, true, sa_call1, sa_valid, &IDENTITY_V1);
    FakeCallableCustom *custom_old = fake_callable_custom_of(&cb_old);
    CHECK(custom_old != NULL, "gen1 creation must succeed");

    gdcc_hrx_deinitialize();
                        gdcc_hrx_test_reset_image_state(); // simulate a fresh DSO image arriving
                        gdcc_hrx_mode mode = gdcc_hrx_initialize_core(
            NULL, (GDExtensionObjectPtr)&g_engine_marker, true, true, 0x88u, GEN2_TABLE, 1);
    CHECK(mode == GDCC_HRX_MODE_ACTIVE, "second generation must stay ACTIVE");
    CHECK(custom_old->is_valid_func(custom_old->userdata) == false,
            "schema-mismatched standalone must stay invalid after reload");

    godot_Callable cb_new = gdcc_hrx_create_standalone(
            "utility", "", "print", 123LL, 2, false, true, sa_call2, sa_valid, &IDENTITY_V2);
    FakeCallableCustom *custom_new = fake_callable_custom_of(&cb_new);
    CHECK(custom_new != NULL, "new-generation create must succeed");
    CHECK(custom_new->userdata != custom_old->userdata,
            "a schema-incompatible zombie must never father new Callables");
    CHECK(custom_new->is_valid_func(custom_new->userdata) == true,
            "the fresh spec must be bound to the new implementation");
    custom_new->call_func(custom_new->userdata, NULL, 0, NULL, NULL);
    CHECK(g_calls2 == 1 && g_calls1 == 0, "the fresh spec must run the NEW implementation");

    godot_Callable cb_new2 = gdcc_hrx_create_standalone(
            "utility", "", "print", 123LL, 2, false, true, sa_call2, sa_valid, &IDENTITY_V2);
    CHECK(fake_callable_custom_of(&cb_new2)->userdata == custom_new->userdata,
            "interning after the mismatch must resolve to the fresh spec");

    GDExtensionCallError err = { 0 };
    godot_Variant ret;
    custom_old->call_func(custom_old->userdata, NULL, 0, &ret, &err);
    CHECK(g_calls1 == 0 && err.error == GDEXTENSION_CALL_ERROR_INVALID_METHOD,
            "the zombie must keep its fail-closed behavior");

    godot_Callable_destroy(&cb_old);
    godot_Callable_destroy(&cb_new);
    godot_Callable_destroy(&cb_new2);
    CHECK(g_mem_balance == balance_base, "zombie + fresh spec must both be reclaimed");
    printf("OK standalone-schema-recreate\n");
    return 0;
}
