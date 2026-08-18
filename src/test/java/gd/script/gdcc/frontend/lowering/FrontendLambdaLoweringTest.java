package gd.script.gdcc.frontend.lowering;

import dev.superice.gdparser.frontend.ast.LambdaExpression;
import dev.superice.gdparser.frontend.ast.Node;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
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
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirInstruction;
import gd.script.gdcc.lir.insn.ReturnInsn;
import gd.script.gdcc.scope.ClassRegistry;
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
