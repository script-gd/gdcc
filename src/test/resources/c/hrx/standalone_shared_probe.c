static void sa_call(void *c, const GDExtensionConstVariantPtr *a, GDExtensionInt n,
        GDExtensionVariantPtr r, GDExtensionCallError *e) { (void)c; }
static GDExtensionBool sa_valid(void *c) { return true; }
static const unsigned char DESC_SA[] = { 5, 5 };
static const gdcc_hrx_identity IDENTITY_SA = {
    .impl_key = "standalone:utility::print", .schema_desc = DESC_SA,
    .schema_desc_len = sizeof(DESC_SA), .argument_count = 1, .schema_fingerprint = { 7 },
};

int main(void) {
    godot_initialize_interface(fake_get_proc_address);
    gdcc_hrx_test_reset_image_state(); // simulate a fresh DSO image arriving
    gdcc_hrx_initialize_core(NULL, (GDExtensionObjectPtr)&g_engine_marker, true, true, 0x77u, NULL, 0);
    const int balance_base = g_mem_balance;

    godot_Callable cb1 = gdcc_hrx_create_standalone(
            "utility", "", "print", 123LL, 1, false, true, sa_call, sa_valid, &IDENTITY_SA);
    godot_Callable cb2 = gdcc_hrx_create_standalone(
            "utility", "", "print", 123LL, 1, false, true, sa_call, sa_valid, &IDENTITY_SA);
    FakeCallableCustom *custom1 = fake_callable_custom_of(&cb1);
    FakeCallableCustom *custom2 = fake_callable_custom_of(&cb2);
    CHECK(custom1 != NULL && custom2 != NULL, "both creations must succeed");
    CHECK(fake_callable_equal(custom1, custom2),
            "interned standalone must share (thunk, spec) so default identity stays equal");
    gdcc_hrx_spec *spec = (gdcc_hrx_spec *)custom1->userdata;
    CHECK(spec->interned == 1, "standalone spec must be interned");
    CHECK(spec->refcount == 2, "two custom objects must share the refcount");
    CHECK(spec->destroy_fn == NULL, "standalone destroy_fn is always NULL (fixed-ABI payload)");

    godot_Callable_destroy(&cb1);
    CHECK(spec->refcount == 1 && !spec->dead,
            "the first free must only decrement: a shared spec must not die early");
    CHECK(custom2->is_valid_func(custom2->userdata) == true, "surviving copy must stay callable");

    godot_Callable_destroy(&cb2);
    // Zero crossing: dead + immediate live-generation sweep. The sweeper must
    // detach the identity from the interning table FIRST (a same-identity
    // recreate must never hit a corpse).
    CHECK(gdcc_hrx_current_hub()->intern_count == 0,
            "the sweeper must detach the identity from the interning table");
    godot_Callable cb3 = gdcc_hrx_create_standalone(
            "utility", "", "print", 123LL, 1, false, true, sa_call, sa_valid, &IDENTITY_SA);
    CHECK(fake_callable_custom_of(&cb3) != NULL, "recreate must succeed");
    CHECK(gdcc_hrx_current_hub()->intern_count == 1, "the fresh spec must intern again");
    godot_Callable_destroy(&cb3);
    CHECK(g_mem_balance == balance_base,
            "payload strings + shell metadata must all be reclaimed (no standalone leak)");
    printf("OK standalone_shared\n");
    return 0;
}
