static gdcc_hrx_hub g_bad_hub;

int main(void) {
    godot_initialize_interface(fake_get_proc_address);
    memset(&g_bad_hub, 0, sizeof(g_bad_hub));
    g_bad_hub.magic = 0xBADBADu; // wrong magic
    fake_append_binding((GDExtensionObjectPtr)&g_engine_marker, (void *)0x2469u, &g_bad_hub);
    gdcc_hrx_test_reset_image_state(); // simulate a fresh DSO image arriving
    gdcc_hrx_mode mode = gdcc_hrx_initialize_core(
            NULL, (GDExtensionObjectPtr)&g_engine_marker, true, false, 0x2469u, NULL, 0);
    CHECK(mode == GDCC_HRX_MODE_UNAVAILABLE,
            "a corrupt anchor must not bypass the probe fail-closed decision");
    CHECK(g_free_binding_calls == 1, "the foreign binding must still be dropped exactly once");
    CHECK(gdcc_hrx_current_hub() == NULL, "no fresh hub may be mounted without a passing probe");
    CHECK(g_print_error_count == 1, "the unavailable decision is reported once");
    printf("OK mismatch-probe\n");
    return 0;
}
