package gd.script.gdcc.frontend.sema;

import dev.superice.gdparser.frontend.ast.ForStatement;
import dev.superice.gdparser.frontend.ast.Node;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.frontend.sema.resolver.FrontendVisibleValueDomain;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendInventoryGateRegistryTest {
    @Test
    void pendingGateFactoryStartsFailClosed() throws Exception {
        var forStatement = parseForStatement();

        var gate = FrontendInventoryGate.pending(
                forStatement,
                forStatement,
                forStatement.body(),
                FrontendVisibleValueDomain.FOR_SUBTREE
        );

        assertEquals(FrontendInventoryGateStatus.PENDING, gate.status());
        assertEquals(FrontendBodyInventoryReadiness.NOT_PUBLISHED, gate.bodyInventoryReadiness());
        assertFalse(gate.isBodyInventoryReady());
    }

    @Test
    void registryTransitionsOnlySupportedPublishedGateToReady() throws Exception {
        var forStatement = parseForStatement();
        var registry = FrontendInventoryGateRegistry.builder()
                .add(FrontendInventoryGate.pending(
                        forStatement,
                        forStatement,
                        forStatement.body(),
                        FrontendVisibleValueDomain.FOR_SUBTREE
                ))
                .build();

        assertFalse(registry.isBodyInventoryReady(forStatement.body()));

        var supported = registry.markSupported(forStatement.body());
        assertEquals(FrontendInventoryGateStatus.SUPPORTED, supported.status());
        assertEquals(FrontendBodyInventoryReadiness.NOT_PUBLISHED, supported.bodyInventoryReadiness());
        assertFalse(registry.isBodyInventoryReady(forStatement.body()));

        var publishing = registry.markBodyInventoryPublishing(forStatement.body());
        assertEquals(FrontendBodyInventoryReadiness.PUBLISHING, publishing.bodyInventoryReadiness());
        assertFalse(registry.isBodyInventoryReady(forStatement.body()));

        var published = registry.markBodyInventoryPublished(forStatement.body());
        assertEquals(FrontendInventoryGateStatus.SUPPORTED, published.status());
        assertEquals(FrontendBodyInventoryReadiness.PUBLISHED, published.bodyInventoryReadiness());
        assertTrue(registry.isBodyInventoryReady(forStatement.body()));

        var unsupported = registry.markUnsupported(forStatement.body());
        assertEquals(FrontendInventoryGateStatus.UNSUPPORTED, unsupported.status());
        assertEquals(FrontendBodyInventoryReadiness.NOT_PUBLISHED, unsupported.bodyInventoryReadiness());
        assertFalse(registry.isBodyInventoryReady(forStatement.body()));
    }

    @Test
    void invalidReadinessStatesAreRejected() throws Exception {
        var forStatement = parseForStatement();
        var pending = FrontendInventoryGate.pending(
                forStatement,
                forStatement,
                forStatement.body(),
                FrontendVisibleValueDomain.FOR_SUBTREE
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> pending.withBodyInventoryReadiness(FrontendBodyInventoryReadiness.PUBLISHING)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new FrontendInventoryGate(
                        forStatement,
                        forStatement,
                        forStatement.body(),
                        FrontendVisibleValueDomain.FOR_SUBTREE,
                        FrontendInventoryGateStatus.UNSUPPORTED,
                        FrontendBodyInventoryReadiness.PUBLISHED
                )
        );
    }

    @Test
    void registryRejectsDuplicateBodyRootAndReturnsImmutableSnapshot() throws Exception {
        var forStatement = parseForStatement();
        var gate = FrontendInventoryGate.pending(
                forStatement,
                forStatement,
                forStatement.body(),
                FrontendVisibleValueDomain.FOR_SUBTREE
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> FrontendInventoryGateRegistry.builder().add(gate).add(gate).build()
        );
        var registry = FrontendInventoryGateRegistry.builder().add(gate).build();

        assertSame(gate, registry.gateForBodyRoot(forStatement.body()));
        assertEquals(List.of(gate), registry.gatesForOwner(forStatement));
        assertEquals(List.of(gate), registry.gatesForHeaderRoot(forStatement));
        assertThrows(UnsupportedOperationException.class, () -> registry.gates().add(gate));
    }

    @Test
    void missingGateStaysFailClosed() throws Exception {
        var forStatement = parseForStatement();

        assertFalse(FrontendInventoryGateRegistry.empty().isBodyInventoryReady(forStatement.body()));
        assertThrows(
                IllegalArgumentException.class,
                () -> FrontendInventoryGateRegistry.empty().markSupported(forStatement.body())
        );
    }

    private static @NotNull ForStatement parseForStatement() throws Exception {
        var diagnostics = new DiagnosticManager();
        var unit = new GdScriptParserService().parseUnit(Path.of("tmp", "gate_registry.gd"), """
                class_name GateRegistry
                extends Node
                
                func ping(values):
                    for value in values:
                        print(value)
                """, diagnostics);
        assertTrue(diagnostics.isEmpty(), () -> "Unexpected parse diagnostics: " + diagnostics.snapshot());
        return findNode(unit.ast(), ForStatement.class, _ -> true);
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
            var match = findNodeOrNull(child, nodeType, predicate);
            if (match != null) {
                return match;
            }
        }
        throw new AssertionError("Node not found: " + nodeType.getSimpleName());
    }

    private static <T extends Node> T findNodeOrNull(
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
            var match = findNodeOrNull(child, nodeType, predicate);
            if (match != null) {
                return match;
            }
        }
        return null;
    }
}
