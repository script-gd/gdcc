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
import gd.script.gdcc.frontend.sema.FrontendLambdaPlan;
import gd.script.gdcc.frontend.sema.analyzer.FrontendSemanticAnalyzer;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.lir.LirCaptureDef;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirInstruction;
import gd.script.gdcc.lir.insn.ConstructLambdaInsn;
import gd.script.gdcc.lir.insn.ReturnInsn;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdObjectType;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Phase E lambda shell-synthesis contract tests (frontend_lambda_plan.md §阶段E / §3.7).
/// Phase F adds the outer-body construction contract (§阶段F): recorded lambda occurrences
/// lower to `LambdaConstructItem` / `construct_lambda` with enclosing-frame capture operands.
///
/// The compile gate still blocks lambdas in `analyzeForCompile` until phase I, so these tests run
/// the shared `analyze()` pipeline and then drive the lowering passes manually — the same recipe
/// `FrontendLoweringFunctionPreparationPassTest` uses for non-lambda modules. Assertions anchor both
/// directions: published plans materialize hidden `is_lambda` shells with faithful captures, while
/// missing plans or name collisions fail fast instead of silently skipping synthesis.
final class FrontendLambdaLoweringTest {

    /// 验收 happy path：一个含两层嵌套 lambda 的函数在 class 上得到两个 hidden `is_lambda` 函数，
    /// capture 列表与已发布 plan 一致（外层按 §3.4 规则 5/9 传递 `seed`）。
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

    /// 验收 happy path：无 capture 的 lambda 没有 `<captures>` 条目，`getCaptureCount() == 0`，
    /// 参数表保持源码形状（含类型、无注入 `self`），返回类型来自声明标注。
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

    /// 验收 happy path（§3.5）：引用外层实例方法的 lambda 把 `self` 作为 leading capture，类型为
    /// enclosing class 的 `GdObjectType`；`setStatic(true)` 保证不注入第二份 `self` 参数。
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

    /// 验收 negative path：进入 preparation 的 lambda 缺已发布 plan → fail-fast，不静默跳过。
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

    /// 验收 negative path 的防御侧：`_lambda_` 前缀在 skeleton 阶段已被保留名规则拦截，若合成名仍
    /// 撞上既有函数则属于不变量破坏，preparation 必须 fail-fast 而不是覆盖既有函数。
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

    /// 阶段 E 接线：BuildCfgPass 接受 `sourceOwner instanceof LambdaExpression` +
    /// `loweringRoot instanceof Block` 的 LAMBDA_BODY 上下文，body 复用 executable-block CFG 构建
    /// （capture 读取经 opaque 符号路由），且函数仍保持 shell-only（块由 body pass 创建）。
    ///
    /// 注意：外层函数 body 的 LambdaExpression 表达式 lowering 属于阶段 F，因此这里只对 lambda
    /// 上下文运行 CFG pass。
    @Test
    void buildCfgPublishesExecutableGraphForLambdaBodyContexts() throws Exception {
        var prepared = prepareLambdaOnlyContexts();
        var lambdaContext = requireLambdaContexts(prepared.context(), 1).getFirst();

        new FrontendLoweringBuildCfgPass().run(prepared.context());

        assertTrue(lambdaContext.hasFrontendCfgGraph());
        assertEquals(0, lambdaContext.targetFunction().getBasicBlockCount());
        assertTrue(lambdaContext.targetFunction().getEntryBlockId().isEmpty());
    }

    /// 阶段 E 接线：BodyInsnPass 把 LAMBDA_BODY 并入共享 `FrontendBodyLoweringSession` 分支，
    /// lambda body 与合成 shell 的变量面（capture 变量类型与 plan 同源）真实落地。
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

    /// 阶段 F 验收 happy path：无 capture 的 lambda 在外层 body 产生一条
    /// `construct_lambda "_lambda_0"`（无 capture operand），且 CFG item 不发布任何
    /// operand value id、绝不走 `DirectSlotAliasValueItem`；`_lambda_0` shell 仍带参数与 return。
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

    /// 阶段 F 验收 happy path：捕获外层 `seed` 时 insn 恰好携带 `$seed` operand；capture 类型
    /// 仍是外层绑定的声明处类型（§3.4），而不是外层函数末尾的 slot 类型。
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

    /// 阶段 F 验收 happy path（§3.5）：使用外层实例成员的 lambda 以 leading `self` capture
    /// 构造，item 侧是专用 `SelfSlotOperand.SELF_SLOT` descriptor（而非伪造的 SELF 标识符
    /// 读取），insn 侧对应 `$self` operand。
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

    /// 阶段 F 验收 happy path：嵌套 lambda 的 capture operand 从外层 lambda 的 CAPTURE slot
    /// 按名读取——内层 `construct_lambda` 出现在外层 lambda body 中且 operand 名与外层
    /// shell 的 capture 名一致。
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

    /// 阶段 F 验收 happy path：return 位置的 lambda 把 preferred result value id 直接穿到
    /// construct item 上，stop node 的 returnValueId 与 item 的 resultValueId 一致，insn 的
    /// result slot 就是该 value id 对应的 `cfg_tmp_*`。
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

    /// 阶段 F 验收 negative path：进入 CFG 构建的 lowering-ready lambda 缺已发布 plan 时
    /// builder fail-fast（prep pass 在更早处已有同类闸门，这里锚定 builder 自身的防御）。
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

    /// 阶段 F 验收 negative path：plan→item 与 plan→shell 任一漂移（此处通过给 shell 手工
    /// 追加 phantom capture 模拟）都会让 body lowering 在 item capture 数与 shell capture 数
    /// 不一致时 fail-fast，而不是静默发射不匹配的 `construct_lambda`。
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

    /// Phase F pipeline: unlike `prepareLambdaOnlyContexts`, the full context set stays published
    /// so the outer executable body (lambda construction) and the lambda bodies both flow through
    /// the CFG and body-insn passes.
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
