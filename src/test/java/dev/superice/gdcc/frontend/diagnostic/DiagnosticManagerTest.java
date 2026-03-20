package dev.superice.gdcc.frontend.diagnostic;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class DiagnosticManagerTest {
    @Test
    void reportPreservesInsertionOrderAndEarlierSnapshotsStayStable() {
        var manager = new DiagnosticManager();
        var first = FrontendDiagnostic.warning("parse.lowering", "first", Path.of("tmp", "first.gd"), null);
        var second = FrontendDiagnostic.error("sema.class_skeleton", "second", Path.of("tmp", "second.gd"), null);

        manager.report(first);
        var beforeSecond = manager.snapshot();
        manager.report(second);

        assertEquals(1, beforeSecond.size());
        assertEquals(first, beforeSecond.getFirst());
        assertEquals(Arrays.asList(first, second), manager.snapshot().asList());
    }

    @Test
    void snapshotIsImmutable() {
        var manager = new DiagnosticManager();
        manager.report(FrontendDiagnostic.warning("parse.lowering", "immutable", null, null));

        var snapshot = manager.snapshot();
        assertThrows(UnsupportedOperationException.class, () -> snapshot.asList().add(
                FrontendDiagnostic.error("parse.internal", "should fail", null, null)
        ));
    }

    @Test
    void hasErrorsOnlyTurnsTrueAfterAnErrorIsReported() {
        var manager = new DiagnosticManager();

        manager.warning("parse.lowering", "warning only", null, null);
        assertFalse(manager.hasErrors());

        manager.error("parse.internal", "error now present", null, null);
        assertTrue(manager.hasErrors());
    }

    @Test
    void reportAllRejectsNullCollectionAndNullElements() {
        var manager = new DiagnosticManager();

        assertThrows(NullPointerException.class, () -> manager.reportAll(null));
        assertThrows(NullPointerException.class, () -> manager.reportAll(Arrays.asList(
                FrontendDiagnostic.warning("parse.lowering", "valid", null, null),
                null
        )));
        assertTrue(manager.isEmpty());
    }

    @Test
    void warningAndErrorConvenienceMethodsPreserveDiagnosticMetadata() {
        var manager = new DiagnosticManager();
        var sourcePath = Path.of("tmp", "player.gd");
        var range = new FrontendRange(1, 3, new FrontendPoint(1, 2), new FrontendPoint(1, 4));

        manager.warning("parse.lowering", "warning", sourcePath, range);
        manager.error("parse.internal", "error", sourcePath, null);

        var snapshot = manager.snapshot();
        assertEquals(2, snapshot.size());

        var warning = snapshot.getFirst();
        assertEquals(FrontendDiagnosticSeverity.WARNING, warning.severity());
        assertEquals("parse.lowering", warning.category());
        assertEquals("warning", warning.message());
        assertEquals(sourcePath, warning.sourcePath());
        assertEquals(range, warning.range());

        var error = snapshot.getLast();
        assertEquals(FrontendDiagnosticSeverity.ERROR, error.severity());
        assertEquals("parse.internal", error.category());
        assertEquals("error", error.message());
        assertEquals(sourcePath, error.sourcePath());
        assertNull(error.range());
    }

    @Test
    void snapshotProvidesConvenientSummaryQueriesWithoutRequiringRawListAccess() {
        var manager = new DiagnosticManager();
        manager.warning("parse.lowering", "warning", null, null);

        var warningOnly = manager.snapshot();
        assertFalse(warningOnly.isEmpty());
        assertEquals(1, warningOnly.size());
        assertFalse(warningOnly.hasErrors());

        manager.error("parse.internal", "error", null, null);
        var withError = manager.snapshot();
        assertTrue(withError.hasErrors());
        assertEquals(2, withError.size());
    }
}
