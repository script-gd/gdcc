static int g_foreign_binding = 0;

int main(void) {
    godot_initialize_interface(fake_get_proc_address);
    fake_append_binding((GDExtensionObjectPtr)&g_engine_marker, (void *)0xDEADu, &g_foreign_binding);
    gdcc_hrx_test_reset_image_state(); // simulate a fresh DSO image arriving
    gdcc_hrx_mode mode = gdcc_hrx_initialize_core(
            NULL, (GDExtensionObjectPtr)&g_engine_marker, true, true, 0x1234u, NULL, 0);
    CHECK(mode == GDCC_HRX_MODE_ACTIVE, "anchor creation must succeed");
    CHECK(g_binding_count == 2, "the hub must be appended alongside the foreign binding");
    CHECK(g_create_callback_calls == 1, "create_callback must run exactly once");
    CHECK(g_anchor_free_cb_was_null == 1 && g_anchor_reference_cb_was_null == 1,
            "anchor free/reference callbacks must be NULL (engine copies them)");
    gdcc_hrx_hub *hub = gdcc_hrx_current_hub();
    CHECK(hub != NULL && hub->magic == GDCC_HRX_HUB_MAGIC && hub->version == GDCC_HRX_HUB_VERSION,
            "the mounted hub must carry magic/version");
    CHECK(fake_get_binding((GDExtensionObjectPtr)&g_engine_marker, (void *)0x1234u, NULL) == hub,
            "the hub must be reachable through the anchor token");
    // Generation restart reuses the hub (no recreation, sweeper re-registered).
    gdcc_hrx_deinitialize();
                        gdcc_hrx_test_reset_image_state(); // simulate a fresh DSO image arriving
                        gdcc_hrx_initialize_core(NULL, (GDExtensionObjectPtr)&g_engine_marker, true, true, 0x1234u, NULL, 0);
    CHECK(g_create_callback_calls == 1, "restart must not recreate the hub");
    CHECK(gdcc_hrx_current_hub() == hub, "restart must keep the same hub");
    CHECK(fake_get_binding((GDExtensionObjectPtr)&g_engine_marker, (void *)0xDEADu, NULL) == &g_foreign_binding,
            "the foreign slot-0 binding must be untouched");
    printf("OK anchor\n");
    return 0;
}
