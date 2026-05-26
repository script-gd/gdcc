package gd.script.gdcc.backend.c.gen.binding;

import gd.script.gdcc.enums.GodotVersion;
import org.jetbrains.annotations.NotNull;

import java.util.List;

final class Godot451FixedBindings extends FixedGodotBindings {
    static final @NotNull Godot451FixedBindings INSTANCE = new Godot451FixedBindings();

    private static final @NotNull List<FixedFunction> FUNCTIONS = List.of(
            new FixedFunction("Engine", "singleton", "godot_Engine_singleton", "godot_Engine *", List.of()),
            new FixedFunction("Engine", "is_editor_hint", "godot_Engine_is_editor_hint", "godot_bool",
                    List.of(new FixedParam("self", "godot_Engine *", GodotBindingSymbol.Abi.MUTABLE_TYPE_PTR))),
            new FixedFunction("ClassDB", "singleton", "godot_ClassDB_singleton", "godot_ClassDB *", List.of()),
            new FixedFunction("ClassDB", "is_parent_class", "godot_ClassDB_is_parent_class", "godot_bool",
                    List.of(
                            new FixedParam("self", "godot_ClassDB *", GodotBindingSymbol.Abi.MUTABLE_TYPE_PTR),
                            new FixedParam("class_name", "const godot_StringName *",
                                    GodotBindingSymbol.Abi.CONST_TYPE_PTR),
                            new FixedParam("inherits", "const godot_StringName *",
                                    GodotBindingSymbol.Abi.CONST_TYPE_PTR)
                    )),
            new FixedFunction("Object", "call", "godot_Object_call", "godot_Variant",
                    List.of(
                            new FixedParam("self", "GDExtensionObjectPtr", GodotBindingSymbol.Abi.VALUE),
                            new FixedParam("method", "const godot_StringName *",
                                    GodotBindingSymbol.Abi.CONST_TYPE_PTR),
                            new FixedParam("argv", "const godot_Variant **", GodotBindingSymbol.Abi.VARIANT_VARARG),
                            new FixedParam("argc", "godot_int", GodotBindingSymbol.Abi.VALUE)
                    )),
            new FixedFunction("Object", "get", "godot_Object_get", "godot_Variant",
                    List.of(
                            new FixedParam("self", "GDExtensionConstObjectPtr", GodotBindingSymbol.Abi.VALUE),
                            new FixedParam("property", "const godot_StringName *",
                                    GodotBindingSymbol.Abi.CONST_TYPE_PTR)
                    )),
            new FixedFunction("Object", "set", "godot_Object_set", "void",
                    List.of(
                            new FixedParam("self", "GDExtensionObjectPtr", GodotBindingSymbol.Abi.VALUE),
                            new FixedParam("property", "const godot_StringName *",
                                    GodotBindingSymbol.Abi.CONST_TYPE_PTR),
                            new FixedParam("value", "const godot_Variant *", GodotBindingSymbol.Abi.CONST_TYPE_PTR)
                    )),
            new FixedFunction("Object", "get_instance_id", "godot_Object_get_instance_id", "godot_int",
                    List.of(new FixedParam("self", "GDExtensionConstObjectPtr", GodotBindingSymbol.Abi.VALUE))),
            new FixedFunction("Object", "notification", "godot_Object_notification", "void",
                    List.of(
                            new FixedParam("self", "GDExtensionObjectPtr", GodotBindingSymbol.Abi.VALUE),
                            new FixedParam("what", "godot_int", GodotBindingSymbol.Abi.VALUE),
                            new FixedParam("reversed", "godot_bool", GodotBindingSymbol.Abi.VALUE)
                    )),
            new FixedFunction("Object", "NOTIFICATION_POSTINITIALIZE", "godot_Object_NOTIFICATION_POSTINITIALIZE",
                    "godot_int", List.of()),
            new FixedFunction("Object", "NOTIFICATION_PREDELETE", "godot_Object_NOTIFICATION_PREDELETE",
                    "godot_int", List.of()),
            new FixedFunction("RefCounted", "reference", "godot_RefCounted_reference", "godot_bool",
                    List.of(new FixedParam("self", "godot_RefCounted *", GodotBindingSymbol.Abi.MUTABLE_TYPE_PTR))),
            new FixedFunction("RefCounted", "unreference", "godot_RefCounted_unreference", "godot_bool",
                    List.of(new FixedParam("self", "godot_RefCounted *", GodotBindingSymbol.Abi.MUTABLE_TYPE_PTR))),
            new FixedFunction("RefCounted", "init_ref", "godot_RefCounted_init_ref", "godot_bool",
                    List.of(new FixedParam("self", "godot_RefCounted *", GodotBindingSymbol.Abi.MUTABLE_TYPE_PTR)))
    );

    private Godot451FixedBindings() {
    }

    @Override
    protected @NotNull String versionLabel() {
        return GodotVersion.V451.version;
    }

    @Override
    protected @NotNull List<FixedFunction> functions() {
        return FUNCTIONS;
    }

    @Override
    protected void appendDefinitions(@NotNull FixedRenderer renderer, @NotNull StringBuilder out) {
        renderer.appendSingletonDefinition(out, "Engine");
        renderer.appendSingletonDefinition(out, "ClassDB");
        renderer.appendClassMethodDefinition(out, "Engine", "is_editor_hint", "godot_Engine_is_editor_hint",
                "godot_bool", "godot_Engine *self", List.of(), "self");
        renderer.appendClassMethodDefinition(out, "ClassDB", "is_parent_class", "godot_ClassDB_is_parent_class",
                "godot_bool",
                "godot_ClassDB *self, const godot_StringName *class_name, const godot_StringName *inherits",
                List.of(new FixedMethodArg("StringName", "class_name"),
                        new FixedMethodArg("StringName", "inherits")),
                "self");
        renderer.appendObjectCall(out);
        renderer.appendClassMethodDefinition(out, "Object", "get", "godot_Object_get", "godot_Variant",
                "GDExtensionConstObjectPtr self, const godot_StringName *property",
                List.of(new FixedMethodArg("StringName", "property")), "self");
        renderer.appendClassMethodDefinition(out, "Object", "set", "godot_Object_set", "void",
                "GDExtensionObjectPtr self, const godot_StringName *property, const godot_Variant *value",
                List.of(new FixedMethodArg("StringName", "property"), new FixedMethodArg("Variant", "value")),
                "self");
        renderer.appendClassMethodDefinition(out, "Object", "get_instance_id", "godot_Object_get_instance_id",
                "godot_int", "GDExtensionConstObjectPtr self", List.of(), "self");
        renderer.appendClassMethodDefinition(out, "Object", "notification", "godot_Object_notification", "void",
                "GDExtensionObjectPtr self, godot_int what, godot_bool reversed",
                List.of(new FixedMethodArg("int", "what"), new FixedMethodArg("bool", "reversed")), "self");
        renderer.appendConstantDefinition(out, "Object", "NOTIFICATION_POSTINITIALIZE");
        renderer.appendConstantDefinition(out, "Object", "NOTIFICATION_PREDELETE");
        renderer.appendClassMethodDefinition(out, "RefCounted", "reference", "godot_RefCounted_reference",
                "godot_bool", "godot_RefCounted *self", List.of(), "self");
        renderer.appendClassMethodDefinition(out, "RefCounted", "unreference", "godot_RefCounted_unreference",
                "godot_bool", "godot_RefCounted *self", List.of(), "self");
        renderer.appendClassMethodDefinition(out, "RefCounted", "init_ref", "godot_RefCounted_init_ref",
                "godot_bool", "godot_RefCounted *self", List.of(), "self");
    }
}
