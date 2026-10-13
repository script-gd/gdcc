int main(void) {
    godot_initialize_interface(fake_get_proc_address);
    // Simulate the tombstone left by a failed first hub creation.
    fake_append_binding((GDExtensionObjectPtr)&g_engine_marker, (void *)0xABu, NULL);
    gdcc_hrx_test_reset_image_state(); // simulate a fresh DSO image arriving
    gdcc_hrx_mode mode = gdcc_hrx_initialize_core(
            NULL, (GDExtensionObjectPtr)&g_engine_marker, true, true, 0xABu, NULL, 0);
    CHECK(mode == GDCC_HRX_MODE_ACTIVE, "the tombstone must not block hub creation");
    CHECK(g_free_binding_calls == 1, "the tombstone must be dropped exactly once");
    gdcc_hrx_hub *hub = gdcc_hrx_current_hub();
    CHECK(hub != NULL && hub->magic == GDCC_HRX_HUB_MAGIC, "a fresh hub must be mounted");
    CHECK(fake_get_binding((GDExtensionObjectPtr)&g_engine_marker, (void *)0xABu, NULL) == hub,
            "the only slot for the token must be the fresh hub");
    CHECK(g_binding_count == 1, "the tombstone must not leave duplicate slots");
    gdcc_hrx_deinitialize();
                        gdcc_hrx_test_reset_image_state(); // simulate a fresh DSO image arriving
                        gdcc_hrx_initialize_core(NULL, (GDExtensionObjectPtr)&g_engine_marker, true, true, 0xABu, NULL, 0);
    CHECK(gdcc_hrx_current_hub() == hub, "the next generation must take over the same hub");
    CHECK(g_create_callback_calls == 1, "no second hub may be created");
    printf("OK tombstone\n");
    return 0;
}
