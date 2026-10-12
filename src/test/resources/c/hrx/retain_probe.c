static int g_destroy_calls = 0;
static void impl1(void *c, const GDExtensionConstVariantPtr *a, GDExtensionInt n,
        GDExtensionVariantPtr r, GDExtensionCallError *e) { (void)c; }
static GDExtensionBool valid1(void *c) { return true; }
static void destroy1(void *c) { g_destroy_calls++; }

int main(void) {
    godot_initialize_interface(fake_get_proc_address);
    gdcc_hrx_test_reset_image_state(); // simulate a fresh DSO image arriving
    gdcc_hrx_initialize_core(NULL, (GDExtensionObjectPtr)&g_engine_marker, true, true, 0x77u, NULL, 0);
    const int balance_base = g_mem_balance;
    int wait = 1;
    gdcc_hrx_spec *spec = NULL;
    godot_Callable cb = gdcc_hrx_create_lambda(&wait, 0, impl1, valid1, destroy1, -1, NULL, &spec);
    // The fake "connection" retains the Callable; the local handle is dropped.
    FakeCallableCustom *connection = fake_callable_custom_of(&cb);
    connection->refs++;
    godot_Callable_destroy(&cb);
    CHECK(spec->refcount == 1, "the connection must be the last owner");

    godot_Callable key = gdcc_hrx_callable_retain(spec);
    CHECK(fake_callable_custom_of(&key) != NULL && spec->refcount == 2,
            "retain must add a custom object on the same spec");
    CHECK(fake_callable_equal(connection, fake_callable_custom_of(&key)),
            "the retained key must compare EQUAL to the connection's callable");
    godot_Callable_destroy(&key);
    CHECK(spec->refcount == 1 && !spec->dead, "destroying the key must only decrement");

    fake_callable_custom_release(connection); // connection removal
    CHECK(g_destroy_calls == 1, "the wait must be released exactly once by the last owner");
    CHECK(g_mem_balance == balance_base, "everything must be reclaimed");

    // Negative: NULL and dead specs refuse retention.
    CHECK(fake_callable_custom_of(&(godot_Callable){0}) == NULL, "sanity: empty callable");
    godot_Callable invalid = gdcc_hrx_callable_retain(NULL);
    CHECK(fake_callable_custom_of(&invalid) == NULL, "NULL spec must refuse");
    gdcc_hrx_spec *spec2 = NULL;
    godot_Callable cb2 = gdcc_hrx_create_lambda(NULL, 0, impl1, valid1, destroy1, -1, NULL, &spec2);
    gdcc_hrx_deinitialize(); // no sweeper: destroy below only marks dead
                        godot_Callable_destroy(&cb2);
    CHECK(spec2->dead, "spec must be dead before the negative retain check");
    godot_Callable refused = gdcc_hrx_callable_retain(spec2);
    CHECK(fake_callable_custom_of(&refused) == NULL, "dead spec must refuse retention");
    gdcc_hrx_test_reset_image_state(); // simulate a fresh DSO image arriving
    gdcc_hrx_initialize_core(NULL, (GDExtensionObjectPtr)&g_engine_marker, true, true, 0x77u, NULL, 0);
    printf("OK retain\n");
    return 0;
}
