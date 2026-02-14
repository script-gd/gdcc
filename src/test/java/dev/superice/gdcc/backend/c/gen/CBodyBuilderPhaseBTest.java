package dev.superice.gdcc.backend.c.gen;

import dev.superice.gdcc.backend.CodegenContext;
import dev.superice.gdcc.backend.ProjectInfo;
import dev.superice.gdcc.enums.GodotVersion;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.lir.LirVariable;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.type.GdBoolType;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdVariantType;
import dev.superice.gdcc.type.GdVoidType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class CBodyBuilderPhaseBTest {
    private CBodyBuilder builder;
    private CGenHelper helper;
    private LirClassDef lirClassDef;
    private LirVariable mockVar;
    private LirVariable mockRefVar;
    private LirVariable mockBoolVar;

    @BeforeEach
    void setUp() throws IOException {
        // Prepare mocks
        var projectInfo = new ProjectInfo("TestProject", GodotVersion.V451, Path.of(".")) {
            // Anonymous subclass
        };
        var extensionAPI = ExtensionApiLoader.loadDefault();
        var classRegistry = new ClassRegistry(extensionAPI);
        var ctx = new CodegenContext(projectInfo, classRegistry);
        lirClassDef = new LirClassDef("TestClass", "RefCounted", false, false, Collections.emptyMap(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        var lirFunctionDef = new LirFunctionDef("testFunc", false, false, false, false, false, Collections.emptyMap(), Collections.emptyList(), Collections.emptyMap(), GdVoidType.VOID, Collections.emptyMap(), new LinkedHashMap<>());

        helper = new CGenHelper(ctx, List.of(lirClassDef));

        builder = new CBodyBuilder(helper, lirClassDef, lirFunctionDef);

        // Mock variables
        mockVar = new LirVariable("v1", GdIntType.INT, lirFunctionDef);
        mockRefVar = new LirVariable("r1", GdIntType.INT, true, lirFunctionDef);
        mockBoolVar = new LirVariable("b1", GdBoolType.BOOL, lirFunctionDef);
    }

    @Test
    void testAssignVar() {
        var target = builder.targetOfVar(mockVar);
        var value = builder.valueOfExpr("10", GdIntType.INT);

        builder.assignVar(target, value);

        assertEquals("$v1 = 10;\n", builder.build());
    }

    @Test
    void testCallVoid() {
        var arg1 = builder.valueOfExpr("1", GdIntType.INT);
        var arg2 = builder.valueOfVar(mockVar);

        builder.callVoid("some_func", List.of(arg1, arg2));

        assertEquals("some_func(1, $v1);\n", builder.build());
    }

    @Test
    void testCallAssign() {
        var target = builder.targetOfVar(mockVar);
        var arg1 = builder.valueOfExpr("1", GdIntType.INT);

        builder.callAssign(target, "some_func", GdIntType.INT, List.of(arg1));

        assertEquals("$v1 = some_func(1);\n", builder.build());
    }

    @Test
    void testJump() {
        builder.jump("block_next");

        assertEquals("goto block_next;\n", builder.build());
    }

    @Test
    void testJumpIf() {
        var cond = builder.valueOfVar(mockBoolVar);
        builder.jumpIf(cond, "block_true", "block_false");

        assertEquals("if ($b1) goto block_true;\nelse goto block_false;\n", builder.build());
    }

    @Test
    void testReturnVoid() {
        builder.returnVoid();

        assertEquals("goto __finally__;\n", builder.build());
    }

    @Test
    void testReturnValue() {
        var nonVoidBuilder = new CBodyBuilder(helper, lirClassDef, createFunctionDef("testFuncReturnInt", GdIntType.INT));
        var val = nonVoidBuilder.valueOfVar(new LirVariable("v1", GdIntType.INT, nonVoidBuilder.func()));
        nonVoidBuilder.returnValue(val);

        assertEquals("_return_val = $v1;\ngoto __finally__;\n", nonVoidBuilder.build());
    }

    @Test
    void testReturnValueForVoidFunctionShouldThrow() {
        var val = builder.valueOfVar(mockVar);
        assertThrows(RuntimeException.class, () -> builder.returnValue(val));
    }

    @Test
    void testReturnVoidInFinallyForNonVoidFunction() {
        var nonVoidBuilder = new CBodyBuilder(helper, lirClassDef, createFunctionDef("testFuncReturnInt", GdIntType.INT));
        nonVoidBuilder.setCurrentPosition(new dev.superice.gdcc.lir.LirBasicBlock("__finally__"), 0, new dev.superice.gdcc.lir.insn.ReturnInsn(null));

        nonVoidBuilder.returnVoid();

        assertEquals("return _return_val;\n", nonVoidBuilder.build());
    }

    @Test
    void testTempVarLifecycle() {
        var temp = builder.newTempVariable("variant", GdVariantType.VARIANT);

        builder.declareTempVar(temp);
        builder.destroyTempVar(temp);

        assertEquals("godot_Variant " + temp.name() + ";\n", builder.build());
    }

    @Test
    void testTempVarFirstCallAssignShouldSkipOldDestroy() {
        var temp = builder.newTempVariable("variant", GdVariantType.VARIANT);

        builder.declareTempVar(temp);
        builder.callAssign(temp, "some_func", GdVariantType.VARIANT, List.of());
        builder.destroyTempVar(temp);

        var expected = "godot_Variant " + temp.name() + ";\n" +
                temp.name() + " = some_func();\n" +
                "godot_Variant_destroy(&" + temp.name() + ");\n";
        assertEquals(expected, builder.build());
    }

    @Test
    void testTargetRefCheck() {
        assertThrows(RuntimeException.class, () -> builder.targetOfVar(mockRefVar));
    }

    private LirFunctionDef createFunctionDef(String name, dev.superice.gdcc.type.GdType returnType) {
        return new LirFunctionDef(name, false, false, false, false, false, Collections.emptyMap(), Collections.emptyList(),
                Collections.emptyMap(), returnType, Collections.emptyMap(), new LinkedHashMap<>());
    }
}
