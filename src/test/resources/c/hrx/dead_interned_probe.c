static void sa_call(void *c, const GDExtensionConstVariantPtr *a, GDExtensionInt n,
        GDExtensionVariantPtr r, GDExtensionCallError *e) { (void)c; }
static GDExtensionBool sa_valid(void *c) { return true; }
static const unsigned char DESC_SA[] = { 3 };
static const gdcc_hrx_identity IDENTITY_SA = {
    .impl_key = "standalone:utility::str", .schema_desc = DESC_SA,
    .schema_desc_len = sizeof(DESC_SA), .argument_count = 0, .schema_fingerprint = { 9 },
};

int main(void) {
    godot_initialize_interface(fake_get_proc_address);
    gdcc_hrx_test_reset_image_state(); // simulate a fresh DSO image arriving
    gdcc_hrx_initialize_core(NULL, (GDExtensionObjectPtr)&g_engine_marker, true, true, 0x88u, NULL, 0);
    const int balance_base = g_mem_balance;
    godot_Callable cb1 = gdcc_hrx_create_standalone(
            "utility", "", "str", 0LL, 0, false, true, sa_call, sa_valid, &IDENTITY_SA);
    gdcc_hrx_spec *spec1 = (gdcc_hrx_spec *)fake_callable_custom_of(&cb1)->userdata;
    gdcc_hrx_deinitialize(); // sweeper detached: frees below only mark dead
                        godot_Callable_destroy(&cb1);
    CHECK(spec1->dead, "refcount zero crossing must mark dead even without a sweeper");

    godot_Callable cb2 = gdcc_hrx_create_standalone(
            "utility", "", "str", 0LL, 0, false, true, sa_call, sa_valid, &IDENTITY_SA);
    gdcc_hrx_spec *spec2 = (gdcc_hrx_spec *)fake_callable_custom_of(&cb2)->userdata;
    CHECK(spec2 != spec1, "interning lookup must reject the dead spec and build a new one");
    CHECK(spec2->refcount == 1 && !spec2->dead, "the new spec must be the live interned entry");

    // Next generation: the dead corpse is swept (payload + shell reclaimed),
    // the live spec2 survives the rebind pass untouched.
    gdcc_hrx_test_reset_image_state(); // simulate a fresh DSO image arriving
    gdcc_hrx_initialize_core(NULL, (GDExtensionObjectPtr)&g_engine_marker, true, true, 0x88u, NULL, 0);
    godot_Callable_destroy(&cb2);
    CHECK(g_mem_balance == balance_base, "corpse and live spec must both end reclaimed");
    printf("OK dead_interned\n");
    return 0;
}
