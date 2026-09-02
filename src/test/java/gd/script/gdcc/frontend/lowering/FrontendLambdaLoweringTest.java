package gd.script.gdcc.frontend.lowering;

import dev.superice.gdparser.frontend.ast.LambdaExpression;
import dev.superice.gdparser.frontend.ast.Node;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.lowering.cfg.FrontendCfgGraph;
import gd.script.gdcc.frontend.lowering.cfg.item.DirectSlotAliasValueItem;
import gd.script.gdcc.frontend.lowering.cfg.item.LambdaConstructItem;
import gd.script.gdcc.frontend.lowering.pass.FrontendLoweringBodyInsnPass;
import gd.script.gdcc.frontend.lowering.pass.FrontendLoweringBuildCfgPass;
import gd.script.gdcc.frontend.lowering.pass.FrontendLoweringClassSkeletonPass;
import gd.script.gdcc.frontend.lowering.pass.FrontendLoweringFunctionPreparationPass;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.FrontendSourceUnit;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.frontend.sema.FrontendAnalysisData;
import gd.script.gdcc.frontend.sema.FrontendLambdaCapturePlan;
import gd.script.gdcc.frontend.sema.FrontendLambdaPlan;
import gd.script.gdcc.frontend.sema.analyzer.FrontendSemanticAnalyzer;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.lir.LirCaptureDef;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirInstruction;
import gd.script.gdcc.lir.insn.AssignInsn;
import gd.script.gdcc.lir.insn.AwaitInsn;
import gd.script.gdcc.lir.insn.CallMethodInsn;
import gd.script.gdcc.lir.insn.ConstructLambdaInsn;
import gd.script.gdcc.lir.insn.LiteralNilInsn;
import gd.script.gdcc.lir.insn.LiteralNodePathInsn;
import gd.script.gdcc.lir.insn.ReturnInsn;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdNodePathType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdVoidType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Lambda shell-synthesis and outer-body construction contract tests.
/// Recorded lambda occurrences lower to `LambdaConstructItem` / `construct_lambda`
/// with enclosing-frame capture operands.
///
/// These tests run the shared `analyze()` pipeline and then drive the lowering passes
/// manually — the same recipe `FrontendLoweringFunctionPreparationPassTest` uses for
/// non-lambda modules. Assertions anchor both directions: published plans materialize
/// hidden `is_lambda` shells with faithful captures, while missing plans or name
/// collisions fail fast instead of silently skipping synthesis.
final class FrontendLambdaLoweringTest {

    /// Nested lambdas synthesize two hidden `is_lambda` shells whose capture lists match the
    /// published plans (the outer layer transit-captures `seed`).
    @Test
    void synthesizesHiddenLambdaShellsForNestedLambdas() throws Exception {
        var prepared = analyzeAndSkeleton("lambda_shell_nested.gd", """
                class_name LambdaShellNested
                extends RefCounted
                
                func ping():
                    var seed := 40
                    var outer := func():
                        var inner := func():
                            return seed + 2
                        return inner
                    return outer
                """);

        new FrontendLoweringFunctionPreparationPass().run(prepared.context());

        var contexts = prepared.context().requireFunctionLoweringContexts();
        assertEquals(1, contexts.stream().filter(context ->
                context.kind() == FunctionLoweringContext.Kind.EXECUTABLE_BODY
        ).count());
        var lambdaContexts = requireLambdaContexts(prepared.context(), 2);
        var lambdaFunctions = lambdaFunctions(requireClass(prepared, "LambdaShellNested"), 2);

        for (var lambdaContext : lambdaContexts) {
            var lambda = assertInstanceOf(LambdaExpression.class, lambdaContext.sourceOwner());
            assertSame(lambda.body(), lambdaContext.loweringRoot());
            var plan = requirePlan(prepared.analysisData(), lambda);
            var shell = lambdaContext.targetFunction();
            assertSame(shell, lambdaFunctions.stream()
                    .filter(function -> function.getName().equals(plan.syntheticName()))
                    .findFirst()
                    .orElseThrow());
            assertLambdaShellShape(shell, plan);
            // Both layers transit-capture `seed` with its declaration-site stabilized type (int).
            assertEquals(1, shell.getCaptureCount());
            var capture = shell.getCapture("seed");
            assertNotNull(capture);
            assertEquals("int", capture.type().getTypeName());
        }
    }

    /// A captureless lambda has no `<captures>` entries, `getCaptureCount() == 0`, keeps the
    /// source parameter shape (typed, no injected `self`), and takes its return type from the
    /// declared annotation.
    @Test
    void synthesizesLambdaShellWithoutCapturesOrSelfParameter() throws Exception {
        var prepared = analyzeAndSkeleton("lambda_shell_plain.gd", """
                class_name LambdaShellPlain
                extends RefCounted
                
                func ping():
                    var cb := func(x: int) -> int:
                        return x + 1
                    return cb
                """);

        new FrontendLoweringFunctionPreparationPass().run(prepared.context());

        var lambdaContext = requireLambdaContexts(prepared.context(), 1).getFirst();
        var lambda = assertInstanceOf(LambdaExpression.class, lambdaContext.sourceOwner());
        var plan = requirePlan(prepared.analysisData(), lambda);
        var shell = lambdaContext.targetFunction();

        assertLambdaShellShape(shell, plan);
        assertEquals(0, shell.getCaptureCount());
        assertTrue(shell.getCaptures().isEmpty());
        assertEquals(1, shell.getParameterCount());
        assertEquals("x", shell.getParameter(0).name());
        assertEquals("int", shell.getParameter(0).type().getTypeName());
        assertNull(shell.getParameter("self"));
        assertEquals("int", shell.getReturnType().getTypeName());
    }

    /// A lambda that uses an enclosing instance method takes `self` as the leading capture,
    /// typed as the enclosing class `GdObjectType`. `setStatic(true)` must not inject a second
    /// `self` parameter.
    @Test
    void synthesizesLambdaShellWithLeadingSelfCapture() throws Exception {
        var prepared = analyzeAndSkeleton("lambda_shell_self.gd", """
                class_name LambdaShellSelf
                extends RefCounted
                
                func helper():
                    return 1
                
                func ping():
                    var cb := func():
                        return helper()
                    return cb
                """);

        new FrontendLoweringFunctionPreparationPass().run(prepared.context());

        var lambdaContext = requireLambdaContexts(prepared.context(), 1).getFirst();
        var lambda = assertInstanceOf(LambdaExpression.class, lambdaContext.sourceOwner());
        var plan = requirePlan(prepared.analysisData(), lambda);
        var shell = lambdaContext.targetFunction();

        assertLambdaShellShape(shell, plan);
        assertTrue(plan.capturesSelf());
        assertEquals(1, shell.getCaptureCount());
        var selfCapture = shell.getCaptureList().getFirst();
        assertEquals("self", selfCapture.getName());
        assertEquals(new GdObjectType("LambdaShellSelf").getTypeName(), selfCapture.getType().getTypeName());
        assertEquals(0, shell.getParameterCount());
        assertNull(shell.getParameter("self"));
    }

    /// A lambda that reaches preparation without a published plan must fail fast, not skip.
    @Test
    void preparationFailsFastWhenLambdaPlanIsMissing() throws Exception {
        var prepared = analyzeAndSkeleton("lambda_missing_plan.gd", """
                class_name LambdaMissingPlan
                extends RefCounted
                
                func ping():
                    var cb := func():
                        return 1
                    return cb
                """);
        var lambda = findNode(prepared.unit().ast(), LambdaExpression.class, _ -> true);
        prepared.analysisData().lambdaPlans().remove(lambda);

        var exception = assertThrows(
                IllegalStateException.class,
                () -> new FrontendLoweringFunctionPreparationPass().run(prepared.context())
        );
        assertTrue(exception.getMessage().contains("lambdaPlans() is missing"), exception::getMessage);
        assertTrue(exception.getMessage().contains("LambdaMissingPlan"), exception::getMessage);
    }

    /// The `_lambda_` prefix is already reserved at skeleton time. If the synthetic name still
    /// collides with an existing function, preparation must fail fast instead of overwriting it.
    @Test
    void preparationFailsFastWhenSyntheticNameIsAlreadyTaken() throws Exception {
        var prepared = analyzeAndSkeleton("lambda_name_collision.gd", """
                class_name LambdaNameCollision
                extends RefCounted
                
                func ping():
                    var cb := func():
                        return 1
                    return cb
                """);
        var lambda = findNode(prepared.unit().ast(), LambdaExpression.class, _ -> true);
        var plan = requirePlan(prepared.analysisData(), lambda);
        var owningClass = requireClass(prepared, "LambdaNameCollision");
        owningClass.addFunction(new LirFunctionDef(plan.syntheticName()));

        var exception = assertThrows(
                IllegalStateException.class,
                () -> new FrontendLoweringFunctionPreparationPass().run(prepared.context())
        );
        assertTrue(exception.getMessage().contains("already declares a function named"), exception::getMessage);
        assertTrue(exception.getMessage().contains(plan.syntheticName()), exception::getMessage);
    }

    /// BuildCfgPass accepts a `LAMBDA_BODY` context whose `sourceOwner` is a `LambdaExpression`
    /// and `loweringRoot` is its `Block`. The body reuses the executable-block CFG build
    /// (capture reads go through the opaque symbol route) and the function stays shell-only
    /// (blocks are created by the body pass).
    ///
    /// Outer-function `LambdaExpression` construction is a separate contract, so this test
    /// only runs the CFG pass on the lambda context.
    @Test
    void buildCfgPublishesExecutableGraphForLambdaBodyContexts() throws Exception {
        var prepared = prepareLambdaOnlyContexts();
        var lambdaContext = requireLambdaContexts(prepared.context(), 1).getFirst();

        new FrontendLoweringBuildCfgPass().run(prepared.context());

        assertTrue(lambdaContext.hasFrontendCfgGraph());
        assertEquals(0, lambdaContext.targetFunction().getBasicBlockCount());
        assertTrue(lambdaContext.targetFunction().getEntryBlockId().isEmpty());
    }

    /// BodyInsnPass folds `LAMBDA_BODY` into the shared `FrontendBodyLoweringSession` branch,
    /// so the lambda body and the synthesized shell share the same capture-variable types.
    @Test
    void bodyInsnMaterializesLambdaBodyThroughSharedSession() throws Exception {
        var prepared = prepareLambdaOnlyContexts();
        var lambdaContext = requireLambdaContexts(prepared.context(), 1).getFirst();
        new FrontendLoweringBuildCfgPass().run(prepared.context());

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var shell = lambdaContext.targetFunction();
        assertTrue(shell.getBasicBlockCount() > 0);
        assertTrue(!shell.getEntryBlockId().isEmpty());
        assertTrue(allInstructions(shell).stream().anyMatch(ReturnInsn.class::isInstance));
        var captureVariable = shell.getVariableById("seed");
        assertNotNull(captureVariable);
        assertEquals("int", captureVariable.type().getTypeName());
    }

    /// An untyped lambda is Variant-returning, so a body without an explicit return must not
    /// emit a value-less terminator (the backend rejects those for non-void functions): the
    /// stop block materializes a Variant nil slot and returns it.
    @Test
    void untypedLambdaWithoutReturnMaterializesReturnNil() throws Exception {
        var prepared = prepareFullPipelineContexts("lambda_implicit_return_nil.gd", """
                class_name LambdaImplicitReturnNil
                extends RefCounted
                
                func ping():
                    var cb := func():
                        pass
                    return cb
                """);

        var shell = lambdaFunctions(requireClass(prepared, "LambdaImplicitReturnNil"), 1).getFirst();
        var nilInsn = requireOnlyInstruction(shell, LiteralNilInsn.class);
        var returnInsn = requireOnlyInstruction(shell, ReturnInsn.class);
        assertAll(
                () -> assertInstanceOf(GdVariantType.class, shell.getReturnType()),
                () -> assertEquals(nilInsn.resultId(), returnInsn.returnValueId()),
                () -> assertInstanceOf(GdVariantType.class, requireVariableType(shell, nilInsn.resultId()))
        );
    }

    /// An explicit bare `return` inside a Variant-returning lambda materializes the same Variant
    /// nil value return instead of a value-less one, matching the type-check rule that allows
    /// bare `return` in Variant callables.
    @Test
    void untypedLambdaExplicitBareReturnMaterializesReturnNil() throws Exception {
        var prepared = prepareFullPipelineContexts("lambda_bare_return_nil.gd", """
                class_name LambdaBareReturnNil
                extends RefCounted
                
                func ping():
                    var cb := func():
                        return
                    return cb
                """);

        var shell = lambdaFunctions(requireClass(prepared, "LambdaBareReturnNil"), 1).getFirst();
        var nilInsn = requireOnlyInstruction(shell, LiteralNilInsn.class);
        var returnInsn = requireOnlyInstruction(shell, ReturnInsn.class);
        assertAll(
                () -> assertInstanceOf(GdVariantType.class, shell.getReturnType()),
                () -> assertEquals(nilInsn.resultId(), returnInsn.returnValueId()),
                () -> assertInstanceOf(GdVariantType.class, requireVariableType(shell, nilInsn.resultId()))
        );
    }

    /// A `-> void` lambda without an explicit return keeps the value-less terminator and must
    /// not grow a nil materialization.
    @Test
    void voidLambdaWithoutReturnKeepsValuelessReturn() throws Exception {
        var prepared = prepareFullPipelineContexts("lambda_void_bare_return.gd", """
                class_name LambdaVoidBareReturn
                extends RefCounted
                
                func ping():
                    var cb := func() -> void:
                        pass
                    return cb
                """);

        var shell = lambdaFunctions(requireClass(prepared, "LambdaVoidBareReturn"), 1).getFirst();
        var returnInsn = requireOnlyInstruction(shell, ReturnInsn.class);
        assertAll(
                () -> assertInstanceOf(GdVoidType.class, shell.getReturnType()),
                () -> assertNull(returnInsn.returnValueId()),
                () -> assertTrue(allInstructions(shell).stream().noneMatch(LiteralNilInsn.class::isInstance))
        );
    }

    /// A captureless lambda produces one outer-body `construct_lambda "_lambda_0"` with no
    /// capture operands. The CFG item publishes no operand value ids and never uses
    /// `DirectSlotAliasValueItem`; the `_lambda_0` shell still keeps its parameters and return.
    @Test
    void outerBodyConstructsCapturelessLambdaValue() throws Exception {
        var prepared = prepareFullPipelineContexts("lambda_construct_plain.gd", """
                class_name LambdaConstructPlain
                extends RefCounted
                
                func ping():
                    var cb := func(x: int):
                        return x
                    return cb
                """);

        var outerContext = requireExecutableContext(prepared.context(), "ping");
        var item = requireSingleLambdaConstructItem(outerContext);
        assertEquals("_lambda_0", item.lambdaName());
        assertTrue(item.captureOperands().isEmpty());
        assertTrue(item.operandValueIds().isEmpty());
        var graph = outerContext.requireFrontendCfgGraph();
        assertTrue(graph.nodeIds().stream()
                .map(graph::requireNode)
                .filter(FrontendCfgGraph.SequenceNode.class::isInstance)
                .flatMap(node -> ((FrontendCfgGraph.SequenceNode) node).items().stream())
                .noneMatch(DirectSlotAliasValueItem.class::isInstance));

        var insn = requireSingleConstructLambdaInsn(outerContext.targetFunction());
        assertEquals("_lambda_0", insn.lambdaName());
        assertTrue(insn.captures().isEmpty());
        assertNotNull(insn.resultId());
        var shell = lambdaFunctions(requireClass(prepared, "LambdaConstructPlain"), 1).getFirst();
        assertEquals(1, shell.getParameterCount());
        assertEquals("x", shell.getParameter(0).name());
        assertTrue(allInstructions(shell).stream().anyMatch(ReturnInsn.class::isInstance));
    }

    /// Capturing the outer `seed` local emits exactly a `$seed` operand. The capture type is
    /// the outer binding's declaration-site type, not the enclosing function's final slot type.
    @Test
    void outerBodyConstructInsnReadsCapturedLocalSlot() throws Exception {
        var prepared = prepareFullPipelineContexts("lambda_construct_capture.gd", """
                class_name LambdaConstructCapture
                extends RefCounted
                
                func ping():
                    var seed := 40
                    var cb := func():
                        return seed + 2
                    return cb
                """);

        var outerContext = requireExecutableContext(prepared.context(), "ping");
        var item = requireSingleLambdaConstructItem(outerContext);
        assertEquals(1, item.captureOperands().size());
        var operand = assertInstanceOf(
                LambdaConstructItem.VariableSlotOperand.class,
                item.captureOperands().getFirst()
        );
        assertEquals("seed", operand.slotId());

        var insn = requireSingleConstructLambdaInsn(outerContext.targetFunction());
        assertEquals(List.of("seed"), captureOperandIds(insn));
        var shell = lambdaFunctions(requireClass(prepared, "LambdaConstructCapture"), 1).getFirst();
        assertEquals("int", shell.getCapture("seed").type().getTypeName());
    }

    /// A recorded lambda body containing a real await marks the lambda owner by AST
    /// identity during sema; the function-preparation pass bridges the marking onto the freshly
    /// synthesized shell — both the `isCoroutine` LIR attribute (backend surface) and the
    /// `coroutineFunctions` membership (body-lowering marker check). Attribution is pinned in
    /// both directions: the enclosing named function stays non-coroutine, and the outer body's
    /// `construct_lambda` capture surface is unchanged.
    @Test
    void lambdaBodyAwaitLowersToCoroutineWithCapture() throws Exception {
        var prepared = prepareFullPipelineContexts("lambda_body_await.gd", """
                class_name LambdaBodyAwait
                extends Node
                
                signal pinged
                
                func watch():
                    var seed := 40
                    var cb := func():
                        var resumed = await pinged
                        return seed + 2
                    return cb
                """);

        var lambdaContext = requireLambdaContexts(prepared.context(), 1).getFirst();
        var lambda = assertInstanceOf(LambdaExpression.class, lambdaContext.sourceOwner());
        var plan = requirePlan(prepared.analysisData(), lambda);
        var shell = lambdaContext.targetFunction();

        // The bridge keeps both coroutine facts in sync on the lambda shell only. (No
        // `assertLambdaShellShape` here: the full pipeline already ran the body pass, so the
        // shell-only block-count contract no longer applies.)
        assertEquals(plan.syntheticName(), shell.getName());
        assertTrue(shell.isLambda());
        assertTrue(shell.isCoroutine());
        assertTrue(prepared.analysisData().coroutineFunctions().contains(shell));
        var outerContext = requireExecutableContext(prepared.context(), "watch");
        assertFalse(outerContext.targetFunction().isCoroutine());

        // The capture plan survives coroutine marking: awaiting the class signal `pinged` pulls
        // in the leading `self` capture, then the source-level `seed` read follows.
        assertEquals(
                List.of("self", "seed"),
                shell.getCaptureList().stream().map(capture -> capture.getName()).toList()
        );

        // The outer body still constructs the Callable with plain `$self`/`$seed` operands.
        var constructInsn = requireSingleConstructLambdaInsn(outerContext.targetFunction());
        assertEquals(List.of("self", "seed"), captureOperandIds(constructInsn));

        // The await lowered into the shell body exactly once.
        var awaits = allInstructions(shell).stream().filter(AwaitInsn.class::isInstance).toList();
        assertEquals(1, awaits.size());
    }

    /// Regression anchor for divergent same-name match binds plus lambda capture: the array
    /// pattern's nested bind (always Variant) and the top-level bind (refined to the subject type)
    /// share one name-keyed storage slot, while the capture plan freezes the section-local
    /// declaration-site type. Whatever storage/conversion strategy lowering picks, the construct
    /// operand's resolved variable type must stay assignable to the shell capture type — the exact
    /// boundary the C backend enforces on `construct_lambda`.
    @Test
    void outerBodyConstructInsnCaptureOperandStaysAssignableForDivergentSharedMatchBind() throws Exception {
        var prepared = prepareFullPipelineContexts("lambda_match_bind_capture.gd", """
                class_name LambdaMatchBindCapture
                extends RefCounted
                
                func ping(value: Array) -> Variant:
                    match value:
                        [var bound]:
                            return bound
                        var bound:
                            var cb := func():
                                return bound
                            return cb
                """);

        var outerContext = requireExecutableContext(prepared.context(), "ping");
        var insn = requireSingleConstructLambdaInsn(outerContext.targetFunction());
        var shell = lambdaFunctions(requireClass(prepared, "LambdaMatchBindCapture"), 1).getFirst();
        var shellCapture = shell.getCapture("bound");
        assertNotNull(shellCapture);
        var operand = assertInstanceOf(
                LirInstruction.VariableOperand.class,
                insn.captures().getFirst()
        );
        var operandVariable = outerContext.targetFunction().getVariableById(operand.id());
        assertNotNull(operandVariable);
        assertTrue(
                new ClassRegistry(ExtensionApiLoader.loadDefault()).checkAssignable(
                        operandVariable.type(),
                        shellCapture.type()
                ),
                () -> "capture operand variable type '" + operandVariable.type().getTypeName()
                        + "' is not assignable to shell capture type '" + shellCapture.type().getTypeName() + "'"
        );
    }

    /// Cross-match same-name divergence surfaces only at CFG finishBuild, after lambda plans froze
    /// their capture types. A lambda capturing such a bind with a non-Variant entry must fail fast
    /// at the unify step instead of reaching the backend's `construct_lambda` boundary.
    @Test
    void buildCfgFailsFastWhenLambdaCapturesCrossMatchDivergentBind() throws Exception {
        var prepared = analyzeAndSkeleton("lambda_cross_match_bind_capture.gd", """
                class_name LambdaCrossMatchBindCapture
                extends RefCounted
                
                func ping(value: Array, payload):
                    match value:
                        var bound:
                            var cb := func():
                                return bound
                    match payload:
                        [var bound]:
                            pass
                """);
        new FrontendLoweringFunctionPreparationPass().run(prepared.context());

        var exception = assertThrows(
                IllegalStateException.class,
                () -> new FrontendLoweringBuildCfgPass().run(prepared.context())
        );
        assertTrue(exception.getMessage().contains("captures match bind 'bound'"), exception::getMessage);
    }

    /// A lambda that uses an enclosing instance member constructs with a leading `self`
    /// capture. The item uses the dedicated `SelfSlotOperand.SELF_SLOT` descriptor (never a
    /// fabricated SELF identifier) and the insn operand is `$self`.
    @Test
    void outerBodyConstructInsnUsesSelfSlotForSelfCapture() throws Exception {
        var prepared = prepareFullPipelineContexts("lambda_construct_self.gd", """
                class_name LambdaConstructSelf
                extends RefCounted
                
                func helper():
                    return 1
                
                func ping():
                    var cb := func():
                        return helper()
                    return cb
                """);

        var outerContext = requireExecutableContext(prepared.context(), "ping");
        var item = requireSingleLambdaConstructItem(outerContext);
        assertEquals(1, item.captureOperands().size());
        assertSame(LambdaConstructItem.SelfSlotOperand.SELF_SLOT, item.captureOperands().getFirst());

        var insn = requireSingleConstructLambdaInsn(outerContext.targetFunction());
        assertEquals(List.of("self"), captureOperandIds(insn));
        var shell = lambdaFunctions(requireClass(prepared, "LambdaConstructSelf"), 1).getFirst();
        assertEquals("self", shell.getCaptureList().getFirst().getName());
    }

    /// A nested lambda reads its capture operand from the enclosing lambda's CAPTURE slot
    /// by name: the inner `construct_lambda` lives in the outer lambda body and the operand
    /// name matches the outer shell capture.
    @Test
    void nestedLambdaConstructInsnReadsEnclosingCaptureSlot() throws Exception {
        var prepared = prepareFullPipelineContexts("lambda_construct_nested.gd", """
                class_name LambdaConstructNested
                extends RefCounted
                
                func ping():
                    var seed := 40
                    var outer := func():
                        var inner := func():
                            return seed + 2
                        return inner
                    return outer
                """);

        var outerContext = requireExecutableContext(prepared.context(), "ping");
        var outerConstruct = requireSingleConstructLambdaInsn(outerContext.targetFunction());
        assertEquals("_lambda_0", outerConstruct.lambdaName());
        assertEquals(List.of("seed"), captureOperandIds(outerConstruct));

        var lambdaContexts = requireLambdaContexts(prepared.context(), 2);
        var outerLambdaContext = lambdaContexts.stream()
                .filter(context -> context.targetFunction().getName().equals("_lambda_0"))
                .findFirst()
                .orElseThrow();
        var innerConstruct = requireSingleConstructLambdaInsn(outerLambdaContext.targetFunction());
        assertEquals("_lambda_1", innerConstruct.lambdaName());
        assertEquals(List.of("seed"), captureOperandIds(innerConstruct));
    }

    /// A get-node (`$`) inside a lambda lowers through the same three-insn desugar as a plain
    /// function body; the only difference is that `self` resolves to the leading capture's
    /// function variable instead of the executable `self` parameter.
    @Test
    void getNodeInsideLambdaLowersThroughCapturedSelfSlot() throws Exception {
        var prepared = prepareFullPipelineContexts("lambda_get_node.gd", """
                class_name LambdaGetNode
                extends Node
                
                func probe():
                    var cb := func():
                        return $Camera3D
                    return cb
                """);

        var lambdaContext = requireLambdaContexts(prepared.context(), 1).getFirst();
        var lambda = assertInstanceOf(LambdaExpression.class, lambdaContext.sourceOwner());
        var plan = requirePlan(prepared.analysisData(), lambda);
        var shell = lambdaContext.targetFunction();

        // Capture discovery counted the get-node as an implicit self use: leading `self` typed
        // as the enclosing class, surfaced as the outer construct's leading `$self` operand.
        assertTrue(plan.capturesSelf());
        assertEquals(List.of("self"), shell.getCaptureList().stream().map(capture -> capture.getName()).toList());
        var outerContext = requireExecutableContext(prepared.context(), "probe");
        var item = requireSingleLambdaConstructItem(outerContext);
        assertSame(LambdaConstructItem.SelfSlotOperand.SELF_SLOT, item.captureOperands().getFirst());
        assertEquals(List.of("self"), captureOperandIds(requireSingleConstructLambdaInsn(outerContext.targetFunction())));

        // `requireSelfSlot` hits the captured function variable, not an executable self parameter.
        var selfVariable = shell.getVariableById("self");
        assertNotNull(selfVariable);
        assertEquals(new GdObjectType("LambdaGetNode"), selfVariable.type());
        var literalInsn = requireOnlyInstruction(shell, LiteralNodePathInsn.class);
        var assignInsn = requireOnlyInstruction(shell, AssignInsn.class);
        var callInsn = requireOnlyInstruction(shell, CallMethodInsn.class);

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors(), prepared.diagnostics().snapshot()::toString),
                () -> assertEquals("Camera3D", literalInsn.value()),
                () -> assertEquals(
                        GdNodePathType.NODE_PATH,
                        requireVariableType(shell, literalInsn.resultId())
                ),
                // The receiver temp is statically typed `Node` even though the captured `self`
                // slot carries the enclosing class type: this pins the ENGINE `Node.get_node`
                // route exactly like the plain function-body desugar.
                () -> assertEquals("self", assignInsn.sourceId()),
                () -> assertEquals(new GdObjectType("Node"), requireVariableType(shell, assignInsn.resultId())),
                () -> assertEquals("get_node", callInsn.methodName()),
                () -> assertEquals(assignInsn.resultId(), callInsn.objectId()),
                () -> assertEquals(new GdObjectType("Node"), requireVariableType(shell, callInsn.resultId()))
        );
    }

    /// Both levels of a nested get-node lambda carry exactly the leading self capture; the inner
    /// `construct_lambda` reads the outer shell's captured `self` slot by name.
    @Test
    void nestedGetNodeLambdaKeepsLeadingSelfOnBothLevels() throws Exception {
        var prepared = prepareFullPipelineContexts("lambda_get_node_nested.gd", """
                class_name LambdaGetNodeNested
                extends Node
                
                func probe():
                    var outer := func():
                        var inner := func():
                            return $Camera3D
                        return inner
                    return outer
                """);

        var outerContext = requireExecutableContext(prepared.context(), "probe");
        var outerConstruct = requireSingleConstructLambdaInsn(outerContext.targetFunction());
        assertEquals("_lambda_0", outerConstruct.lambdaName());
        assertEquals(List.of("self"), captureOperandIds(outerConstruct));

        var lambdaContexts = requireLambdaContexts(prepared.context(), 2);
        var outerLambdaContext = lambdaContexts.stream()
                .filter(context -> context.targetFunction().getName().equals("_lambda_0"))
                .findFirst()
                .orElseThrow();
        var innerLambdaContext = lambdaContexts.stream()
                .filter(context -> context.targetFunction().getName().equals("_lambda_1"))
                .findFirst()
                .orElseThrow();
        for (var shell : List.of(outerLambdaContext.targetFunction(), innerLambdaContext.targetFunction())) {
            assertEquals(List.of("self"), shell.getCaptureList().stream().map(capture -> capture.getName()).toList());
        }
        var innerConstruct = requireSingleConstructLambdaInsn(outerLambdaContext.targetFunction());
        assertEquals("_lambda_1", innerConstruct.lambdaName());
        assertEquals(List.of("self"), captureOperandIds(innerConstruct));

        // The innermost body still lowers the get-node triple against its captured self slot.
        var innerShell = innerLambdaContext.targetFunction();
        var assignInsn = requireOnlyInstruction(innerShell, AssignInsn.class);
        var callInsn = requireOnlyInstruction(innerShell, CallMethodInsn.class);
        assertEquals("self", assignInsn.sourceId());
        assertEquals("get_node", callInsn.methodName());
        assertEquals(assignInsn.resultId(), callInsn.objectId());
    }

    /// A coroutine lambda (body contains `await`) reads the same local `self` slot for get-node;
    /// the coroutine frame copy machinery lives in the backend, so the frontend LIR keeps the
    /// plain sync shape.
    @Test
    void coroutineGetNodeLambdaReadsCapturedSelfSlotLikeSyncForm() throws Exception {
        var prepared = prepareFullPipelineContexts("lambda_get_node_coroutine.gd", """
                class_name LambdaGetNodeCoroutine
                extends Node
                
                signal pinged
                
                func watch():
                    var cb := func():
                        var resumed = await pinged
                        return $Camera3D
                    return cb
                """);

        var lambdaContext = requireLambdaContexts(prepared.context(), 1).getFirst();
        var shell = lambdaContext.targetFunction();
        assertTrue(shell.isCoroutine());
        assertEquals(List.of("self"), shell.getCaptureList().stream().map(capture -> capture.getName()).toList());
        var awaits = allInstructions(shell).stream().filter(AwaitInsn.class::isInstance).toList();
        assertEquals(1, awaits.size());

        var callInsn = allInstructions(shell).stream()
                .filter(CallMethodInsn.class::isInstance)
                .map(CallMethodInsn.class::cast)
                .filter(insn -> insn.methodName().equals("get_node"))
                .findFirst()
                .orElseThrow();
        // The get-node receiver assign still sources the plain `self` slot; other assigns the
        // await machinery may emit must not be confused with it, so match by the call receiver.
        var receiverAssign = allInstructions(shell).stream()
                .filter(AssignInsn.class::isInstance)
                .map(AssignInsn.class::cast)
                .filter(insn -> insn.resultId().equals(callInsn.objectId()))
                .findFirst()
                .orElseThrow();
        assertEquals("self", receiverAssign.sourceId());
        assertEquals(new GdObjectType("Node"), requireVariableType(shell, receiverAssign.resultId()));
    }

    /// A script override of `get_node(NodePath)` stays invisible from the lambda desugar: the
    /// receiver temp is statically typed `Node`, so backend resolution starts at the engine
    /// class (same pinning mechanism as the plain function body).
    @Test
    void getNodeInsideLambdaPinsEngineNodeAboveScriptOverride() throws Exception {
        var prepared = prepareFullPipelineContexts("lambda_get_node_override.gd", """
                class_name LambdaGetNodeOverride
                extends Node
                
                func get_node(path: NodePath) -> Node:
                    return self
                
                func probe():
                    var cb := func():
                        return $Camera3D
                    return cb
                """);

        var lambdaContext = requireLambdaContexts(prepared.context(), 1).getFirst();
        var shell = lambdaContext.targetFunction();
        var callInsn = allInstructions(shell).stream()
                .filter(CallMethodInsn.class::isInstance)
                .map(CallMethodInsn.class::cast)
                .filter(insn -> insn.methodName().equals("get_node"))
                .findFirst()
                .orElseThrow();
        assertNotEquals("self", callInsn.objectId());
        assertEquals(new GdObjectType("Node"), requireVariableType(shell, callInsn.objectId()));
    }

    /// If a published lambda plan loses its leading self capture while the body still contains a
    /// get-node (a publish-order protocol violation), body lowering must fail fast at
    /// `requireSelfSlot` instead of emitting a receiver-less call.
    @Test
    void bodyInsnFailsFastWhenLambdaPlanLacksSelfCaptureForGetNode() throws Exception {
        var prepared = analyzeAndSkeleton("lambda_get_node_missing_self.gd", """
                class_name LambdaGetNodeMissingSelf
                extends Node
                
                func probe():
                    var cb := func():
                        return $Camera3D
                    return cb
                """);
        var lambda = findNode(prepared.unit().ast(), LambdaExpression.class, _ -> true);
        var plan = requirePlan(prepared.analysisData(), lambda);
        assertTrue(plan.capturesSelf());
        // Strip the leading self capture after analysis to simulate the protocol violation.
        prepared.analysisData().lambdaPlans().remove(lambda);
        prepared.analysisData().lambdaPlans().put(lambda, new FrontendLambdaPlan(
                plan.lambda(),
                plan.syntheticName(),
                FrontendLambdaCapturePlan.of(List.of()),
                plan.returnType(),
                plan.enclosingCallable(),
                plan.owningClassCanonicalName()
        ));

        new FrontendLoweringFunctionPreparationPass().run(prepared.context());
        new FrontendLoweringBuildCfgPass().run(prepared.context());
        var exception = assertThrows(
                IllegalStateException.class,
                () -> new FrontendLoweringBodyInsnPass().run(prepared.context())
        );
        assertTrue(exception.getMessage().contains("requires an implicit self receiver slot"), exception::getMessage);
    }

    /// A return-position lambda threads the preferred result value id onto the construct
    /// item. The stop node's returnValueId matches the item's resultValueId, and the insn
    /// result slot is the corresponding `cfg_tmp_*`.
    @Test
    void returnPositionLambdaConstructThreadsPreferredResultId() throws Exception {
        var prepared = prepareFullPipelineContexts("lambda_construct_return.gd", """
                class_name LambdaConstructReturn
                extends RefCounted
                
                func ping():
                    return func(x: int):
                        return x
                """);

        var outerContext = requireExecutableContext(prepared.context(), "ping");
        var item = requireSingleLambdaConstructItem(outerContext);
        var graph = outerContext.requireFrontendCfgGraph();
        var stopNode = graph.nodeIds().stream()
                .map(graph::requireNode)
                .filter(FrontendCfgGraph.StopNode.class::isInstance)
                .map(FrontendCfgGraph.StopNode.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals(item.resultValueId(), stopNode.returnValueIdOrNull());

        var insn = requireSingleConstructLambdaInsn(outerContext.targetFunction());
        assertEquals(FrontendBodyLoweringSupport.cfgTempSlotId(item.resultValueId()), insn.resultId());
    }

    /// A lowering-ready lambda that reaches CFG construction without a published plan must
    /// fail fast in the builder (the prep pass already has the same gate; this anchors the
    /// builder's own defense).
    @Test
    void buildCfgFailsFastWhenLambdaPlanIsMissing() throws Exception {
        var prepared = analyzeAndSkeleton("lambda_construct_missing_plan.gd", """
                class_name LambdaConstructMissingPlan
                extends RefCounted
                
                func ping():
                    var cb := func():
                        return 1
                    return cb
                """);
        new FrontendLoweringFunctionPreparationPass().run(prepared.context());
        var lambda = findNode(prepared.unit().ast(), LambdaExpression.class, _ -> true);
        prepared.analysisData().lambdaPlans().remove(lambda);

        var exception = assertThrows(
                IllegalStateException.class,
                () -> new FrontendLoweringBuildCfgPass().run(prepared.context())
        );
        assertTrue(exception.getMessage().contains("without a published lambda plan"), exception::getMessage);
    }

    /// If the plan→item and plan→shell capture lists drift (simulated here by appending a
    /// phantom capture to the shell), body lowering must fail fast on the count mismatch
    /// instead of silently emitting a mismatched `construct_lambda`.
    @Test
    void bodyInsnFailsFastWhenItemCaptureCountDivergesFromShell() throws Exception {
        var prepared = analyzeAndSkeleton("lambda_construct_count_mismatch.gd", """
                class_name LambdaConstructCountMismatch
                extends RefCounted
                
                func ping():
                    var seed := 40
                    var cb := func():
                        return seed + 2
                    return cb
                """);
        new FrontendLoweringFunctionPreparationPass().run(prepared.context());
        var shell = lambdaFunctions(requireClass(prepared, "LambdaConstructCountMismatch"), 1).getFirst();
        shell.addCapture(new LirCaptureDef("phantom", GdIntType.INT, shell));
        new FrontendLoweringBuildCfgPass().run(prepared.context());

        var exception = assertThrows(
                IllegalStateException.class,
                () -> new FrontendLoweringBodyInsnPass().run(prepared.context())
        );
        assertTrue(exception.getMessage().contains("capture count mismatch"), exception::getMessage);
        assertTrue(exception.getMessage().contains("_lambda_0"), exception::getMessage);
    }

    /// Unlike `prepareLambdaOnlyContexts`, the full context set stays published so the outer
    /// executable body (lambda construction) and the lambda bodies both flow through the CFG
    /// and body-insn passes.
    private static @NotNull PreparedLambdaModule prepareFullPipelineContexts(
            @NotNull String fileName,
            @NotNull String source
    ) throws Exception {
        var prepared = analyzeAndSkeleton(fileName, source);
        new FrontendLoweringFunctionPreparationPass().run(prepared.context());
        new FrontendLoweringBuildCfgPass().run(prepared.context());
        new FrontendLoweringBodyInsnPass().run(prepared.context());
        return prepared;
    }

    private static @NotNull FunctionLoweringContext requireExecutableContext(
            @NotNull FrontendLoweringContext context,
            @NotNull String functionName
    ) {
        return context.requireFunctionLoweringContexts().stream()
                .filter(functionContext -> functionContext.kind() == FunctionLoweringContext.Kind.EXECUTABLE_BODY)
                .filter(functionContext -> functionContext.targetFunction().getName().equals(functionName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Executable context not found: " + functionName));
    }

    private static @NotNull LambdaConstructItem requireSingleLambdaConstructItem(
            @NotNull FunctionLoweringContext functionContext
    ) {
        var graph = functionContext.requireFrontendCfgGraph();
        var items = graph.nodeIds().stream()
                .map(graph::requireNode)
                .filter(FrontendCfgGraph.SequenceNode.class::isInstance)
                .flatMap(node -> ((FrontendCfgGraph.SequenceNode) node).items().stream())
                .filter(LambdaConstructItem.class::isInstance)
                .map(LambdaConstructItem.class::cast)
                .toList();
        assertEquals(1, items.size());
        return items.getFirst();
    }

    private static @NotNull ConstructLambdaInsn requireSingleConstructLambdaInsn(
            @NotNull LirFunctionDef function
    ) {
        var insns = allInstructions(function).stream()
                .filter(ConstructLambdaInsn.class::isInstance)
                .map(ConstructLambdaInsn.class::cast)
                .toList();
        assertEquals(1, insns.size());
        return insns.getFirst();
    }

    private static @NotNull List<String> captureOperandIds(@NotNull ConstructLambdaInsn insn) {
        return insn.captures().stream()
                .map(operand -> assertInstanceOf(LirInstruction.VariableOperand.class, operand).id())
                .toList();
    }

    private static <T extends LirInstruction> @NotNull T requireOnlyInstruction(
            @NotNull LirFunctionDef function,
            @NotNull Class<T> insnType
    ) {
        var matches = allInstructions(function).stream()
                .filter(insnType::isInstance)
                .map(insnType::cast)
                .toList();
        assertEquals(1, matches.size(), () -> "Expected exactly one " + insnType.getSimpleName());
        return matches.getFirst();
    }

    private static @NotNull GdType requireVariableType(
            @NotNull LirFunctionDef function,
            @Nullable String variableId
    ) {
        var variable = function.getVariableById(Objects.requireNonNull(variableId, "variableId must not be null"));
        assertNotNull(variable, () -> "Expected lowered variable to exist: " + variableId);
        return variable.type();
    }

    /// Runs the compile-ready CFG fixture through analysis/skeleton/preparation, then re-publishes
    /// only the lambda contexts so the outer body (still phase-F territory) never reaches the CFG
    /// pass.
    private static @NotNull PreparedLambdaModule prepareLambdaOnlyContexts() throws Exception {
        var prepared = analyzeAndSkeleton("lambda_cfg_capture.gd", """
                class_name LambdaCfgCapture
                extends RefCounted
                
                func ping():
                    var seed := 40
                    var cb := func():
                        return seed + 2
                    return cb
                """);
        new FrontendLoweringFunctionPreparationPass().run(prepared.context());
        prepared.context().publishFunctionLoweringContexts(
                requireLambdaContexts(prepared.context(), 1)
        );
        return prepared;
    }

    private static void assertLambdaShellShape(
            @NotNull LirFunctionDef shell,
            @NotNull FrontendLambdaPlan plan
    ) {
        assertEquals(plan.syntheticName(), shell.getName());
        assertTrue(shell.isLambda());
        assertTrue(shell.isHidden());
        assertTrue(shell.isStatic());
        // Shell-only contract: blocks and entry metadata belong to the body pass.
        assertEquals(0, shell.getBasicBlockCount());
        assertTrue(shell.getEntryBlockId().isEmpty());
        // Capture order and types must mirror the frozen plan list exactly.
        assertEquals(plan.captures().size(), shell.getCaptureCount());
        var captureList = shell.getCaptureList();
        for (var index = 0; index < plan.captures().size(); index++) {
            assertEquals(plan.captures().get(index).name(), captureList.get(index).getName());
            assertEquals(
                    plan.captures().get(index).type().getTypeName(),
                    captureList.get(index).getType().getTypeName()
            );
        }
    }

    private static @NotNull List<FunctionLoweringContext> requireLambdaContexts(
            @NotNull FrontendLoweringContext context,
            int expectedCount
    ) {
        var lambdaContexts = context.requireFunctionLoweringContexts().stream()
                .filter(functionContext -> functionContext.kind() == FunctionLoweringContext.Kind.LAMBDA_BODY)
                .toList();
        assertEquals(expectedCount, lambdaContexts.size());
        return lambdaContexts;
    }

    private static @NotNull List<LirFunctionDef> lambdaFunctions(
            @NotNull LirClassDef classDef,
            int expectedCount
    ) {
        var lambdaFunctions = classDef.getFunctions().stream().filter(LirFunctionDef::isLambda).toList();
        assertEquals(expectedCount, lambdaFunctions.size());
        return lambdaFunctions;
    }

    private static @NotNull FrontendLambdaPlan requirePlan(
            @NotNull FrontendAnalysisData analysisData,
            @NotNull LambdaExpression lambda
    ) {
        var plan = analysisData.lambdaPlans().get(lambda);
        assertNotNull(plan);
        return plan;
    }

    private static @NotNull LirClassDef requireClass(
            @NotNull PreparedLambdaModule prepared,
            @NotNull String className
    ) {
        return prepared.context().requireLirModule().getClassDefs().stream()
                .filter(classDef -> classDef.getName().equals(className))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Class not found: " + className));
    }

    private static @NotNull List<LirInstruction> allInstructions(@NotNull LirFunctionDef function) {
        var instructions = new ArrayList<LirInstruction>();
        for (var block : function) {
            instructions.addAll(block.getInstructions());
        }
        return instructions;
    }

    /// Lambdas never survive `analyzeForCompile` before phase I, so the lowering pipeline is fed
    /// from the shared semantic analysis directly, mirroring the production pass order afterwards.
    private static @NotNull PreparedLambdaModule analyzeAndSkeleton(
            @NotNull String fileName,
            @NotNull String source
    ) throws Exception {
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", fileName), source, diagnostics);
        assertTrue(diagnostics.isEmpty(), () -> "Unexpected parse diagnostics: " + diagnostics.snapshot());
        var module = new FrontendModule("test_module", List.of(unit));
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var analysisData = new FrontendSemanticAnalyzer().analyze(module, registry, diagnostics);
        assertTrue(diagnostics.isEmpty(), () -> "Unexpected sema diagnostics: " + diagnostics.snapshot());
        var context = new FrontendLoweringContext(module, registry, diagnostics);
        context.publishAnalysisData(analysisData);
        new FrontendLoweringClassSkeletonPass().run(context);
        return new PreparedLambdaModule(unit, module, analysisData, context, diagnostics);
    }

    private static <T extends Node> @NotNull T findNode(
            @NotNull Node root,
            @NotNull Class<T> nodeType,
            @NotNull Predicate<T> predicate
    ) {
        var matches = new ArrayList<T>();
        collectMatchingNodes(root, nodeType, predicate, matches);
        return matches.stream()
                .findFirst()
                .orElseThrow(() -> new AssertionError("Node not found: " + nodeType.getSimpleName()));
    }

    private static <T extends Node> void collectMatchingNodes(
            @NotNull Node node,
            @NotNull Class<T> nodeType,
            @NotNull Predicate<T> predicate,
            @NotNull List<T> matches
    ) {
        if (nodeType.isInstance(node) && predicate.test(nodeType.cast(node))) {
            matches.add(nodeType.cast(node));
        }
        for (var child : node.getChildren()) {
            collectMatchingNodes(child, nodeType, predicate, matches);
        }
    }

    private record PreparedLambdaModule(
            @NotNull FrontendSourceUnit unit,
            @NotNull FrontendModule module,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull FrontendLoweringContext context,
            @NotNull DiagnosticManager diagnostics
    ) {
    }
}
