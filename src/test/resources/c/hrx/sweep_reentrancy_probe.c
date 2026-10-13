static void impl1(void *c, const GDExtensionConstVariantPtr *a, GDExtensionInt n,
        GDExtensionVariantPtr r, GDExtensionCallError *e) { (void)c; }
static GDExtensionBool valid1(void *c) { return true; }
static void destroy_b(void *c) { log_event("destroy:B"); }
static void destroy_a(void *c) {
    log_event("destroy:A");
    // Reentrant release of B's only Callable while A's sweep is in flight.
    godot_Callable *sibling = (godot_Callable *)c;
    godot_Callable_destroy(sibling);
}
static const gdcc_hrx_identity IDENTITY_A = {
    .impl_key = "Worker::a#0", .schema_desc = NULL, .schema_desc_len = 0,
    .argument_count = 0, .schema_fingerprint = { 1 },
};
static const gdcc_hrx_identity IDENTITY_B = {
    .impl_key = "Worker::b#1", .schema_desc = NULL, .schema_desc_len = 0,
    .argument_count = 0, .schema_fingerprint = { 2 },
};

int main(void) {
    godot_initialize_interface(fake_get_proc_address);
    gdcc_hrx_test_reset_image_state(); // simulate a fresh DSO image arriving
    gdcc_hrx_initialize_core(NULL, (GDExtensionObjectPtr)&g_engine_marker, true, true, 0xCDu, NULL, 0);
    const int balance_base = g_mem_balance;
    godot_Callable cb_b = gdcc_hrx_create_lambda(NULL, 0, impl1, valid1, destroy_b, 0, &IDENTITY_B, NULL);
    // A's capture block IS the sibling Callable handle (plain pointer copy).
    godot_Callable cb_a = gdcc_hrx_create_lambda(&cb_b, 0, impl1, valid1, destroy_a, 0, &IDENTITY_A, NULL);
    (void)cb_a;
    // Keep B alive through A's own reference only: drop no handle manually;
    // destroying A's Callable starts the cascade.
    godot_Callable cb_a_handle = cb_a;
    godot_Callable_destroy(&cb_a_handle);
    printf("EV done\n");
    CHECK(g_mem_balance == balance_base, "reentrant sweep must reclaim both specs");
    return 0;
}
