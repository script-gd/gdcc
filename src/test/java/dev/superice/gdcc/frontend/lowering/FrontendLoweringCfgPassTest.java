package dev.superice.gdcc.frontend.lowering;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.lowering.pass.FrontendLoweringAnalysisPass;
import dev.superice.gdcc.frontend.lowering.pass.FrontendLoweringCfgPass;
import dev.superice.gdcc.frontend.lowering.pass.FrontendLoweringClassSkeletonPass;
import dev.superice.gdcc.frontend.lowering.pass.FrontendLoweringFunctionPreparationPass;
import dev.superice.gdcc.frontend.parse.FrontendModule;
import dev.superice.gdcc.frontend.parse.GdScriptParserService;
import dev.superice.gdcc.frontend.sema.FrontendAnalysisData;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.lir.LirBasicBlock;
import dev.superice.gdcc.lir.LirModule;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.IfStatement;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.WhileStatement;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendLoweringCfgPassTest {
    @Test
    void runPublishesMetadataOnlyCfgSkeletonBundlesForNestedControlFlow() throws Exception {
        var prepared = prepareControlFlowContext();

        new FrontendLoweringCfgPass().run(prepared.context());

        var contexts = prepared.context().requireFunctionLoweringContexts();
        var executableContext = requireContext(
                contexts,
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeCfgPassShape",
                "ping"
        );
        var propertyContext = requireContext(
                contexts,
                FunctionLoweringContext.Kind.PROPERTY_INIT,
                "RuntimeCfgPassShape",
                "_field_init_ready_value"
        );
        var sourceFile = prepared.module().units().getFirst().ast();
        var pingFunction = requireFunctionDeclaration(sourceFile, "ping");
        var rootBlock = pingFunction.body();
        var outerIf = assertInstanceOf(IfStatement.class, rootBlock.statements().getFirst());
        var outerWhile = assertInstanceOf(WhileStatement.class, outerIf.body().statements().getFirst());
        var nestedIf = assertInstanceOf(IfStatement.class, outerWhile.body().statements().getFirst());
        var outerElif = outerIf.elifClauses().getFirst();
        var trailingWhile = assertInstanceOf(WhileStatement.class, rootBlock.statements().getLast());

        var rootBlocks = assertInstanceOf(
                FunctionLoweringContext.BlockCfgNodeBlocks.class,
                executableContext.requireCfgNodeBlocks(rootBlock)
        );
        var outerIfBlocks = assertInstanceOf(
                FunctionLoweringContext.IfCfgNodeBlocks.class,
                executableContext.requireCfgNodeBlocks(outerIf)
        );
        var outerElifBlocks = assertInstanceOf(
                FunctionLoweringContext.ElifCfgNodeBlocks.class,
                executableContext.requireCfgNodeBlocks(outerElif)
        );
        var outerWhileBlocks = assertInstanceOf(
                FunctionLoweringContext.WhileCfgNodeBlocks.class,
                executableContext.requireCfgNodeBlocks(outerWhile)
        );
        var nestedIfBlocks = assertInstanceOf(
                FunctionLoweringContext.IfCfgNodeBlocks.class,
                executableContext.requireCfgNodeBlocks(nestedIf)
        );
        var trailingWhileBlocks = assertInstanceOf(
                FunctionLoweringContext.WhileCfgNodeBlocks.class,
                executableContext.requireCfgNodeBlocks(trailingWhile)
        );

        assertAll(
                () -> assertSame(prepared.context().requireAnalysisData(), executableContext.analysisData()),
                () -> assertEquals(List.of("entry"), blockIds(rootBlocks.blocks())),
                () -> assertEquals("if_then_0", outerIfBlocks.thenEntry().id()),
                () -> assertEquals("if_false_0", outerIfBlocks.elseOrNextClauseEntry().id()),
                () -> assertEquals("if_merge_0", outerIfBlocks.merge().id()),
                () -> assertEquals(
                        List.of("if_then_0", "if_false_0", "if_merge_0"),
                        blockIds(outerIfBlocks.blocks())
                ),
                () -> assertEquals("elif_body_0", outerElifBlocks.bodyEntry().id()),
                () -> assertEquals("elif_false_0", outerElifBlocks.nextClauseOrMerge().id()),
                () -> assertEquals(List.of("elif_body_0", "elif_false_0"), blockIds(outerElifBlocks.blocks())),
                () -> assertEquals("while_cond_0", outerWhileBlocks.conditionEntry().id()),
                () -> assertEquals("while_body_0", outerWhileBlocks.bodyEntry().id()),
                () -> assertEquals("while_exit_0", outerWhileBlocks.exit().id()),
                () -> assertEquals(
                        List.of("while_cond_0", "while_body_0", "while_exit_0"),
                        blockIds(outerWhileBlocks.blocks())
                ),
                () -> assertEquals("if_then_1", nestedIfBlocks.thenEntry().id()),
                () -> assertSame(nestedIfBlocks.merge(), nestedIfBlocks.elseOrNextClauseEntry()),
                () -> assertEquals("if_merge_1", nestedIfBlocks.merge().id()),
                () -> assertEquals(List.of("if_then_1", "if_merge_1"), blockIds(nestedIfBlocks.blocks())),
                () -> assertEquals("while_cond_1", trailingWhileBlocks.conditionEntry().id()),
                () -> assertEquals("while_body_1", trailingWhileBlocks.bodyEntry().id()),
                () -> assertEquals("while_exit_1", trailingWhileBlocks.exit().id()),
                () -> assertEquals(0, executableContext.targetFunction().getBasicBlockCount()),
                () -> assertTrue(executableContext.targetFunction().getEntryBlockId().isEmpty()),
                () -> assertEquals(0, propertyContext.targetFunction().getBasicBlockCount()),
                () -> assertTrue(propertyContext.targetFunction().getEntryBlockId().isEmpty()),
                () -> assertNull(propertyContext.cfgNodeBlocksOrNull(propertyContext.loweringRoot())),
                () -> assertFalse(prepared.diagnostics().hasErrors())
        );
    }

    @Test
    void cfgNodeSideTableUsesAstIdentityAndRejectsDuplicatePublication() throws Exception {
        var prepared = prepareControlFlowContext();
        var executableContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeCfgPassShape",
                "ping"
        );
        var sourceFile = prepared.module().units().getFirst().ast();
        var pingFunction = requireFunctionDeclaration(sourceFile, "ping");
        var outerIf = assertInstanceOf(IfStatement.class, pingFunction.body().statements().getFirst());
        var nestedIf = findNode(
                pingFunction.body(),
                IfStatement.class,
                candidate -> candidate != outerIf
        );
        var unrelatedWhile = assertInstanceOf(WhileStatement.class, pingFunction.body().statements().getLast());

        var equivalentModule = parseModule(
                List.of(new SourceFixture("cfg_pass_shape.gd", CFG_PASS_SHAPE_SOURCE)),
                Map.of("CfgPassShape", "RuntimeCfgPassShape")
        );
        var equivalentPing = requireFunctionDeclaration(equivalentModule.units().getFirst().ast(), "ping");
        var equivalentOuterIf = assertInstanceOf(IfStatement.class, equivalentPing.body().statements().getFirst());
        var outerIfBlocks = new FunctionLoweringContext.IfCfgNodeBlocks(
                new LirBasicBlock("if_then_test"),
                new LirBasicBlock("if_false_test"),
                new LirBasicBlock("if_merge_test")
        );
        var nestedIfBlocks = new FunctionLoweringContext.IfCfgNodeBlocks(
                new LirBasicBlock("nested_then_test"),
                new LirBasicBlock("nested_merge_test"),
                new LirBasicBlock("nested_merge_test")
        );

        executableContext.publishCfgNodeBlocks(outerIf, outerIfBlocks);
        executableContext.publishCfgNodeBlocks(nestedIf, nestedIfBlocks);

        var duplicatePublication = assertThrows(
                IllegalStateException.class,
                () -> executableContext.publishCfgNodeBlocks(
                        outerIf,
                        new FunctionLoweringContext.BlockCfgNodeBlocks(new LirBasicBlock("duplicate"))
                )
        );

        assertAll(
                () -> assertSame(outerIfBlocks, executableContext.requireCfgNodeBlocks(outerIf)),
                () -> assertSame(nestedIfBlocks, executableContext.requireCfgNodeBlocks(nestedIf)),
                () -> assertNull(executableContext.cfgNodeBlocksOrNull(unrelatedWhile)),
                () -> assertNull(executableContext.cfgNodeBlocksOrNull(equivalentOuterIf)),
                () -> assertTrue(duplicatePublication.getMessage().contains("already been published"))
        );
    }

    @Test
    void runStopsPublishingLexicallyLaterSkeletonAfterFullyTerminatingIfElse() throws Exception {
        var prepared = prepareFullyTerminatingIfElseContext();

        new FrontendLoweringCfgPass().run(prepared.context());

        var executableContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeCfgPassTerminal",
                "ping"
        );
        var sourceFile = prepared.module().units().getFirst().ast();
        var pingFunction = requireFunctionDeclaration(sourceFile, "ping");
        var rootBlock = pingFunction.body();
        var outerIf = assertInstanceOf(IfStatement.class, rootBlock.statements().getFirst());
        var trailingWhile = assertInstanceOf(WhileStatement.class, rootBlock.statements().getLast());

        assertAll(
                () -> assertTrue(executableContext.hasCfgNodeBlocks(rootBlock)),
                () -> assertTrue(executableContext.hasCfgNodeBlocks(outerIf)),
                () -> assertFalse(executableContext.hasCfgNodeBlocks(trailingWhile)),
                () -> assertNull(executableContext.cfgNodeBlocksOrNull(trailingWhile)),
                () -> assertEquals(0, executableContext.targetFunction().getBasicBlockCount()),
                () -> assertTrue(executableContext.targetFunction().getEntryBlockId().isEmpty())
        );
    }

    @Test
    void runFailsFastBeforeAnalysisPublication() throws Exception {
        var context = new FrontendLoweringContext(
                new FrontendModule("test_module", List.of()),
                new ClassRegistry(ExtensionApiLoader.loadDefault()),
                new DiagnosticManager()
        );

        var exception = assertThrows(IllegalStateException.class, () -> new FrontendLoweringCfgPass().run(context));

        assertTrue(exception.getMessage().contains("analysisData"), exception.getMessage());
    }

    @Test
    void runFailsFastBeforeLirModulePublication() throws Exception {
        var context = new FrontendLoweringContext(
                new FrontendModule("test_module", List.of()),
                new ClassRegistry(ExtensionApiLoader.loadDefault()),
                new DiagnosticManager()
        );
        context.publishAnalysisData(FrontendAnalysisData.bootstrap());

        var exception = assertThrows(IllegalStateException.class, () -> new FrontendLoweringCfgPass().run(context));

        assertTrue(exception.getMessage().contains("lirModule"), exception.getMessage());
    }

    @Test
    void runFailsFastBeforeFunctionLoweringContextsPublication() throws Exception {
        var context = new FrontendLoweringContext(
                new FrontendModule("test_module", List.of()),
                new ClassRegistry(ExtensionApiLoader.loadDefault()),
                new DiagnosticManager()
        );
        context.publishAnalysisData(FrontendAnalysisData.bootstrap());
        context.publishLirModule(new LirModule("test_module", List.of()));

        var exception = assertThrows(IllegalStateException.class, () -> new FrontendLoweringCfgPass().run(context));

        assertTrue(exception.getMessage().contains("functionLoweringContexts"), exception.getMessage());
    }

    @Test
    void runRejectsParameterDefaultContextsUntilTheirCompileSurfaceExists() throws Exception {
        var prepared = prepareCompileReadyContext();
        var executableContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeCfgPassReady",
                "ping"
        );
        var parameterDefaultContext = new FunctionLoweringContext(
                FunctionLoweringContext.Kind.PARAMETER_DEFAULT_INIT,
                executableContext.sourcePath(),
                executableContext.sourceClassRelation(),
                executableContext.owningClass(),
                executableContext.targetFunction(),
                executableContext.sourceOwner(),
                executableContext.loweringRoot(),
                executableContext.analysisData()
        );
        prepared.context().publishFunctionLoweringContexts(List.of(executableContext, parameterDefaultContext));

        var exception = assertThrows(
                IllegalStateException.class,
                () -> new FrontendLoweringCfgPass().run(prepared.context())
        );

        assertTrue(exception.getMessage().contains("parameter default"), exception.getMessage());
    }

    @Test
    void compileBlockedModuleStopsBeforeCfgPassRuns() throws Exception {
        var cfgRan = new AtomicBoolean();
        var continuationRan = new AtomicBoolean();
        var diagnostics = new DiagnosticManager();
        var lowered = new FrontendLoweringPassManager(List.of(
                new FrontendLoweringAnalysisPass(),
                new FrontendLoweringClassSkeletonPass(),
                new FrontendLoweringFunctionPreparationPass(),
                context -> {
                    cfgRan.set(true);
                    new FrontendLoweringCfgPass().run(context);
                },
                _ -> continuationRan.set(true)
        )).lower(
                parseModule(
                        List.of(new SourceFixture(
                                "cfg_pass_compile_blocked.gd",
                                """
                                        class_name CfgPassCompileBlocked
                                        extends RefCounted
                                        
                                        func ping(value):
                                            assert(value, "blocked in compile mode")
                                        """
                        )),
                        Map.of()
                ),
                new ClassRegistry(ExtensionApiLoader.loadDefault()),
                diagnostics
        );

        assertAll(
                () -> assertNull(lowered),
                () -> assertFalse(cfgRan.get()),
                () -> assertFalse(continuationRan.get()),
                () -> assertTrue(diagnostics.hasErrors())
        );
    }

    private static final @NotNull String CFG_PASS_SHAPE_SOURCE = """
            class_name CfgPassShape
            extends RefCounted
            
            var ready_value: int = 1
            
            func ping(flag: bool, other: bool) -> void:
                if flag:
                    while other:
                        if flag:
                            pass
                    pass
                elif other:
                    return
                else:
                    pass
                while flag:
                    pass
            """;

    private static final @NotNull String CFG_PASS_TERMINAL_SOURCE = """
            class_name CfgPassTerminal
            extends RefCounted
            
            func ping(flag: bool) -> void:
                if flag:
                    return
                else:
                    return
                while flag:
                    pass
            """;

    private static @NotNull PreparedContext prepareCompileReadyContext() throws Exception {
        var diagnostics = new DiagnosticManager();
        var module = parseModule(
                List.of(new SourceFixture(
                        "cfg_pass_ready.gd",
                        """
                                class_name CfgPassReady
                                extends RefCounted
                                
                                var ready_value: int = 1
                                
                                func ping(value: int) -> int:
                                    return value + 1
                                """
                )),
                Map.of("CfgPassReady", "RuntimeCfgPassReady")
        );
        var context = new FrontendLoweringContext(
                module,
                new ClassRegistry(ExtensionApiLoader.loadDefault()),
                diagnostics
        );
        new FrontendLoweringAnalysisPass().run(context);
        new FrontendLoweringClassSkeletonPass().run(context);
        new FrontendLoweringFunctionPreparationPass().run(context);
        return new PreparedContext(context, diagnostics, module);
    }

    private static @NotNull PreparedContext prepareControlFlowContext() throws Exception {
        return prepareContext(
                "cfg_pass_shape.gd",
                CFG_PASS_SHAPE_SOURCE,
                Map.of("CfgPassShape", "RuntimeCfgPassShape")
        );
    }

    private static @NotNull PreparedContext prepareFullyTerminatingIfElseContext() throws Exception {
        return prepareContext(
                "cfg_pass_terminal.gd",
                CFG_PASS_TERMINAL_SOURCE,
                Map.of("CfgPassTerminal", "RuntimeCfgPassTerminal")
        );
    }

    private static @NotNull PreparedContext prepareContext(
            @NotNull String fileName,
            @NotNull String source,
            @NotNull Map<String, String> topLevelCanonicalNameMap
    ) throws Exception {
        var diagnostics = new DiagnosticManager();
        var module = parseModule(List.of(new SourceFixture(fileName, source)), topLevelCanonicalNameMap);
        var context = new FrontendLoweringContext(
                module,
                new ClassRegistry(ExtensionApiLoader.loadDefault()),
                diagnostics
        );
        new FrontendLoweringAnalysisPass().run(context);
        new FrontendLoweringClassSkeletonPass().run(context);
        new FrontendLoweringFunctionPreparationPass().run(context);
        return new PreparedContext(context, diagnostics, module);
    }

    private static @NotNull FrontendModule parseModule(
            @NotNull List<SourceFixture> fixtures,
            @NotNull Map<String, String> topLevelCanonicalNameMap
    ) {
        var parserService = new GdScriptParserService();
        var parseDiagnostics = new DiagnosticManager();
        var units = fixtures.stream()
                .map(fixture -> parserService.parseUnit(Path.of("tmp", fixture.fileName()), fixture.source(), parseDiagnostics))
                .toList();
        assertTrue(parseDiagnostics.isEmpty(), () -> "Unexpected parse diagnostics: " + parseDiagnostics.snapshot());
        return new FrontendModule("test_module", units, topLevelCanonicalNameMap);
    }

    private static @NotNull FunctionLoweringContext requireContext(
            @NotNull List<FunctionLoweringContext> contexts,
            @NotNull FunctionLoweringContext.Kind kind,
            @NotNull String owningClassName,
            @NotNull String functionName
    ) {
        return contexts.stream()
                .filter(context -> context.kind() == kind)
                .filter(context -> context.owningClass().getName().equals(owningClassName))
                .filter(context -> context.targetFunction().getName().equals(functionName))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Missing context " + kind + " " + owningClassName + "." + functionName
                ));
    }

    private static @NotNull List<String> blockIds(@NotNull List<LirBasicBlock> blocks) {
        return blocks.stream().map(LirBasicBlock::id).toList();
    }

    private static @NotNull FunctionDeclaration requireFunctionDeclaration(
            @NotNull dev.superice.gdparser.frontend.ast.SourceFile sourceFile,
            @NotNull String functionName
    ) {
        return sourceFile.statements().stream()
                .filter(FunctionDeclaration.class::isInstance)
                .map(FunctionDeclaration.class::cast)
                .filter(function -> function.name().equals(functionName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing function declaration " + functionName));
    }

    private static <T extends Node> @NotNull T findNode(
            @NotNull Node root,
            @NotNull Class<T> nodeType,
            @NotNull Predicate<T> predicate
    ) {
        if (nodeType.isInstance(root)) {
            var candidate = nodeType.cast(root);
            if (predicate.test(candidate)) {
                return candidate;
            }
        }
        for (var child : root.getChildren()) {
            try {
                return findNode(child, nodeType, predicate);
            } catch (AssertionError ignored) {
            }
        }
        throw new AssertionError("Node not found: " + nodeType.getSimpleName());
    }

    private record PreparedContext(
            @NotNull FrontendLoweringContext context,
            @NotNull DiagnosticManager diagnostics,
            @NotNull FrontendModule module
    ) {
    }

    private record SourceFixture(
            @NotNull String fileName,
            @NotNull String source
    ) {
    }
}
