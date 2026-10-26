<#-- Onready class-lookup dispatch, one block per physical root that has chain-fact rows. -->
<#-- Non-engine `_ready` entry initialization resolves the instance's exact Godot class -->
<#-- name against a compile-time row table (exact class -> root-signature apply adapter); -->
<#-- the engine composite impl never consults this table and replays its own chain. -->
<#list helper.onreadyPlan().rootLookupPlans() as rootPlan>
    <#assign rootName = rootPlan.rootClassName()>
typedef struct ${helper.renderOnreadyLookupEntryTypeName(rootName)} {
    const godot_StringName* (*class_name)(void);
    void (*apply)(${rootName}* root);
} ${helper.renderOnreadyLookupEntryTypeName(rootName)};

    <#list rootPlan.classNames() as className>
static const godot_StringName* ${helper.renderOnreadyClassNameGetterName(className)}(void) {
    <#-- The literal lives at one expansion site per class: GD_STATIC_SN caches per -->
    <#-- expansion point, so calling it in a loop would share one key across all rows. -->
    return GD_STATIC_SN(u8"${className}");
}
    </#list>

static const ${helper.renderOnreadyLookupEntryTypeName(rootName)} ${helper.renderOnreadyLookupTableName(rootName)}[] = {
    <#list rootPlan.classNames() as className>
    { &${helper.renderOnreadyClassNameGetterName(className)}, &${helper.renderOnreadyRootApplyHelperName(className)} },
    </#list>
};

<#-- DONE is the attempt latch (set before the query so re-entry never re-queries); -->
<#-- CTOR_COMPLETE fail-closes while construction runs; HAS_ONREADY short-circuits -->
<#-- chain-free instances. On success the temporary class name is destroyed BEFORE -->
<#-- apply(root) runs user-adjacent code; every error path reports once and keeps DONE. -->
    <#assign rootFlagsExpr = helper.renderGdccFlagsAccessExpr(rootName, "root")>
static void ${helper.renderOnreadyInitializeHelperName(rootName)}(${rootName}* root) {
    if ((${rootFlagsExpr} & GDCC_FLAG_ONREADY_DONE) != 0) {
        return;
    }
    if ((${rootFlagsExpr} & GDCC_FLAG_CTOR_COMPLETE) == 0) {
        return;
    }
    if ((${rootFlagsExpr} & GDCC_FLAG_HAS_ONREADY) == 0) {
        return;
    }
    ${rootFlagsExpr} |= GDCC_FLAG_ONREADY_DONE;
    GDExtensionObjectPtr raw = ${rootName}_object_ptr(root);
    if (raw == NULL) {
        GDCC_PRINT_RUNTIME_ERROR("gdcc: onready lookup reached a wrapper without a Godot object", __func__, __FILE__, __LINE__);
        return;
    }
    godot_StringName actual_name;
    if (!godot_object_get_class_name(raw, class_library, &actual_name)) {
        GDCC_PRINT_RUNTIME_ERROR("gdcc: onready class-name query failed", __func__, __FILE__, __LINE__);
        return;
    }
    void (*apply)(${rootName}*) = NULL;
    for (size_t i = 0; i < sizeof(${helper.renderOnreadyLookupTableName(rootName)}) / sizeof(${helper.renderOnreadyLookupTableName(rootName)}[0]); i++) {
        if (godot_StringName_op_equal_StringName(&actual_name, ${helper.renderOnreadyLookupTableName(rootName)}[i].class_name())) {
            apply = ${helper.renderOnreadyLookupTableName(rootName)}[i].apply;
            break;
        }
    }
    godot_StringName_destroy(&actual_name);
    if (apply == NULL) {
        GDCC_PRINT_RUNTIME_ERROR("gdcc: instance class has no onready lookup row", __func__, __FILE__, __LINE__);
        return;
    }
    apply(root);
}
</#list>
