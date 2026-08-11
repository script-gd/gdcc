package gd.script.gdcc.frontend.lowering.pass.body;

import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.lowering.FrontendLoweringContext;
import gd.script.gdcc.frontend.lowering.FunctionLoweringContext;
import gd.script.gdcc.frontend.lowering.cfg.FrontendCfgGraph;
import gd.script.gdcc.frontend.lowering.cfg.item.ContainerLiteralItem;
import gd.script.gdcc.frontend.lowering.pass.FrontendLoweringBodyInsnPass;
import gd.script.gdcc.frontend.lowering.pass.FrontendLoweringBuildCfgPass;
import gd.script.gdcc.frontend.lowering.pass.FrontendLoweringClassSkeletonPass;
import gd.script.gdcc.frontend.lowering.pass.FrontendLoweringFunctionPreparationPass;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.frontend.sema.analyzer.FrontendSemanticAnalyzer;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.scope.ClassRegistry;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Phase 3: dedicated processor is registered and fail-fasts until Phase 4 LIR lands.
class FrontendContainerLiteralInsnLoweringTest {
    @Test
    void bodyPassFailsFastOnContainerLiteralItemUntilPhase4() throws Exception {
        var diagnostics = new DiagnosticManager();
        var unit = new GdScriptParserService().parseUnit(
                Path.of("tmp", "container_literal_body_shell.gd"),
                """
                        class_name ContainerLiteralBodyShell
                        extends RefCounted
                        
                        func probe() -> Array:
                            return [1, 2]
                        """,
                diagnostics
        );
        assertTrue(diagnostics.isEmpty(), () -> "Unexpected parse diagnostics: " + diagnostics.snapshot());
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var module = new FrontendModule(
                "test_module",
                List.of(unit),
                Map.of("ContainerLiteralBodyShell", "RuntimeContainerLiteralBodyShell")
        );
        // Shared semantic only: compile gate still intercepts ArrayExpression for compile-mode.
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

        var functionContext = context.requireFunctionLoweringContexts().stream()
                .filter(candidate -> candidate.kind() == FunctionLoweringContext.Kind.EXECUTABLE_BODY)
                .filter(candidate -> candidate.targetFunction().getName().equals("probe"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing executable body context for probe"));
        var containerItems = collectContainerItems(functionContext.requireFrontendCfgGraph());
        assertFalse(containerItems.isEmpty(), "CFG must publish ContainerLiteralItem before body pass");

        var exception = assertThrows(
                IllegalStateException.class,
                () -> new FrontendLoweringBodyInsnPass().run(context)
        );
        assertTrue(
                exception.getMessage().contains("ContainerLiteralItem"),
                () -> "Expected dedicated-item fail-fast, got: " + exception.getMessage()
        );
        assertTrue(
                exception.getMessage().contains("Phase 4")
                        || exception.getMessage().contains("construct_container_literal")
                        || exception.getMessage().contains("not supported"),
                () -> "Expected Phase 4 deferral detail, got: " + exception.getMessage()
        );
    }

    private static @NotNull List<ContainerLiteralItem> collectContainerItems(@NotNull FrontendCfgGraph graph) {
        var items = new ArrayList<ContainerLiteralItem>();
        for (var nodeId : graph.nodeIds()) {
            if (!(graph.requireNode(nodeId) instanceof FrontendCfgGraph.SequenceNode(_, var sequenceItems, _))) {
                continue;
            }
            for (var item : sequenceItems) {
                if (item instanceof ContainerLiteralItem containerLiteralItem) {
                    items.add(containerLiteralItem);
                }
            }
        }
        return items;
    }
}
