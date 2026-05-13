package gd.script.gdcc.backend.c.gen;

import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.backend.ProjectInfo;
import gd.script.gdcc.backend.c.gen.intrinsic.CIntToFloatIntrinsic;
import gd.script.gdcc.backend.c.gen.intrinsic.CVectorIToVectorIntrinsic;
import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.exception.InvalidInsnException;
import gd.script.gdcc.gdextension.ExtensionAPI;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.lir.LirBasicBlock;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirInstruction;
import gd.script.gdcc.lir.LirModule;
import gd.script.gdcc.lir.insn.CallIntrinsicInsn;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdBoolType;
import gd.script.gdcc.type.GdFloatVectorType;
import gd.script.gdcc.type.GdFloatType;
import gd.script.gdcc.type.GdIntVectorType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVoidType;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CallIntrinsicInsnGenTest {
    @Test
    @DisplayName("CALL_INTRINSIC should dispatch c_int_to_float through CCodegen registry")
    void callIntrinsicShouldDispatchIntToFloatThroughCodegenRegistry() {
        var body = generateBody(
                new CallIntrinsicInsn(
                        "f",
                        CIntToFloatIntrinsic.NAME,
                        List.of(new LirInstruction.VariableOperand("i"))
                ),
                List.of(
                        new VariableSpec("i", GdIntType.INT, false),
                        new VariableSpec("f", GdFloatType.FLOAT, false)
                )
        );

        assertTrue(body.contains("$f = (godot_float)$i;"), body);
    }

    @Test
    @DisplayName("CALL_INTRINSIC should dispatch vector widening through CCodegen registry")
    void callIntrinsicShouldDispatchVectorWideningThroughCodegenRegistry() {
        var body = generateBody(
                new CallIntrinsicInsn(
                        "v",
                        CVectorIToVectorIntrinsic.VECTOR3I_TO_VECTOR3_NAME,
                        List.of(new LirInstruction.VariableOperand("vi"))
                ),
                List.of(
                        new VariableSpec("vi", GdIntVectorType.VECTOR3I, false),
                        new VariableSpec("v", GdFloatVectorType.VECTOR3, false)
                )
        );

        assertTrue(body.contains("$v = godot_new_Vector3_with_Vector3i(&$vi);"), body);
        assertTrue(body.contains("godot_new_Vector3_with_Vector3i"), body);
    }

    @Test
    @DisplayName("CALL_INTRINSIC should reject unknown intrinsic names")
    void callIntrinsicShouldRejectUnknownIntrinsicNames() {
        var ex = assertInvalidInsn(
                new CallIntrinsicInsn(
                        "f",
                        "unknown",
                        List.of(new LirInstruction.VariableOperand("i"))
                ),
                List.of(
                        new VariableSpec("i", GdIntType.INT, false),
                        new VariableSpec("f", GdFloatType.FLOAT, false)
                )
        );

        assertMessageContains(ex, "Intrinsic function 'unknown' not found in registry");
    }

    @Test
    @DisplayName("CALL_INTRINSIC should reject non-variable operands")
    void callIntrinsicShouldRejectNonVariableOperands() {
        var ex = assertInvalidInsn(
                new CallIntrinsicInsn(
                        "f",
                        CIntToFloatIntrinsic.NAME,
                        List.of(new LirInstruction.StringOperand("not_a_var"))
                ),
                List.of(
                        new VariableSpec("i", GdIntType.INT, false),
                        new VariableSpec("f", GdFloatType.FLOAT, false)
                )
        );

        assertMessageContains(ex, "Argument #1 of intrinsic '" + CIntToFloatIntrinsic.NAME +
                "' must be a variable operand");
    }

    @Test
    @DisplayName("CALL_INTRINSIC should reject non-variable operands for vector intrinsics")
    void callIntrinsicShouldRejectNonVariableOperandsForVectorIntrinsics() {
        var ex = assertInvalidInsn(
                new CallIntrinsicInsn(
                        "v",
                        CVectorIToVectorIntrinsic.VECTOR3I_TO_VECTOR3_NAME,
                        List.of(new LirInstruction.StringOperand("not_a_var"))
                ),
                List.of(
                        new VariableSpec("vi", GdIntVectorType.VECTOR3I, false),
                        new VariableSpec("v", GdFloatVectorType.VECTOR3, false)
                )
        );

        assertMessageContains(ex, "Argument #1 of intrinsic '" +
                CVectorIToVectorIntrinsic.VECTOR3I_TO_VECTOR3_NAME + "' must be a variable operand");
    }

    @Test
    @DisplayName("CALL_INTRINSIC should reject missing argument variables")
    void callIntrinsicShouldRejectMissingArgumentVariables() {
        var ex = assertInvalidInsn(
                new CallIntrinsicInsn(
                        "f",
                        CIntToFloatIntrinsic.NAME,
                        List.of(new LirInstruction.VariableOperand("missing"))
                ),
                List.of(new VariableSpec("f", GdFloatType.FLOAT, false))
        );

        assertMessageContains(ex, "Argument variable ID 'missing' not found in function");
        assertMessageContains(ex, CIntToFloatIntrinsic.NAME);
    }

    @Test
    @DisplayName("CALL_INTRINSIC should reject missing result variables")
    void callIntrinsicShouldRejectMissingResultVariables() {
        var ex = assertInvalidInsn(
                new CallIntrinsicInsn(
                        "missing",
                        CIntToFloatIntrinsic.NAME,
                        List.of(new LirInstruction.VariableOperand("i"))
                ),
                List.of(new VariableSpec("i", GdIntType.INT, false))
        );

        assertMessageContains(ex, "Result variable ID 'missing' not found in function");
    }

    @Test
    @DisplayName("CALL_INTRINSIC should let intrinsic reject absent result")
    void callIntrinsicShouldLetIntrinsicRejectAbsentResult() {
        var ex = assertInvalidInsn(
                new CallIntrinsicInsn(
                        null,
                        CIntToFloatIntrinsic.NAME,
                        List.of(new LirInstruction.VariableOperand("i"))
                ),
                List.of(new VariableSpec("i", GdIntType.INT, false))
        );

        assertMessageContains(ex, "requires a result variable");
        assertMessageContains(ex, CIntToFloatIntrinsic.NAME);
    }

    @Test
    @DisplayName("CALL_INTRINSIC should let intrinsic reject ref result")
    void callIntrinsicShouldLetIntrinsicRejectRefResult() {
        var ex = assertInvalidInsn(
                new CallIntrinsicInsn(
                        "f",
                        CIntToFloatIntrinsic.NAME,
                        List.of(new LirInstruction.VariableOperand("i"))
                ),
                List.of(
                        new VariableSpec("i", GdIntType.INT, false),
                        new VariableSpec("f", GdFloatType.FLOAT, true)
                )
        );

        assertMessageContains(ex, "cannot be a reference");
        assertMessageContains(ex, CIntToFloatIntrinsic.NAME);
    }

    @Test
    @DisplayName("CALL_INTRINSIC should let intrinsic reject non-float result")
    void callIntrinsicShouldLetIntrinsicRejectNonFloatResult() {
        var ex = assertInvalidInsn(
                new CallIntrinsicInsn(
                        "f",
                        CIntToFloatIntrinsic.NAME,
                        List.of(new LirInstruction.VariableOperand("i"))
                ),
                List.of(
                        new VariableSpec("i", GdIntType.INT, false),
                        new VariableSpec("f", GdIntType.INT, false)
                )
        );

        assertMessageContains(ex, "must be float");
        assertMessageContains(ex, CIntToFloatIntrinsic.NAME);
    }

    @Test
    @DisplayName("CALL_INTRINSIC should let intrinsic reject wrong argument count")
    void callIntrinsicShouldLetIntrinsicRejectWrongArgumentCount() {
        var noArg = assertInvalidInsn(
                new CallIntrinsicInsn("f", CIntToFloatIntrinsic.NAME, List.of()),
                List.of(new VariableSpec("f", GdFloatType.FLOAT, false))
        );
        assertMessageContains(noArg, "exactly one argument");

        var twoArgs = assertInvalidInsn(
                new CallIntrinsicInsn(
                        "f",
                        CIntToFloatIntrinsic.NAME,
                        List.of(
                                new LirInstruction.VariableOperand("i"),
                                new LirInstruction.VariableOperand("j")
                        )
                ),
                List.of(
                        new VariableSpec("i", GdIntType.INT, false),
                        new VariableSpec("j", GdIntType.INT, false),
                        new VariableSpec("f", GdFloatType.FLOAT, false)
                )
        );
        assertMessageContains(twoArgs, "exactly one argument");
    }

    @Test
    @DisplayName("CALL_INTRINSIC should let intrinsic reject non-int source")
    void callIntrinsicShouldLetIntrinsicRejectNonIntSource() {
        var ex = assertInvalidInsn(
                new CallIntrinsicInsn(
                        "f",
                        CIntToFloatIntrinsic.NAME,
                        List.of(new LirInstruction.VariableOperand("b"))
                ),
                List.of(
                        new VariableSpec("b", GdBoolType.BOOL, false),
                        new VariableSpec("f", GdFloatType.FLOAT, false)
                )
        );

        assertMessageContains(ex, "must be int");
        assertMessageContains(ex, CIntToFloatIntrinsic.NAME);
    }

    private static @NotNull InvalidInsnException assertInvalidInsn(@NotNull CallIntrinsicInsn insn,
                                                                   @NotNull List<VariableSpec> variables) {
        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(insn, variables));
        assertInstanceOf(InvalidInsnException.class, ex);
        return ex;
    }

    private static void assertMessageContains(@NotNull InvalidInsnException ex,
                                              @NotNull String expected) {
        assertTrue(ex.getMessage().contains(expected), ex.getMessage());
    }

    private static @NotNull String generateBody(@NotNull CallIntrinsicInsn insn,
                                                @NotNull List<VariableSpec> variables) {
        var clazz = new LirClassDef("Worker", "RefCounted", false, false,
                Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("call_intrinsic_test");
        func.setReturnType(GdVoidType.VOID);
        for (var variable : variables) {
            if (variable.ref()) {
                func.createAndAddRefVariable(variable.id(), variable.type());
            } else {
                func.createAndAddVariable(variable.id(), variable.type());
            }
        }
        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(insn);
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        clazz.addFunction(func);

        var module = new LirModule("test_module", List.of(clazz));
        var codegen = newCodegen(module, List.of(clazz));
        return codegen.generateFuncBody(clazz, func);
    }

    private static @NotNull CCodegen newCodegen(@NotNull LirModule module,
                                                @NotNull List<LirClassDef> gdccClasses) {
        ExtensionAPI api;
        try {
            api = ExtensionApiLoader.loadDefault();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load default extension API for call_intrinsic tests", ex);
        }
        var classRegistry = new ClassRegistry(api);
        for (var gdccClass : gdccClasses) {
            classRegistry.addGdccClass(gdccClass);
        }
        ProjectInfo projectInfo = new ProjectInfo("TestProject", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);
        var codegen = new CCodegen();
        codegen.prepare(ctx, module);
        return codegen;
    }

    private record VariableSpec(@NotNull String id, @NotNull GdType type, boolean ref) {
    }
}
