package dev.superice.gdcc.backend.c.gen;

import dev.superice.gdcc.backend.CodegenContext;
import dev.superice.gdcc.backend.ProjectInfo;
import dev.superice.gdcc.enums.GodotVersion;
import dev.superice.gdcc.gdextension.ExtensionAPI;
import dev.superice.gdcc.gdextension.ExtensionBuiltinClass;
import dev.superice.gdcc.gdextension.ExtensionFunctionArgument;
import dev.superice.gdcc.gdextension.ExtensionUtilityFunction;
import dev.superice.gdcc.lir.LirBasicBlock;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.lir.LirVariable;
import dev.superice.gdcc.lir.insn.NopInsn;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.type.GdFloatType;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdType;
import dev.superice.gdcc.type.GdStringType;
import dev.superice.gdcc.type.GdVariantType;
import dev.superice.gdcc.type.GdVoidType;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CBodyBuilderCallUtilityTest {
    private CBodyBuilder builder;
    private LirFunctionDef functionDef;

    @BeforeEach
    void setUp() {
        var projectInfo = new ProjectInfo("TestProject", GodotVersion.V451, Path.of(".")) {
        };
        var extensionAPI = new ExtensionAPI(
                null,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                List.of(
                        new ExtensionUtilityFunction(
                                "print",
                                null,
                                "general",
                                true,
                                0,
                                List.of(new ExtensionFunctionArgument("arg1", "Variant", null, null))
                        ),
                        new ExtensionUtilityFunction(
                                "printerr",
                                null,
                                "general",
                                true,
                                0,
                                List.of(new ExtensionFunctionArgument("arg1", "Variant", null, null))
                        ),
                        new ExtensionUtilityFunction(
                                "deg_to_rad",
                                "float",
                                "math",
                                false,
                                2140049587,
                                List.of(new ExtensionFunctionArgument("deg", "float", null, null))
                        ),
                        new ExtensionUtilityFunction(
                                "utility_with_default",
                                "void",
                                "test",
                                false,
                                0,
                                List.of(
                                        new ExtensionFunctionArgument("required", "int", null, null),
                                        new ExtensionFunctionArgument("optional", "int", "7", null)
                                )
                        ),
                        new ExtensionUtilityFunction(
                                "utility_with_string_default",
                                "void",
                                "test",
                                false,
                                0,
                                List.of(
                                        new ExtensionFunctionArgument("required", "String", null, null),
                                        new ExtensionFunctionArgument("optional", "String", "\"suffix\"", null)
                                )
                        ),
                        new ExtensionUtilityFunction(
                                "utility_with_vector3_default",
                                "void",
                                "test",
                                false,
                                0,
                                List.of(
                                        new ExtensionFunctionArgument("required", "int", null, null),
                                        new ExtensionFunctionArgument("optional", "Vector3", "Vector3(0, 1, 0)", null)
                                )
                        ),
                        new ExtensionUtilityFunction(
                                "utility_with_node_path_default",
                                "void",
                                "test",
                                false,
                                0,
                                List.of(
                                        new ExtensionFunctionArgument("required", "int", null, null),
                                        new ExtensionFunctionArgument("optional", "NodePath", "NodePath(\"\")", null)
                                )
                        ),
                        new ExtensionUtilityFunction(
                                "utility_with_typed_array_default",
                                "void",
                                "test",
                                false,
                                0,
                                List.of(
                                        new ExtensionFunctionArgument("required", "int", null, null),
                                        new ExtensionFunctionArgument("optional", "Array", "Array[StringName]([])", null)
                                )
                        ),
                        new ExtensionUtilityFunction(
                                "utility_with_transform2d_default",
                                "void",
                                "test",
                                false,
                                0,
                                List.of(
                                        new ExtensionFunctionArgument("required", "int", null, null),
                                        new ExtensionFunctionArgument("optional", "Transform2D",
                                                "Transform2D(1, 0, 0, 1, 0, 0)", null)
                                )
                        ),
                        new ExtensionUtilityFunction(
                                "utility_with_transform3d_default",
                                "void",
                                "test",
                                false,
                                0,
                                List.of(
                                        new ExtensionFunctionArgument("required", "int", null, null),
                                        new ExtensionFunctionArgument("optional", "Transform3D",
                                                "Transform3D(1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0)", null)
                                )
                        )
                ),
                List.of(
                        builtinClass("Vector3", List.of(constructor("Vector3", 0,
                                List.of("float", "float", "float")))),
                        builtinClass("NodePath", List.of(constructor("NodePath", 0, List.of("String")))),
                        builtinClass("Array", List.of(constructor("Array", 0, List.of()))),
                        builtinClass("Transform2D", List.of(constructor("Transform2D", 0,
                                List.of("Vector2", "Vector2", "Vector2")))),
                        builtinClass("Transform3D", List.of(constructor("Transform3D", 0,
                                List.of("Basis", "Vector3"))))
                ),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList()
        );

        var classRegistry = new ClassRegistry(extensionAPI);
        var context = new CodegenContext(projectInfo, classRegistry);
        var clazz = new LirClassDef("TestClass", "RefCounted");
        functionDef = new LirFunctionDef("test_func");
        functionDef.setReturnType(GdVoidType.VOID);

        var entry = new LirBasicBlock("entry");
        functionDef.addBasicBlock(entry);
        functionDef.setEntryBlockId("entry");

        var helper = new CGenHelper(context, List.of(clazz));
        builder = new CBodyBuilder(helper, clazz, functionDef);
        builder.setCurrentPosition(entry, 0, new NopInsn());
    }

    @Test
    @DisplayName("callUtilityVoid should normalize unprefixed utility name")
    void testCallUtilityVoidNormalizeName() {
        var arg1 = addVar("v1", GdVariantType.VARIANT);

        builder.callUtilityVoid("print", List.of(builder.valueOfVar(arg1)));

        assertEquals("godot_print(&$v1, NULL, (godot_int)0);\n", builder.build());
    }

    @Test
    @DisplayName("callUtilityVoid should accept prefixed utility name")
    void testCallUtilityVoidPrefixedName() {
        var arg1 = addVar("v1", GdVariantType.VARIANT);

        builder.callUtilityVoid("godot_print", List.of(builder.valueOfVar(arg1)));

        assertEquals("godot_print(&$v1, NULL, (godot_int)0);\n", builder.build());
    }

    @Test
    @DisplayName("callUtilityVoid should support other vararg utility")
    void testCallUtilityVoidOtherVarargUtility() {
        var arg1 = addVar("v1", GdVariantType.VARIANT);
        var arg2 = addVar("v2", GdVariantType.VARIANT);

        builder.callUtilityVoid("printerr", List.of(builder.valueOfVar(arg1), builder.valueOfVar(arg2)));

        assertEquals(
                """
                const godot_Variant* __gdcc_tmp_argv_0[] = { &$v2 };
                godot_printerr(&$v1, __gdcc_tmp_argv_0, (godot_int)1);
                """,
                builder.build()
        );
    }

    @Test
    @DisplayName("vararg utility should generate unique argv temp names")
    void testCallUtilityVoidVarargArgvUniqueNames() {
        var arg1 = addVar("v1", GdVariantType.VARIANT);
        var arg2 = addVar("v2", GdVariantType.VARIANT);
        var arg3 = addVar("v3", GdVariantType.VARIANT);

        builder.callUtilityVoid("print", List.of(
                builder.valueOfVar(arg1),
                builder.valueOfVar(arg2),
                builder.valueOfVar(arg3)
        ));
        builder.callUtilityVoid("print", List.of(
                builder.valueOfVar(arg1),
                builder.valueOfVar(arg2)
        ));

        var out = builder.build();
        assertTrue(out.contains("const godot_Variant* __gdcc_tmp_argv_0[] = { &$v2, &$v3 };"));
        assertTrue(out.contains("godot_print(&$v1, __gdcc_tmp_argv_0, (godot_int)2);"));
        assertTrue(out.contains("const godot_Variant* __gdcc_tmp_argv_1[] = { &$v2 };"));
        assertTrue(out.contains("godot_print(&$v1, __gdcc_tmp_argv_1, (godot_int)1);"));
    }

    @Test
    @DisplayName("vararg extra arg must be Variant")
    void testCallUtilityVoidVarargExtraMustBeVariant() {
        var arg1 = addVar("v1", GdVariantType.VARIANT);
        var badArg = addVar("s1", GdStringType.STRING);

        var ex = assertThrows(RuntimeException.class, () ->
                builder.callUtilityVoid("print", List.of(builder.valueOfVar(arg1), builder.valueOfVar(badArg)))
        );
        assertTrue(Objects.requireNonNull(ex.getMessage()).contains("must be Variant"));
    }

    @Test
    @DisplayName("vararg extra arg must be a variable")
    void testCallUtilityVoidVarargExtraMustBeVariable() {
        var arg1 = addVar("v1", GdVariantType.VARIANT);

        var ex = assertThrows(RuntimeException.class, () ->
                builder.callUtilityVoid("print", List.of(
                        builder.valueOfVar(arg1),
                        builder.valueOfExpr("some_variant_expr", GdVariantType.VARIANT)
                ))
        );
        assertTrue(Objects.requireNonNull(ex.getMessage()).contains("must be a variable"));
    }

    @Test
    @DisplayName("vararg ref extra arg should be passed as-is")
    void testCallUtilityVoidVarargRefExtraArg() {
        var arg1 = addVar("v1", GdVariantType.VARIANT);
        var refExtra = Objects.requireNonNull(functionDef.createAndAddRefVariable("v2", GdVariantType.VARIANT));

        builder.callUtilityVoid("print", List.of(
                builder.valueOfVar(arg1),
                builder.valueOfVar(refExtra)
        ));

        assertEquals(
                """
                const godot_Variant* __gdcc_tmp_argv_0[] = { $v2 };
                godot_print(&$v1, __gdcc_tmp_argv_0, (godot_int)1);
                """,
                builder.build()
        );
    }

    @Test
    @DisplayName("callUtilityAssign should resolve and call utility return")
    void testCallUtilityAssignSuccess() {
        var deg = addVar("deg", GdFloatType.FLOAT);
        var result = addVar("ret", GdFloatType.FLOAT);

        builder.callUtilityAssign(builder.targetOfVar(result), "deg_to_rad", List.of(builder.valueOfVar(deg)));

        assertEquals("$ret = godot_deg_to_rad($deg);\n", builder.build());
    }

    @Test
    @DisplayName("callUtilityAssign should accept prefixed name")
    void testCallUtilityAssignPrefixedName() {
        var deg = addVar("deg", GdFloatType.FLOAT);
        var result = addVar("ret", GdFloatType.FLOAT);

        builder.callUtilityAssign(builder.targetOfVar(result), "godot_deg_to_rad", List.of(builder.valueOfVar(deg)));

        assertEquals("$ret = godot_deg_to_rad($deg);\n", builder.build());
    }

    @Test
    @DisplayName("callUtilityAssign should support discarding return value")
    void testCallUtilityAssignDiscardReturn() {
        var deg = addVar("deg", GdFloatType.FLOAT);

        builder.callUtilityAssign(builder.discardRef(), "deg_to_rad", List.of(builder.valueOfVar(deg)));

        assertEquals("godot_deg_to_rad($deg);\n", builder.build());
    }

    @Test
    @DisplayName("callUtilityVoid should fill omitted int default argument")
    void testCallUtilityVoidWithIntDefaultArgument() {
        var required = addVar("required", GdIntType.INT);

        builder.callUtilityVoid("utility_with_default", List.of(builder.valueOfVar(required)));

        assertEquals(
                """
                godot_int __gdcc_tmp_default_int_0 = 7;
                godot_utility_with_default($required, __gdcc_tmp_default_int_0);
                """,
                builder.build()
        );
    }

    @Test
    @DisplayName("callUtilityVoid should materialize and destroy string default argument temp")
    void testCallUtilityVoidWithStringDefaultArgument() {
        var required = addVar("required", GdStringType.STRING);

        builder.callUtilityVoid("utility_with_string_default", List.of(builder.valueOfVar(required)));

        assertEquals(
                """
                godot_String __gdcc_tmp_default_string_0 = godot_new_String_with_String(GD_STATIC_S(u8"suffix"));
                godot_utility_with_string_default(&$required, &__gdcc_tmp_default_string_0);
                godot_String_destroy(&__gdcc_tmp_default_string_0);
                """,
                builder.build()
        );
    }

    @Test
    @DisplayName("callUtilityVoid should use typed constructor suffix for Vector3 default argument")
    void testCallUtilityVoidWithVector3DefaultArgument() {
        var required = addVar("required", GdIntType.INT);

        builder.callUtilityVoid("utility_with_vector3_default", List.of(builder.valueOfVar(required)));

        assertEquals(
                """
                godot_Vector3 __gdcc_tmp_default_vector3_0 = godot_new_Vector3_with_float_float_float(0, 1, 0);
                godot_utility_with_vector3_default($required, &__gdcc_tmp_default_vector3_0);
                """,
                builder.build()
        );
    }

    @Test
    @DisplayName("callUtilityVoid should special-case NodePath string default argument")
    void testCallUtilityVoidWithNodePathDefaultArgument() {
        var required = addVar("required", GdIntType.INT);

        builder.callUtilityVoid("utility_with_node_path_default", List.of(builder.valueOfVar(required)));

        assertEquals(
                """
                godot_NodePath __gdcc_tmp_default_nodepath_0 = godot_new_NodePath_with_utf8_chars(u8"");
                godot_utility_with_node_path_default($required, &__gdcc_tmp_default_nodepath_0);
                godot_NodePath_destroy(&__gdcc_tmp_default_nodepath_0);
                """,
                builder.build()
        );
    }

    @Test
    @DisplayName("callUtilityVoid should treat typed-array empty literal default as no-arg array constructor")
    void testCallUtilityVoidWithTypedArrayEmptyDefaultArgument() {
        var required = addVar("required", GdIntType.INT);

        builder.callUtilityVoid("utility_with_typed_array_default", List.of(builder.valueOfVar(required)));

        assertEquals(
                """
                godot_Array __gdcc_tmp_default_array_0 = godot_new_Array();
                godot_array_set_typed(&__gdcc_tmp_default_array_0, GDEXTENSION_VARIANT_TYPE_STRING_NAME, GD_STATIC_SN(u8""), NULL);
                godot_utility_with_typed_array_default($required, &__gdcc_tmp_default_array_0);
                godot_Array_destroy(&__gdcc_tmp_default_array_0);
                """,
                builder.build()
        );
    }

    @Test
    @DisplayName("callUtilityVoid should construct Transform2D default via gdcc helper constructor")
    void testCallUtilityVoidWithTransform2DDefaultArgument() {
        var required = addVar("required", GdIntType.INT);

        builder.callUtilityVoid("utility_with_transform2d_default", List.of(builder.valueOfVar(required)));

        assertEquals(
                """
                godot_Transform2D __gdcc_tmp_default_transform2d_0 = godot_new_Transform2D_with_float_float_float_float_float_float(1, 0, 0, 1, 0, 0);
                godot_utility_with_transform2d_default($required, &__gdcc_tmp_default_transform2d_0);
                """,
                builder.build()
        );
    }

    @Test
    @DisplayName("callUtilityVoid should construct Transform3D default via gdcc helper constructor")
    void testCallUtilityVoidWithTransform3DDefaultArgument() {
        var required = addVar("required", GdIntType.INT);

        builder.callUtilityVoid("utility_with_transform3d_default", List.of(builder.valueOfVar(required)));

        assertEquals(
                """
                godot_Transform3D __gdcc_tmp_default_transform3d_0 = godot_new_Transform3D_with_float_float_float_float_float_float_float_float_float_float_float_float(1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0);
                godot_utility_with_transform3d_default($required, &__gdcc_tmp_default_transform3d_0);
                """,
                builder.build()
        );
    }

    @Test
    @DisplayName("callUtilityAssign should reject void utility")
    void testCallUtilityAssignVoidUtility() {
        var arg1 = addVar("v1", GdVariantType.VARIANT);
        var result = addVar("ret", GdFloatType.FLOAT);

        var ex = assertThrows(RuntimeException.class, () ->
                builder.callUtilityAssign(builder.targetOfVar(result), "print", List.of(builder.valueOfVar(arg1)))
        );
        assertTrue(Objects.requireNonNull(ex.getMessage()).contains("has no return value"));
    }

    @Test
    @DisplayName("utility function not found should throw")
    void testCallUtilityNotFound() {
        var ex = assertThrows(RuntimeException.class, () -> builder.callUtilityVoid("missing_utility", List.of()));
        assertTrue(Objects.requireNonNull(ex.getMessage()).contains("not found"));
    }

    private @NotNull LirVariable addVar(@NotNull String id, @NotNull GdType type) {
        return Objects.requireNonNull(functionDef.createAndAddVariable(id, type));
    }

    private @NotNull ExtensionBuiltinClass builtinClass(@NotNull String name,
                                                        @NotNull List<ExtensionBuiltinClass.ConstructorInfo> constructors) {
        return new ExtensionBuiltinClass(
                name,
                false,
                List.of(),
                List.of(),
                List.of(),
                constructors,
                List.of(),
                List.of()
        );
    }

    private @NotNull ExtensionBuiltinClass.ConstructorInfo constructor(@NotNull String className,
                                                                       int index,
                                                                       @NotNull List<String> argTypes) {
        var args = new java.util.ArrayList<ExtensionFunctionArgument>(argTypes.size());
        for (var i = 0; i < argTypes.size(); i++) {
            args.add(new ExtensionFunctionArgument("arg" + i, argTypes.get(i), null, null));
        }
        return new ExtensionBuiltinClass.ConstructorInfo(className, index, args);
    }
}
