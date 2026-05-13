package gd.script.gdcc.backend.c.gen;

import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.backend.ProjectInfo;
import gd.script.gdcc.backend.c.gen.intrinsic.CVectorIToVectorIntrinsic;
import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.exception.InvalidInsnException;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.lir.LirBasicBlock;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirInstruction;
import gd.script.gdcc.lir.LirVariable;
import gd.script.gdcc.lir.insn.CallIntrinsicInsn;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdBoolType;
import gd.script.gdcc.type.GdFloatVectorType;
import gd.script.gdcc.type.GdIntVectorType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVoidType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CVectorIToVectorIntrinsicTest {
    @Test
    @DisplayName("vector intrinsic should emit Godot constructor conversion for each supported dimension")
    void vectorIntrinsicShouldEmitGodotConstructorConversionForEachSupportedDimension() {
        var cases = List.of(
                new Case(CVectorIToVectorIntrinsic.vector2(), GdIntVectorType.VECTOR2I,
                        GdFloatVectorType.VECTOR2, "godot_new_Vector2_with_Vector2i"),
                new Case(CVectorIToVectorIntrinsic.vector3(), GdIntVectorType.VECTOR3I,
                        GdFloatVectorType.VECTOR3, "godot_new_Vector3_with_Vector3i"),
                new Case(CVectorIToVectorIntrinsic.vector4(), GdIntVectorType.VECTOR4I,
                        GdFloatVectorType.VECTOR4, "godot_new_Vector4_with_Vector4i")
        );

        for (var testCase : cases) {
            var fixture = new Fixture(List.of(
                    new VariableSpec("vi", testCase.sourceType(), false),
                    new VariableSpec("v", testCase.targetType(), false)
            ), testCase.intrinsic().name());

            testCase.intrinsic().generateCCode(
                    fixture.builder(),
                    fixture.variable("v"),
                    List.of(fixture.variable("vi"))
            );

            var body = fixture.builder().build();
            assertAll(testCase.intrinsic().name(),
                    () -> assertTrue(body.contains("$v = " + testCase.constructorName() + "(&$vi);"), body),
                    () -> assertFalse(body.contains("(godot_Vector"), body)
            );
        }
    }

    @Test
    @DisplayName("vector intrinsic should pass ref source directly to constructor")
    void vectorIntrinsicShouldPassRefSourceDirectlyToConstructor() {
        var fixture = new Fixture(List.of(
                new VariableSpec("vi", GdIntVectorType.VECTOR3I, true),
                new VariableSpec("v", GdFloatVectorType.VECTOR3, false)
        ), CVectorIToVectorIntrinsic.VECTOR3I_TO_VECTOR3_NAME);
        var intrinsic = CVectorIToVectorIntrinsic.vector3();

        intrinsic.generateCCode(
                fixture.builder(),
                fixture.variable("v"),
                List.of(fixture.variable("vi"))
        );

        var body = fixture.builder().build();
        assertTrue(body.contains("$v = godot_new_Vector3_with_Vector3i($vi);"), body);
        assertFalse(body.contains("(&$vi)"), body);
    }

    @Test
    @DisplayName("vector intrinsic should reject missing result")
    void vectorIntrinsicShouldRejectMissingResult() {
        assertInvalidSignature(
                CVectorIToVectorIntrinsic.vector3(),
                null,
                List.of(new VariableSpec("vi", GdIntVectorType.VECTOR3I, false)),
                List.of("vi"),
                "requires a result variable"
        );
    }

    @Test
    @DisplayName("vector intrinsic should reject ref result")
    void vectorIntrinsicShouldRejectRefResult() {
        assertInvalidSignature(
                CVectorIToVectorIntrinsic.vector3(),
                "v",
                List.of(
                        new VariableSpec("vi", GdIntVectorType.VECTOR3I, false),
                        new VariableSpec("v", GdFloatVectorType.VECTOR3, true)
                ),
                List.of("vi"),
                "cannot be a reference"
        );
    }

    @Test
    @DisplayName("vector intrinsic should reject wrong result type and reverse targets")
    void vectorIntrinsicShouldRejectWrongResultTypeAndReverseTargets() {
        assertInvalidSignature(
                CVectorIToVectorIntrinsic.vector3(),
                "v",
                List.of(
                        new VariableSpec("vi", GdIntVectorType.VECTOR3I, false),
                        new VariableSpec("v", GdIntVectorType.VECTOR3I, false)
                ),
                List.of("vi"),
                "must be Vector3"
        );
        assertInvalidSignature(
                CVectorIToVectorIntrinsic.vector3(),
                "v",
                List.of(
                        new VariableSpec("vi", GdIntVectorType.VECTOR3I, false),
                        new VariableSpec("v", GdFloatVectorType.VECTOR2, false)
                ),
                List.of("vi"),
                "must be Vector3"
        );
    }

    @Test
    @DisplayName("vector intrinsic should reject wrong source type and reverse sources")
    void vectorIntrinsicShouldRejectWrongSourceTypeAndReverseSources() {
        assertInvalidSignature(
                CVectorIToVectorIntrinsic.vector3(),
                "v",
                List.of(
                        new VariableSpec("vi", GdFloatVectorType.VECTOR3, false),
                        new VariableSpec("v", GdFloatVectorType.VECTOR3, false)
                ),
                List.of("vi"),
                "must be Vector3i"
        );
        assertInvalidSignature(
                CVectorIToVectorIntrinsic.vector3(),
                "v",
                List.of(
                        new VariableSpec("vi", GdIntVectorType.VECTOR2I, false),
                        new VariableSpec("v", GdFloatVectorType.VECTOR3, false)
                ),
                List.of("vi"),
                "must be Vector3i"
        );
        assertInvalidSignature(
                CVectorIToVectorIntrinsic.vector3(),
                "v",
                List.of(
                        new VariableSpec("vi", GdBoolType.BOOL, false),
                        new VariableSpec("v", GdFloatVectorType.VECTOR3, false)
                ),
                List.of("vi"),
                "must be Vector3i"
        );
    }

    @Test
    @DisplayName("vector intrinsic should reject wrong argument count")
    void vectorIntrinsicShouldRejectWrongArgumentCount() {
        assertInvalidSignature(
                CVectorIToVectorIntrinsic.vector3(),
                "v",
                List.of(
                        new VariableSpec("vi", GdIntVectorType.VECTOR3I, false),
                        new VariableSpec("vj", GdIntVectorType.VECTOR3I, false),
                        new VariableSpec("v", GdFloatVectorType.VECTOR3, false)
                ),
                List.of(),
                "exactly one argument"
        );
        assertInvalidSignature(
                CVectorIToVectorIntrinsic.vector3(),
                "v",
                List.of(
                        new VariableSpec("vi", GdIntVectorType.VECTOR3I, false),
                        new VariableSpec("vj", GdIntVectorType.VECTOR3I, false),
                        new VariableSpec("v", GdFloatVectorType.VECTOR3, false)
                ),
                List.of("vi", "vj"),
                "exactly one argument"
        );
    }

    private static void assertInvalidSignature(@NotNull CVectorIToVectorIntrinsic intrinsic,
                                               @Nullable String resultId,
                                               @NotNull List<VariableSpec> variableSpecs,
                                               @NotNull List<String> argIds,
                                               @NotNull String expectedMessage) {
        var fixture = new Fixture(variableSpecs, intrinsic.name());
        var resultVar = resultId == null ? null : fixture.variable(resultId);
        var argVars = argIds.stream()
                .map(fixture::variable)
                .toList();

        var ex = assertThrows(InvalidInsnException.class, () ->
                intrinsic.generateCCode(fixture.builder(), resultVar, argVars)
        );

        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains(expectedMessage), ex.getMessage());
        assertTrue(ex.getMessage().contains(intrinsic.name()), ex.getMessage());
    }

    private static @NotNull CGenHelper newHelper(@NotNull LirClassDef workerClass) {
        ClassRegistry classRegistry;
        try {
            classRegistry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load default extension API for vector intrinsic tests", ex);
        }
        classRegistry.addGdccClass(workerClass);
        ProjectInfo projectInfo = new ProjectInfo("TestProject", GodotVersion.V451, Path.of(".")) {
        };
        return new CGenHelper(new CodegenContext(projectInfo, classRegistry), List.of(workerClass));
    }

    private record Fixture(@NotNull CBodyBuilder builder,
                           @NotNull LirFunctionDef func) {
        Fixture(@NotNull List<VariableSpec> variableSpecs,
                @NotNull String intrinsicName) {
            this(newWorkerClass(), new LirFunctionDef("intrinsic_test"), variableSpecs, intrinsicName);
        }

        private Fixture(@NotNull LirClassDef workerClass,
                        @NotNull LirFunctionDef func,
                        @NotNull List<VariableSpec> variableSpecs,
                        @NotNull String intrinsicName) {
            this(newBuilder(workerClass, func, variableSpecs, intrinsicName), func);
        }

        private static @NotNull LirClassDef newWorkerClass() {
            return new LirClassDef("Worker", "RefCounted", false, false,
                    Map.of(), List.of(), List.of(), List.of());
        }

        private static @NotNull CBodyBuilder newBuilder(@NotNull LirClassDef workerClass,
                                                        @NotNull LirFunctionDef func,
                                                        @NotNull List<VariableSpec> variableSpecs,
                                                        @NotNull String intrinsicName) {
            func.setReturnType(GdVoidType.VOID);
            for (var variableSpec : variableSpecs) {
                if (variableSpec.ref()) {
                    func.createAndAddRefVariable(variableSpec.id(), variableSpec.type());
                } else {
                    func.createAndAddVariable(variableSpec.id(), variableSpec.type());
                }
            }
            var block = new LirBasicBlock("entry");
            var insn = currentInsn(intrinsicName);
            block.appendNonTerminatorInstruction(insn);
            func.addBasicBlock(block);
            func.setEntryBlockId("entry");
            workerClass.addFunction(func);

            return new CBodyBuilder(newHelper(workerClass), workerClass, func)
                    .setCurrentPosition(block, 0, insn);
        }

        private static @NotNull LirInstruction currentInsn(@NotNull String intrinsicName) {
            return new CallIntrinsicInsn("v", intrinsicName, List.of());
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

    private record Case(@NotNull CVectorIToVectorIntrinsic intrinsic,
                        @NotNull GdIntVectorType sourceType,
                        @NotNull GdFloatVectorType targetType,
                        @NotNull String constructorName) {
    }
}
