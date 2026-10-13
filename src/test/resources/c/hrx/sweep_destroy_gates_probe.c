static int g_destroy1_calls = 0;
static int g_destroy2_calls = 0;
static void *g_destroy2_captures = NULL;
static void impl1(void *c, const GDExtensionConstVariantPtr *a, GDExtensionInt n,
        GDExtensionVariantPtr r, GDExtensionCallError *e) { (void)c; }
static GDExtensionBool valid1(void *c) { return true; }
static void destroy1(void *c) { g_destroy1_calls++; }
static void destroy2(void *c) { g_destroy2_calls++; g_destroy2_captures = c; }

static const unsigned char DESC_A[] = { 1, 2 };
static const unsigned char DESC_B[] = { 3, 4 };
static const unsigned char DESC_B_ENTRY[] = { 9, 9, 9 };
static const gdcc_hrx_identity IDENTITY_A = {
    .impl_key = "Worker::a#0", .schema_desc = DESC_A, .schema_desc_len = sizeof(DESC_A),
    .argument_count = 0, .schema_fingerprint = { 0xAA },
};
static const gdcc_hrx_identity IDENTITY_B = {
    .impl_key = "Worker::b#1", .schema_desc = DESC_B, .schema_desc_len = sizeof(DESC_B),
    .argument_count = 0, .schema_fingerprint = { 0xBB },
};
// Table entry for B: same key, DIFFERENT descriptor and fingerprint 0xCC.
static const gdcc_hrx_identity IDENTITY_B_ENTRY = {
    .impl_key = "Worker::b#1", .schema_desc = DESC_B_ENTRY, .schema_desc_len = sizeof(DESC_B_ENTRY),
    .argument_count = 0, .schema_fingerprint = { 0xCC },
};
static const gdcc_hrx_rebind_entry GEN2_TABLE[] = {
    { &IDENTITY_A, impl1, destroy2, valid1 },
    { &IDENTITY_B_ENTRY, impl1, destroy2, valid1 },
};

int main(void) {
    godot_initialize_interface(fake_get_proc_address);
    gdcc_hrx_test_reset_image_state(); // simulate a fresh DSO image arriving
    gdcc_hrx_initialize_core(NULL, (GDExtensionObjectPtr)&g_engine_marker, true, true, 0xABu, NULL, 0);
    const int balance_base = g_mem_balance;
    int captures_a = 11;
    int captures_b = 22;
    godot_Callable cb_a = gdcc_hrx_create_lambda(&captures_a, 0, impl1, valid1, destroy1, 0, &IDENTITY_A, NULL);
    godot_Callable cb_b = gdcc_hrx_create_lambda(&captures_b, 0, impl1, valid1, destroy1, 0, &IDENTITY_B, NULL);
    gdcc_hrx_spec *spec_b = (gdcc_hrx_spec *)fake_callable_custom_of(&cb_b)->userdata;
    gdcc_hrx_deinitialize();
                        godot_Callable_destroy(&cb_a);
    godot_Callable_destroy(&cb_b);
    CHECK(g_destroy1_calls == 0 && g_destroy2_calls == 0,
            "deinitialize must detach the sweeper before any destruction");
    // Fingerprint-collision craft: B's fingerprint now equals the table entry's
    // while its descriptor still differs.
    memcpy(spec_b->schema_fingerprint, IDENTITY_B_ENTRY.schema_fingerprint,
            sizeof(spec_b->schema_fingerprint));

    gdcc_hrx_test_reset_image_state(); // simulate a fresh DSO image arriving
    gdcc_hrx_initialize_core(NULL, (GDExtensionObjectPtr)&g_engine_marker, true, true, 0xABu,
            GEN2_TABLE, 2);
    CHECK(g_destroy2_calls == 1 && g_destroy2_captures == &captures_a,
            "matching spec must be destroyed through the REBIND TABLE destroy_fn");
    CHECK(g_destroy1_calls == 0, "the NULLed spec destroy_fn must never be consulted");
    CHECK(gdcc_hrx_current_hub()->leaked_capture_count == 1,
            "descriptor mismatch must leak the capture block intentionally");
    printf("OK sweep_destroy_gates\n");
    return 0;
}
