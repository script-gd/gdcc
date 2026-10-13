static int g_free_calls = 0;
static void counting_free(void *c) { g_free_calls++; }
static void impl1(void *c, const GDExtensionConstVariantPtr *a, GDExtensionInt n,
        GDExtensionVariantPtr r, GDExtensionCallError *e) { (void)c; }
static GDExtensionBool valid1(void *c) { return true; }
static const gdcc_hrx_identity IDENTITY_U = {
    .impl_key = "Worker::u#0", .schema_desc = NULL, .schema_desc_len = 0,
    .argument_count = 0, .schema_fingerprint = { 1 },
};

int main(void) {
    godot_initialize_interface(fake_get_proc_address);
    gdcc_hrx_test_reset_image_state(); // simulate a fresh DSO image arriving
    gdcc_hrx_mode mode = gdcc_hrx_initialize_core(
            NULL, (GDExtensionObjectPtr)&g_engine_marker, true, false, 0x33u, NULL, 0);
    CHECK(mode == GDCC_HRX_MODE_UNAVAILABLE, "failed probe must resolve to UNAVAILABLE");
    CHECK(g_print_error_count == 1, "the probe failure is reported once at initialize");

    int captures = 7;
    godot_Callable cb = gdcc_new_lambda_callable(&captures, 0, impl1, valid1, counting_free, NULL, &IDENTITY_U);
    CHECK(fake_callable_custom_of(&cb) == NULL, "creation must be refused (invalid Callable)");
    CHECK(g_free_calls == 1, "captures must be consumed exactly once");
    CHECK(g_print_error_count == 1, "no per-creation error spam on the first refusal");

    godot_Callable cb2 = gdcc_new_lambda_callable(&captures, 0, impl1, valid1, counting_free, NULL, &IDENTITY_U);
    CHECK(fake_callable_custom_of(&cb2) == NULL && g_free_calls == 2,
            "every refusal consumes its captures");
    CHECK(g_print_error_count == 1, "the unavailable error is one-time");
    godot_Callable cb3 = gdcc_new_standalone_callable(
            "utility", "", "print", 0LL, 1, false, true, &IDENTITY_U);
    CHECK(fake_callable_custom_of(&cb3) == NULL, "standalone creation must also fail closed");
    printf("OK unavailable\n");
    return 0;
}
