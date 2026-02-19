package dev.superice.gdcc.backend.c.gen;

import dev.superice.gdcc.backend.CodegenContext;
import dev.superice.gdcc.backend.ProjectInfo;
import dev.superice.gdcc.enums.GodotVersion;
import dev.superice.gdcc.gdextension.ExtensionAPI;
import dev.superice.gdcc.gdextension.ExtensionFunctionArgument;
import dev.superice.gdcc.gdextension.ExtensionUtilityFunction;
import dev.superice.gdcc.lir.LirBasicBlock;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.lir.LirVariable;
import dev.superice.gdcc.lir.insn.NopInsn;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.type.GdFloatType;
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
                                "deg_to_rad",
                                "float",
                                "math",
                                false,
                                2140049587,
                                List.of(new ExtensionFunctionArgument("deg", "float", null, null))
                        )
                ),
                Collections.emptyList(),
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
}
