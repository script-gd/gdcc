package gd.script.gdcc.api.analysis;

import gd.script.gdcc.api.API;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static gd.script.gdcc.api.analysis.QueryTestSupport.file;
import static gd.script.gdcc.api.analysis.QueryTestSupport.files;
import static gd.script.gdcc.api.analysis.QueryTestSupport.offset;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Acceptance-anchored tests for `FrontendSnapshotCompletionService` (LSP foundation plan
/// Phase 5 acceptance 1-6): member access enumerates the instance surface for typed receivers
/// and the static surface for class-name receivers, identifier prefixes enumerate visible
/// values with declaration-after-use filtering plus the global namespace, type positions
/// enumerate type names and file inner classes, skipped/error subtrees and fact-free receivers
/// yield empty results without exceptions, and a stale snapshot answers from its own text.
class CompletionServiceTest {
    // Fixtures place a plain statement AFTER each unfinished `receiver.` line inside the same
    // function: gdparser 0.6.0 then keeps the receiver's published facts (completion works),
    // while a partial chain sitting at a function tail before the next `func` silently drops
    // its receiver facts, and a line ending right after `call().` becomes an ErrorStatement.
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
                var x = true if  else false
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
                file(SKIP_PATH, SKIP_SOURCE)
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
    void memberAccessOnFactFreeReceiverReturnsEmptyWithoutThrowing() {
        // A partial chain sitting at a function tail (before the next `func`) loses its
        // receiver facts in this gdparser version: the completion context still classifies
        // MEMBER_ACCESS, but without receiver facts the answer is an empty list, never an
        // exception (acceptance 2's "no type facts" form).
        var result = FrontendSnapshotCompletionService.completionCandidatesAt(
                snapshot, BUILTIN_PATH, endOf(BUILTIN_SOURCE, "dropped = v.", 0));
        assertEquals(CompletionContextKind.MEMBER_ACCESS, result.contextKind());
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
    void callArgumentContextYieldsNoCandidatesInV1() {
        // Zero-width cursor inside an empty argument list classifies as CALL_ARGUMENT; the
        // minimal V1 ruleset deliberately answers with an empty list.
        var result = FrontendSnapshotCompletionService.completionCandidatesAt(
                snapshot, IDENT_PATH, endOf(IDENT_SOURCE, "compute()", 0) - 1);
        assertEquals(CompletionContextKind.CALL_ARGUMENT, result.contextKind());
        assertTrue(result.candidates().isEmpty());
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

    // ------------------------------------------------------------------
    // Acceptance 5: skipped/error subtrees yield empty candidates
    // ------------------------------------------------------------------

    @Test
    void cursorInsideSkippedErrorSubtreeReturnsEmpty() {
        // The fixture's last statement is an anchored ErrorStatement form (Phase 2): the whole
        // subtree is recorded as skipped. The cursor sits on the declaration name `x`, which the
        // completion classifier reports as IDENTIFIER — a context that WOULD produce scope-value
        // candidates without the gate, so the empty answer proves the gate fired.
        var cursor = offset(SKIP_SOURCE, "x = true", 0);
        var unitIndex = snapshot.astIndex().forDisplayPath(SKIP_PATH);
        var node = FrontendSnapshotQueryService.nodeAt(snapshot, SKIP_PATH, cursor);
        var insideSkipped = false;
        for (var current = node; current != null; current = unitIndex.parentOf(current)) {
            if (snapshot.analysisData().skippedSubtreeRoots().containsKey(current)) {
                insideSkipped = true;
                break;
            }
        }
        assertTrue(insideSkipped, "the cursor node must lie inside a recorded skipped root");
        var result = FrontendSnapshotCompletionService.completionCandidatesAt(snapshot, SKIP_PATH, cursor);
        assertEquals(CompletionContextKind.IDENTIFIER, result.contextKind());
        assertTrue(result.candidates().isEmpty());
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
