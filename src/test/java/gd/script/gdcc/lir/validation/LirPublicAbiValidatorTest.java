package gd.script.gdcc.lir.validation;

import gd.script.gdcc.lir.LirBasicBlock;
import gd.script.gdcc.lir.LirCaptureDef;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirModule;
import gd.script.gdcc.lir.LirParameterDef;
import gd.script.gdcc.lir.LirPropertyDef;
import gd.script.gdcc.lir.LirSignalDef;
import gd.script.gdcc.lir.insn.ReturnInsn;
import gd.script.gdcc.type.GdccCoroStateType;
import gd.script.gdcc.type.GdccForRangeIterType;
import gd.script.gdcc.type.GdVoidType;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LirPublicAbiValidatorTest {
    private final LirPublicAbiValidator validator = new LirPublicAbiValidator();

    @Test
    @DisplayName("compiler-only local variable should stay valid for backend-only LIR")
    void compilerOnlyLocalVariableShouldStayValid() {
        var function = newFunction("iter_helper");
        function.createAndAddVariable("iter", GdccForRangeIterType.FOR_RANGE_ITER);

        var module = new LirModule("m", List.of(newClassWith(function)));

        assertDoesNotThrow(() -> validator.validateModule(module));
    }

    @Test
    @DisplayName("coroutine state type is legal on locals but rejected on function return")
    void coroStateTypeFollowsSameCompilerOnlyBoundaries() {
        // Positive: the erased coroutine-state type on a function-local variable is the one
        // legal use site (LirTypeUseSite.FUNCTION_VARIABLE).
        var localFn = newFunction("consume_state");
        localFn.createAndAddVariable("state", GdccCoroStateType.CORO_STATE);
        assertDoesNotThrow(() -> validator.validateModule(new LirModule("m", List.of(newClassWith(localFn)))));

        // Negative: the same type on a function return surface is an ABI leak, even for hidden
        // functions (hidden is not a backdoor around the ABI validator).
        var returnFn = newFunction("produce_state");
        returnFn.setHidden(true);
        returnFn.setReturnType(GdccCoroStateType.CORO_STATE);
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validateModule(new LirModule("m", List.of(newClassWith(returnFn))))
        );
        assertTrue(exception.getMessage().contains("compiler-only type leaked into function return"), exception.getMessage());
    }

    @Test
    @DisplayName("validator should reject compiler-only ABI surfaces including hidden function return")
    void validatorShouldRejectCompilerOnlyAbiSurfaces() {
        assertSurfaceRejected("property", classWithCompilerOnlyProperty());
        assertSurfaceRejected("signal parameter", classWithCompilerOnlySignalParameter());
        assertSurfaceRejected("function parameter", classWithCompilerOnlyFunctionParameter());
        assertSurfaceRejected("function parameter", classWithCompilerOnlyHiddenFunctionParameter());
        assertSurfaceRejected("function return", classWithCompilerOnlyHiddenFunctionReturn());
        assertSurfaceRejected("function capture", classWithCompilerOnlyFunctionCapture());
    }

    private void assertSurfaceRejected(@NotNull String surfaceName, @NotNull LirClassDef classDef) {
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validateModule(new LirModule("m", List.of(classDef))),
                surfaceName
        );

        assertTrue(exception.getMessage().contains("compiler-only type leaked into " + surfaceName), exception.getMessage());
    }

    private static @NotNull LirClassDef classWithCompilerOnlyProperty() {
        var classDef = new LirClassDef("Worker", "RefCounted");
        classDef.addProperty(new LirPropertyDef("iter", GdccForRangeIterType.FOR_RANGE_ITER));
        return classDef;
    }

    private static @NotNull LirClassDef classWithCompilerOnlySignalParameter() {
        var classDef = new LirClassDef("Worker", "RefCounted");
        var signal = new LirSignalDef("changed");
        signal.addParameter(new LirParameterDef("iter", GdccForRangeIterType.FOR_RANGE_ITER, null, signal));
        classDef.addSignal(signal);
        return classDef;
    }

    private static @NotNull LirClassDef classWithCompilerOnlyFunctionParameter() {
        var function = newFunction("consume_iter");
        function.addParameter(new LirParameterDef("iter", GdccForRangeIterType.FOR_RANGE_ITER, null, function));
        return newClassWith(function);
    }

    private static @NotNull LirClassDef classWithCompilerOnlyHiddenFunctionParameter() {
        var function = newFunction("consume_hidden_iter");
        function.setHidden(true);
        function.addParameter(new LirParameterDef("iter", GdccForRangeIterType.FOR_RANGE_ITER, null, function));
        return newClassWith(function);
    }

    private static @NotNull LirClassDef classWithCompilerOnlyHiddenFunctionReturn() {
        var function = newFunction("produce_iter");
        function.setHidden(true);
        function.setReturnType(GdccForRangeIterType.FOR_RANGE_ITER);
        return newClassWith(function);
    }

    private static @NotNull LirClassDef classWithCompilerOnlyFunctionCapture() {
        var function = newFunction("lambda0");
        function.setLambda(true);
        function.addCapture(new LirCaptureDef("iter", GdccForRangeIterType.FOR_RANGE_ITER, function));
        return newClassWith(function);
    }

    private static @NotNull LirFunctionDef newFunction(@NotNull String name) {
        var function = new LirFunctionDef(name);
        function.setReturnType(GdVoidType.VOID);
        function.addBasicBlock(new LirBasicBlock("entry", List.of(new ReturnInsn(null))));
        function.setEntryBlockId("entry");
        return function;
    }

    private static @NotNull LirClassDef newClassWith(@NotNull LirFunctionDef function) {
        var classDef = new LirClassDef("Worker", "RefCounted");
        classDef.addFunction(function);
        return classDef;
    }
}
