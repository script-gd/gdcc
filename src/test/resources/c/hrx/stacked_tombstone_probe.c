static gdcc_hrx_hub g_legacy_hub;

int main(void) {
    godot_initialize_interface(fake_get_proc_address);
    // A legacy (pre-fix) process leaves: [T, T, T] ahead of a valid hub H1.
    fake_append_binding((GDExtensionObjectPtr)&g_engine_marker, (void *)0xACu, NULL);
    fake_append_binding((GDExtensionObjectPtr)&g_engine_marker, (void *)0xACu, NULL);
    fake_append_binding((GDExtensionObjectPtr)&g_engine_marker, (void *)0xACu, NULL);
    memset(&g_legacy_hub, 0, sizeof(g_legacy_hub));
    g_legacy_hub.magic = GDCC_HRX_HUB_MAGIC;
    g_legacy_hub.version = GDCC_HRX_HUB_VERSION;
    g_legacy_hub.intern_table_size = 64; // GDCC_HRX_INTERN_BUCKETS_INIT (private to gdcc_hrx.c)
    g_legacy_hub.intern_table = (gdcc_hrx_spec **)godot_mem_alloc(64 * sizeof(gdcc_hrx_spec *));
    for (unsigned i = 0; i < 64; i++) g_legacy_hub.intern_table[i] = NULL;
    // H1's thunk page is irrelevant for takeover (specs are never created here).
    fake_append_binding((GDExtensionObjectPtr)&g_engine_marker, (void *)0xACu, &g_legacy_hub);

    gdcc_hrx_test_reset_image_state(); // simulate a fresh DSO image arriving
    gdcc_hrx_mode mode = gdcc_hrx_initialize_core(
            NULL, (GDExtensionObjectPtr)&g_engine_marker, true, true, 0xACu, NULL, 0);
    CHECK(mode == GDCC_HRX_MODE_ACTIVE, "stacked tombstones must not block takeover");
    CHECK(g_free_binding_calls == 3, "every stacked tombstone must be swept exactly once");
    CHECK(gdcc_hrx_current_hub() == &g_legacy_hub,
            "the valid hub buried under the tombstones must be taken over");
    CHECK(g_binding_count == 1, "only the valid hub slot may remain");
    CHECK(g_create_callback_calls == 0, "no fresh hub may be created when a valid one surfaced");
    gdcc_hrx_deinitialize();
                        gdcc_hrx_test_reset_image_state(); // simulate a fresh DSO image arriving
                        gdcc_hrx_initialize_core(NULL, (GDExtensionObjectPtr)&g_engine_marker, true, true, 0xACu, NULL, 0);
    CHECK(gdcc_hrx_current_hub() == &g_legacy_hub, "the next generation must keep the same hub");
    printf("OK stacked-tombstone\n");
    return 0;
}
