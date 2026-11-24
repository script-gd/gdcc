package gd.script.gdcc.api.analysis;

import dev.superice.gdparser.frontend.ast.AttributeExpression;
import dev.superice.gdparser.frontend.ast.ClassDeclaration;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.LambdaExpression;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.Parameter;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import gd.script.gdcc.api.API;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnosticSeverity;
import gd.script.gdcc.frontend.scope.BlockScope;
import gd.script.gdcc.frontend.sema.FrontendExpressionTypeStatus;
import gd.script.gdcc.type.GdFloatVectorType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static gd.script.gdcc.api.analysis.QueryTestSupport.file;
import static gd.script.gdcc.api.analysis.QueryTestSupport.files;
import static gd.script.gdcc.api.analysis.QueryTestSupport.offset;
import static gd.script.gdcc.api.analysis.QueryTestSupport.offsetInside;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for `FrontendSnapshotCompletionService`
/// (`frontend_lsp_foundation_implementation.md` §2.5): member access enumerates the instance
/// surface for typed receivers and the static surface for class-name receivers, identifier
/// prefixes enumerate visible
/// values with declaration-after-use filtering plus the global namespace, type positions
/// enumerate type names and file inner classes, skipped/error subtrees and fact-free receivers
/// yield empty results without exceptions, and a stale snapshot answers from its own text.
class CompletionServiceTest {
    private static final String FIXTURE_PATH = "/src/comp_fixture.gd";

    // A following statement exercises replacement-range isolation across line boundaries.
    // Function-tail cases separately verify that receiver facts survive at the end of a suite.
    private static final String NODE_PATH = "/src/comp_node.gd";
    private static final String NODE_SOURCE = """
            class_name CompNodeUser
            extends Node2D
            
            func ready_a() -> void:
                var a = self.
                var ta = 1
            
            func ready_b() -> void:
                var b = self.na
            
            func ready_cast() -> void:
                var c = (self as Node2D).na
            """;

    private static final String BUILTIN_PATH = "/src/comp_builtin.gd";
    private static final String BUILTIN_SOURCE = """
            # 中文注释：非 ASCII 字节偏移必须仍然正确
            class_name CompBuiltinUser
            extends Node
            
            var v: Vector2 = Vector2(1, 2)
            
            func make() -> Vector2:
                return Vector2(1, 2)
            
            func use_v() -> void:
                var a = v.
                var t1 = 1
            
            func use_factory() -> void:
                var b = make().
                var t2 = 1
            
            func use_chain() -> void:
                var c = v.normalized().no
            
            func use_static() -> void:
                var z = Vector2.
                var t3 = 1
            
            func use_tail() -> void:
                var dropped = v.
            
            func use_untyped() -> void:
                var vu = Vector2(1, 2)
                var bad = vu.
                var t4 = 1
            
            func use_paren() -> void:
                var pc = (v).no
            
            func foo(vv: Vector2) -> String:
                return ""
            
            func bar() -> String:
                return ""
            
            func use_nested_call() -> void:
                var ok = (foo((v))).le
            
            func use_failed_call() -> void:
                var bad = (bar((v))).le
            """;

    private static final String STATIC_PATH = "/src/comp_static.gd";
    private static final String STATIC_SOURCE = """
            class_name CompStaticUser
            extends Node
            
            enum State { IDLE, RUNNING }
            
            static func build() -> int:
                return 1
            
            func instance_fn() -> void:
                pass
            
            class Base:
                static func smake() -> int:
                    return 1
            
            class Derived extends Base:
                func smake() -> int:
                    return 2
            
            func use_a() -> void:
                var a = CompStaticUser.
                var t1 = 1
            
            func use_s() -> void:
                var s = State.
                var t2 = 1
            
            func use_q() -> void:
                var q = CompStaticUser.State.
                var t3 = 1
            
            func use_b() -> void:
                var b = Base.
                var t5 = 1
            
            func use_sub() -> void:
                var e = CompStaticUser.State["IDLE"].
                var t6 = 1
            
            func use_d() -> void:
                var d = Derived.
                var t4 = 1
            """;

    private static final String IDENT_PATH = "/src/comp_ident.gd";
    private static final String IDENT_SOURCE = """
            class_name CompIdUser
            extends Node2D
            
            var hp = 10
            var PI = 3.14
            
            func len(x):
                return x
            
            func hide(x):
                pass
            
            func compute(seed: int) -> void:
                var before = 1
                var mid = se
                var after = 3
                var total = seed + se
                var y = compute()
            
            func use_super() -> void:
                super.
                var t = 1
            
            class RootBase:
                func act(x) -> void:
                    pass
            
            class MidBase extends RootBase:
                func act() -> void:
                    pass
            
            class LeafClass extends MidBase:
                func probe() -> void:
                    super.
                    var t = 1
            """;

    private static final String TYPE_PATH = "/src/comp_type.gd";
    private static final String TYPE_SOURCE = """
            class_name CompTypeUser
            extends Node
            
            class Inner:
                pass
            
            func f() -> void:
                var v: Ve
            """;

    private static final String SKIP_PATH = "/src/comp_skip.gd";
    private static final String SKIP_SOURCE = """
            class_name CompSkipUser
            extends Node
            
            func f() -> void:
                var ok = 1
                var valid = ok
                var x = ok if  else false
            """;

    private static final String MASK_BASE_PATH = "/src/comp_mask_base.gd";
    private static final String MASK_BASE_SOURCE = """
            class_name CompMaskBase
            extends Node
            
            const INF = MISSING
            const TAU = MISSING
            var hp = 1
            """;

    private static final String MASK_PATH = "/src/comp_mask.gd";
    private static final String MASK_SOURCE = """
            class_name CompMaskUser
            extends CompMaskBase
            
            const PI = MISSING
            const hp = MISSING
            var safe = 1
            var TAU = 1
            
            func probe() -> void:
                var ok = cu
                var t = 1
            
            func probe2() -> void:
                var c: CompMaskUser
                var x = c.hp
                var t2 = 1
            """;

    private static API api;
    private static ModuleAnalysisSnapshot snapshot;

    @BeforeAll
    static void analyzePlayground() {
        api = new API();
        snapshot = QueryTestSupport.analyze(api, "completion_playground", files(
                file(NODE_PATH, NODE_SOURCE),
                file(BUILTIN_PATH, BUILTIN_SOURCE),
                file(STATIC_PATH, STATIC_SOURCE),
                file(IDENT_PATH, IDENT_SOURCE),
                file(TYPE_PATH, TYPE_SOURCE),
                file(SKIP_PATH, SKIP_SOURCE),
                file(MASK_BASE_PATH, MASK_BASE_SOURCE),
                file(MASK_PATH, MASK_SOURCE)
        ), Map.of("CompTypeUser", "CompTypeUser"));
    }

    @AfterAll
    static void closeApi() {
        api.close();
    }

    // ------------------------------------------------------------------
    // Acceptance 1: member access candidates
    // ------------------------------------------------------------------

    @Test
    void memberAccessOnSelfListsInheritedEngineMembers() {
        var cursor = endOf(NODE_SOURCE, "self.", 0);
        var result = FrontendSnapshotCompletionService.completionCandidatesAt(snapshot, NODE_PATH, cursor);
        assertEquals(CompletionContextKind.MEMBER_ACCESS, result.contextKind());
        // Zero-width replaceable range right after the dot.
        assertNotNull(result.replaceableRange());
        assertEquals(cursor, result.replaceableRange().startByte());
        assertEquals(cursor, result.replaceableRange().endByte());
        // Node property, CanvasItem method and Node signal all surface through the hierarchy.
        assertHas(result, "name", CompletionCandidateKind.PROPERTY);
        assertHas(result, "hide", CompletionCandidateKind.METHOD);
        assertHas(result, "tree_exiting", CompletionCandidateKind.VALUE);
    }

    @Test
    void memberAccessInsideMemberPrefixCoversFullIdentifier() {
        // Cursor rests exactly at the end of the `na` prefix: the replaceable range must cover
        // the whole `na` token so accepting `name` replaces both sides of the cursor.
        var cursor = endOf(NODE_SOURCE, "self.na", 0);
        var result = FrontendSnapshotCompletionService.completionCandidatesAt(snapshot, NODE_PATH, cursor);
        assertEquals(CompletionContextKind.MEMBER_ACCESS, result.contextKind());
        assertNotNull(result.replaceableRange());
        assertEquals(endOf(NODE_SOURCE, "self.", 1), result.replaceableRange().startByte());
        assertEquals(cursor, result.replaceableRange().endByte());
        assertHas(result, "name", CompletionCandidateKind.PROPERTY);
        // A cursor INSIDE the prefix classifies identically (acceptance 1's `obj.pa|r` form).
        var interior = FrontendSnapshotCompletionService.completionCandidatesAt(
                snapshot, NODE_PATH, result.replaceableRange().startByte() + 1);
        assertEquals(result.contextKind(), interior.contextKind());
        assertEquals(result.replaceableRange(), interior.replaceableRange());
        assertHas(interior, "name", CompletionCandidateKind.PROPERTY);
    }

    @Test
    void memberAccessOnBuiltinInstanceListsMembers() {
        var result = FrontendSnapshotCompletionService.completionCandidatesAt(
                snapshot, BUILTIN_PATH, endOf(BUILTIN_SOURCE, "v.", 0));
        assertEquals(CompletionContextKind.MEMBER_ACCESS, result.contextKind());
        assertHas(result, "x", CompletionCandidateKind.PROPERTY);
        assertHas(result, "y", CompletionCandidateKind.PROPERTY);
        assertHas(result, "normalized", CompletionCandidateKind.METHOD);
    }

    @Test
    void memberAccessOnCallAndChainedReceiversResolvesTypes() {
        // `factory().` style: a lone partial chain maps to MissingAttributeStep, so the bare
        // call receiver keeps its declared return type for member enumeration.
        var callResult = FrontendSnapshotCompletionService.completionCandidatesAt(
                snapshot, BUILTIN_PATH, endOf(BUILTIN_SOURCE, "make().", 0));
        assertEquals(CompletionContextKind.MEMBER_ACCESS, callResult.contextKind());
        assertHas(callResult, "x", CompletionCandidateKind.PROPERTY);
        // Chained receiver with a member prefix already typed: the full chain parses normally
        // and the final call step of `v.normalized()` carries the published type.
        var chainedResult = FrontendSnapshotCompletionService.completionCandidatesAt(
                snapshot, BUILTIN_PATH, endOf(BUILTIN_SOURCE, "v.normalized().no", 0));
        assertEquals(CompletionContextKind.MEMBER_ACCESS, chainedResult.contextKind());
        assertNotNull(chainedResult.replaceableRange());
        assertEquals(endOf(BUILTIN_SOURCE, "v.normalized().", 0), chainedResult.replaceableRange().startByte());
        assertHas(chainedResult, "x", CompletionCandidateKind.PROPERTY);
    }

    @Test
    void memberAccessOnTypedReceiverAtFunctionTailListsMembers() {
        var cursor = endOf(BUILTIN_SOURCE, "dropped = v.", 0);
        var result = FrontendSnapshotCompletionService.completionCandidatesAt(snapshot, BUILTIN_PATH, cursor);
        assertEquals(CompletionContextKind.MEMBER_ACCESS, result.contextKind());
        assertNotNull(result.replaceableRange());
        assertEquals(cursor, result.replaceableRange().startByte());
        assertEquals(cursor, result.replaceableRange().endByte());
        assertHas(result, "x", CompletionCandidateKind.PROPERTY);
        assertHas(result, "y", CompletionCandidateKind.PROPERTY);
        assertHas(result, "normalized", CompletionCandidateKind.METHOD);
    }

    @Test
    void memberAccessOnUnknownReceiverReturnsEmptyWithoutThrowing() {
        var path = "/src/comp_unknown_receiver.gd";
        var source = """
                class_name ReceiverProbe
                extends Node
                var v: Vector2 = Vector2(1, 2)
                func test():
                    var out = unknown_recv.
                func after():
                    pass
                """;
        var unknownSnapshot = QueryTestSupport.analyze(api, "completion_unknown_receiver", files(file(path, source)));
        var unitIndex = unknownSnapshot.astIndex().forDisplayPath(path);
        assertNotNull(unitIndex);
        var receiver = assertInstanceOf(IdentifierExpression.class, unitIndex.nodeAt(offset(source, "unknown_recv", 0)));
        var analysis = unknownSnapshot.analysisData();
        var receiverType = analysis.expressionTypes().get(receiver);
        assertNotNull(receiverType);
        assertEquals(FrontendExpressionTypeStatus.FAILED, receiverType.status());
        assertNull(receiverType.publishedType());
        for (Node node = receiver; node != null; node = unitIndex.parentOf(node)) {
            assertFalse(analysis.skippedSubtreeRoots().containsKey(node));
        }

        var cursor = endOf(source, "unknown_recv.", 0);
        var result = FrontendSnapshotCompletionService.completionCandidatesAt(
                unknownSnapshot, path, cursor);
        assertEquals(CompletionContextKind.MEMBER_ACCESS, result.contextKind());
        assertNotNull(result.replaceableRange());
        assertEquals(cursor, result.replaceableRange().startByte());
        assertEquals(cursor, result.replaceableRange().endByte());
        assertTrue(result.candidates().isEmpty());
    }

    @Test
    void memberAccessOnClassNameListsStaticSurfaceOnly() {
        var result = FrontendSnapshotCompletionService.completionCandidatesAt(
                snapshot, STATIC_PATH, endOf(STATIC_SOURCE, "CompStaticUser.", 0));
        assertEquals(CompletionContextKind.MEMBER_ACCESS, result.contextKind());
        assertHas(result, "build", CompletionCandidateKind.METHOD);
        // Enum group, its values and an inherited engine class constant complete on the class.
        assertHas(result, "State", CompletionCandidateKind.VALUE);
        assertHas(result, "IDLE", CompletionCandidateKind.VALUE);
        assertHas(result, "PROCESS_MODE_INHERIT", CompletionCandidateKind.VALUE);
        // Members without the static modifier are never listed for a class-name receiver.
        assertLacks(result, "instance_fn");
        assertLacks(result, "name");
    }

    @Test
    void memberAccessOnBuiltinClassNameListsConstantsButNotInstanceMembers() {
        var result = FrontendSnapshotCompletionService.completionCandidatesAt(
                snapshot, BUILTIN_PATH, endOf(BUILTIN_SOURCE, "Vector2.", 0));
        assertEquals(CompletionContextKind.MEMBER_ACCESS, result.contextKind());
        assertHas(result, "ZERO", CompletionCandidateKind.VALUE);
        assertLacks(result, "x");
        assertLacks(result, "normalized");
    }

    @Test
    void memberAccessOnEnumGroupListsValues() {
        var result = FrontendSnapshotCompletionService.completionCandidatesAt(
                snapshot, STATIC_PATH, endOf(STATIC_SOURCE, "State.", 0));
        assertEquals(CompletionContextKind.MEMBER_ACCESS, result.contextKind());
        assertHas(result, "IDLE", CompletionCandidateKind.VALUE);
        assertHas(result, "RUNNING", CompletionCandidateKind.VALUE);
        assertLacks(result, "build");
    }

    @Test
    void memberAccessOnQualifiedEnumGroupListsValues() {
        // The `State` step of `CompStaticUser.State` carries its group declaration on the
        // RESOLVED member fact: same value enumeration as the bare enum receiver.
        var result = FrontendSnapshotCompletionService.completionCandidatesAt(
                snapshot, STATIC_PATH, endOf(STATIC_SOURCE, "CompStaticUser.State.", 0));
        assertEquals(CompletionContextKind.MEMBER_ACCESS, result.contextKind());
        assertHas(result, "IDLE", CompletionCandidateKind.VALUE);
        assertHas(result, "RUNNING", CompletionCandidateKind.VALUE);
        assertLacks(result, "build");
    }

    @Test
    void staticSurfaceShadowsAncestorStaticBehindSubclassInstance() {
        // Base offers its static method on the class-name receiver.
        var baseResult = FrontendSnapshotCompletionService.completionCandidatesAt(
                snapshot, STATIC_PATH, endOf(STATIC_SOURCE, "Base.", 0));
        assertHas(baseResult, "smake", CompletionCandidateKind.METHOD);
        // Derived declares an INSTANCE smake: terminal shadowing hides the ancestor's static
        // smake instead of leaking it onto Derived's static surface.
        var derivedResult = FrontendSnapshotCompletionService.completionCandidatesAt(
                snapshot, STATIC_PATH, endOf(STATIC_SOURCE, "Derived.", 0));
        assertEquals(CompletionContextKind.MEMBER_ACCESS, derivedResult.contextKind());
        assertLacks(derivedResult, "smake");
    }

    @Test
    void memberAccessOnParenthesizedReceiverResolvesInnerExpression() {
        // The AST maps parentheses away, so the typed inner expression ends before `)`. The
        // fixture's leading CJK comment also keeps the fallback's UTF-8 byte math honest.
        var result = FrontendSnapshotCompletionService.completionCandidatesAt(
                snapshot, BUILTIN_PATH, endOf(BUILTIN_SOURCE, "(v).no", 0));
        assertEquals(CompletionContextKind.MEMBER_ACCESS, result.contextKind());
        assertHas(result, "x", CompletionCandidateKind.PROPERTY);
        assertNotNull(result.replaceableRange());
        assertEquals(endOf(BUILTIN_SOURCE, "(v).", 0), result.replaceableRange().startByte());
    }

    @Test
    void parenthesizedReceiverSkipsOnlyAsciiWhitespace() {
        var path = "/src/comp_receiver_trivia.gd";
        var source = """
                # UTF-8 前缀
                class_name CompReceiverTrivia
                extends Node
                var v: Vector2
                
                func probe():
                    var single = (v \t).no
                    var lf = (v \t
                    ).no
                    var crlf = (v \t\r
                    ).no
                    var cr = (v\r).no
                    var comment = (v # ignored
                    ).no
                    var continuation = (v \\
                    ).no
                """;
        var triviaSnapshot = QueryTestSupport.analyze(api, "completion_receiver_trivia", files(file(path, source)));
        var analysis = triviaSnapshot.analysisData();
        var unitIndex = triviaSnapshot.astIndex().forDisplayPath(path);
        assertNotNull(unitIndex);
        var parserDiagnostics = triviaSnapshot.diagnostics().asList().stream()
                .filter(diagnostic -> diagnostic.category().startsWith("parse."))
                .toList();
        assertEquals(1, parserDiagnostics.size(), parserDiagnostics::toString);
        var strayCr = parserDiagnostics.getFirst();
        assertEquals("parse.lexical.error", strayCr.category());
        assertEquals(FrontendDiagnosticSeverity.ERROR, strayCr.severity());
        assertEquals("Stray carriage return character in source code.", strayCr.message());
        var crOffset = offset(source, "\r).no", 0);
        assertEquals(crOffset, strayCr.range().startByte());
        assertEquals(crOffset + 1, strayCr.range().endByte());

        var cases = List.of("single", "lf", "crlf", "cr", "comment", "continuation");
        for (var occurrence = 0; occurrence < cases.size(); occurrence++) {
            var name = cases.get(occurrence);
            var cursor = endOf(source, ").no", occurrence);
            var result = FrontendSnapshotCompletionService.completionCandidatesAt(triviaSnapshot, path, cursor);
            assertEquals(CompletionContextKind.MEMBER_ACCESS, result.contextKind(), name);
            assertNotNull(result.replaceableRange(), name);
            assertEquals(cursor - 2, result.replaceableRange().startByte(), name);
            assertEquals(cursor, result.replaceableRange().endByte(), name);

            var expression = assertInstanceOf(AttributeExpression.class, unitIndex.parentOf(unitIndex.nodeAt(cursor - 1)));
            var declaration = assertInstanceOf(VariableDeclaration.class, unitIndex.parentOf(expression));
            assertEquals(name, declaration.name());
            var receiver = assertInstanceOf(IdentifierExpression.class, expression.base());
            assertEquals("v", receiver.name());
            if (name.equals("cr")) {
                // A standalone CR is a lexical error, not a whitespace case for the receiver scan.
                var recoveries = analysis.parseRecoveriesByOwner().get(declaration);
                assertNotNull(recoveries);
                assertEquals(1, recoveries.size());
                var recovery = recoveries.getFirst();
                assertSame(declaration, recovery.owner());
                assertEquals("variable.initializer", recovery.facet());
                assertEquals(strayCr.category(), recovery.code());
                assertEquals(strayCr.range(), recovery.range());
                assertTrue(analysis.skippedSubtreeRoots().containsKey(declaration));
                assertNull(analysis.expressionTypes().get(receiver));
                assertTrue(result.candidates().isEmpty(), name);
                continue;
            }

            // The comment and continuation cases must reach the scanner with a usable receiver.
            assertFalse(analysis.skippedSubtreeRoots().containsKey(declaration), name);
            var receiverType = analysis.expressionTypes().get(receiver);
            assertNotNull(receiverType, name);
            assertEquals(FrontendExpressionTypeStatus.RESOLVED, receiverType.status(), name);
            assertEquals(GdFloatVectorType.VECTOR2, receiverType.publishedType(), name);
            if (name.equals("comment") || name.equals("continuation")) {
                assertTrue(result.candidates().isEmpty(), name);
            } else {
                assertHas(result, "x", CompletionCandidateKind.PROPERTY);
            }
        }
    }

    @Test
    void memberAccessOnNestedCallReceiverStopsAtCallNode() {
        // `(foo((v))).le`: the outer wrap peels once, then the typed call node (String result)
        // matches at its own `)` — peeling must NOT continue into the argument `v`.
        var result = FrontendSnapshotCompletionService.completionCandidatesAt(
                snapshot, BUILTIN_PATH, endOf(BUILTIN_SOURCE, "(foo((v))).le", 0));
        assertEquals(CompletionContextKind.MEMBER_ACCESS, result.contextKind());
        assertHas(result, "length", CompletionCandidateKind.METHOD);
        assertLacks(result, "x");
    }

    @Test
    void memberAccessOnFailedCallReceiverReturnsEmpty() {
        // `(bar((v))).le`: `bar((v))` has invalid arity, so the matched call has no usable
        // result type — terminal empty instead of peeling into the argument `v`.
        var result = FrontendSnapshotCompletionService.completionCandidatesAt(
                snapshot, BUILTIN_PATH, endOf(BUILTIN_SOURCE, "(bar((v))).le", 0));
        assertEquals(CompletionContextKind.MEMBER_ACCESS, result.contextKind());
        assertTrue(result.candidates().isEmpty());
    }

    @Test
    void memberAccessOnCastReceiverResolvesInnerExpression() {
        var result = FrontendSnapshotCompletionService.completionCandidatesAt(
                snapshot, NODE_PATH, endOf(NODE_SOURCE, "(self as Node2D).na", 0));
        assertEquals(CompletionContextKind.MEMBER_ACCESS, result.contextKind());
        assertHas(result, "name", CompletionCandidateKind.PROPERTY);
    }

    @Test
    void superReceiverListsLexicalSuperclassMethodsOnly() {
        // The super contract supports method calls from the lexical superclass and rejects
        // property access: methods of the Node2D hierarchy complete, but neither the enclosing
        // class's own members nor any property.
        var result = FrontendSnapshotCompletionService.completionCandidatesAt(
                snapshot, IDENT_PATH, endOf(IDENT_SOURCE, "super.", 0));
        assertEquals(CompletionContextKind.MEMBER_ACCESS, result.contextKind());
        assertHas(result, "hide", CompletionCandidateKind.METHOD);
        assertLacks(result, "compute");
        assertLacks(result, "len");
        assertLacks(result, "hp");
        assertLacks(result, "name");
    }

    @Test
    void superCandidatesStopAtNearestDeclaringOwner() {
        // LeafClass.probe's super chain starts at MidBase: its `act()` claims the name, so
        // RootBase's `act(x)` must not leak in as an extra overload.
        var result = FrontendSnapshotCompletionService.completionCandidatesAt(
                snapshot, IDENT_PATH, endOf(IDENT_SOURCE, "super.", 1));
        assertEquals(CompletionContextKind.MEMBER_ACCESS, result.contextKind());
        var actCandidates = result.candidates().stream().filter(c -> c.name().equals("act")).toList();
        assertEquals(1, actCandidates.size());
        assertEquals("act() -> void", actCandidates.getFirst().signatureText());
    }

    @Test
    void bareMethodCandidatesStopAtNearestDeclaringOwner() {
        // The enclosing class declares `hide(x)`: bare-method lookup stops at the nearest
        // declaring owner, so CanvasItem's `hide()` is hidden rather than merged.
        var result = FrontendSnapshotCompletionService.completionCandidatesAt(
                snapshot, IDENT_PATH, endOf(IDENT_SOURCE, "var mid = se", 0));
        var hideCandidates = result.candidates().stream().filter(c -> c.name().equals("hide")).toList();
        assertEquals(1, hideCandidates.size());
        assertTrue(hideCandidates.getFirst().signatureText().contains("x"));
    }

    @Test
    void memberAccessOnEnumSubscriptUsesResultType() {
        // `State["IDLE"]` carries the enum group only as container provenance: the subscript
        // result (Variant) drives completion, yielding an empty list instead of enum values.
        var result = FrontendSnapshotCompletionService.completionCandidatesAt(
                snapshot, STATIC_PATH, endOf(STATIC_SOURCE, "CompStaticUser.State[\"IDLE\"].", 0));
        assertEquals(CompletionContextKind.MEMBER_ACCESS, result.contextKind());
        assertTrue(result.candidates().isEmpty());
    }

    // ------------------------------------------------------------------
    // Acceptance 2: fact-free receivers yield empty candidates
    // ------------------------------------------------------------------

    @Test
    void memberAccessOnUntypedReceiverReturnsEmptyWithoutThrowing() {
        // `vu` has no declared type: the published dynamic fact carries Variant, which has no
        // member surface — the answer is an empty list, never an exception.
        var result = FrontendSnapshotCompletionService.completionCandidatesAt(
                snapshot, BUILTIN_PATH, endOf(BUILTIN_SOURCE, "vu.", 0));
        assertEquals(CompletionContextKind.MEMBER_ACCESS, result.contextKind());
        assertTrue(result.candidates().isEmpty());
    }

    // ------------------------------------------------------------------
    // Acceptance 3: bare identifier prefix candidates
    // ------------------------------------------------------------------

    @Test
    void identifierPrefixListsVisibleValuesAndGlobalNamespace() {
        var cursor = endOf(IDENT_SOURCE, "var mid = se", 0);
        var result = FrontendSnapshotCompletionService.completionCandidatesAt(snapshot, IDENT_PATH, cursor);
        assertEquals(CompletionContextKind.IDENTIFIER, result.contextKind());
        // The replaceable range covers the whole `se` prefix token.
        assertNotNull(result.replaceableRange());
        assertEquals(offset(IDENT_SOURCE, "var mid = se", 0) + "var mid = ".length(),
                result.replaceableRange().startByte());
        assertEquals(cursor, result.replaceableRange().endByte());
        // Parameter, previously declared local, class property and class method.
        var seed = assertHas(result, "seed", CompletionCandidateKind.VALUE);
        assertEquals("int", seed.typeText());
        assertHas(result, "before", CompletionCandidateKind.VALUE);
        assertHas(result, "hp", CompletionCandidateKind.PROPERTY);
        assertHas(result, "compute", CompletionCandidateKind.METHOD);
        // Global namespace: engine global constant, global enum, utility function. (The class
        // property PI and the class method len claim their namespaces — see the shadowing test.)
        assertHas(result, "MOUSE_BUTTON_LEFT", CompletionCandidateKind.VALUE);
        assertHas(result, "Key", CompletionCandidateKind.VALUE);
        assertHas(result, "print", CompletionCandidateKind.METHOD);
        // Declaration-after-use: the local declared below the cursor and the variable whose
        // initializer is being typed are both excluded.
        assertLacks(result, "after");
        assertLacks(result, "mid");
    }

    @Test
    void identifierCandidatesShadowGlobalNamespaceByName() {
        var result = FrontendSnapshotCompletionService.completionCandidatesAt(
                snapshot, IDENT_PATH, endOf(IDENT_SOURCE, "var mid = se", 0));
        // The class property PI claims the value namespace: the language constant PI is not
        // offered as a second, same-named candidate.
        var piCandidates = result.candidates().stream().filter(c -> c.name().equals("PI")).toList();
        assertEquals(1, piCandidates.size());
        assertEquals(CompletionCandidateKind.PROPERTY, piCandidates.getFirst().kind());
        // The class method len claims the function namespace: the GDScript language function
        // len is not a fallback overload.
        var lenCandidates = result.candidates().stream().filter(c -> c.name().equals("len")).toList();
        assertEquals(1, lenCandidates.size());
        assertEquals(CompletionCandidateKind.METHOD, lenCandidates.getFirst().kind());
        assertTrue(lenCandidates.getFirst().signatureText().startsWith("len("));
    }

    @Test
    void identifierPrefixOnScopeLessLeafFallsBackToParentScope() {
        // `se` nested inside a binary expression has no scope record of its own: the parent
        // index walk must reach the enclosing callable scope for parameters to be visible.
        var result = FrontendSnapshotCompletionService.completionCandidatesAt(
                snapshot, IDENT_PATH, endOf(IDENT_SOURCE, "seed + se", 0));
        assertEquals(CompletionContextKind.IDENTIFIER, result.contextKind());
        assertHas(result, "seed", CompletionCandidateKind.VALUE);
        assertHas(result, "before", CompletionCandidateKind.VALUE);
        // `after` is declared above this line, so it IS visible here — unlike at the earlier
        // cursor of identifierPrefixListsVisibleValuesAndGlobalNamespace.
        assertHas(result, "after", CompletionCandidateKind.VALUE);
    }

    @Test
    void identifierCompletionSuppressesFailedConstNamesFromGlobalNamespace() {
        // The cursor sits inside a healthy method; the class's own failed `const PI` claims
        // the name terminally, so the global language constant PI must not be offered —
        // matching single-name resolution, which reports blocked instead of falling back.
        var result = FrontendSnapshotCompletionService.completionCandidatesAt(
                snapshot, MASK_PATH, endOf(MASK_SOURCE, "var ok = cu", 0));
        assertEquals(CompletionContextKind.IDENTIFIER, result.contextKind());
        assertLacks(result, "PI");
        // The parent's failed `const INF` is an inherited mask and equally blocks the global.
        assertLacks(result, "INF");
        // Legitimate visible values still enumerate normally.
        assertHas(result, "safe", CompletionCandidateKind.PROPERTY);
    }

    @Test
    void identifierCompletionNearerValidDeclarationShadowsFailedAncestor() {
        var result = FrontendSnapshotCompletionService.completionCandidatesAt(
                snapshot, MASK_PATH, endOf(MASK_SOURCE, "var ok = cu", 0));
        // `var TAU` on the child is a nearer legitimate declaration of the same name the
        // parent's failed `const TAU` masks — the value claim wins over both the inherited
        // mask and the global language constant TAU, so it appears exactly once, as a property.
        var tauCandidates = result.candidates().stream()
                .filter(candidate -> candidate.name().equals("TAU"))
                .toList();
        assertEquals(1, tauCandidates.size());
        assertEquals(CompletionCandidateKind.PROPERTY, tauCandidates.getFirst().kind());
    }

    @Test
    void memberAccessBelowFailedConstStaysConsistentWithInstanceResolution() {
        // Instance member resolution (`c.hp`) walks properties without consulting const masks:
        // the child's failed `const hp` masks bare/qualified lookup only. The completion surface
        // must mirror resolution — the parent's `var hp` is still offered.
        var result = FrontendSnapshotCompletionService.completionCandidatesAt(
                snapshot, MASK_PATH, endOf(MASK_SOURCE, "var x = c.", 0));
        assertEquals(CompletionContextKind.MEMBER_ACCESS, result.contextKind());
        assertHas(result, "hp", CompletionCandidateKind.PROPERTY);
    }

    @Test
    void callArgumentContextYieldsNoCandidatesInV1() {
        // Zero-width cursor inside an empty argument list classifies as CALL_ARGUMENT; the
        // minimal V1 ruleset deliberately answers with an empty list.
        var result = FrontendSnapshotCompletionService.completionCandidatesAt(
                snapshot, IDENT_PATH, endOf(IDENT_SOURCE, "compute()", 0) - 1);
        assertEquals(CompletionContextKind.CALL_ARGUMENT, result.contextKind());
        assertTrue(result.candidates().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "func f(x: int):\n\tvar ok = x not |",
            "func f(x: int):\n\tvar ok = x is int not |",
            "func f(x: int):\n\tvar ok = x not \\\n\t\t|"
    })
    void expectedKeywordAfterInfixNotIsOnlyIn(String markedSource) {
        var fixture = analyzeFixture(markedSource);
        assertExpectedIn(fixture);
    }

    @Test
    void expectedKeywordSurvivesSkippedInitializer() {
        var fixture = analyzeFixture("func f(x: int):\n\tvar ok = (x not |)");
        // The syntax-only answer must survive even when the ordinary cursor gate would reject it.
        var index = fixture.index();
        var anchor = index.nodeAt(fixture.cursor());
        if (anchor == null) {
            anchor = index.nodeAt(fixture.cursor() - 1);
        }
        assertSkippedVariable(fixture.analyzed(), index, anchor, "ok");
        assertExpectedIn(fixture);
    }

    @Test
    void notAfterIsExpectsTypeRatherThanIn() {
        var fixture = analyzeFixture("func f(x: int):\n\tvar ok = x is not |");
        var result = assertGap(fixture, CompletionContextKind.TYPE_POSITION);
        assertTrue(result.candidates().stream().allMatch(candidate -> candidate.kind() == CompletionCandidateKind.TYPE));
        assertLacks(result, "in");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "func f(|): pass",
            "func f(x: Array[Inner, |): pass",
            "signal s(a, |)"
    })
    void parameterNameSlotsHaveNoCandidates(String markedSource) {
        assertEmptyGap(analyzeFixture(markedSource), CompletionContextKind.PARAMETER_NAME);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "func f():\n\tvar x = \"hel|lo\"",
            "func f():\n\tvar x = not |",
            "func f():\n\tvar x = 1 not\n\t\t|",
            "func f():\n\t|",
            "func f(): # note\n\t|",
            "func outer():\n\tvar cb = (func(seed: int): # note\n\t\t|",
            "func f(seed: int): \\\n# tri|via\n\tpass"
    })
    void unknownContextsDoNotOfferCandidates(String markedSource) {
        assertEmptyGap(analyzeFixture(markedSource), CompletionContextKind.UNKNOWN);
    }

    // ------------------------------------------------------------------
    // Completion at a callable's same-logical-line body
    // ------------------------------------------------------------------

    @Test
    void suiteStartAtFunctionEofUsesTheColonOwner() {
        var fixture = analyzeFixture("func f(seed: int):|");
        var index = fixture.index();
        // The callable ends at the cursor. Its half-open AST range still covers the colon.
        assertNull(index.nodeAt(fixture.cursor()));
        var atColon = index.nodeAt(fixture.cursor() - 1);
        assertNotNull(atColon, "the colon must have an AST owner");
        var owner = assertInstanceOf(FunctionDeclaration.class, atColon);
        assertEquals(fixture.cursor(), owner.range().endByte());
        assertEquals(fixture.cursor(), owner.body().range().startByte());
        assertHas(assertSuite(fixture), "seed", CompletionCandidateKind.VALUE);
    }

    @Test
    void suiteStartKeepsParametersWithWhitespaceAndSiblingDeclarations() {
        var fixture = analyzeFixture("""
                func prior():
                    var unrelated = true if  else false
                func f(seed: int): |
                func next(neighbor: int): pass
                """);
        var result = assertSuite(fixture);
        assertHas(result, "seed", CompletionCandidateKind.VALUE);
        assertLacks(result, "neighbor");
        assertLacks(result, "unrelated");
    }

    @Test
    void suiteStartUsesParametersOfConstructorsAndRecoveredSignatures() {
        assertHas(assertSuite(analyzeFixture("func _init(seed: int):|")), "seed", CompletionCandidateKind.VALUE);
        assertHas(assertSuite(analyzeFixture("func f(x = 1]:|")), "x", CompletionCandidateKind.VALUE);
    }

    @ParameterizedTest(name = "[{index}]")
    @ValueSource(strings = {"\n", "\r\n"})
    void suiteStartCrossesContinuedTriviaWithUtf8Coordinates(String newline) {
        var fixture = analyzeFixture(("# 前缀😀\nfunc f(seed: int): \\\n# trivia\n\n\t\\\n\t|").replace("\n", newline));
        var result = assertSuite(fixture);
        assertHas(result, "seed", CompletionCandidateKind.VALUE);
        assertEquals(new QuerySourceRange(FIXTURE_PATH, fixture.cursor(), fixture.cursor(), 6, 2, 6, 2),
                result.replaceableRange());
        assertEquals(result, FrontendSnapshotCompletionService.completionCandidatesAt(fixture.analyzed(), FIXTURE_PATH, 5, 1));
    }

    @ParameterizedTest
    @ValueSource(ints = {0xD83D, 0xDE00, 0x1F600})
    void suiteStartCoordinatesAgreeAfterSurrogatesInSource(int codePoint) {
        var fixture = analyzeFixture("# " + Character.toString(codePoint) + "\nfunc f(seed: int):|");
        // The JDK's UTF-8 encoder replaces a lone surrogate with one byte; a valid pair
        // takes four. Both cursor APIs must agree on those physical offsets.
        var expectedByte = Character.isBmpCodePoint(codePoint) ? 22 : 25;
        assertEquals(expectedByte, fixture.cursor());
        var byRow = FrontendSnapshotCompletionService.completionCandidatesAt(fixture.analyzed(), FIXTURE_PATH, 1, 18);
        var byByte = assertSuite(fixture);
        assertEquals(byByte, byRow);
        assertEquals(new QuerySourceRange(FIXTURE_PATH, expectedByte, expectedByte, 2, 19, 2, 19),
                byByte.replaceableRange());
        assertHas(byByte, "seed", CompletionCandidateKind.VALUE);
    }

    @Test
    void suiteStartCannotBorrowLfBeyondTheCursor() {
        var fixture = analyzeFixture("func f(seed: int): \\\r|\n# trivia\r\n\tpass");
        // The parser reports SUITE_START even between CR and LF, but the gap may only use bytes
        // before the cursor.
        assertEmptyGap(fixture, CompletionContextKind.SUITE_START);
        // Stop before the tab: at the start of `pass`, the cursor is no longer in the gap.
        var afterTrivia = new CompletionFixture(fixture.analyzed(), fixture.source(), offset(fixture.source(), "\tpass", 0));
        assertHas(assertSuite(afterTrivia), "seed", CompletionCandidateKind.VALUE);
    }

    @Test
    void suiteStartInLocalLambdaOmitsTheVariableStillBeingInitialized() {
        var fixture = analyzeFixture("func helper(): pass\nfunc outer():\n\tvar before = 1\n\tvar cb = func(seed: int):|");
        var index = fixture.index();
        assertNull(index.nodeAt(fixture.cursor()));
        var atColon = index.nodeAt(fixture.cursor() - 1);
        assertNotNull(atColon, "the lambda must cover its colon");
        var lambda = assertInstanceOf(LambdaExpression.class, atColon);
        var parent = index.parentOf(lambda);
        assertNotNull(parent, "the lambda must be part of the initializer");
        var declaration = assertInstanceOf(VariableDeclaration.class, parent);
        assertEquals("cb", declaration.name());
        // Enumerating at the cursor instead of the colon would make cb look fully declared.
        assertEquals(fixture.cursor(), declaration.range().endByte());
        assertEquals(fixture.cursor(), lambda.body().range().startByte());
        assertTrue(fixture.analyzed().analysisData().lambdaPlans().containsKey(lambda));

        var result = assertSuite(fixture);
        assertHas(result, "seed", CompletionCandidateKind.VALUE);
        assertHas(result, "before", CompletionCandidateKind.VALUE);
        assertHas(result, "helper", CompletionCandidateKind.METHOD);
        assertHas(result, "print", CompletionCandidateKind.METHOD);
        assertLacks(result, "cb");
    }

    @Test
    void suiteStartInDictionaryLambdaUsesItsOwnParametersAndEarlierLocals() {
        var fixture = analyzeFixture("""
                func outer():
                    var before = 1
                    var d = {"f": func(seed: int):|}
                    var later = 2
                """);
        assertTrue(fixture.analyzed().analysisData().lambdaPlans().containsKey(fixture.lambda()));
        var result = assertSuite(fixture);
        assertHas(result, "seed", CompletionCandidateKind.VALUE);
        assertHas(result, "before", CompletionCandidateKind.VALUE);
        assertLacks(result, "d");
        assertLacks(result, "later");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "func outer(kept, kept = func(inner):| pass): pass",
            "func outer(kept, kept = func(inner):|):",
            "func outer(kept, kept = func(inner):|"
    })
    void suiteStartRejectsTheColonOfADiscardedDefaultLambda(String markedSource) {
        var fixture = analyzeFixture(markedSource);
        var index = fixture.index();
        var owner = index.preorderNodes().stream()
                .filter(FunctionDeclaration.class::isInstance)
                .map(FunctionDeclaration.class::cast)
                .findFirst().orElseThrow();
        assertEquals("outer", owner.name());
        assertEquals(List.of("kept"), owner.parameters().stream().map(Parameter::name).toList());
        assertFalse(index.preorderNodes().stream().anyMatch(LambdaExpression.class::isInstance));
        var atColon = index.nodeAt(fixture.cursor() - 1);
        // The colon maps to a live outer callable (or its placeholder body), not the dropped lambda.
        assertTrue(atColon == owner || atColon == owner.body());
        assertInstanceOf(BlockScope.class, fixture.analyzed().analysisData().scopesByAst().get(owner.body()));
        assertNull(skippedAncestor(fixture.analyzed(), index, owner));
        assertEmptyGap(fixture, CompletionContextKind.SUITE_START);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "var cb = func(seed: int):|",
            "func outer(ok = func(inner):|):",
            "func outer():\n\tconst CB = {\"f\": func(seed: int):|}"
    })
    void suiteStartDoesNotOfferCandidatesForLambdasWithoutExecutablePlans(String markedSource) {
        var fixture = analyzeFixture(markedSource);
        var lambda = fixture.lambda();
        var data = fixture.analyzed().analysisData();
        // A scope exists even though the initializer is not part of the executable inventory.
        assertInstanceOf(BlockScope.class, data.scopesByAst().get(lambda.body()));
        assertNull(skippedAncestor(fixture.analyzed(), fixture.index(), lambda));
        assertFalse(data.lambdaPlans().containsKey(lambda));
        assertEmptyGap(fixture, CompletionContextKind.SUITE_START);
    }

    @Test
    void suiteStartInLambdaInsideSkippedInitializerHasNoCandidates() {
        var fixture = analyzeFixture("func outer():\n\tvar cb = (1 + func(seed: int):|");
        assertSkippedVariable(fixture.analyzed(), fixture.index(), fixture.lambda(), "cb");
        assertEmptyGap(fixture, CompletionContextKind.SUITE_START);
    }

    // ------------------------------------------------------------------
    // Acceptance 4: type-position candidates
    // ------------------------------------------------------------------

    @Test
    void typePositionListsBuiltinEngineGlobalAndInnerClassNames() {
        var cursor = endOf(TYPE_SOURCE, "Ve", 0);
        var result = FrontendSnapshotCompletionService.completionCandidatesAt(snapshot, TYPE_PATH, cursor);
        assertEquals(CompletionContextKind.TYPE_POSITION, result.contextKind());
        assertNotNull(result.replaceableRange());
        assertEquals(offset(TYPE_SOURCE, "Ve", 0), result.replaceableRange().startByte());
        assertEquals(cursor, result.replaceableRange().endByte());
        assertHas(result, "Vector2", CompletionCandidateKind.TYPE);
        assertHas(result, "Node2D", CompletionCandidateKind.TYPE);
        assertHas(result, "CompTypeUser", CompletionCandidateKind.TYPE);
        assertHas(result, "Inner", CompletionCandidateKind.TYPE);
        // A type position never mixes in value/property candidates.
        for (var candidate : result.candidates()) {
            assertEquals(CompletionCandidateKind.TYPE, candidate.kind());
        }
    }

    @Test
    void typePositionIgnoresUnnamedInnerClassButKeepsNamedSibling() {
        var path = "/src/comp_unnamed_inner.gd";
        var source = """
                class Inner:
                    pass
                class:
                    pass
                func f():
                    var v: Ve
                """;
        var analyzed = QueryTestSupport.analyze(api, "completion_unnamed_inner", files(file(path, source)));
        var index = analyzed.astIndex().forDisplayPath(path);
        assertNotNull(index);
        assertTrue(index.preorderNodes().stream()
                        .anyMatch(node -> node instanceof ClassDeclaration inner && inner.name() == null),
                "the recovered unnamed class must be present in the snapshot");

        var result = FrontendSnapshotCompletionService.completionCandidatesAt(analyzed, path, endOf(source, "Ve", 0));
        assertEquals(CompletionContextKind.TYPE_POSITION, result.contextKind());
        assertHas(result, "Inner", CompletionCandidateKind.TYPE);
        assertHas(result, "Vector2", CompletionCandidateKind.TYPE);
        assertLacks(result, "null");
        assertTrue(result.candidates().stream()
                .allMatch(candidate -> candidate.kind() == CompletionCandidateKind.TYPE && !candidate.name().isBlank()));
    }

    // ------------------------------------------------------------------
    // Acceptance 5: skipped/error subtrees yield empty candidates
    // ------------------------------------------------------------------

    @Test
    void cursorInsideSkippedErrorSubtreeReturnsEmpty() {
        // The binding name is not an expression slot. Query the value reference inside the
        // damaged initializer and compare it with a healthy use of the same name.
        var cursor = offset(SKIP_SOURCE, "ok if", 0);
        var index = snapshot.astIndex().forDisplayPath(SKIP_PATH);
        assertNotNull(index);
        assertSkippedVariable(snapshot, index, index.nodeAt(cursor), "x");
        var result = FrontendSnapshotCompletionService.completionCandidatesAt(snapshot, SKIP_PATH, cursor);
        assertEquals(CompletionContextKind.IDENTIFIER, result.contextKind());
        assertTrue(result.candidates().isEmpty());

        var healthyCursor = endOf(SKIP_SOURCE, "valid = ", 0);
        assertNull(skippedAncestor(snapshot, index, index.nodeAt(healthyCursor)));
        var healthy = FrontendSnapshotCompletionService.completionCandidatesAt(snapshot, SKIP_PATH, healthyCursor);
        assertEquals(CompletionContextKind.IDENTIFIER, healthy.contextKind());
        assertHas(healthy, "ok", CompletionCandidateKind.VALUE);
    }

    @ParameterizedTest(name = "[{index}]")
    @ValueSource(strings = {
            "{\"a\":|",
            "{\"a\":| ",
            "{\"a\":|\n",
            "{a =| ",
            "{\"a\":\n\t\t| "
    })
    void dictionaryValueGapDoesNotLeakSkippedInitializerCandidates(String markedInitializer) {
        var fixture = analyzeFixture("func f(ok: Node):\n\tvar x = " + markedInitializer);
        var index = fixture.index();
        // The skipped declaration can end before the caret, even though the parser still
        // sees its unfilled value slot. Trivia must not pull candidates from the parent.
        var declaration = index.nodeAt(offset(fixture.source(), "var x", 0));
        assertSkippedVariable(fixture.analyzed(), index, declaration, "x");
        assertEmptyGap(fixture, CompletionContextKind.IDENTIFIER);
    }

    @ParameterizedTest(name = "[{index}]")
    @ValueSource(strings = {"{\"a\":| 1}", "{a =| 1}", "{\"a\":\n\t\t|1\n\t}"})
    void healthyDictionaryValueGapStillOffersVisibleParameter(String markedInitializer) {
        var fixture = analyzeFixture("func f(ok: Node):\n\tvar x = " + markedInitializer);
        var index = fixture.index();
        var declaration = index.nodeAt(offset(fixture.source(), "var x", 0));
        assertNotNull(declaration);
        assertNull(skippedAncestor(fixture.analyzed(), index, declaration));
        assertHas(assertGap(fixture, CompletionContextKind.IDENTIFIER), "ok", CompletionCandidateKind.VALUE);
    }

    @Test
    void typePositionInsideSkippedDeclarationDoesNotSuppressHealthySibling() {
        var fixture = analyzeFixture("""
                func f():
                    var damaged: Array[Array[In|ner]]
                    var healthy: Vector2
                """);
        var index = fixture.index();
        assertSkippedVariable(fixture.analyzed(), index, index.nodeAt(fixture.cursor()), "damaged");
        var result = fixture.complete();
        assertEquals(CompletionContextKind.TYPE_POSITION, result.contextKind());
        assertTrue(result.candidates().isEmpty());

        var healthyCursor = offsetInside(fixture.source(), "Vector2", 0);
        assertNull(skippedAncestor(fixture.analyzed(), index, index.nodeAt(healthyCursor)));
        var healthy = FrontendSnapshotCompletionService.completionCandidatesAt(fixture.analyzed(), FIXTURE_PATH, healthyCursor);
        assertEquals(CompletionContextKind.TYPE_POSITION, healthy.contextKind());
        assertHas(healthy, "Vector2", CompletionCandidateKind.TYPE);
        assertTrue(healthy.candidates().stream().allMatch(candidate -> candidate.kind() == CompletionCandidateKind.TYPE));
    }

    // ------------------------------------------------------------------
    // Acceptance 6: stale snapshot self-consistency
    // ------------------------------------------------------------------

    @Test
    void staleSnapshotAnswersFromItsOwnSourceText() {
        var stalePath = "/src/comp_stale.gd";
        var staleModule = "completion_stale";
        var oldSource = """
                class_name CompStaleUser
                extends Node
                
                func f() -> void:
                    var oldname = 1
                    var mid = ol
                """;
        var staleSnapshot = QueryTestSupport.analyze(
                api, staleModule, files(file(stalePath, oldSource)));
        // Overwrite the module content WITHOUT re-analyzing: the published snapshot keeps its
        // own generation and must answer from its own text.
        api.putFile(staleModule, stalePath, oldSource
                .replace("oldname", "newname").replace("CompStaleUser", "CompStaleUser2"));
        var result = FrontendSnapshotCompletionService.completionCandidatesAt(
                staleSnapshot, stalePath, endOf(oldSource, "var mid = ol", 0));
        assertEquals(CompletionContextKind.IDENTIFIER, result.contextKind());
        assertHas(result, "oldname", CompletionCandidateKind.VALUE);
        assertLacks(result, "newname");
        // The replaceable range must be the OLD text's `ol` span: the class-name edit shifts
        // every later byte by one, so an implementation reading the live text would answer a
        // different range.
        assertNotNull(result.replaceableRange());
        assertEquals(offset(oldSource, "var mid = ol", 0) + "var mid = ".length(),
                result.replaceableRange().startByte());
        assertEquals(endOf(oldSource, "var mid = ol", 0), result.replaceableRange().endByte());
    }

    // ------------------------------------------------------------------
    // Coordinate contract
    // ------------------------------------------------------------------

    @Test
    void unknownDisplayPathReturnsUnknownEmptyResult() {
        var result = FrontendSnapshotCompletionService.completionCandidatesAt(snapshot, "/src/missing.gd", 0);
        assertEquals(CompletionContextKind.UNKNOWN, result.contextKind());
        assertNull(result.replaceableRange());
        assertTrue(result.candidates().isEmpty());
    }

    @Test
    void offsetArgumentPolicyIsDocumentedAndBounded() {
        var sourceLength = NODE_SOURCE.getBytes(StandardCharsets.UTF_8).length;
        // Negative offsets are a programming error.
        assertThrows(IllegalArgumentException.class,
                () -> FrontendSnapshotCompletionService.completionCandidatesAt(snapshot, NODE_PATH, -1));
        // Offsets past the source end are an absent result, mirroring the row/column overload.
        for (var beyond : new int[]{sourceLength + 1, Integer.MAX_VALUE}) {
            var result = FrontendSnapshotCompletionService.completionCandidatesAt(snapshot, NODE_PATH, beyond);
            assertEquals(CompletionContextKind.UNKNOWN, result.contextKind());
            assertNull(result.replaceableRange());
            assertTrue(result.candidates().isEmpty());
        }
        // The EOF position itself remains a valid cursor.
        assertNotNull(FrontendSnapshotCompletionService.completionCandidatesAt(snapshot, NODE_PATH, sourceLength));
    }

    @Test
    void rowColumnOverloadMatchesByteOffsetResult() {
        // Row 8 (0-based) is `    var b = self.na`; the line-end column lands right after `na`.
        var rowColumn = FrontendSnapshotCompletionService.completionCandidatesAt(snapshot, NODE_PATH, 8,
                "    var b = self.na".length());
        var byteOffset = FrontendSnapshotCompletionService.completionCandidatesAt(
                snapshot, NODE_PATH, endOf(NODE_SOURCE, "self.na", 0));
        assertEquals(byteOffset.contextKind(), rowColumn.contextKind());
        assertEquals(byteOffset.candidates(), rowColumn.candidates());
        assertEquals(byteOffset.replaceableRange(), rowColumn.replaceableRange());
    }

    // ------------------------------------------------------------------
    // Assertion helpers
    // ------------------------------------------------------------------

    private record CompletionFixture(ModuleAnalysisSnapshot analyzed, String source, int cursor) {
        private AstUnitIndex index() {
            var index = analyzed.astIndex().forDisplayPath(FIXTURE_PATH);
            assertNotNull(index);
            return index;
        }

        private CompletionLookupResult complete() {
            return FrontendSnapshotCompletionService.completionCandidatesAt(analyzed, FIXTURE_PATH, cursor);
        }

        private LambdaExpression lambda() {
            var lambdas = index().preorderNodes().stream()
                    .filter(LambdaExpression.class::isInstance)
                    .map(LambdaExpression.class::cast)
                    .toList();
            assertEquals(1, lambdas.size(), "fixture must contain one retained lambda");
            return lambdas.getFirst();
        }
    }

    /// The marker is only for fixture readability. The parser and snapshot see the same unmarked text.
    private static CompletionFixture analyzeFixture(String markedSource) {
        var marker = markedSource.indexOf('|');
        assertTrue(marker >= 0 && marker == markedSource.lastIndexOf('|'), "fixture must contain one cursor marker");
        var source = markedSource.substring(0, marker) + markedSource.substring(marker + 1);
        var cursor = markedSource.substring(0, marker).getBytes(StandardCharsets.UTF_8).length;
        try (var fixtureApi = new API()) {
            var analyzed = QueryTestSupport.analyze(fixtureApi, "completion_fixture", files(file(FIXTURE_PATH, source)));
            return new CompletionFixture(analyzed, source, cursor);
        }
    }

    private static @Nullable Node skippedAncestor(ModuleAnalysisSnapshot analyzed, AstUnitIndex index, @Nullable Node node) {
        for (var current = node; current != null; current = index.parentOf(current)) {
            if (analyzed.analysisData().skippedSubtreeRoots().containsKey(current)) {
                return current;
            }
        }
        return null;
    }

    private static void assertSkippedVariable(ModuleAnalysisSnapshot analyzed, AstUnitIndex index, @Nullable Node node, String name) {
        var skipped = skippedAncestor(analyzed, index, node);
        assertNotNull(skipped, () -> "fixture must mark " + name + " as a skipped ancestor");
        assertEquals(name, assertInstanceOf(VariableDeclaration.class, skipped).name());
    }

    private static CompletionLookupResult assertGap(CompletionFixture fixture, CompletionContextKind kind) {
        var result = fixture.complete();
        assertEquals(kind, result.contextKind());
        var range = result.replaceableRange();
        assertNotNull(range);
        assertEquals(FIXTURE_PATH, range.displayPath());
        assertEquals(fixture.cursor(), range.startByte());
        assertEquals(fixture.cursor(), range.endByte());
        return result;
    }

    private static void assertEmptyGap(CompletionFixture fixture, CompletionContextKind kind) {
        assertTrue(assertGap(fixture, kind).candidates().isEmpty());
    }

    private static void assertExpectedIn(CompletionFixture fixture) {
        assertEquals(List.of(new CompletionCandidate("in", CompletionCandidateKind.KEYWORD, null, null)),
                assertGap(fixture, CompletionContextKind.EXPECTED_KEYWORD).candidates());
    }

    private static CompletionLookupResult assertSuite(CompletionFixture fixture) {
        var result = assertGap(fixture, CompletionContextKind.SUITE_START);
        var keywords = result.candidates().stream().filter(candidate -> candidate.kind() == CompletionCandidateKind.KEYWORD).toList();
        var expected = Set.of("pass", "return", "var", "const", "if", "for", "while", "match", "assert");
        assertEquals(expected.size(), keywords.size());
        assertEquals(expected, Set.copyOf(keywords.stream().map(CompletionCandidate::name).toList()));
        for (var keyword : keywords) {
            assertNull(keyword.typeText());
            assertNull(keyword.signatureText());
        }
        return result;
    }

    private static @NotNull CompletionCandidate assertHas(
            @NotNull CompletionLookupResult result,
            @NotNull String name,
            @NotNull CompletionCandidateKind kind
    ) {
        var candidate = candidateNamed(result, name);
        assertNotNull(candidate, () -> "expected candidate '" + name + "' of kind " + kind
                + " in " + result.candidates().size() + " candidates");
        assertEquals(kind, candidate.kind(), () -> "candidate '" + name + "' kind");
        return candidate;
    }

    private static void assertLacks(@NotNull CompletionLookupResult result, @NotNull String name) {
        assertNull(candidateNamed(result, name), () -> "candidate '" + name + "' must be absent");
    }

    private static @Nullable CompletionCandidate candidateNamed(
            @NotNull CompletionLookupResult result,
            @NotNull String name
    ) {
        for (var candidate : result.candidates()) {
            if (candidate.name().equals(name)) {
                return candidate;
            }
        }
        return null;
    }

    /// UTF-8 byte offset one past the `occurrence`-th appearance of `token` — a cursor resting
    /// exactly at the token end (the common "just typed" position).
    private static int endOf(@NotNull String source, @NotNull String token, int occurrence) {
        return offset(source, token, occurrence) + token.getBytes(StandardCharsets.UTF_8).length;
    }
}
