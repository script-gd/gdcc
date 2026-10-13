package gd.script.gdcc.api.analysis;

import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.MissingAttributeStep;
import gd.script.gdcc.api.API;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static gd.script.gdcc.api.analysis.QueryTestSupport.file;
import static gd.script.gdcc.api.analysis.QueryTestSupport.files;
import static gd.script.gdcc.api.analysis.QueryTestSupport.offset;
import static gd.script.gdcc.api.analysis.QueryTestSupport.offsetInside;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Integration tests for `FrontendSnapshotQueryService` nodeAt / definitionAt / usagesAt /
/// typeAt (`frontend_lsp_foundation_implementation.md` §2.4). All queries
/// run against one shared playground module; every positive case is paired with the negative
/// shapes the plan documents (absent sets, half-open edges, stale snapshots).
class FrontendSnapshotQueryServiceTest {
    private static final String BASE_PATH = "/src/base.gd";
    private static final String BASE_SOURCE = """
            class_name QueryBase
            extends RefCounted
            
            var base_count: int = 0
            
            func greet() -> String:
                return "base"
            """;
    private static final String DERIVED_PATH = "/src/derived.gd";
    private static final String DERIVED_SOURCE = """
            class_name QueryDerived
            extends QueryBase
            
            enum State { IDLE, RUN }
            
            var count: int = 1
            
            func greet() -> String:
                return "derived"
            
            func use(param: int) -> void:
                var local_x = param
                var y = local_x + local_x
                var typed: int = 3
                var c1 = count
                var c2 = self.count
                var f1 = greet
                var f2 = self.greet
                var bc = base_count
                var inst = QueryBase.new()
                var bad_static = QueryBase.greet
                var bad_class_member = QueryBase.base_count
                var st = State.IDLE
                print(y)
                var l = len("abc")
                var p = PI
                var m = MOUSE_BUTTON_LEFT
                var k = Key.KEY_A
                var v: Vector2 = Vector2(1, 2)
                var vx = v.x
                var vn = v.normalized()
                var z = Vector2.ZERO
                var vu = Vector2(1, 2)
                var bad = vu.x
                var bad2 = self.no_such_member
                var bad3 = missing_name
                # a comment line
            """;
    private static final String NODE_USER_PATH = "/src/node_user.gd";
    private static final String NODE_USER_SOURCE = """
            class_name QueryNodeUser
            extends Node2D
            
            var mode = Node2D.PROCESS_MODE_INHERIT
            var notif = Node2D.NOTIFICATION_READY
            
            func ready() -> void:
                var n = self.name
                self.hide()
                var s = self.tree_exiting
            """;
    private static final String DAMAGED_PATH = "/src/damaged.gd";
    private static final String DAMAGED_SOURCE = """
            class_name QueryDamaged
            extends QueryBase
            
            func hurt() -> void:
                if :
                    pass
                var z = base_count
                var w = z
            """;

    private static API api;
    private static ModuleAnalysisSnapshot snapshot;

    @BeforeAll
    static void analyzePlayground() {
        api = new API();
        snapshot = QueryTestSupport.analyze(api, "query_playground", files(
                file(BASE_PATH, BASE_SOURCE),
                file(DERIVED_PATH, DERIVED_SOURCE),
                file(NODE_USER_PATH, NODE_USER_SOURCE),
                file(DAMAGED_PATH, DAMAGED_SOURCE)
        ));
    }

    @AfterAll
    static void closeApi() {
        api.close();
    }

    // ------------------------------------------------------------------
    // nodeAt (acceptance 4/5)
    // ------------------------------------------------------------------

    @Test
    void nodeAtSelectsDeepestNodeUnderHalfOpenCoverage() {
        var useOffset = offset(DERIVED_SOURCE, "local_x", 1);
        var deepest = FrontendSnapshotQueryService.nodeAt(snapshot, DERIVED_PATH, useOffset);
        assertInstanceOf(IdentifierExpression.class, deepest);
        assertEquals("local_x", ((IdentifierExpression) deepest).name());

        // Half-open rule: the offset exactly at endByte belongs to an ancestor, not the identifier.
        var identifierEnd = deepest.range().endByte();
        var atEnd = FrontendSnapshotQueryService.nodeAt(snapshot, DERIVED_PATH, identifierEnd);
        assertNotNull(atEnd);
        assertFalse(atEnd instanceof IdentifierExpression, "endByte offset must not select the identifier");
        assertTrue(atEnd.range().startByte() <= identifierEnd && identifierEnd < atEnd.range().endByte());
    }

    @Test
    void nodeAtNeverSelectsZeroWidthNodes() {
        // `self.` produces a zero-width MissingAttributeStep right after the dot (partial chain).
        var source = "extends RefCounted\nfunc f() -> void:\n    var x = self.\n";
        var localApi = new API();
        try {
            var localSnapshot = QueryTestSupport.analyze(localApi, "zero_width", files(file("/src/z.gd", source)));
            var dotEnd = offset(source, "self.", 0) + "self.".length();
            var missing = QueryTestSupport.findNode(
                    localSnapshot.module().units().getFirst().ast(),
                    node -> node instanceof MissingAttributeStep
            );
            assertNotNull(missing, "fixture must contain the partial-chain marker");
            assertEquals(missing.range().startByte(), missing.range().endByte(), "marker must be zero-width");

            var node = FrontendSnapshotQueryService.nodeAt(localSnapshot, "/src/z.gd", dotEnd);
            assertFalse(node instanceof MissingAttributeStep, "zero-width nodes never cover an offset");
        } finally {
            localApi.close();
        }
    }

    @Test
    void nodeAtRowColumnInputMatchesByteOffsetInput() {
        var useOffset = offset(DERIVED_SOURCE, "local_x", 1);
        var byOffset = FrontendSnapshotQueryService.nodeAt(snapshot, DERIVED_PATH, useOffset);
        assertNotNull(byOffset);
        var point = byOffset.range().startPoint();
        var byRowColumn = FrontendSnapshotQueryService.nodeAt(snapshot, DERIVED_PATH, point.row(), point.column());
        assertTrue(byOffset == byRowColumn, "row/column and byte-offset inputs must resolve the same node");
    }

    @Test
    void nodeAtReturnsNullForUnknownPathAndOutOfRangeOffsets() {
        assertNull(FrontendSnapshotQueryService.nodeAt(snapshot, "/src/nope.gd", 0));
        assertNull(FrontendSnapshotQueryService.nodeAt(snapshot, DERIVED_PATH, Integer.MAX_VALUE / 2));
        // Row/column coordinates that run past the line end must not leak into the next line.
        assertNull(FrontendSnapshotQueryService.nodeAt(snapshot, DERIVED_PATH, 9999, 0));
        assertNull(FrontendSnapshotQueryService.nodeAt(snapshot, DERIVED_PATH, 0, 99999));
        // A huge column on an existing row must not overflow the bounds check.
        assertNull(FrontendSnapshotQueryService.nodeAt(snapshot, DERIVED_PATH, 5, Integer.MAX_VALUE));
    }

    @Test
    void nodeAtRowColumnRoundTripsThroughMultibyteAndCrLfSources() {
        // Multibyte characters and CRLF line endings before the cursor: the (row, byte-column)
        // conversion must round-trip the exact node the byte offset selects.
        var utf8Source = "extends RefCounted\n# 注释 é字\nfunc f() -> void:\n    var a = 1\n    var b = a\n";
        var crlfSource = "extends RefCounted\r\nfunc f() -> void:\r\n    var a = 1\r\n    var b = a\r\n";
        var localApi = new API();
        try {
            var localSnapshot = QueryTestSupport.analyze(localApi, "coord_module", files(
                    file("/src/utf8.gd", utf8Source),
                    file("/src/crlf.gd", crlfSource)
            ));
            assertRoundTrip(localSnapshot, "/src/utf8.gd", utf8Source);
            assertRoundTrip(localSnapshot, "/src/crlf.gd", crlfSource);
        } finally {
            localApi.close();
        }
    }

    private static void assertRoundTrip(ModuleAnalysisSnapshot localSnapshot, String path, String source) {
        var useOffset = offset(source, "= a", 0) + 2;
        var byOffset = FrontendSnapshotQueryService.nodeAt(localSnapshot, path, useOffset);
        assertNotNull(byOffset);
        assertInstanceOf(IdentifierExpression.class, byOffset);
        var point = byOffset.range().startPoint();
        var byRowColumn = FrontendSnapshotQueryService.nodeAt(localSnapshot, path, point.row(), point.column());
        assertTrue(byOffset == byRowColumn, () -> "row/column must round-trip through " + path);
    }

    // ------------------------------------------------------------------
    // definitionAt (acceptance 1)
    // ------------------------------------------------------------------

    @Test
    void definitionAtResolvesLocalVariableParameterPropertyAndMethod() {
        var localResult = FrontendSnapshotQueryService.definitionAt(
                snapshot, DERIVED_PATH, offsetInside(DERIVED_SOURCE, "local_x", 1));
        assertEquals(DeclarationLookupResult.Kind.SINGLE_SOURCE, localResult.kind());
        assertLocatedBetween(localResult, DERIVED_PATH, offset(DERIVED_SOURCE, "var local_x", 0));

        var paramResult = FrontendSnapshotQueryService.definitionAt(
                snapshot, DERIVED_PATH, offsetInside(DERIVED_SOURCE, "param", 1));
        assertEquals(DeclarationLookupResult.Kind.SINGLE_SOURCE, paramResult.kind());
        assertLocatedBetween(paramResult, DERIVED_PATH, offset(DERIVED_SOURCE, "param: int", 0));

        var propertyResult = FrontendSnapshotQueryService.definitionAt(
                snapshot, DERIVED_PATH, offsetInside(DERIVED_SOURCE, "count", 2));
        assertEquals(DeclarationLookupResult.Kind.SINGLE_SOURCE, propertyResult.kind());
        assertLocatedBetween(propertyResult, DERIVED_PATH, offset(DERIVED_SOURCE, "count: int", 0));

        // Method references publish as a one-element overload list; the single normalized
        // declaration still reports SINGLE_SOURCE at the (nearest) FunctionDeclaration.
        var methodResult = FrontendSnapshotQueryService.definitionAt(
                snapshot, DERIVED_PATH, offsetInside(DERIVED_SOURCE, "greet", 1));
        assertEquals(DeclarationLookupResult.Kind.SINGLE_SOURCE, methodResult.kind());
        assertLocatedBetween(methodResult, DERIVED_PATH, offset(DERIVED_SOURCE, "func greet", 0));
    }

    @Test
    void definitionAtResolvesCrossFileClassReference() {
        // `extends QueryBase` is a scalar type position (no fact); the expression-position
        // class reference in `QueryBase.new()` carries the TYPE_META binding.
        var result = FrontendSnapshotQueryService.definitionAt(
                snapshot, DERIVED_PATH, offsetInside(DERIVED_SOURCE, "QueryBase", 1));
        assertEquals(DeclarationLookupResult.Kind.SINGLE_SOURCE, result.kind());
        var location = result.candidates().getFirst().location();
        assertNotNull(location);
        assertEquals(BASE_PATH, location.displayPath());
    }

    @Test
    void definitionAtReportsExternalForEngineAndBuiltinDeclarations() {
        var utility = FrontendSnapshotQueryService.definitionAt(
                snapshot, DERIVED_PATH, offsetInside(DERIVED_SOURCE, "print", 0));
        assertEquals(DeclarationLookupResult.Kind.EXTERNAL, utility.kind());

        var engineProperty = FrontendSnapshotQueryService.definitionAt(
                snapshot, NODE_USER_PATH, offsetInside(NODE_USER_SOURCE, "name", 1));
        assertEquals(DeclarationLookupResult.Kind.EXTERNAL, engineProperty.kind());
    }

    @Test
    void definitionAtReportsNoneWithoutFactOrProvenance() {
        var comment = FrontendSnapshotQueryService.definitionAt(
                snapshot, DERIVED_PATH, offsetInside(DERIVED_SOURCE, "a comment line", 0));
        assertEquals(DeclarationLookupResult.Kind.NONE, comment.kind());

        // Unbound identifiers carry no declaration provenance.
        var unbound = FrontendSnapshotQueryService.definitionAt(
                snapshot, DERIVED_PATH, offsetInside(DERIVED_SOURCE, "missing_name", 0));
        assertEquals(DeclarationLookupResult.Kind.NONE, unbound.kind());
    }

    // ------------------------------------------------------------------
    // usagesAt (acceptance 2)
    // ------------------------------------------------------------------

    @Test
    void usagesAtGroupsEveryUseSiteOfALocalVariable() {
        var usages = FrontendSnapshotQueryService.usagesAt(
                snapshot, DERIVED_PATH, offsetInside(DERIVED_SOURCE, "local_x", 1));
        assertEquals(2, usages.size());
        assertEquals(offset(DERIVED_SOURCE, "local_x", 1), usages.get(0).startByte());
        assertEquals(offset(DERIVED_SOURCE, "local_x", 2), usages.get(1).startByte());
        // The declaration itself is not a use site.
        assertTrue(usages.stream().noneMatch(site -> site.startByte() == offset(DERIVED_SOURCE, "local_x", 0)));
    }

    @Test
    void usagesAtGroupsBareAndQualifiedReferencesOfOneMethod() {
        // `QueryBase.greet` is a static-access-to-instance-method failure; its receiver-class
        // provenance is diagnostic context, not usage intent — the group must stay exactly the
        // two real reference sites.
        var usages = FrontendSnapshotQueryService.usagesAt(
                snapshot, DERIVED_PATH, offsetInside(DERIVED_SOURCE, "greet", 1));
        var startBytes = usages.stream().mapToInt(QuerySourceRange::startByte).toArray();
        assertEquals(2, usages.size(), () -> "expected bare + self.greet grouped, got " + usages);
        assertEquals(offset(DERIVED_SOURCE, "greet", 1), startBytes[0]);
        assertEquals(offset(DERIVED_SOURCE, "greet", 2), startBytes[1]);
    }

    @Test
    void usagesAtIncludesSitesInUndamagedSubtreesOfDamagedFiles() {
        // The playground contains a parse-damaged file; anchor that damage first.
        var hasParseError = snapshot.diagnostics().asList().stream().anyMatch(diagnostic ->
                diagnostic.category().startsWith("parse.") && diagnostic.severity().name().equals("ERROR"));
        assertTrue(hasParseError, "playground must carry a parse error from the damaged file");

        var usages = FrontendSnapshotQueryService.usagesAt(
                snapshot, DERIVED_PATH, offsetInside(DERIVED_SOURCE, "base_count", 0));
        var damagedSite = offset(DAMAGED_SOURCE, "base_count", 0);
        assertTrue(
                usages.stream().anyMatch(site ->
                        site.displayPath().equals(DAMAGED_PATH) && site.startByte() == damagedSite),
                () -> "healthy subtree of the damaged file must contribute usages, got " + usages
        );
        assertTrue(usages.stream().anyMatch(site -> site.displayPath().equals(DERIVED_PATH)));
    }

    @Test
    void usagesAtOmitsDocumentedAbsentSets() {
        // Type-position references are absent: the extends target carries no fact at all, and
        // while the constructor-call step of `QueryBase.new()` legitimately groups under the
        // class declaration, the TYPE_META head site itself must never appear in the group.
        assertTrue(FrontendSnapshotQueryService.usagesAt(
                snapshot, DERIVED_PATH, offsetInside(DERIVED_SOURCE, "QueryBase", 0)).isEmpty());
        var typeHeadOffset = offset(DERIVED_SOURCE, "QueryBase", 1);
        var usagesFromTypePosition = FrontendSnapshotQueryService.usagesAt(
                snapshot, DERIVED_PATH, offsetInside(DERIVED_SOURCE, "QueryBase", 1));
        assertTrue(
                usagesFromTypePosition.stream().noneMatch(site -> site.startByte() == typeHeadOffset),
                () -> "the TYPE_META head site itself must be absent, got " + usagesFromTypePosition
        );
        // A failed static member access on the class (`QueryBase.base_count`) must not become a
        // usage of the class either: DEFERRED/UNSUPPORTED/FAILED member sites are absent by
        // status, not by lack of provenance.
        var failedMemberOffset = offset(DERIVED_SOURCE, "base_count", 1);
        assertTrue(
                usagesFromTypePosition.stream().noneMatch(site -> site.startByte() == failedMemberOffset),
                () -> "failed static member step must be absent, got " + usagesFromTypePosition
        );
        // External declarations (utility functions) have no source identity to group under.
        assertTrue(FrontendSnapshotQueryService.usagesAt(
                snapshot, DERIVED_PATH, offsetInside(DERIVED_SOURCE, "print", 0)).isEmpty());
        // Unbound identifiers have no provenance at all.
        assertTrue(FrontendSnapshotQueryService.usagesAt(
                snapshot, DERIVED_PATH, offsetInside(DERIVED_SOURCE, "missing_name", 0)).isEmpty());
    }

    // ------------------------------------------------------------------
    // typeAt (acceptance 3)
    // ------------------------------------------------------------------

    @Test
    void typeAtReturnsPublishedTypesForExpressionStepAndSlotKeys() {
        assertEquals("int", FrontendSnapshotQueryService.typeAt(
                snapshot, DERIVED_PATH, offset(DERIVED_SOURCE, "= 3", 0) + 2).orElseThrow());
        assertEquals("float", FrontendSnapshotQueryService.typeAt(
                snapshot, DERIVED_PATH, offsetInside(DERIVED_SOURCE, "PI", 0)).orElseThrow());
        // Attribute step key: the property step itself carries the published type.
        assertEquals("StringName", FrontendSnapshotQueryService.typeAt(
                snapshot, NODE_USER_PATH, offsetInside(NODE_USER_SOURCE, "name", 1)).orElseThrow());
        // Variable slot key: the declaration node carries the slot type.
        assertEquals("int", FrontendSnapshotQueryService.typeAt(
                snapshot, DERIVED_PATH, offsetInside(DERIVED_SOURCE, "typed", 0)).orElseThrow());
    }

    @Test
    void typeAtReturnsEmptyForFactFreeNodes() {
        assertTrue(FrontendSnapshotQueryService.typeAt(
                snapshot, DERIVED_PATH, offsetInside(DERIVED_SOURCE, "a comment line", 0)).isEmpty());
        assertTrue(FrontendSnapshotQueryService.typeAt(snapshot, "/src/nope.gd", 0).isEmpty());
    }

    // ------------------------------------------------------------------
    // stale snapshot self-consistency + concurrent usages build (acceptance 6)
    // ------------------------------------------------------------------

    @Test
    void staleSnapshotQueriesStaySelfConsistent() {
        var path = "/src/stale.gd";
        var sourceV1 = "extends RefCounted\nfunc f() -> void:\n    var a = 1\n    var b = a\n";
        var localApi = new API();
        try {
            var stale = QueryTestSupport.analyze(localApi, "stale_module", files(file(path, sourceV1)));
            var usagesV1 = FrontendSnapshotQueryService.usagesAt(stale, path, offsetInside(sourceV1, "= a\n", 0));
            assertEquals(1, usagesV1.size());

            var sourceV2 = sourceV1 + "    var c = a\n";
            localApi.putFile("stale_module", path, sourceV2);
            var resultV2 = localApi.analyze("stale_module");
            assertEquals(gd.script.gdcc.api.AnalysisResult.Outcome.COMPLETED, resultV2.outcome());
            var fresh = localApi.getLatestAnalysisSnapshot("stale_module");
            assertNotNull(fresh);

            // The old snapshot still answers from its own frozen generation.
            assertEquals(usagesV1, FrontendSnapshotQueryService.usagesAt(stale, path, offsetInside(sourceV1, "= a\n", 0)));
            assertEquals(2, FrontendSnapshotQueryService.usagesAt(fresh, path, offsetInside(sourceV2, "= a\n", 0)).size());
        } finally {
            localApi.close();
        }
    }

    @Test
    void concurrentUsagesAtFirstBuildIsRaceFree() throws Exception {
        // A fresh module whose usages reverse index has NEVER been built: every worker's first
        // call races the lazy single-compute in queryMemo.
        var raceSource = "extends RefCounted\nfunc f() -> void:\n    var a = 1\n    var b = a\n    var c = a\n";
        var raceApi = new API();
        try {
            var raceSnapshot = QueryTestSupport.analyze(raceApi, "race_module", files(file("/src/race.gd", raceSource)));
            var cursor = offset(raceSource, "= a", 0) + 2;
            var threads = 8;
            var iterations = 25;
            var pool = Executors.newFixedThreadPool(threads);
            var ready = new CountDownLatch(threads);
            var go = new CountDownLatch(1);
            var futures = new ArrayList<Future<List<QuerySourceRange>>>();
            try {
                for (var t = 0; t < threads; t++) {
                    futures.add(pool.submit(() -> {
                        ready.countDown();
                        go.await();
                        List<QuerySourceRange> last = List.of();
                        for (var i = 0; i < iterations; i++) {
                            last = FrontendSnapshotQueryService.usagesAt(raceSnapshot, "/src/race.gd", cursor);
                        }
                        return last;
                    }));
                }
                assertTrue(ready.await(10, TimeUnit.SECONDS));
                go.countDown();
                // Collect every worker FIRST (propagating thread exceptions), and only then
                // compute the expected value — a main-thread query before this point could
                // win the race and pre-warm the memo itself.
                var observed = new ArrayList<List<QuerySourceRange>>(futures.size());
                for (var future : futures) {
                    observed.add(future.get(30, TimeUnit.SECONDS));
                }
                var expected = FrontendSnapshotQueryService.usagesAt(raceSnapshot, "/src/race.gd", cursor);
                assertEquals(2, expected.size());
                for (var actual : observed) {
                    assertEquals(expected, actual);
                }
            } finally {
                pool.shutdownNow();
            }
        } finally {
            raceApi.close();
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /// Asserts the single located candidate sits in `displayPath` and its range contains the
    /// expected declaration token offset.
    private static void assertLocatedBetween(DeclarationLookupResult result, String displayPath, int tokenOffset) {
        var location = result.candidates().getFirst().location();
        assertNotNull(location);
        assertEquals(displayPath, location.displayPath());
        assertTrue(
                location.startByte() <= tokenOffset && tokenOffset < location.endByte(),
                () -> "declaration range " + location + " must contain token offset " + tokenOffset
        );
    }
}
