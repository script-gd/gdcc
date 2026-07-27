package gd.script.gdcc.backend.c.gen;

import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.backend.ProjectInfo;
import gd.script.gdcc.backend.c.gen.intrinsic.foriter.CForRangeIterIntrinsic;
import gd.script.gdcc.backend.c.gen.intrinsic.conversion.CIntToFloatIntrinsic;
import gd.script.gdcc.backend.c.gen.intrinsic.foriter.CForRangeIterRawInitIntrinsic;
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
import gd.script.gdcc.type.GdccForRangeIterType;
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

class GdccForRangeIterIntrinsicTest {
    @Test
    @DisplayName("prepare init should produce default compiler-only iterator storage")
    void prepareInitShouldProduceDefaultCompilerOnlyIteratorStorage() {
        var fixture = new Fixture(List.of(
                new VariableSpec("iter", GdccForRangeIterType.FOR_RANGE_ITER, false)
        ), CForRangeIterRawInitIntrinsic.NAME, "__prepare__");
        var intrinsic = new CForRangeIterRawInitIntrinsic();

        intrinsic.generateCCode(fixture.builder(), fixture.variable("iter"), List.of());

        assertEquals("$iter = gdcc_for_range_iter_init();\n", fixture.builder().build());
    }

    @Test
    @DisplayName("range init should emit normalized bounds helper through slot write path")
    void rangeInitShouldEmitNormalizedBoundsHelperThroughSlotWritePath() {
        var fixture = new Fixture(List.of(
                new VariableSpec("start", GdIntType.INT, false),
                new VariableSpec("end", GdIntType.INT, false),
                new VariableSpec("step", GdIntType.INT, false),
                new VariableSpec("iter", GdccForRangeIterType.FOR_RANGE_ITER, false)
        ), CForRangeIterIntrinsic.INIT_NAME);

        CForRangeIterIntrinsic.init().generateCCode(
                fixture.builder(),
                fixture.variable("iter"),
                List.of(fixture.variable("start"), fixture.variable("end"), fixture.variable("step"))
        );

        assertEquals("""
                gdcc_for_range_iter_destroy(&$iter);
                $iter = gdcc_for_range_iter_from_bounds($start, $end, $step);
                """, fixture.builder().build());
    }

    @Test
    @DisplayName("range iteration intrinsics should emit continue next and get helpers")
    void rangeIterationIntrinsicsShouldEmitContinueNextAndGetHelpers() {
        var fixture = new Fixture(List.of(
                new VariableSpec("iter", GdccForRangeIterType.FOR_RANGE_ITER, false),
                new VariableSpec("next_iter", GdccForRangeIterType.FOR_RANGE_ITER, false),
                new VariableSpec("cond", GdBoolType.BOOL, false),
                new VariableSpec("value", GdIntType.INT, false)
        ), CForRangeIterIntrinsic.SHOULD_CONTINUE_NAME);

        CForRangeIterIntrinsic.shouldContinue().generateCCode(
                fixture.builder(), fixture.variable("cond"), List.of(fixture.variable("iter")));
        CForRangeIterIntrinsic.get().generateCCode(
                fixture.builder(), fixture.variable("value"), List.of(fixture.variable("iter")));
        CForRangeIterIntrinsic.next().generateCCode(
                fixture.builder(), fixture.variable("next_iter"), List.of(fixture.variable("iter")));

        assertEquals("""
                $cond = gdcc_for_range_iter_should_continue(&$iter);
                $value = gdcc_for_range_iter_get(&$iter);
                gdcc_for_range_iter_destroy(&$next_iter);
                $next_iter = gdcc_for_range_iter_next(&$iter);
                """, fixture.builder().build());
    }

    @Test
    @DisplayName("range intrinsics should reject missing ref or wrong result slots")
    void rangeIntrinsicsShouldRejectBadResultSlots() {
        assertInvalidSignature(
                CForRangeIterIntrinsic.init(),
                null,
                List.of(
                        new VariableSpec("start", GdIntType.INT, false),
                        new VariableSpec("end", GdIntType.INT, false),
                        new VariableSpec("step", GdIntType.INT, false)
                ),
                List.of("start", "end", "step"),
                "requires a result variable"
        );
        assertInvalidSignature(
                CForRangeIterIntrinsic.init(),
                "iter",
                List.of(
                        new VariableSpec("start", GdIntType.INT, false),
                        new VariableSpec("end", GdIntType.INT, false),
                        new VariableSpec("step", GdIntType.INT, false),
                        new VariableSpec("iter", GdccForRangeIterType.FOR_RANGE_ITER, true)
                ),
                List.of("start", "end", "step"),
                "cannot be a reference"
        );
        assertInvalidSignature(
                CForRangeIterIntrinsic.shouldContinue(),
                "cond",
                List.of(
                        new VariableSpec("iter", GdccForRangeIterType.FOR_RANGE_ITER, false),
                        new VariableSpec("cond", GdIntType.INT, false)
                ),
                List.of("iter"),
                "must be bool"
        );
    }

    @Test
    @DisplayName("range intrinsics should reject wrong arity and argument types")
    void rangeIntrinsicsShouldRejectWrongArityAndArgumentTypes() {
        assertInvalidSignature(
                CForRangeIterIntrinsic.init(),
                "iter",
                List.of(new VariableSpec("iter", GdccForRangeIterType.FOR_RANGE_ITER, false)),
                List.of(),
                "requires exactly 3 arguments"
        );
        assertInvalidSignature(
                CForRangeIterIntrinsic.init(),
                "iter",
                List.of(
                        new VariableSpec("start", GdBoolType.BOOL, false),
                        new VariableSpec("end", GdIntType.INT, false),
                        new VariableSpec("step", GdIntType.INT, false),
                        new VariableSpec("iter", GdccForRangeIterType.FOR_RANGE_ITER, false)
                ),
                List.of("start", "end", "step"),
                "argument #1 variable 'start' must be int"
        );
        assertInvalidSignature(
                CForRangeIterIntrinsic.get(),
                "value",
                List.of(
                        new VariableSpec("not_iter", GdIntType.INT, false),
                        new VariableSpec("value", GdIntType.INT, false)
                ),
                List.of("not_iter"),
                "argument #1 variable 'not_iter' must be GdccForRangeIter"
        );
    }

    @Test
    @DisplayName("non range intrinsics should reject compiler-only iterator operands")
    void nonRangeIntrinsicsShouldRejectCompilerOnlyIteratorOperands() {
        var fixture = new Fixture(List.of(
                new VariableSpec("iter", GdccForRangeIterType.FOR_RANGE_ITER, false),
                new VariableSpec("f", GdFloatType.FLOAT, false)
        ), CIntToFloatIntrinsic.NAME);
        var intrinsic = new CIntToFloatIntrinsic();

        var ex = assertThrows(InvalidInsnException.class, () -> intrinsic.generateCCode(
                fixture.builder(), fixture.variable("f"), List.of(fixture.variable("iter"))));

        assertTrue(ex.getMessage().contains(CIntToFloatIntrinsic.NAME), ex.getMessage());
        assertTrue(ex.getMessage().contains("must be int"), ex.getMessage());
    }

    private static void assertInvalidSignature(@NotNull CForRangeIterIntrinsic intrinsic,
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
