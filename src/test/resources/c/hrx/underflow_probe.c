static void impl1(void *c, const GDExtensionConstVariantPtr *a, GDExtensionInt n,
        GDExtensionVariantPtr r, GDExtensionCallError *e) { (void)c; }
static GDExtensionBool valid1(void *c) { return true; }
static void noop_destroy(void *c) { (void)c; }
static const gdcc_hrx_identity IDENTITY_U = {
    .impl_key = "Worker::guard#0", .schema_desc = NULL, .schema_desc_len = 0,
    .argument_count = 0, .schema_fingerprint = { 0 },
};

int main(void) {
    godot_initialize_interface(fake_get_proc_address);
    gdcc_hrx_test_reset_image_state(); // simulate a fresh DSO image arriving
    gdcc_hrx_initialize_core(NULL, (GDExtensionObjectPtr)&g_engine_marker, true, true, 0x99u, NULL, 0);
    const int balance_base = g_mem_balance;
    godot_Callable cb = gdcc_hrx_create_lambda(NULL, 0, impl1, valid1, noop_destroy, 0, &IDENTITY_U, NULL);
    FakeCallableCustom *custom = fake_callable_custom_of(&cb);
    gdcc_hrx_spec *spec = (gdcc_hrx_spec *)custom->userdata;
    // Saved before destroy: the engine frees the custom wrapper after free_func.
    GDExtensionCallableCustomFree free_fn = custom->free_func;
    gdcc_hrx_deinitialize(); // no sweeper: frees only mark dead
                        godot_Callable_destroy(&cb);
    CHECK(spec->dead && spec->refcount == 0, "first free must mark dead exactly once");
    // Erroneous second free with the same (correct) userdata, invoked directly:
    free_fn(spec);
    CHECK(spec->dead && spec->refcount == 0, "underflow guard must keep state unchanged");
    // The next generation reclaims the shell through the two-phase sweep.
    gdcc_hrx_test_reset_image_state(); // simulate a fresh DSO image arriving
    gdcc_hrx_initialize_core(NULL, (GDExtensionObjectPtr)&g_engine_marker, true, true, 0x99u, NULL, 0);
    CHECK(g_mem_balance == balance_base, "shell must be reclaimed by the sweep");
    printf("OK underflow\n");
    return 0;
}
