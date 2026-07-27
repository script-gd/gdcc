package gd.script.gdcc.backend.c.gen;

import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.backend.ProjectInfo;
import gd.script.gdcc.backend.c.gen.intrinsic.foriter.CForVariantIterIntrinsic;
import gd.script.gdcc.backend.c.gen.intrinsic.foriter.CForVariantIterRawInitIntrinsic;
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
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdccForVariantIterType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GdccForVariantIterIntrinsicTest {
    @Test
    @DisplayName("prepare init should produce default variant iterator storage")
    void prepareInitShouldProduceDefaultVariantIteratorStorage() {
        var fixture = new Fixture(List.of(
                new VariableSpec("iter", GdccForVariantIterType.FOR_VARIANT_ITER, false)
        ), CForVariantIterRawInitIntrinsic.NAME, "__prepare__");
        var intrinsic = new CForVariantIterRawInitIntrinsic();

        intrinsic.generateCCode(fixture.builder(), fixture.variable("iter"), List.of());

        assertEquals("$iter = gdcc_for_variant_iter_init();\n", fixture.builder().build());
    }

    @Test
    @DisplayName("variant init should emit from_variant helper with destroy-before-assign")
    void variantInitShouldEmitFromVariantHelperWithDestroyBeforeAssign() {
        var fixture = new Fixture(List.of(
                new VariableSpec("source", GdVariantType.VARIANT, false),
                new VariableSpec("iter", GdccForVariantIterType.FOR_VARIANT_ITER, false)
        ), CForVariantIterIntrinsic.INIT_NAME);

        CForVariantIterIntrinsic.init().generateCCode(
                fixture.builder(),
                fixture.variable("iter"),
                List.of(fixture.variable("source"))
        );

        assertEquals("""
                gdcc_for_variant_iter_destroy(&$iter);
                $iter = gdcc_for_variant_iter_from_variant(&$source);
                """, fixture.builder().build());
    }

    @Test
    @DisplayName("variant iteration intrinsics should emit continue next and get helpers")
    void variantIterationIntrinsicsShouldEmitContinueNextAndGetHelpers() {
        var fixture = new Fixture(List.of(
                new VariableSpec("iter", GdccForVariantIterType.FOR_VARIANT_ITER, false),
                new VariableSpec("next_iter", GdccForVariantIterType.FOR_VARIANT_ITER, false),
                new VariableSpec("cond", GdBoolType.BOOL, false),
                new VariableSpec("value", GdVariantType.VARIANT, false)
        ), CForVariantIterIntrinsic.SHOULD_CONTINUE_NAME);

        CForVariantIterIntrinsic.shouldContinue().generateCCode(
                fixture.builder(), fixture.variable("cond"), List.of(fixture.variable("iter")));
        CForVariantIterIntrinsic.get().generateCCode(
                fixture.builder(), fixture.variable("value"), List.of(fixture.variable("iter")));
        CForVariantIterIntrinsic.next().generateCCode(
                fixture.builder(), fixture.variable("next_iter"), List.of(fixture.variable("iter")));

        assertEquals("""
                $cond = gdcc_for_variant_iter_should_continue(&$iter);
                godot_Variant_destroy(&$value);
                $value = gdcc_for_variant_iter_get(&$iter);
                gdcc_for_variant_iter_destroy(&$next_iter);
                $next_iter = gdcc_for_variant_iter_next(&$iter);
                """, fixture.builder().build());
    }

    @Test
    @DisplayName("state commit assign should emit destroy-before-copy through copy helper")
    void stateCommitAssignShouldEmitDestroyBeforeCopyThroughCopyHelper() {
        var fixture = new Fixture(List.of(
                new VariableSpec("state", GdccForVariantIterType.FOR_VARIANT_ITER, false),
                new VariableSpec("next_temp", GdccForVariantIterType.FOR_VARIANT_ITER, false)
        ), CForVariantIterIntrinsic.NEXT_NAME);

        fixture.builder().assignVar(
                fixture.builder().targetOfVar(fixture.variable("state")),
                fixture.builder().valueOfVar(fixture.variable("next_temp"))
        );

        assertEquals("""
                gdcc_for_variant_iter_destroy(&$state);
                $state = gdcc_for_variant_iter_copy(&$next_temp);
                """, fixture.builder().build());
    }

    @Test
    @DisplayName("variant intrinsics should reject missing ref or wrong result slots")
    void variantIntrinsicsShouldRejectBadResultSlots() {
        assertInvalidSignature(
                CForVariantIterIntrinsic.init(),
                null,
                List.of(new VariableSpec("source", GdVariantType.VARIANT, false)),
                List.of("source"),
                "requires a result variable"
        );
        assertInvalidSignature(
                CForVariantIterIntrinsic.init(),
                "iter",
                List.of(
                        new VariableSpec("source", GdVariantType.VARIANT, false),
                        new VariableSpec("iter", GdccForVariantIterType.FOR_VARIANT_ITER, true)
                ),
                List.of("source"),
                "cannot be a reference"
        );
        assertInvalidSignature(
                CForVariantIterIntrinsic.shouldContinue(),
                "cond",
                List.of(
                        new VariableSpec("iter", GdccForVariantIterType.FOR_VARIANT_ITER, false),
                        new VariableSpec("cond", GdIntType.INT, false)
                ),
                List.of("iter"),
                "must be bool"
        );
    }

    @Test
    @DisplayName("variant intrinsics should reject wrong arity and argument types")
    void variantIntrinsicsShouldRejectWrongArityAndArgumentTypes() {
        assertInvalidSignature(
                CForVariantIterIntrinsic.init(),
                "iter",
                List.of(new VariableSpec("iter", GdccForVariantIterType.FOR_VARIANT_ITER, false)),
                List.of(),
                "requires exactly 1 argument"
        );
        assertInvalidSignature(
                CForVariantIterIntrinsic.init(),
                "iter",
                List.of(
                        new VariableSpec("bad_source", GdIntType.INT, false),
                        new VariableSpec("iter", GdccForVariantIterType.FOR_VARIANT_ITER, false)
                ),
                List.of("bad_source"),
                "argument #1 variable 'bad_source' must be Variant"
        );
        assertInvalidSignature(
                CForVariantIterIntrinsic.get(),
                "value",
                List.of(
                        new VariableSpec("not_iter", GdIntType.INT, false),
                        new VariableSpec("value", GdVariantType.VARIANT, false)
                ),
                List.of("not_iter"),
                "argument #1 variable 'not_iter' must be GdccForVariantIter"
        );
    }

    private static void assertInvalidSignature(@NotNull CForVariantIterIntrinsic intrinsic,
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
        Fixture(@NotNull List<VariableSpec> variableSpecs,
                @NotNull String intrinsicName) {
            this(variableSpecs, intrinsicName, "entry");
        }

        Fixture(@NotNull List<VariableSpec> variableSpecs,
                @NotNull String intrinsicName,
                @NotNull String blockId) {
            this(newWorkerClass(), new LirFunctionDef("intrinsic_test"), variableSpecs, intrinsicName, blockId);
        }

        private Fixture(@NotNull LirClassDef workerClass,
                        @NotNull LirFunctionDef func,
                        @NotNull List<VariableSpec> variableSpecs,
                        @NotNull String intrinsicName,
                        @NotNull String blockId) {
            this(newBuilder(workerClass, func, variableSpecs, intrinsicName, blockId), func);
        }

        private static @NotNull LirClassDef newWorkerClass() {
            return new LirClassDef("Worker", "RefCounted", false, false,
                    Map.of(), List.of(), List.of(), List.of());
        }

        private static @NotNull CBodyBuilder newBuilder(@NotNull LirClassDef workerClass,
                                                        @NotNull LirFunctionDef func,
                                                        @NotNull List<VariableSpec> variableSpecs,
                                                        @NotNull String intrinsicName,
                                                        @NotNull String blockId) {
            func.setReturnType(GdIntType.INT);
            for (var variableSpec : variableSpecs) {
                if (variableSpec.ref()) {
                    func.createAndAddRefVariable(variableSpec.id(), variableSpec.type());
                } else {
                    func.createAndAddVariable(variableSpec.id(), variableSpec.type());
                }
            }
            var block = new LirBasicBlock(blockId);
            var insn = currentInsn(intrinsicName);
            block.appendNonTerminatorInstruction(insn);
            func.addBasicBlock(block);
            func.setEntryBlockId(blockId);
            workerClass.addFunction(func);

            return new CBodyBuilder(newHelper(workerClass), workerClass, func)
                    .setCurrentPosition(block, 0, insn);
        }

        private static @NotNull LirInstruction currentInsn(@NotNull String intrinsicName) {
            return new CallIntrinsicInsn("result", intrinsicName, List.of());
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
