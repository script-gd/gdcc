static int g_impl_calls = 0;
static void impl1(void *c, const GDExtensionConstVariantPtr *a, GDExtensionInt n,
        GDExtensionVariantPtr r, GDExtensionCallError *e) { g_impl_calls++; }
static GDExtensionBool valid1(void *c) { return true; }
static void noop_destroy(void *c) { (void)c; }

int main(void) {
    godot_initialize_interface(fake_get_proc_address);
    gdcc_hrx_test_reset_image_state(); // simulate a fresh DSO image arriving
    gdcc_hrx_initialize_core(NULL, (GDExtensionObjectPtr)&g_engine_marker, true, true, 0x66u, NULL, 0);
    int captures = 9;
    godot_Callable immediate = gdcc_hrx_create_lambda(&captures, 0, impl1, valid1, noop_destroy, -1, NULL, NULL);
    godot_Callable_destroy(&immediate);
    CHECK(g_null_free_count == 0, "identityless waiter cleanup must not free NULL metadata");
    godot_Callable cb = gdcc_hrx_create_lambda(&captures, 0, impl1, valid1, noop_destroy, -1, NULL, NULL);
    FakeCallableCustom *custom = fake_callable_custom_of(&cb);
    custom->call_func(custom->userdata, NULL, 0, NULL, NULL);
    CHECK(g_impl_calls == 1, "waiter must be callable in its own generation");

    gdcc_hrx_deinitialize();
                        // New generation arrives with an EMPTY rebind table: nothing can rebind the waiter.
    gdcc_hrx_test_reset_image_state(); // simulate a fresh DSO image arriving
    gdcc_hrx_initialize_core(NULL, (GDExtensionObjectPtr)&g_engine_marker, true, true, 0x66u, NULL, 0);
    CHECK(custom->is_valid_func(custom->userdata) == false,
            "waiter must be permanently invalid after a reload");
    GDExtensionCallError err = { 0 };
    godot_Variant ret;
    custom->call_func(custom->userdata, NULL, 0, &ret, &err);
    CHECK(g_impl_calls == 1 && err.error == GDEXTENSION_CALL_ERROR_INVALID_METHOD,
            "a stray waiter must take the engine error path, never old code");
    godot_Callable_destroy(&cb);
    CHECK(gdcc_hrx_current_hub()->leaked_capture_count == 1,
            "post-reload waiter captures are intentionally leaked, not mis-destroyed");
    CHECK(g_null_free_count == 0, "post-reload shell cleanup must not free NULL metadata");
    printf("OK waiter_no_rebind\n");
    return 0;
}
