static gdcc_hrx_hub g_bad_hub;

int main(void) {
    godot_initialize_interface(fake_get_proc_address);
    memset(&g_bad_hub, 0, sizeof(g_bad_hub));
    g_bad_hub.magic = 0xBADBADu; // wrong magic
    fake_append_binding((GDExtensionObjectPtr)&g_engine_marker, (void *)0x2468u, &g_bad_hub);
    gdcc_hrx_test_reset_image_state(); // simulate a fresh DSO image arriving
    gdcc_hrx_mode mode = gdcc_hrx_initialize_core(
            NULL, (GDExtensionObjectPtr)&g_engine_marker, true, true, 0x2468u, NULL, 0);
    CHECK(mode == GDCC_HRX_MODE_ACTIVE, "mismatch recovery must still activate");
    CHECK(g_free_binding_calls == 1, "the foreign binding must be dropped exactly once");
    gdcc_hrx_hub *hub = gdcc_hrx_current_hub();
    CHECK(hub != NULL && hub != &g_bad_hub && hub->magic == GDCC_HRX_HUB_MAGIC,
            "a fresh hub must replace the foreign one");
    CHECK(fake_get_binding((GDExtensionObjectPtr)&g_engine_marker, (void *)0x2468u, NULL) == hub,
            "the fresh hub must be mounted under the token");
    printf("OK mismatch\n");
    return 0;
}
