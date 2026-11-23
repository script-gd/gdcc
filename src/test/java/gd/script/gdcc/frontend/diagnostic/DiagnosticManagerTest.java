package gd.script.gdcc.frontend.diagnostic;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DiagnosticManagerTest {
    @Test
    void liveContainsUsesAllFiveFieldsAndDoesNotDeduplicateSnapshots() {
        var manager = new DiagnosticManager();
        var range = new FrontendRange(1, 3, new FrontendPoint(1, 2), new FrontendPoint(1, 4));
        var anotherRange = new FrontendRange(1, 3, new FrontendPoint(2, 2), new FrontendPoint(2, 4));
        var error = FrontendDiagnostic.error("parse.syntax", "original", "first.gd", range);
        var sameError = FrontendDiagnostic.error("parse.syntax", "original", "first.gd", range);
        var otherMessage = FrontendDiagnostic.error("parse.syntax", "another", "first.gd", range);

        assertFalse(manager.contains(error));
        manager.report(error);
        var beforeBatch = manager.snapshot();
        assertTrue(manager.contains(sameError));
        assertFalse(manager.contains(otherMessage));
        assertFalse(manager.contains(FrontendDiagnostic.warning("parse.syntax", "original", "first.gd", range)));
        assertFalse(manager.contains(FrontendDiagnostic.error("parse.other", "original", "first.gd", range)));
        assertFalse(manager.contains(FrontendDiagnostic.error("parse.syntax", "original", "second.gd", range)));
        assertFalse(manager.contains(FrontendDiagnostic.error("parse.syntax", "original", "first.gd", anotherRange)));

        manager.reportAll(List.of(sameError, otherMessage));

        assertTrue(manager.contains(otherMessage));
        assertEquals(List.of(error), beforeBatch.asList());
        assertEquals(List.of(error, sameError, otherMessage), manager.snapshot().asList());
    }

    @Test
    void firstErrorAcrossTwoRangesUsesAppendOrderAndIgnoresWarningsAndOtherFiles() {
        var manager = new DiagnosticManager();
        var firstRange = new FrontendRange(1, 2, new FrontendPoint(1, 2), new FrontendPoint(1, 3));
        var secondRange = new FrontendRange(4, 6, new FrontendPoint(1, 5), new FrontendPoint(1, 7));
        var overlappingRange = new FrontendRange(4, 5, new FrontendPoint(1, 5), new FrontendPoint(1, 6));
        var earlierError = FrontendDiagnostic.error("sema.type_check", "earlier", "first.gd", secondRange);
        var laterError = FrontendDiagnostic.error("sema.type_check", "later", "first.gd", firstRange);
        var otherMessage = FrontendDiagnostic.error("sema.type_check", "same location", "first.gd", secondRange);

        manager.report(FrontendDiagnostic.warning("parse.syntax", "warning", "first.gd", firstRange));
        manager.report(FrontendDiagnostic.error("parse.syntax", "other file", "second.gd", secondRange));
        manager.report(FrontendDiagnostic.error("parse.syntax", "overlap", "first.gd", overlappingRange));
        assertNull(manager.firstErrorAtTwoRanges("first.gd", firstRange, secondRange));

        manager.reportAll(List.of(earlierError, laterError, otherMessage));

        assertSame(earlierError, manager.firstErrorAtTwoRanges("first.gd", firstRange, secondRange));
        assertSame(earlierError, manager.firstErrorAtTwoRanges("first.gd", secondRange, firstRange));
        assertSame(laterError, manager.firstErrorAtTwoRanges("first.gd", firstRange, firstRange));
        assertNull(manager.firstErrorAtTwoRanges(null, firstRange, secondRange));
        var pathless = FrontendDiagnostic.error("parse.syntax", "pathless", null, firstRange);
        manager.report(pathless);
        assertSame(pathless, manager.firstErrorAtTwoRanges(null, firstRange, secondRange));
    }

    @Test
    void rejectedBatchDoesNotLeakIntoEitherLiveQuery() {
        var manager = new DiagnosticManager();
        var range = new FrontendRange(0, 1, new FrontendPoint(1, 1), new FrontendPoint(1, 2));
        var error = FrontendDiagnostic.error("parse.syntax", "batch item", "sample.gd", range);
        var before = manager.snapshot();
        assertFalse(manager.contains(error));
        assertNull(manager.firstErrorAtTwoRanges("sample.gd", range, range));

        assertThrows(NullPointerException.class, () -> manager.reportAll(Arrays.asList(error, null)));

        assertFalse(manager.contains(error));
        assertNull(manager.firstErrorAtTwoRanges("sample.gd", range, range));
        assertEquals(before, manager.snapshot());
        manager.reportAll(List.of(error));
        assertTrue(manager.contains(error));
        assertSame(error, manager.firstErrorAtTwoRanges("sample.gd", range, range));
        assertTrue(before.isEmpty());
    }

    @Test
    void reportPreservesInsertionOrderAndEarlierSnapshotsStayStable() {
        var manager = new DiagnosticManager();
        var first = FrontendDiagnostic.warning(
                "parse.lowering",
                "first",
                FrontendDiagnostic.sourcePathText(Path.of("tmp", "first.gd")),
                null
        );
        var second = FrontendDiagnostic.error(
                "sema.class_skeleton",
                "second",
                FrontendDiagnostic.sourcePathText(Path.of("tmp", "second.gd")),
                null
        );

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
        assertEquals(FrontendDiagnostic.sourcePathText(sourcePath), warning.sourcePath());
        assertEquals(range, warning.range());

        var error = snapshot.getLast();
        assertEquals(FrontendDiagnosticSeverity.ERROR, error.severity());
        assertEquals("parse.internal", error.category());
        assertEquals("error", error.message());
        assertEquals(FrontendDiagnostic.sourcePathText(sourcePath), error.sourcePath());
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
