package gd.script.gdcc.api.analysis;

import gd.script.gdcc.api.API;
import gd.script.gdcc.api.AnalysisResult;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Locks the immutability contract of a published `ModuleAnalysisSnapshot`
/// (`frontend_lsp_foundation_implementation.md` §2.1): every collection reachable through the
/// public surface is frozen, and the AST object graph is deeply immutable, so attempted external
/// mutation is rejected instead
/// of corrupting concurrent readers.
class ModuleAnalysisSnapshotImmutabilityTest {
    @Test
    void publishedSnapshotRejectsExternalMutation() {
        var snapshot = publishSnapshot();

        assertAll(
                () -> assertThrows(UnsupportedOperationException.class,
                        () -> snapshot.topLevelCanonicalNameMap().put("X", "Y")),
                () -> assertThrows(UnsupportedOperationException.class,
                        () -> snapshot.sourceViews().clear()),
                () -> assertThrows(UnsupportedOperationException.class,
                        () -> snapshot.diagnostics().asList().clear()),
                // The AST object graph is deeply frozen by gdparser (List.copyOf in compact
                // constructors), which is the precondition for safe cross-thread snapshot reads.
                () -> assertThrows(UnsupportedOperationException.class,
                        () -> snapshot.module().units().clear()),
                () -> assertThrows(UnsupportedOperationException.class,
                        () -> snapshot.module().units().getFirst().ast().statements().clear())
        );
    }

    @Test
    void snapshotViewsStaySelfConsistentAcrossLaterModuleWrites() {
        var api = new API();
        try {
            api.createModule("demo", "Immutable Demo");
            api.putFile("demo", "/src/demo.gd", """
                    class_name ImmutableDemo
                    extends RefCounted
                    
                    var hp = 1
                    """);
            var first = api.analyze("demo");
            var firstSnapshot = snapshotOf(api);

            // Later content writes publish a NEW snapshot; the old one must stay self-consistent.
            api.putFile("demo", "/src/demo.gd", """
                    class_name ImmutableDemo
                    extends RefCounted
                    
                    var hp = 2
                    var mp = 3
                    """);
            var second = api.analyze("demo");

            assertAll(
                    () -> assertEquals(first.snapshotVersion(), firstSnapshot.snapshotVersion()),
                    () -> assertEquals(1, firstSnapshot.sourceViews().size()),
                    () -> assertTrue(firstSnapshot.sourceViews().getFirst().source().contains("var hp = 1")),
                    () -> assertTrue(second.snapshotVersion() > firstSnapshot.snapshotVersion()),
                    () -> assertEquals(second.snapshotVersion(), snapshotOf(api).snapshotVersion())
            );
        } finally {
            api.close();
        }
    }

    @Test
    void publishedSnapshotPayloadIsStructurallyFrozen() {
        // Freeze-on-publish: the whole analysis generation is
        // physically unwritable the moment it becomes a snapshot — mutation attempts from any
        // channel throw instead of corrupting concurrent readers.
        var snapshot = publishSnapshot();
        var analysisData = snapshot.analysisData();
        var registry = snapshot.classRegistry();

        assertAll(
                () -> assertTrue(analysisData.isFrozen()),
                () -> assertTrue(registry.isFrozen()),
                // Side tables reject direct writes...
                () -> assertThrows(IllegalStateException.class, () -> analysisData.scopesByAst().clear()),
                () -> assertThrows(IllegalStateException.class, () -> analysisData.expressionTypes().clear()),
                () -> assertThrows(IllegalStateException.class, () -> analysisData.declarationOrigins().clear()),
                // ...and writes through their live views (the freeze-aware views reject at
                // operation time with the same guard exception).
                () -> assertThrows(IllegalStateException.class,
                        () -> analysisData.skippedSubtreeRoots().entrySet().clear()),
                // Whole-carrier mutators reject too (guards run before argument validation).
                () -> assertThrows(IllegalStateException.class, () -> analysisData.updateModuleSkeleton(null)),
                () -> assertThrows(IllegalStateException.class, () -> analysisData.updateDiagnostics(null)),
                () -> assertThrows(IllegalStateException.class, () -> analysisData.updateScopesByAst(null)),
                () -> assertThrows(IllegalStateException.class, analysisData::drainAwaitCallPendings),
                () -> assertThrows(IllegalStateException.class, () -> analysisData.addAwaitCallPending(null)),
                // Registry mutation channels are closed.
                () -> assertThrows(IllegalStateException.class, () -> registry.removeGdccClass("ImmutableDemo")),
                () -> assertThrows(IllegalStateException.class,
                        () -> registry.addGdccClass(registry.findGdccClass("ImmutableDemo")))
        );
        // Reads stay open: the frozen generation still answers queries.
        assertNotNull(registry.findGdccClass("ImmutableDemo"));
    }

    @Test
    void frozenSideTablesRejectMutationThroughViewsAndInheritedMapChannels() {
        // JDK map views and default methods do not funnel through the guarded mutators, so the
        // freeze must close them separately: `Map.Entry.setValue`, `replaceAll`, and iterator
        // removal all stay rejected on a really published snapshot.
        var snapshot = publishSnapshot();
        var scopes = snapshot.analysisData().scopesByAst();
        var origins = snapshot.analysisData().declarationOrigins();
        assertTrue(!scopes.isEmpty() && !origins.isEmpty(), "fixture must publish non-empty tables");

        assertAll(
                () -> assertThrows(IllegalStateException.class,
                        () -> scopes.entrySet().iterator().next().setValue(null)),
                () -> assertThrows(IllegalStateException.class,
                        () -> scopes.replaceAll((_, _) -> null)),
                () -> {
                    var iterator = scopes.keySet().iterator();
                    iterator.next();
                    assertThrows(IllegalStateException.class, iterator::remove);
                },
                () -> assertThrows(IllegalStateException.class,
                        () -> origins.replaceAll((_, _) -> null)),
                () -> assertThrows(IllegalStateException.class,
                        () -> origins.entrySet().iterator().next().setValue(null))
        );
    }

    private static @NotNull ModuleAnalysisSnapshot publishSnapshot() {
        var api = new API();
        try {
            api.createModule("demo", "Immutable Demo");
            api.putFile("demo", "/src/demo.gd", """
                    class_name ImmutableDemo
                    extends RefCounted
                    
                    var hp = 1
                    """);
            var result = api.analyze("demo");
            assertEquals(AnalysisResult.Outcome.COMPLETED, result.outcome());
            var snapshot = api.getLatestAnalysisSnapshot("demo");
            if (snapshot == null) {
                throw new AssertionError("expected a published snapshot");
            }
            return snapshot;
        } finally {
            api.close();
        }
    }

    private static @NotNull ModuleAnalysisSnapshot snapshotOf(@NotNull API api) {
        var snapshot = api.getLatestAnalysisSnapshot("demo");
        if (snapshot == null) {
            throw new AssertionError("expected a published snapshot");
        }
        return snapshot;
    }
}
