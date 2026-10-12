static int g_impl_calls = 0;
static void *g_impl_captures = NULL;
static const GDExtensionConstVariantPtr *g_impl_args = NULL;
static GDExtensionInt g_impl_argc = -1;
static GDExtensionVariantPtr g_impl_ret = NULL;
static GDExtensionCallError *g_impl_err = NULL;
static void recording_impl(void *captures, const GDExtensionConstVariantPtr *args, GDExtensionInt argc,
        GDExtensionVariantPtr r_return, GDExtensionCallError *r_error) {
    g_impl_calls++;
    g_impl_captures = captures;
    g_impl_args = args;
    g_impl_argc = argc;
    g_impl_ret = r_return;
    g_impl_err = r_error;
    if (r_error != NULL) r_error->error = GDEXTENSION_CALL_OK;
}
static GDExtensionBool always_valid(void *captures) { (void)captures; return true; }
static void noop_destroy(void *captures) { (void)captures; }

static const unsigned char DESC_A[] = { 1, 2, 3, 4 };
static const gdcc_hrx_identity IDENTITY_A = {
    .impl_key = "Worker::run#0",
    .schema_desc = DESC_A,
    .schema_desc_len = sizeof(DESC_A),
    .argument_count = 2,
    .schema_fingerprint = { 0xA0, 0xA1, 0xA2, 0xA3, 0xA4, 0xA5, 0xA6, 0xA7,
                            0xA8, 0xA9, 0xAA, 0xAB, 0xAC, 0xAD, 0xAE, 0xAF },
};

int main(void) {
    godot_initialize_interface(fake_get_proc_address);
    gdcc_hrx_test_reset_image_state(); // simulate a fresh DSO image arriving
    gdcc_hrx_mode mode = gdcc_hrx_initialize_core(
            NULL, (GDExtensionObjectPtr)&g_engine_marker, true, true, 0x1234u, NULL, 0);
    CHECK(mode == GDCC_HRX_MODE_ACTIVE, "editor + probe must resolve to ACTIVE");
    const int balance_base = g_mem_balance;

    int captures = 42;
    gdcc_hrx_spec *spec = NULL;
    godot_Callable cb = gdcc_hrx_create_lambda(
            &captures, 0, recording_impl, always_valid, noop_destroy, 2, &IDENTITY_A, &spec);
    FakeCallableCustom *custom = fake_callable_custom_of(&cb);
    CHECK(custom != NULL, "callable must be non-empty");
    CHECK(spec != NULL && custom->userdata == spec, "userdata must be the pinned spec");
    CHECK(custom->call_func != (GDExtensionCallableCustomCall)recording_impl,
            "call_func must be a heap thunk, never the library address");
    CHECK(spec->refcount == 1 && !spec->dead
            && spec->binding_state == GDCC_HRX_BOUND_COMPATIBLE, "spec must be live and bound");
    CHECK(spec->impl_key != IDENTITY_A.impl_key
            && strcmp(spec->impl_key, IDENTITY_A.impl_key) == 0,
            "impl_key must be a heap copy (library rodata dies at dlclose)");

    godot_Variant ret;
    GDExtensionCallError err = { 0 };
    GDExtensionConstVariantPtr args[2] = { NULL, NULL };
    custom->call_func(custom->userdata, args, 2, &ret, &err);
    CHECK(g_impl_calls == 1 && g_impl_captures == &captures,
            "thunk must swap arg0 to the capture block and run the impl");
    CHECK(g_impl_args == args && g_impl_argc == 2, "args/argc must pass through untouched");
    CHECK(g_impl_ret == &ret && g_impl_err == &err, "return/error pointers must pass through");
    CHECK(err.error == GDEXTENSION_CALL_OK, "impl result must propagate");

    CHECK(custom->is_valid_func(custom->userdata) == true, "bound spec must be valid");
    GDExtensionBool argc_valid = false;
    CHECK(custom->argc_func(custom->userdata, &argc_valid) == 2 && argc_valid,
            "argument count must come from spec data with the two-parameter ABI");
    CHECK(custom->argc_func(custom->userdata, NULL) == 2, "NULL r_is_valid must be tolerated");

    gdcc_hrx_deinitialize();
                        CHECK(custom->is_valid_func(custom->userdata) == false, "deinitialize must invalidate");
    g_impl_calls = 0;
    err.error = (GDExtensionCallErrorType)777;
    custom->call_func(custom->userdata, args, 2, &ret, &err);
    CHECK(g_impl_calls == 0, "invalidated spec must never run the impl");
    CHECK(err.error == GDEXTENSION_CALL_ERROR_INVALID_METHOD && err.argument == 0 && err.expected == 0,
            "the invalid path must write the full GDExtensionCallError");
    argc_valid = true;
    CHECK(custom->argc_func(custom->userdata, &argc_valid) == 2 && !argc_valid,
            "argument count validity flag must flip with binding state");

    // The sweeper is detached now: the free only marks dead, and the NEXT
    // generation's two-phase sweep reclaims the shell.
    godot_Callable_destroy(&cb);
    CHECK(spec->dead, "refcount zero crossing must mark dead without a sweeper");
    gdcc_hrx_test_reset_image_state(); // simulate a fresh DSO image arriving
    gdcc_hrx_initialize_core(NULL, (GDExtensionObjectPtr)&g_engine_marker, true, true, 0x1234u, NULL, 0);
    CHECK(g_mem_balance == balance_base, "the two-phase sweep must reclaim everything");
    printf("OK lambda_roundtrip\n");
    return 0;
}
