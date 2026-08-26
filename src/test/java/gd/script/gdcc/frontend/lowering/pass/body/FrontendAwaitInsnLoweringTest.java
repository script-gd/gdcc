package gd.script.gdcc.frontend.lowering.pass.body;

import dev.superice.gdparser.frontend.ast.AwaitExpression;
import dev.superice.gdparser.frontend.ast.LiteralExpression;
import dev.superice.gdparser.frontend.ast.Point;
import dev.superice.gdparser.frontend.ast.Range;
import gd.script.gdcc.enums.LifecycleProvenance;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.lowering.FrontendLoweringContext;
import gd.script.gdcc.frontend.lowering.FunctionLoweringContext;
import gd.script.gdcc.frontend.lowering.cfg.FrontendCfgGraph;
import gd.script.gdcc.frontend.lowering.cfg.item.AwaitItem;
import gd.script.gdcc.frontend.lowering.cfg.item.IntConstantItem;
import gd.script.gdcc.frontend.lowering.pass.FrontendLoweringBodyInsnPass;
import gd.script.gdcc.frontend.lowering.pass.FrontendLoweringBuildCfgPass;
import gd.script.gdcc.frontend.lowering.pass.FrontendLoweringClassSkeletonPass;
import gd.script.gdcc.frontend.lowering.pass.FrontendLoweringFunctionPreparationPass;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.frontend.sema.FrontendExpressionType;
import gd.script.gdcc.frontend.sema.analyzer.FrontendSemanticAnalyzer;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirInstruction;
import gd.script.gdcc.lir.insn.AssignInsn;
import gd.script.gdcc.lir.insn.AwaitInsn;
import gd.script.gdcc.lir.insn.CallMethodInsn;
import gd.script.gdcc.lir.insn.CallStaticMethodInsn;
import gd.script.gdcc.lir.insn.ConstructSignalInsn;
import gd.script.gdcc.lir.insn.DestructInsn;
import gd.script.gdcc.lir.insn.LiteralNullInsn;
import gd.script.gdcc.lir.parser.DomLirParser;
import gd.script.gdcc.lir.parser.DomLirSerializer;
import gd.script.gdcc.lir.validation.ControlFlowIntegrityValidator;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdSignalType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdccCoroStateType;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Body-lowering contract for `await` (`frontend_await_implementation.md` §9).
///
/// These tests drive the lowering pipeline manually on top of shared `analyze(...)` (the same
/// pattern used by `FrontendTypeTestInsnLoweringTest`): skeleton → function preparation → CFG →
/// body insn pass.
/// This also anchors that `FrontendLoweringClassSkeletonPass` propagates the sema coroutine marks
/// onto `LirFunctionDef.isCoroutine`.
class FrontendAwaitInsnLoweringTest {

    @Test
    void signalAwaitLowersToConstructSignalThenAwaitInsn() throws Exception {
        var context = lowerModule("""
                class_name AwaitLoweringSignalBasic
                extends Node
                
                signal pinged
                
                func run():
                    var result = await pinged
                """);
        var run = requireFunction(context, "run");
        var signalLoad = requireOnly(run, ConstructSignalInsn.class);
        var awaitInsn = requireOnly(run, AwaitInsn.class);

        assertAll(
                () -> assertTrue(run.isCoroutine(), "signal await must mark the enclosing function"),
                () -> assertEquals(signalLoad.resultId(), awaitInsn.operandId(),
                        "await must consume the materialized signal value"),
                () -> assertEquals(GdVariantType.VARIANT, requireVariableType(run, awaitInsn.resultId()),
                        "0-param signal await resumes with Variant"),
                () -> assertOrdered(run, signalLoad, awaitInsn)
        );
    }

    @Test
    void oneParamSignalAwaitPublishesDeclaredResultType() throws Exception {
        var context = lowerModule("""
                class_name AwaitLoweringSignalTyped
                extends Node
                
                signal pinged(count: int)
                
                func run():
                    var count = await pinged
                """);
        var run = requireFunction(context, "run");
        var awaitInsn = requireOnly(run, AwaitInsn.class);

        assertAll(
                () -> assertTrue(run.isCoroutine()),
                () -> assertEquals(GdIntType.INT, requireVariableType(run, awaitInsn.resultId()),
                        "1-param signal await resumes with the declared parameter type")
        );
    }

    @Test
    void coroutineCallAwaitUsesCoroStateSlotAndAwaitInsn() throws Exception {
        var context = lowerModule("""
                class_name AwaitLoweringCallSuspend
                extends Node
                
                signal pinged
                
                func inner() -> int:
                    await pinged
                    return 1
                
                func run():
                    var result = await inner()
                """);
        var run = requireFunction(context, "run");
        var inner = requireFunction(context, "inner");
        var call = requireOnly(run, CallMethodInsn.class);
        var awaitInsn = requireOnly(run, AwaitInsn.class);

        assertAll(
                () -> assertTrue(inner.isCoroutine(), "callee containing await must be marked"),
                () -> assertTrue(run.isCoroutine(), "awaiting a coroutine call must mark the caller"),
                () -> assertNotNull(call.resultId(), "coroutine call must carry a result slot"),
                () -> assertTrue(call.resultId().startsWith("__coro_state_"), call.resultId()),
                () -> assertEquals(
                        GdccCoroStateType.CORO_STATE,
                        requireVariableType(run, call.resultId()),
                        "coroutine call result slot carries the OWNED state reference"
                ),
                () -> assertEquals(call.resultId(), awaitInsn.operandId()),
                () -> assertEquals(GdIntType.INT, requireVariableType(run, awaitInsn.resultId()),
                        "await result keeps the callee declared return type"),
                () -> assertEquals(0, count(run, DestructInsn.class),
                        "awaited state reference moves into the await; no detach destruct"),
                () -> assertOrdered(run, call, awaitInsn)
        );
    }

    @Test
    void lambdaAwaitUsesCaptureBackedCoroutineFrame() throws Exception {
        var context = lowerModule("""
                class_name AwaitLoweringLambdaCapture
                extends Node
                
                signal pinged
                
                func watch():
                    var seed := 40
                    var cb := func():
                        var resumed = await pinged
                        return seed + 2
                    cb.call()
                """);
        var lambdaShell = requireLambdaFunction(context, "_lambda_0");
        var watch = requireFunction(context, "watch");
        var awaitInsn = requireOnly(lambdaShell, AwaitInsn.class);

        assertAll(
                () -> assertTrue(lambdaShell.isLambda()),
                () -> assertTrue(lambdaShell.isCoroutine(),
                        "await in a lambda body marks the synthesized shell"),
                () -> assertFalse(watch.isCoroutine(),
                        "the enclosing named function is not the suspend owner"),
                () -> assertNotNull(lambdaShell.getCapture("seed"),
                        "the capture plan survives coroutine marking; backend maps it to a frame field"),
                () -> assertEquals(GdVariantType.VARIANT, requireVariableType(lambdaShell, awaitInsn.resultId()),
                        "0-param signal await resumes with Variant")
        );
    }

    @Test
    void nestedLambdaAwaitMarksInnermostLambdaOnly() throws Exception {
        // The owner set is keyed by the lambda's own AST identity: a nested lambda's await must
        // mark the inner shell, never the outer lambda or the enclosing named function — a
        // marking derived from `FrontendLambdaPlan.enclosingCallable()` (nearest non-lambda
        // callable) would wrongly leak outward.
        var context = lowerModule("""
                class_name AwaitLoweringNestedLambda
                extends Node
                
                signal pinged
                
                func watch():
                    var outer := func():
                        var inner := func():
                            var resumed = await pinged
                        inner.call()
                    outer.call()
                """);
        var outerShell = requireLambdaFunction(context, "_lambda_0");
        var innerShell = requireLambdaFunction(context, "_lambda_1");
        var watch = requireFunction(context, "watch");

        assertAll(
                () -> assertTrue(innerShell.isCoroutine(), "the inner lambda owns the await"),
                () -> assertFalse(outerShell.isCoroutine(), "the outer lambda only invokes the inner Callable"),
                () -> assertFalse(watch.isCoroutine()),
                () -> assertEquals(1, count(innerShell, AwaitInsn.class)),
                () -> assertEquals(0, count(outerShell, AwaitInsn.class))
        );
    }

    @Test
    void voidCoroutineCallAwaitPublishesVariantResult() throws Exception {
        var context = lowerModule("""
                class_name AwaitLoweringVoidCoroutineCall
                extends Node
                
                signal pinged
                
                func inner():
                    await pinged
                
                func run():
                    var result = await inner()
                """);
        var run = requireFunction(context, "run");
        var awaitInsn = requireOnly(run, AwaitInsn.class);

        assertAll(
                () -> assertTrue(run.isCoroutine()),
                () -> assertEquals(GdVariantType.VARIANT, requireVariableType(run, awaitInsn.resultId()),
                        "void coroutine resumes with nil, published as Variant")
        );
    }

    @Test
    void attributeSignalAwaitConsumesSignalLoadValue() throws Exception {
        // Attribute-form signal reads build a SignalLoadItem at CFG level (unlike the bare
        // identifier form, which stays opaque until body lowering); the await consumes it directly.
        var context = lowerModule("""
                class_name AwaitLoweringSignalAttribute
                extends Node
                
                signal pinged
                
                func run(other: AwaitLoweringSignalAttribute):
                    var result = await other.pinged
                """);
        var run = requireFunction(context, "run");
        var signalLoad = requireOnly(run, ConstructSignalInsn.class);
        var awaitInsn = requireOnly(run, AwaitInsn.class);

        assertAll(
                () -> assertTrue(run.isCoroutine()),
                () -> assertEquals(signalLoad.resultId(), awaitInsn.operandId()),
                () -> assertOrdered(run, signalLoad, awaitInsn)
        );
    }

    @Test
    void dynamicAwaitLowersToAwaitInsnOnVariantOperand() throws Exception {
        var context = lowerModule("""
                class_name AwaitLoweringDynamic
                extends Node
                
                func run(target):
                    var result = await target
                """);
        var run = requireFunction(context, "run");
        var awaitInsn = requireOnly(run, AwaitInsn.class);

        assertAll(
                () -> assertTrue(run.isCoroutine(), "dynamic await must mark the enclosing function"),
                () -> assertEquals(GdVariantType.VARIANT, requireVariableType(run, awaitInsn.operandId())),
                () -> assertEquals(GdVariantType.VARIANT, requireVariableType(run, awaitInsn.resultId()))
        );
    }

    @Test
    void redundantAwaitOnNonCoroutineCallLowersToPassThrough() throws Exception {
        var context = lowerModule("""
                class_name AwaitLoweringRedundantCall
                extends Node
                
                func inner() -> int:
                    return 1
                
                func run():
                    var result: int = await inner()
                """);
        var run = requireFunction(context, "run");
        var call = requireOnly(run, CallMethodInsn.class);

        assertAll(
                () -> assertFalse(run.isCoroutine(), "redundant await must not mark the caller"),
                () -> assertEquals(0, count(run, AwaitInsn.class), "redundant await emits no AwaitInsn"),
                () -> assertNotNull(call.resultId()),
                () -> assertTrue(call.resultId().startsWith("cfg_tmp_"), call.resultId()),
                () -> {
                    // The passthrough assign copies the call result into the await result slot;
                    // the later local-init assign (`result = ...`) is ordinary variable behavior.
                    var passthrough = allInstructions(run).stream()
                            .filter(AssignInsn.class::isInstance)
                            .map(AssignInsn.class::cast)
                            .filter(insn -> insn.sourceId().equals(call.resultId()))
                            .toList();
                    assertEquals(1, passthrough.size(), () -> "Missing passthrough assign in " + allInstructions(run));
                    assertEquals(GdIntType.INT, requireVariableType(run, passthrough.getFirst().resultId()));
                },
                () -> assertTrue(
                        context.requireAnalysisData().diagnostics().asList().stream().anyMatch(
                                diagnostic -> diagnostic.category().equals("sema.redundant_await")
                        ),
                        "sema warning stays the user-facing signal for redundant awaits"
                )
        );
    }

    @Test
    void redundantAwaitOnVoidCallMaterializesNilResult() throws Exception {
        var context = lowerModule("""
                class_name AwaitLoweringVoidRedundant
                extends Node
                
                func inner() -> void:
                    pass
                
                func run():
                    var result = await inner()
                """);
        var run = requireFunction(context, "run");
        var call = requireOnly(run, CallMethodInsn.class);
        var nullLiteral = requireOnly(run, LiteralNullInsn.class);

        assertAll(
                () -> assertFalse(run.isCoroutine()),
                () -> assertEquals(0, count(run, AwaitInsn.class)),
                () -> assertNull(call.resultId(), "void call keeps the no-result shape"),
                () -> assertEquals(
                        GdVariantType.VARIANT,
                        requireVariableType(run, nullLiteral.resultId()),
                        "void-callee redundant await resumes with nil Variant"
                )
        );
    }

    @Test
    void fireAndForgetCoroutineCallDetachesViaInternalDestruct() throws Exception {
        var context = lowerModule("""
                class_name AwaitLoweringFireAndForget
                extends Node
                
                signal pinged
                
                func inner() -> int:
                    await pinged
                    return 1
                
                func run():
                    inner()
                """);
        var run = requireFunction(context, "run");
        var call = requireOnly(run, CallMethodInsn.class);
        var destruct = requireOnly(run, DestructInsn.class);

        assertAll(
                () -> assertFalse(run.isCoroutine(), "fire-and-forget must not mark the caller"),
                () -> assertEquals(0, count(run, AwaitInsn.class)),
                () -> assertNotNull(call.resultId(), "statement-position coroutine call keeps a state result slot"),
                () -> assertTrue(call.resultId().startsWith("__coro_state_"), call.resultId()),
                () -> assertEquals(call.resultId(), destruct.variableId()),
                () -> assertEquals(LifecycleProvenance.INTERNAL, destruct.provenance()),
                () -> assertOrdered(run, call, destruct)
        );
    }

    @Test
    void voidCoroutineStatementCallKeepsStateResultAndDetaches() throws Exception {
        // Regression anchor: resolved-void statement calls normally skip result publication, but a
        // void coroutine call must still carry its `compiler::GdccCoroState` result slot.
        var context = lowerModule("""
                class_name AwaitLoweringVoidFireAndForget
                extends Node
                
                signal pinged
                
                func fire():
                    await pinged
                
                func run():
                    fire()
                """);
        var run = requireFunction(context, "run");
        var call = requireOnly(run, CallMethodInsn.class);
        var destruct = requireOnly(run, DestructInsn.class);

        assertAll(
                () -> assertFalse(run.isCoroutine()),
                () -> assertNotNull(call.resultId(), "void coroutine call still yields the state reference"),
                () -> assertTrue(call.resultId().startsWith("__coro_state_"), call.resultId()),
                () -> assertEquals(
                        GdccCoroStateType.CORO_STATE,
                        requireVariableType(run, call.resultId())
                ),
                () -> assertEquals(call.resultId(), destruct.variableId()),
                () -> assertOrdered(run, call, destruct)
        );
    }

    @Test
    void loweredCoroutineModuleKeepsIntegrityAndRoundTrips() throws Exception {
        var context = lowerModule("""
                class_name AwaitLoweringRoundTrip
                extends Node
                
                signal pinged
                
                func inner() -> int:
                    await pinged
                    return 1
                
                func run():
                    var result = await inner()
                """);
        var run = requireFunction(context, "run");

        // Basic-block integrity on the freshly lowered function (entry wiring + terminators).
        assertFalse(run.getEntryBlockId().isEmpty(), "lowered coroutine must keep an entry block");
        new ControlFlowIntegrityValidator().validateFunction(run);

        var xml = new DomLirSerializer().serializeToString(context.requireLirModule());
        assertTrue(xml.contains("is_coroutine=\"true\""), xml);

        var parsed = new DomLirParser(new ClassRegistry(ExtensionApiLoader.loadDefault())).parse(xml);
        var parsedRun = parsed.getClassDefs().stream()
                .flatMap(classDef -> classDef.getFunctions().stream())
                .filter(function -> function.getName().equals("run"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing parsed run function"));
        assertAll(
                () -> assertTrue(parsedRun.isCoroutine(), "is_coroutine must survive the XML round-trip"),
                () -> assertEquals(1, count(parsedRun, AwaitInsn.class), "await insn must survive the round-trip"),
                () -> assertEquals(1, count(parsedRun, CallMethodInsn.class)),
                () -> new ControlFlowIntegrityValidator().validateFunction(parsedRun)
        );
    }

    @Test
    void signalReturningNonCoroutineCallLowersToSignalAwait() throws Exception {
        // Godot awaits the Signal returned by a synchronous function. The call remains ordinary,
        // while its Signal result feeds the signal-channel AwaitInsn in the coroutine caller.
        var context = lowerModule("""
                class_name AwaitLoweringSignalReturningCall
                extends Node
                
                signal counted(value: int)
                
                func copy_signal() -> Signal:
                    return counted
                
                func run():
                    var result: Variant = await copy_signal()
                """);
        var run = requireFunction(context, "run");
        var call = requireOnly(run, CallMethodInsn.class);
        var awaitInsn = requireOnly(run, AwaitInsn.class);

        assertAll(
                () -> assertTrue(run.isCoroutine(), "signal-returning await must mark the caller"),
                () -> assertEquals(call.resultId(), awaitInsn.operandId()),
                () -> assertInstanceOf(GdSignalType.class, requireVariableType(run, call.resultId())),
                () -> assertEquals("Variant", requireVariableType(run, awaitInsn.resultId()).getTypeName()),
                () -> assertTrue(
                        context.requireAnalysisData().diagnostics().asList().stream().noneMatch(
                                diagnostic -> diagnostic.category().equals("sema.redundant_await")
                        )
                )
        );
    }

    @Test
    void attributeChainCoroutineCallAwaitUsesCoroStateSlot() throws Exception {
        // Chain-call anchor (`AttributeCallStep`) instead of a bare `CallExpression`: the coroutine
        // detection and the await operand wiring must follow the step-anchored call fact.
        var context = lowerModule("""
                class_name AwaitLoweringChainCall
                extends Node
                
                signal pinged
                
                func inner() -> int:
                    await pinged
                    return 1
                
                func run(other: AwaitLoweringChainCall):
                    var result: int = await other.inner()
                """);
        var run = requireFunction(context, "run");
        var call = requireOnly(run, CallMethodInsn.class);
        var awaitInsn = requireOnly(run, AwaitInsn.class);

        assertAll(
                () -> assertTrue(run.isCoroutine()),
                () -> assertNotNull(call.resultId()),
                () -> assertTrue(call.resultId().startsWith("__coro_state_"), call.resultId()),
                () -> assertEquals(call.resultId(), awaitInsn.operandId()),
                () -> assertEquals(GdIntType.INT, requireVariableType(run, awaitInsn.resultId())),
                () -> assertEquals(0, count(run, DestructInsn.class))
        );
    }

    @Test
    void multipleAwaitsInOneFunctionGetDistinctStateSlots() throws Exception {
        var context = lowerModule("""
                class_name AwaitLoweringMultiAwait
                extends Node
                
                signal pinged
                
                func inner():
                    await pinged
                
                func run():
                    await inner()
                    await inner()
                """);
        var run = requireFunction(context, "run");
        var calls = allInstructions(run).stream()
                .filter(CallMethodInsn.class::isInstance)
                .map(CallMethodInsn.class::cast)
                .toList();
        var awaits = allInstructions(run).stream()
                .filter(AwaitInsn.class::isInstance)
                .map(AwaitInsn.class::cast)
                .toList();

        assertAll(
                () -> assertTrue(run.isCoroutine()),
                () -> assertEquals(2, calls.size(), () -> allInstructions(run).toString()),
                () -> assertEquals(2, awaits.size()),
                () -> assertNotNull(calls.get(0).resultId()),
                () -> assertNotNull(calls.get(1).resultId()),
                () -> assertFalse(
                        calls.get(0).resultId().equals(calls.get(1).resultId()),
                        "each coroutine call must get its own state slot"
                ),
                () -> assertEquals(calls.get(0).resultId(), awaits.get(0).operandId()),
                () -> assertEquals(calls.get(1).resultId(), awaits.get(1).operandId()),
                () -> assertEquals(0, count(run, DestructInsn.class))
        );
    }

    @Test
    void awaitInsideIfBodyLowersInsideBranchBlock() throws Exception {
        // Multi-sequence CFG: the await-operand consumption set is graph-wide, so the coroutine
        // call inside the branch must not be detached even though statement scanning crosses nodes.
        var context = lowerModule("""
                class_name AwaitLoweringBranch
                extends Node
                
                signal pinged
                
                func inner():
                    await pinged
                
                func run(flag: bool):
                    if flag:
                        await inner()
                """);
        var run = requireFunction(context, "run");
        var call = requireOnly(run, CallMethodInsn.class);
        var awaitInsn = requireOnly(run, AwaitInsn.class);

        assertAll(
                () -> assertTrue(run.isCoroutine()),
                () -> assertNotNull(call.resultId()),
                () -> assertEquals(call.resultId(), awaitInsn.operandId()),
                () -> assertEquals(0, count(run, DestructInsn.class),
                        "awaited state reference must not be detached"),
                () -> new ControlFlowIntegrityValidator().validateFunction(run)
        );
    }

    /// `await` on a static coroutine call lowers to `CallStaticMethodInsn` + `AwaitInsn`
    /// and the caller is marked as a coroutine — the fixed point covers static callees too.
    @Test
    void staticCoroutineCallAwaitLowersToCallStaticMethodInsn() throws Exception {
        var context = lowerModule("""
                class_name AwaitLoweringStaticCoroutine
                extends Node
                
                static func s_coro(target):
                    await target
                
                func run():
                    var result = await AwaitLoweringStaticCoroutine.s_coro(1)
                """);
        var run = requireFunction(context, "run");
        var worker = requireFunction(context, "s_coro");
        var call = requireOnly(run, CallStaticMethodInsn.class);
        var awaitInsn = requireOnly(run, AwaitInsn.class);

        assertAll(
                () -> assertTrue(worker.isCoroutine()),
                () -> assertTrue(run.isCoroutine(), "awaited static coroutine call must mark the caller"),
                () -> assertEquals("AwaitLoweringStaticCoroutine", call.className()),
                () -> assertEquals("s_coro", call.methodName()),
                () -> assertNotNull(call.resultId()),
                () -> assertTrue(call.resultId().startsWith("__coro_state_"), call.resultId()),
                () -> assertEquals(call.resultId(), awaitInsn.operandId()),
                () -> assertEquals(0, count(run, DestructInsn.class),
                        "awaited state reference must not be detached"),
                () -> new ControlFlowIntegrityValidator().validateFunction(run)
        );
    }

    /// A statement-root static coroutine call is fire-and-forget — the caller stays
    /// non-coroutine, the call keeps its state result slot, and the reference detaches via an
    /// `INTERNAL` destruct right after the call, same discipline as the instance route.
    @Test
    void staticCoroutineStatementCallKeepsStateResultAndDetaches() throws Exception {
        var context = lowerModule("""
                class_name AwaitLoweringStaticFireAndForget
                extends Node
                
                static func s_coro(target):
                    await target
                
                func run():
                    AwaitLoweringStaticFireAndForget.s_coro(1)
                """);
        var run = requireFunction(context, "run");
        var call = requireOnly(run, CallStaticMethodInsn.class);
        var destruct = requireOnly(run, DestructInsn.class);

        assertAll(
                () -> assertFalse(run.isCoroutine(), "fire-and-forget must not mark the caller"),
                () -> assertEquals(0, count(run, AwaitInsn.class)),
                () -> assertNotNull(call.resultId(), "statement-position coroutine call keeps a state result slot"),
                () -> assertTrue(call.resultId().startsWith("__coro_state_"), call.resultId()),
                () -> assertEquals(
                        GdccCoroStateType.CORO_STATE,
                        requireVariableType(run, call.resultId())
                ),
                () -> assertEquals(call.resultId(), destruct.variableId()),
                () -> assertEquals(LifecycleProvenance.INTERNAL, destruct.provenance()),
                () -> assertOrdered(run, call, destruct),
                () -> new ControlFlowIntegrityValidator().validateFunction(run)
        );
    }

    @Test
    void awaitOnNonAwaitableStaticValueFailsFastAtLowering() throws Exception {
        // Negative invariant anchor for the dispatch fallthrough: sema rejects static non-signal
        // non-call operands (UNSUPPORTED), so this shape is unreachable from source. The hand-built
        // graph simulates a protocol violation to prove the processor fails fast instead of
        // emitting an AwaitInsn for an operand the backend would reject more obscurely.
        var diagnostics = new DiagnosticManager();
        var unit = new GdScriptParserService().parseUnit(
                Path.of("tmp", "await_bad_operand_lowering.gd"),
                """
                        class_name AwaitLoweringBadOperand
                        extends Node
                        
                        func run():
                            pass
                        """,
                diagnostics
        );
        assertTrue(diagnostics.isEmpty(), () -> "Unexpected parse diagnostics: " + diagnostics.snapshot());
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var module = new FrontendModule("test_module", List.of(unit), Map.of());
        var analysisData = new FrontendSemanticAnalyzer().analyze(module, classRegistry, diagnostics);
        var context = new FrontendLoweringContext(module, classRegistry, diagnostics);
        context.publishAnalysisData(analysisData);
        new FrontendLoweringClassSkeletonPass().run(context);
        new FrontendLoweringFunctionPreparationPass().run(context);
        var functionContext = context.requireFunctionLoweringContexts().stream()
                .filter(candidate -> candidate.kind() == FunctionLoweringContext.Kind.EXECUTABLE_BODY)
                .filter(candidate -> candidate.targetFunction().getName().equals("run"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing executable body context for run"));

        var range = new Range(0, 1, new Point(0, 0), new Point(0, 1));
        var literalAnchor = new LiteralExpression("integer", "1", range);
        var awaitExpression = new AwaitExpression(literalAnchor, range);
        analysisData.expressionTypes().put(awaitExpression, FrontendExpressionType.resolved(GdVariantType.VARIANT));
        functionContext.publishFrontendCfgGraph(new FrontendCfgGraph(
                "seq_0",
                Map.of(
                        "seq_0",
                        new FrontendCfgGraph.SequenceNode("seq_0", List.of(
                                new IntConstantItem(literalAnchor, 1, "v0"),
                                new AwaitItem(awaitExpression, "v0", "v1")
                        ), "stop_1"),
                        "stop_1",
                        new FrontendCfgGraph.StopNode("stop_1", FrontendCfgGraph.StopKind.RETURN, "v1")
                )
        ));

        var error = assertThrows(
                IllegalStateException.class,
                () -> new FrontendBodyLoweringSession(functionContext, classRegistry).run()
        );
        assertTrue(
                error.getMessage().contains("no published signal/coroutine/dynamic route"),
                () -> "Expected dispatch fail-fast, got: " + error.getMessage()
        );
    }

    /// Runs sema (shared, non-compile path) then the four lowering passes manually; tests wire
    /// the pipeline themselves instead of going through the compile gate.
    private static @NotNull FrontendLoweringContext lowerModule(@NotNull String source) throws Exception {
        var diagnostics = new DiagnosticManager();
        var unit = new GdScriptParserService().parseUnit(
                Path.of("tmp", "await_body_lowering.gd"),
                source,
                diagnostics
        );
        assertTrue(diagnostics.isEmpty(), () -> "Unexpected parse diagnostics: " + diagnostics.snapshot());
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var module = new FrontendModule("test_module", List.of(unit), Map.of());
        var analysisData = new FrontendSemanticAnalyzer().analyze(module, classRegistry, diagnostics);
        assertFalse(
                diagnostics.hasErrors(),
                () -> "Unexpected semantic errors before body lowering: " + diagnostics.snapshot()
        );

        var context = new FrontendLoweringContext(module, classRegistry, diagnostics);
        context.publishAnalysisData(analysisData);
        new FrontendLoweringClassSkeletonPass().run(context);
        new FrontendLoweringFunctionPreparationPass().run(context);
        new FrontendLoweringBuildCfgPass().run(context);
        new FrontendLoweringBodyInsnPass().run(context);
        return context;
    }

    private static @NotNull LirFunctionDef requireFunction(
            @NotNull FrontendLoweringContext context,
            @NotNull String functionName
    ) {
        return context.requireFunctionLoweringContexts().stream()
                .filter(candidate -> candidate.kind() == FunctionLoweringContext.Kind.EXECUTABLE_BODY)
                .map(FunctionLoweringContext::targetFunction)
                .filter(function -> function.getName().equals(functionName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing executable body function " + functionName));
    }

    /// Lambda bodies lower under their synthesized `_lambda_<n>` shells (`LAMBDA_BODY` contexts),
    /// so the ordinary executable-body lookup cannot see them.
    private static @NotNull LirFunctionDef requireLambdaFunction(
            @NotNull FrontendLoweringContext context,
            @NotNull String functionName
    ) {
        return context.requireFunctionLoweringContexts().stream()
                .filter(candidate -> candidate.kind() == FunctionLoweringContext.Kind.LAMBDA_BODY)
                .map(FunctionLoweringContext::targetFunction)
                .filter(function -> function.getName().equals(functionName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing lambda body function " + functionName));
    }

    private static @NotNull GdType requireVariableType(
            @NotNull LirFunctionDef function,
            @NotNull String variableId
    ) {
        var variable = function.getVariableById(variableId);
        assertNotNull(variable, () -> "Missing variable " + variableId + " in " + function.getName());
        return variable.type();
    }

    private static <T extends LirInstruction> @NotNull T requireOnly(
            @NotNull LirFunctionDef function,
            @NotNull Class<T> type
    ) {
        var matches = allInstructions(function).stream().filter(type::isInstance).map(type::cast).toList();
        assertEquals(1, matches.size(), () -> "Expected exactly one " + type.getSimpleName()
                + " in " + allInstructions(function));
        return matches.getFirst();
    }

    private static int count(@NotNull LirFunctionDef function, @NotNull Class<? extends LirInstruction> type) {
        return (int) allInstructions(function).stream().filter(type::isInstance).count();
    }

    /// Strict forward-scan ordering across the whole flattened instruction stream.
    private static void assertOrdered(
            @NotNull LirFunctionDef function,
            @NotNull LirInstruction first,
            @NotNull LirInstruction second
    ) {
        var instructions = allInstructions(function);
        var firstIndex = instructions.indexOf(first);
        var secondIndex = instructions.indexOf(second);
        assertTrue(firstIndex >= 0 && secondIndex >= 0, () -> "Both instructions must exist in " + instructions);
        assertTrue(firstIndex < secondIndex, () -> "Expected " + first + " before " + second);
    }

    private static @NotNull List<LirInstruction> allInstructions(@NotNull LirFunctionDef function) {
        var instructions = new ArrayList<LirInstruction>();
        for (var block : function) {
            instructions.addAll(block.getInstructions());
        }
        return List.copyOf(instructions);
    }
}
