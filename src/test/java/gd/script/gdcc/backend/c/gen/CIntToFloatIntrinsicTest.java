package gd.script.gdcc.backend.c.gen;

import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.backend.ProjectInfo;
import gd.script.gdcc.backend.c.gen.intrinsic.CIntToFloatIntrinsic;
import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.exception.InvalidInsnException;
import gd.script.gdcc.gdextension.ExtensionAPI;
import gd.script.gdcc.lir.LirBasicBlock;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirInstruction;
import gd.script.gdcc.lir.LirVariable;
import gd.script.gdcc.lir.insn.CallIntrinsicInsn;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdBoolType;
import gd.script.gdcc.type.GdFloatType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CIntToFloatIntrinsicTest {
    @Test
    @DisplayName("int to float intrinsic should emit casted assignment")
    void intToFloatIntrinsicShouldEmitCastedAssignment() {
        var fixture = new Fixture(List.of(
                new VariableSpec("i", GdIntType.INT, false),
                new VariableSpec("f", GdFloatType.FLOAT, false)
        ));
        var intrinsic = new CIntToFloatIntrinsic();

        intrinsic.generateCCode(
                fixture.builder(),
                fixture.variable("f"),
                List.of(fixture.variable("i"))
        );

        var body = fixture.builder().build();
        assertEquals("$f = (godot_float)$i;\n", body);
        assertFalse(body.contains("__gdcc_tmp_"), body);
        assertFalse(body.contains("_destroy"), body);
    }

    @Test
    @DisplayName("int to float intrinsic should reject missing result")
    void intToFloatIntrinsicShouldRejectMissingResult() {
        assertInvalidSignature(
                null,
                List.of(new VariableSpec("i", GdIntType.INT, false)),
                List.of("i"),
                "requires a result variable"
        );
    }

    @Test
    @DisplayName("int to float intrinsic should reject ref result")
    void intToFloatIntrinsicShouldRejectRefResult() {
        assertInvalidSignature(
                "f",
                List.of(
                        new VariableSpec("i", GdIntType.INT, false),
                        new VariableSpec("f", GdFloatType.FLOAT, true)
                ),
                List.of("i"),
                "cannot be a reference"
        );
    }

    @Test
    @DisplayName("int to float intrinsic should reject non-float result")
    void intToFloatIntrinsicShouldRejectNonFloatResult() {
        assertInvalidSignature(
                "f",
                List.of(
                        new VariableSpec("i", GdIntType.INT, false),
                        new VariableSpec("f", GdIntType.INT, false)
                ),
                List.of("i"),
                "must be float"
        );
    }

    @Test
    @DisplayName("int to float intrinsic should reject wrong argument count")
    void intToFloatIntrinsicShouldRejectWrongArgumentCount() {
        assertInvalidSignature(
                "f",
                List.of(
                        new VariableSpec("i", GdIntType.INT, false),
                        new VariableSpec("j", GdIntType.INT, false),
                        new VariableSpec("f", GdFloatType.FLOAT, false)
                ),
                List.of(),
                "exactly one argument"
        );
        assertInvalidSignature(
                "f",
                List.of(
                        new VariableSpec("i", GdIntType.INT, false),
                        new VariableSpec("j", GdIntType.INT, false),
                        new VariableSpec("f", GdFloatType.FLOAT, false)
                ),
                List.of("i", "j"),
                "exactly one argument"
        );
    }

    @Test
    @DisplayName("int to float intrinsic should reject non-int source")
    void intToFloatIntrinsicShouldRejectNonIntSource() {
        assertInvalidSignature(
                "f",
                List.of(
                        new VariableSpec("b", GdBoolType.BOOL, false),
                        new VariableSpec("f", GdFloatType.FLOAT, false)
                ),
                List.of("b"),
                "must be int"
        );
    }

    private static void assertInvalidSignature(@Nullable String resultId,
                                               @NotNull List<VariableSpec> variableSpecs,
                                               @NotNull List<String> argIds,
                                               @NotNull String expectedMessage) {
        var fixture = new Fixture(variableSpecs);
        var resultVar = resultId == null ? null : fixture.variable(resultId);
        var argVars = argIds.stream()
                .map(fixture::variable)
                .toList();
        var intrinsic = new CIntToFloatIntrinsic();

        var ex = assertThrows(InvalidInsnException.class, () ->
                intrinsic.generateCCode(fixture.builder(), resultVar, argVars)
        );

        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains(expectedMessage), ex.getMessage());
        assertTrue(ex.getMessage().contains(CIntToFloatIntrinsic.NAME), ex.getMessage());
    }

    private static @NotNull CGenHelper newHelper(@NotNull LirClassDef workerClass) {
        var api = new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of());
        var classRegistry = new ClassRegistry(api);
        classRegistry.addGdccClass(workerClass);
        ProjectInfo projectInfo = new ProjectInfo("TestProject", GodotVersion.V451, Path.of(".")) {
        };
        return new CGenHelper(new CodegenContext(projectInfo, classRegistry), List.of(workerClass));
    }

    private record Fixture(@NotNull CBodyBuilder builder,
                           @NotNull LirFunctionDef func) {
        Fixture(@NotNull List<VariableSpec> variableSpecs) {
            this(newWorkerClass(), new LirFunctionDef("intrinsic_test"), variableSpecs);
        }

        private Fixture(@NotNull LirClassDef workerClass,
                        @NotNull LirFunctionDef func,
                        @NotNull List<VariableSpec> variableSpecs) {
            this(newBuilder(workerClass, func, variableSpecs), func);
        }

        private static @NotNull LirClassDef newWorkerClass() {
            return new LirClassDef("Worker", "RefCounted", false, false,
                    Map.of(), List.of(), List.of(), List.of());
        }

        private static @NotNull CBodyBuilder newBuilder(@NotNull LirClassDef workerClass,
                                                        @NotNull LirFunctionDef func,
                                                        @NotNull List<VariableSpec> variableSpecs) {
            func.setReturnType(GdFloatType.FLOAT);
            for (var variableSpec : variableSpecs) {
                if (variableSpec.ref()) {
                    func.createAndAddRefVariable(variableSpec.id(), variableSpec.type());
                } else {
                    func.createAndAddVariable(variableSpec.id(), variableSpec.type());
                }
            }
            var block = new LirBasicBlock("entry");
            var insn = currentInsn();
            block.appendNonTerminatorInstruction(insn);
            func.addBasicBlock(block);
            func.setEntryBlockId("entry");
            workerClass.addFunction(func);

            return new CBodyBuilder(newHelper(workerClass), workerClass, func)
                    .setCurrentPosition(block, 0, insn);
        }

        private static @NotNull LirInstruction currentInsn() {
            return new CallIntrinsicInsn("f", CIntToFloatIntrinsic.NAME, List.of());
        }

        @NotNull LirVariable variable(@NotNull String id) {
            var variable = func.getVariableById(id);
            if (variable == null) {
                throw new IllegalArgumentException("Unknown fixture variable: " + id);
            }
            return variable;
        }
    }

    private record VariableSpec(@NotNull String id, @NotNull GdType type, boolean ref) {
    }
}
