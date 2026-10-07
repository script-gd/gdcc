package gd.script.gdcc.api.analysis;

import gd.script.gdcc.api.API;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static gd.script.gdcc.api.analysis.QueryTestSupport.file;
import static gd.script.gdcc.api.analysis.QueryTestSupport.files;
import static gd.script.gdcc.api.analysis.QueryTestSupport.offset;
import static gd.script.gdcc.api.analysis.QueryTestSupport.offsetInside;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for `documentationAt` (`frontend_lsp_foundation_implementation.md` §2.4):
/// ENGINE/BUILTIN members resolve the ACTUAL declaring class (never the receiver's static
/// type), utility functions split by registry provenance, globals classify by registry
/// provenance (not value type), GDCC symbols carry every normalized source position, and
/// non-RESOLVED / unbound sites return empty.
class DocumentationAtTest {
    private static final String DERIVED_PATH = "/src/doc_derived.gd";
    private static final String DERIVED_SOURCE = """
            class_name DocDerived
            extends DocBase
            
            enum State { IDLE, RUN }
            
            var count: int = 1
            
            func greet() -> String:
                return "derived"
            
            func use(param: int) -> void:
                var local_x = param
                var c2 = self.count
                var f1 = greet
                var st = State.IDLE
                print(local_x)
                var p_ref = print
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
            """;
    private static final String BASE_PATH = "/src/doc_base.gd";
    private static final String BASE_SOURCE = """
            class_name DocBase
            extends RefCounted
            
            func greet() -> String:
                return "base"
            """;
    private static final String NODE_USER_PATH = "/src/doc_node_user.gd";
    private static final String NODE_USER_SOURCE = """
            class_name DocNodeUser
            extends Node2D
            
            var mode = Node2D.PROCESS_MODE_INHERIT
            var notif = Node2D.NOTIFICATION_READY
            
            func ready() -> void:
                var n = self.name
                self.hide()
                var s = self.tree_exiting
            """;

    private static API api;
    private static ModuleAnalysisSnapshot snapshot;

    @BeforeAll
    static void analyzePlayground() {
        api = new API();
        snapshot = QueryTestSupport.analyze(api, "doc_playground", files(
                file(BASE_PATH, BASE_SOURCE),
                file(DERIVED_PATH, DERIVED_SOURCE),
                file(NODE_USER_PATH, NODE_USER_SOURCE)
        ));
    }

    @AfterAll
    static void closeApi() {
        api.close();
    }

    @Test
    void engineMembersResolveTheirActualDeclaringClass() {
        // hide() is declared on CanvasItem; the receiver's static type is DocNodeUser (Node2D
        // subclass) — the owner must be the declaring class, not the receiver type.
        assertDescriptor(
                DocSymbolKind.METHOD, DocNamespace.ENGINE, "CanvasItem", "hide",
                FrontendSnapshotQueryService.documentationAt(
                        snapshot, NODE_USER_PATH, offsetInside(NODE_USER_SOURCE, "hide", 0)).orElseThrow());
        // name is declared on Node.
        assertDescriptor(
                DocSymbolKind.PROPERTY, DocNamespace.ENGINE, "Node", "name",
                FrontendSnapshotQueryService.documentationAt(
                        snapshot, NODE_USER_PATH, offsetInside(NODE_USER_SOURCE, "name", 1)).orElseThrow());
        // tree_exiting is declared on Node.
        assertDescriptor(
                DocSymbolKind.SIGNAL, DocNamespace.ENGINE, "Node", "tree_exiting",
                FrontendSnapshotQueryService.documentationAt(
                        snapshot, NODE_USER_PATH, offsetInside(NODE_USER_SOURCE, "tree_exiting", 0)).orElseThrow());
        // Inherited enum value accessed through Node2D is still declared on Node.
        assertDescriptor(
                DocSymbolKind.ENUM_VALUE, DocNamespace.ENGINE, "Node", "PROCESS_MODE_INHERIT",
                FrontendSnapshotQueryService.documentationAt(
                        snapshot, NODE_USER_PATH, offsetInside(NODE_USER_SOURCE, "PROCESS_MODE_INHERIT", 0)).orElseThrow());
        // Inherited constant accessed through Node2D is still declared on Node.
        assertDescriptor(
                DocSymbolKind.CONSTANT, DocNamespace.ENGINE, "Node", "NOTIFICATION_READY",
                FrontendSnapshotQueryService.documentationAt(
                        snapshot, NODE_USER_PATH, offsetInside(NODE_USER_SOURCE, "NOTIFICATION_READY", 0)).orElseThrow());
    }

    @Test
    void builtinMembersResolveTheBuiltinClass() {
        assertDescriptor(
                DocSymbolKind.PROPERTY, DocNamespace.BUILTIN, "Vector2", "x",
                FrontendSnapshotQueryService.documentationAt(
                        snapshot, DERIVED_PATH, offsetInside(DERIVED_SOURCE, ".x", 0)).orElseThrow());
        assertDescriptor(
                DocSymbolKind.METHOD, DocNamespace.BUILTIN, "Vector2", "normalized",
                FrontendSnapshotQueryService.documentationAt(
                        snapshot, DERIVED_PATH, offsetInside(DERIVED_SOURCE, "normalized", 0)).orElseThrow());
        assertDescriptor(
                DocSymbolKind.CONSTANT, DocNamespace.BUILTIN, "Vector2", "ZERO",
                FrontendSnapshotQueryService.documentationAt(
                        snapshot, DERIVED_PATH, offsetInside(DERIVED_SOURCE, "ZERO", 0)).orElseThrow());
    }

    @Test
    void utilityFunctionsSplitByRegistryProvenance() {
        // Dump-sourced utility function -> @GlobalScope; registry-synthesized GDScript language
        // function -> @GDScript. Both publish ownerKind=ENGINE, so this split must NOT key on
        // ownerKind.
        var print = FrontendSnapshotQueryService.documentationAt(
                snapshot, DERIVED_PATH, offsetInside(DERIVED_SOURCE, "print", 0)).orElseThrow();
        assertDescriptor(DocSymbolKind.UTILITY_FUNCTION, DocNamespace.GLOBAL_SCOPE, null, "print", print);

        var len = FrontendSnapshotQueryService.documentationAt(
                snapshot, DERIVED_PATH, offsetInside(DERIVED_SOURCE, "len", 0)).orElseThrow();
        assertDescriptor(DocSymbolKind.UTILITY_FUNCTION, DocNamespace.GDSCRIPT, null, "len", len);

        // A bare (un-called) utility reference classifies identically — it publishes as a
        // collection of ExtensionUtilityFunction and must be unwrapped, not mistaken for an
        // engine method overload set.
        var barePrint = FrontendSnapshotQueryService.documentationAt(
                snapshot, DERIVED_PATH, offsetInside(DERIVED_SOURCE, "print", 1)).orElseThrow();
        assertDescriptor(DocSymbolKind.UTILITY_FUNCTION, DocNamespace.GLOBAL_SCOPE, null, "print", barePrint);
    }

    @Test
    void globalsClassifyByRegistryProvenanceNotValueType() {
        // Synthesized language constant -> @GDScript.
        var pi = FrontendSnapshotQueryService.documentationAt(
                snapshot, DERIVED_PATH, offsetInside(DERIVED_SOURCE, "PI", 0)).orElseThrow();
        assertDescriptor(DocSymbolKind.CONSTANT, DocNamespace.GDSCRIPT, null, "PI", pi);

        // Bare global enum value -> @GlobalScope (its VALUE type is int; that must not route it
        // to an int/builtin owner).
        var bare = FrontendSnapshotQueryService.documentationAt(
                snapshot, DERIVED_PATH, offsetInside(DERIVED_SOURCE, "MOUSE_BUTTON_LEFT", 0)).orElseThrow();
        assertDescriptor(DocSymbolKind.ENUM_VALUE, DocNamespace.GLOBAL_SCOPE, null, "MOUSE_BUTTON_LEFT", bare);

        // Qualified global enum value access -> @GlobalScope as well.
        var qualified = FrontendSnapshotQueryService.documentationAt(
                snapshot, DERIVED_PATH, offsetInside(DERIVED_SOURCE, "KEY_A", 0)).orElseThrow();
        assertDescriptor(DocSymbolKind.ENUM_VALUE, DocNamespace.GLOBAL_SCOPE, null, "KEY_A", qualified);

        // The enum group name itself -> ENUM_GROUP/@GlobalScope.
        var group = FrontendSnapshotQueryService.documentationAt(
                snapshot, DERIVED_PATH, offsetInside(DERIVED_SOURCE, "Key", 0)).orElseThrow();
        assertDescriptor(DocSymbolKind.ENUM_GROUP, DocNamespace.GLOBAL_SCOPE, null, "Key", group);
    }

    @Test
    void gdccSymbolsCarryDeclaringOwnerAndSourcePositions() {
        var local = FrontendSnapshotQueryService.documentationAt(
                snapshot, DERIVED_PATH, offsetInside(DERIVED_SOURCE, "local_x", 1)).orElseThrow();
        assertDescriptor(DocSymbolKind.LOCAL_VARIABLE, DocNamespace.GDCC, null, "local_x", local);
        assertEquals(1, local.sourceLocations().size());
        assertEquals(DERIVED_PATH, local.sourceLocations().getFirst().displayPath());

        var param = FrontendSnapshotQueryService.documentationAt(
                snapshot, DERIVED_PATH, offsetInside(DERIVED_SOURCE, "param", 1)).orElseThrow();
        assertDescriptor(DocSymbolKind.PARAMETER, DocNamespace.GDCC, null, "param", param);
        assertEquals(1, param.sourceLocations().size());

        // Un-called (overridden) method reference: the nearest declaration (DocDerived.greet)
        // shadows DocBase.greet, so the position lands in the derived file.
        var method = FrontendSnapshotQueryService.documentationAt(
                snapshot, DERIVED_PATH, offsetInside(DERIVED_SOURCE, "greet", 1)).orElseThrow();
        assertDescriptor(DocSymbolKind.METHOD, DocNamespace.GDCC, "DocDerived", "greet", method);
        assertEquals(1, method.sourceLocations().size());
        assertEquals(DERIVED_PATH, method.sourceLocations().getFirst().displayPath());
        assertTrue(method.sourceLocations().getFirst().startByte() <= offset(DERIVED_SOURCE, "func greet", 0));

        // Property through self: declaring class is DocDerived.
        var property = FrontendSnapshotQueryService.documentationAt(
                snapshot, DERIVED_PATH, offsetInside(DERIVED_SOURCE, "count", 1)).orElseThrow();
        assertDescriptor(DocSymbolKind.PROPERTY, DocNamespace.GDCC, "DocDerived", "count", property);

        // GDCC enum group and enum value carry source positions.
        var enumGroup = FrontendSnapshotQueryService.documentationAt(
                snapshot, DERIVED_PATH, offsetInside(DERIVED_SOURCE, "State", 1)).orElseThrow();
        assertDescriptor(DocSymbolKind.ENUM_GROUP, DocNamespace.GDCC, "DocDerived", "State", enumGroup);
        assertEquals(1, enumGroup.sourceLocations().size());

        var enumValue = FrontendSnapshotQueryService.documentationAt(
                snapshot, DERIVED_PATH, offsetInside(DERIVED_SOURCE, "IDLE", 1)).orElseThrow();
        assertDescriptor(DocSymbolKind.ENUM_VALUE, DocNamespace.GDCC, "DocDerived", "IDLE", enumValue);
        assertEquals(1, enumValue.sourceLocations().size());
    }

    @Test
    void nonResolvedAndUnboundSitesProduceNoDescriptor() {
        // DYNAMIC site (untyped receiver).
        assertTrue(FrontendSnapshotQueryService.documentationAt(
                snapshot, DERIVED_PATH, offsetInside(DERIVED_SOURCE, ".x", 1)).isEmpty());
        // FAILED member site.
        assertTrue(FrontendSnapshotQueryService.documentationAt(
                snapshot, DERIVED_PATH, offsetInside(DERIVED_SOURCE, "no_such_member", 0)).isEmpty());
        // Unbound identifier.
        assertTrue(FrontendSnapshotQueryService.documentationAt(
                snapshot, DERIVED_PATH, offsetInside(DERIVED_SOURCE, "missing_name", 0)).isEmpty());
        // Unknown path.
        assertTrue(FrontendSnapshotQueryService.documentationAt(snapshot, "/src/nope.gd", 0).isEmpty());
    }

    private static void assertDescriptor(
            DocSymbolKind kind,
            DocNamespace namespace,
            String ownerName,
            String memberName,
            SymbolDocDescriptor descriptor
    ) {
        assertEquals(kind, descriptor.symbolKind());
        assertEquals(namespace, descriptor.namespace());
        assertEquals(ownerName, descriptor.ownerName());
        assertEquals(memberName, descriptor.memberName());
        if (namespace == DocNamespace.GDCC) {
            assertNotNull(descriptor.sourceLocations());
        } else {
            assertTrue(descriptor.sourceLocations().isEmpty(), "external descriptors carry no source locations");
        }
    }
}
