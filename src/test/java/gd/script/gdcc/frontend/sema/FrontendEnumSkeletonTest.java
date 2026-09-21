package gd.script.gdcc.frontend.sema;

import dev.superice.gdparser.frontend.ast.EnumDeclaration;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnosticSeverity;
import gd.script.gdcc.frontend.diagnostic.FrontendRange;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.FrontendSourceUnit;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.GdScriptClassConstant;
import gd.script.gdcc.scope.GdScriptEnumConstant;
import gd.script.gdcc.scope.GdScriptEnumGroup;
import gd.script.gdcc.type.GdDictionaryType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdVariantType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/// Acceptance tests for the skeleton enum pre-pass: constant-table publication, conflict rules,
/// the restricted value evaluator boundary, and the per-enum diagnostic/skip recovery contract.
class FrontendEnumSkeletonTest {
    @Test
    void anonymousEnumPublishesAutoIncrementedAndExplicitIntConstants() throws IOException {
        var fixture = build("enum_anonymous_happy.gd", """
                class_name EnumAnonymousHappy
                extends RefCounted

                enum { IDLE, RUNNING, JUMP = 5, FALLING }
                enum { NEG = -1, HEX = 0xF0, BIN = 0b101, SEP = 1_000 }
                enum { MASK = 1 << 3, ALL = 0xF0 | MASK, FLIP = ~0 }
                enum { DUP_A = 1, DUP_B = 1 }
                """);

        var classDef = findClass(fixture, "EnumAnonymousHappy");
        assertAll(
                () -> assertConstantValues(classDef, "IDLE", 0),
                () -> assertConstantValues(classDef, "RUNNING", 1),
                () -> assertConstantValues(classDef, "JUMP", 5),
                () -> assertConstantValues(classDef, "FALLING", 6),
                () -> assertConstantValues(classDef, "NEG", -1),
                () -> assertConstantValues(classDef, "HEX", 0xF0),
                () -> assertConstantValues(classDef, "BIN", 0b101),
                () -> assertConstantValues(classDef, "SEP", 1_000),
                () -> assertConstantValues(classDef, "MASK", 8),
                () -> assertConstantValues(classDef, "ALL", 0xF8),
                () -> assertConstantValues(classDef, "FLIP", ~0),
                // Duplicate values across members stay legal, matching Godot.
                () -> assertConstantValues(classDef, "DUP_A", 1),
                () -> assertConstantValues(classDef, "DUP_B", 1),
                () -> assertTrue(fixture.diagnostics().snapshot().isEmpty())
        );
    }

    @Test
    void namedEnumPublishesDictionaryGroupConstantAndIntTypeMeta() throws IOException {
        var fixture = build("enum_named_happy.gd", """
                class_name EnumNamedHappy
                extends RefCounted

                enum State { IDLE, JUMP = 5 }
                var x: State
                """);

        var classDef = findClass(fixture, "EnumNamedHappy");
        var groupConstant = findConstant(classDef, "State");
        assertAll(
                () -> assertEquals(GdIntType.INT, findPropertyType(classDef, "x")),
                () -> assertInstanceOf(GdDictionaryType.class, groupConstant.type()),
                () -> assertTrue(((GdDictionaryType) groupConstant.type()).isGenericDictionary())
        );

        var group = assertInstanceOf(GdScriptEnumGroup.class, groupConstant.declaration());
        assertAll(
                () -> assertEquals("State", group.name()),
                () -> assertEquals("EnumNamedHappy", group.ownerClassCanonicalName()),
                () -> assertEquals(2, group.members().size()),
                () -> assertEquals(0, requireMember(group, "IDLE").value()),
                () -> assertEquals(5, requireMember(group, "JUMP").value()),
                () -> assertEquals("State", requireMember(group, "JUMP").groupName()),
                () -> assertTrue(fixture.diagnostics().snapshot().isEmpty())
        );
    }

    @Test
    void typeAnnotationResolvesRegardlessOfDeclarationOrder() throws IOException {
        // The enum pre-pass runs before member filling, so a property may reference a later enum.
        var fixture = build("enum_order_free.gd", """
                class_name EnumOrderFree
                extends RefCounted

                var x: State
                enum State { IDLE }
                """);

        assertAll(
                () -> assertEquals(GdIntType.INT, findPropertyType(findClass(fixture, "EnumOrderFree"), "x")),
                () -> assertTrue(fixture.diagnostics().snapshot().isEmpty())
        );
    }

    @Test
    void enumValuesReferenceEarlierMembersAndEarlierEnumConstants() throws IOException {
        var fixture = build("enum_value_refs.gd", """
                class_name EnumValueRefs
                extends RefCounted

                enum { A = 1, B = A + 1 }
                enum { C = B }
                """);

        var classDef = findClass(fixture, "EnumValueRefs");
        assertAll(
                () -> assertConstantValues(classDef, "A", 1),
                () -> assertConstantValues(classDef, "B", 2),
                () -> assertConstantValues(classDef, "C", 2),
                () -> assertTrue(fixture.diagnostics().snapshot().isEmpty())
        );
    }

    @Test
    void enumValuesReferenceGlobalEnumValues() throws IOException {
        var fixture = build("enum_global_refs.gd", """
                class_name EnumGlobalRefs
                extends RefCounted

                enum { NIL = TYPE_NIL, FAILURE = FAILED }
                """);

        var classDef = findClass(fixture, "EnumGlobalRefs");
        assertAll(
                () -> assertConstantValues(classDef, "NIL", 0),
                () -> assertConstantValues(classDef, "FAILURE", 1),
                () -> assertTrue(fixture.diagnostics().snapshot().isEmpty())
        );
    }

    @Test
    void enumDeclaredInInnerClassBodyPublishesOnInnerClassShell() throws IOException {
        var fixture = build("enum_inner_class.gd", """
                class_name EnumInnerOuter
                extends RefCounted

                class Inner:
                    enum { INNER_A, INNER_B = 3 }
                    enum InnerState { STOP = 2 }
                    var v: InnerState
                """);

        var relation = fixture.result().sourceClassRelations().getFirst();
        var innerDef = relation.innerClassRelations().getFirst().classDef();
        assertAll(
                () -> assertEquals("EnumInnerOuter__sub__Inner", innerDef.getName()),
                () -> assertConstantValues(innerDef, "INNER_A", 0),
                () -> assertConstantValues(innerDef, "INNER_B", 3),
                () -> assertEquals(GdIntType.INT, findPropertyType(innerDef, "v")),
                () -> assertEquals("EnumInnerOuter__sub__Inner",
                        ((GdScriptEnumGroup) findConstant(innerDef, "InnerState").declaration()).ownerClassCanonicalName()),
                () -> assertTrue(fixture.diagnostics().snapshot().isEmpty())
        );
    }

    @Test
    void outerEnumTypeMetaStaysVisibleInsideInnerClassLexicalChain() throws IOException {
        var fixture = build("enum_outer_visible_inner.gd", """
                class_name EnumOuterVisible
                extends RefCounted

                enum State { IDLE }
                class Inner:
                    var x: State
                """);

        var innerDef = fixture.result().sourceClassRelations().getFirst()
                .innerClassRelations().getFirst().classDef();
        assertAll(
                () -> assertEquals(GdIntType.INT, findPropertyType(innerDef, "x")),
                () -> assertTrue(fixture.diagnostics().snapshot().isEmpty())
        );
    }

    @Test
    void namedEnumMembersCoexistWithSameNamedClassLevelMembers() throws IOException {
        // Named enum members never enter the class-level namespace, so `IDLE` as a group member
        // may coexist with a class-level property, function, or anonymous enum member.
        var fixture = build(List.of(
                new SourceSpec("enum_coexist_property.gd", """
                        class_name EnumCoexistProperty
                        extends RefCounted

                        enum State { IDLE }
                        var IDLE: int
                        """),
                new SourceSpec("enum_coexist_function.gd", """
                        class_name EnumCoexistFunction
                        extends RefCounted

                        enum State { IDLE }
                        func IDLE():
                            pass
                        """),
                new SourceSpec("enum_coexist_anonymous.gd", """
                        class_name EnumCoexistAnonymous
                        extends RefCounted

                        enum State { IDLE }
                        enum { IDLE }
                        """)
        ));

        var propertyClass = findClass(fixture, "EnumCoexistProperty");
        var functionClass = findClass(fixture, "EnumCoexistFunction");
        var anonymousClass = findClass(fixture, "EnumCoexistAnonymous");
        assertAll(
                () -> assertNotNull(findConstant(propertyClass, "State")),
                () -> assertNotNull(propertyClass.getProperties().stream()
                        .filter(property -> property.getName().equals("IDLE")).findFirst().orElse(null)),
                () -> assertNotNull(findConstant(functionClass, "State")),
                () -> assertTrue(functionClass.hasFunction("IDLE")),
                () -> assertNotNull(findConstant(anonymousClass, "State")),
                () -> assertConstantValues(anonymousClass, "IDLE", 0),
                () -> assertTrue(fixture.diagnostics().snapshot().isEmpty())
        );
    }

    @Test
    void duplicateMemberInsideOneEnumIsRejected() throws IOException {
        var fixture = build("enum_dup_member.gd", """
                class_name EnumDupMember
                extends RefCounted

                enum State { IDLE, JUMP, IDLE }
                var hp: int
                """);

        var enumNode = firstEnumStatement(fixture, 0);
        assertEnumRejected(fixture, enumNode, "duplicate member 'IDLE'");
        var classDef = findClass(fixture, "EnumDupMember");
        assertAll(
                () -> assertTrue(classDef.getScriptConstants().isEmpty()),
                () -> assertNotNull(findProperty(classDef, "hp"))
        );
    }

    @Test
    void groupNameConflictingWithClassMembersIsRejected() throws IOException {
        var fixture = build(List.of(
                new SourceSpec("enum_group_vs_property.gd", """
                        class_name EnumGroupVsProperty
                        extends RefCounted

                        var State: int
                        enum State { IDLE }
                        """),
                new SourceSpec("enum_group_vs_inner_class.gd", """
                        class_name EnumGroupVsInnerClass
                        extends RefCounted

                        class Inner:
                            pass
                        enum Inner { IDLE }
                        """),
                new SourceSpec("enum_group_vs_function.gd", """
                        class_name EnumGroupVsFunction
                        extends RefCounted

                        func State():
                            pass
                        enum State { IDLE }
                        """),
                new SourceSpec("enum_group_vs_signal.gd", """
                        class_name EnumGroupVsSignal
                        extends RefCounted

                        signal State
                        enum State { IDLE }
                        """)
        ));

        var skeletonDiagnostics = skeletonDiagnostics(fixture);
        assertAll(
                () -> assertEquals(4, skeletonDiagnostics.size()),
                () -> assertTrue(skeletonDiagnostics.stream().allMatch(diagnostic ->
                        diagnostic.message().contains("conflicts with an existing class member"))),
                () -> assertTrue(findClass(fixture, "EnumGroupVsProperty").getScriptConstants().isEmpty()),
                () -> assertTrue(findClass(fixture, "EnumGroupVsInnerClass").getScriptConstants().isEmpty()),
                // The inner class itself survives its sibling enum's rejection.
                () -> assertEquals(1, fixture.result().sourceClassRelations().stream()
                        .filter(relation -> relation.canonicalName().equals("EnumGroupVsInnerClass"))
                        .findFirst().orElseThrow().innerClassRelations().size()),
                () -> assertTrue(findClass(fixture, "EnumGroupVsFunction").getScriptConstants().isEmpty()),
                () -> assertTrue(findClass(fixture, "EnumGroupVsSignal").getScriptConstants().isEmpty())
        );
    }

    @Test
    void anonymousMemberConflictingWithPropertyIsRejected() throws IOException {
        var fixture = build("enum_anon_vs_property.gd", """
                class_name EnumAnonVsProperty
                extends RefCounted

                var IDLE: int
                enum { IDLE, RUNNING }
                """);

        assertEnumRejected(fixture, firstEnumStatement(fixture, 0), "conflicts with an existing class member");
        assertTrue(findClass(fixture, "EnumAnonVsProperty").getScriptConstants().isEmpty());
    }

    @Test
    void crossEnumNameConflictsAreRejectedPerEnum() throws IOException {
        var fixture = build("enum_cross_conflicts.gd", """
                class_name EnumCrossConflicts
                extends RefCounted

                enum { A }
                enum { A }
                enum State { IDLE }
                enum State { JUMP }
                enum Mode { ON }
                enum { Mode }
                """);

        var classDef = findClass(fixture, "EnumCrossConflicts");
        var skeletonDiagnostics = skeletonDiagnostics(fixture);
        assertAll(
                // Second anonymous `A`, second `State` group, and anonymous `Mode` all conflict.
                () -> assertEquals(3, skeletonDiagnostics.size()),
                // First occurrences stay published; later conflicting enums leave nothing behind.
                () -> assertConstantValues(classDef, "A", 0),
                () -> assertNotNull(findConstant(classDef, "State")),
                () -> assertNotNull(findConstant(classDef, "Mode")),
                () -> assertEquals(3, classDef.getScriptConstants().size()),
                () -> assertTrue(fixture.analysisData().skippedSubtreeRoots()
                        .containsKey(enumStatement(fixture, 1))),
                () -> assertTrue(fixture.analysisData().skippedSubtreeRoots()
                        .containsKey(enumStatement(fixture, 3))),
                () -> assertTrue(fixture.analysisData().skippedSubtreeRoots()
                        .containsKey(enumStatement(fixture, 5)))
        );
    }

    @Test
    void unsupportedValueExpressionsAreRejectedPerMemberWithSiblingsAlive() throws IOException {
        var fixture = build("enum_bad_values.gd", """
                class_name EnumBadValues
                extends RefCounted

                const FOO = 5
                enum State { IDLE }
                enum { C_CALL = abs(1) }
                enum { C_ATTR = State.IDLE }
                enum { C_POWER = 2 ** 3 }
                enum { C_SUBSCRIPT = State["IDLE"] }
                enum { C_DIV_ZERO = 1 / 0 }
                enum { C_UNKNOWN = MISSING }
                enum { C_CLASS_CONST = FOO }
                enum { C_LANGUAGE = PI }
                enum { GOOD = 9 }
                var hp: int
                """);

        var classDef = findClass(fixture, "EnumBadValues");
        var skeletonDiagnostics = skeletonDiagnostics(fixture);
        assertAll(
                () -> assertEquals(8, skeletonDiagnostics.size()),
                () -> assertTrue(skeletonDiagnostics.stream().allMatch(diagnostic ->
                        diagnostic.severity() == FrontendDiagnosticSeverity.ERROR)),
                // The good enum, the named group, and the property all survive.
                () -> assertConstantValues(classDef, "GOOD", 9),
                () -> assertNotNull(findConstant(classDef, "State")),
                () -> assertNotNull(findProperty(classDef, "hp")),
                () -> assertEquals(2, classDef.getScriptConstants().size())
        );
        // Every rejected enum subtree is skipped individually (enum indexes 1..8; index 0 is the
        // healthy named group and index 9 is the surviving sibling).
        for (var index = 1; index <= 8; index++) {
            var enumNode = enumStatement(fixture, index);
            assertTrue(fixture.analysisData().skippedSubtreeRoots().containsKey(enumNode),
                    "Expected skipped subtree root for enum index " + index);
        }
    }

    @Test
    void parserBrokenEnumPublishesNoFactsAndSkipsSubtreeWithoutDuplicateDiagnostic() throws IOException {
        // The parser rejects string/float initializers itself and its recovery drops the value,
        // leaving members that look like auto-increment candidates. The pre-pass must not
        // fabricate constants from them: no constant rows, subtree skipped, and no second
        // diagnostic on the parser-owned root cause.
        var fixture = build("enum_parser_owned_values.gd", """
                class_name EnumParserOwnedValues
                extends RefCounted

                enum { C_STRING = "x" }
                enum { C_FLOAT = 1.5 }
                enum { GOOD = 9 }
                var hp: int
                """);

        var parseDiagnostics = fixture.diagnostics().snapshot().asList().stream()
                .filter(diagnostic -> diagnostic.category().startsWith("parse."))
                .toList();
        var classDef = findClass(fixture, "EnumParserOwnedValues");
        assertAll(
                () -> assertEquals(2, parseDiagnostics.size()),
                () -> assertTrue(skeletonDiagnostics(fixture).isEmpty()),
                () -> assertEquals(List.of("GOOD"), classDef.getScriptConstants().stream()
                        .map(GdScriptClassConstant::name)
                        .toList()),
                () -> assertTrue(fixture.analysisData().skippedSubtreeRoots()
                        .containsKey(enumStatement(fixture, 0))),
                () -> assertTrue(fixture.analysisData().skippedSubtreeRoots()
                        .containsKey(enumStatement(fixture, 1))),
                () -> assertNotNull(findProperty(classDef, "hp"))
        );
    }

    @Test
    void valueEvaluationFailureAnchorsTheMemberNode() throws IOException {
        var fixture = build("enum_anchor.gd", """
                class_name EnumAnchor
                extends RefCounted

                enum { BROKEN = 1 / 0 }
                """);

        var diagnostic = skeletonDiagnostics(fixture).getFirst();
        var memberNode = enumStatement(fixture, 0).members().getFirst();
        assertEquals(FrontendRange.fromAstRange(memberNode.range()), diagnostic.range());
    }

    @Test
    void inheritedEnumConstantReferenceIsBlockedBeforeGlobalFallback() throws IOException {
        // Parent declares `enum { OK = 123 }` while the global Error enum exposes OK = 0. The
        // ancestor blocker must reject the reference instead of silently resolving to the global.
        var fixture = build(List.of(
                new SourceSpec("enum_child.gd", """
                        class_name EnumChild
                        extends EnumBase

                        enum { NEXT = OK }
                        """),
                new SourceSpec("enum_base.gd", """
                        class_name EnumBase
                        extends RefCounted

                        enum { OK = 123 }
                        """)
        ));

        var childClass = findClass(fixture, "EnumChild");
        var skeletonDiagnostics = skeletonDiagnostics(fixture);
        assertAll(
                () -> assertEquals(1, skeletonDiagnostics.size()),
                () -> assertTrue(skeletonDiagnostics.getFirst().message().contains("inherited enum constant")),
                () -> assertTrue(childClass.getScriptConstants().isEmpty()),
                // The parent enum is unaffected and keeps its own values.
                () -> assertConstantValues(findClass(fixture, "EnumBase"), "OK", 123)
        );
    }

    @Test
    void inheritedEnumConstantReferenceWithoutGlobalCollisionIsRejected() throws IOException {
        var fixture = build(List.of(
                new SourceSpec("enum_child_plain.gd", """
                        class_name EnumChildPlain
                        extends EnumBasePlain

                        enum { NEXT = BASE + 1 }
                        """),
                new SourceSpec("enum_base_plain.gd", """
                        class_name EnumBasePlain
                        extends RefCounted

                        enum { BASE = 7 }
                        """)
        ));

        assertAll(
                () -> assertEquals(1, skeletonDiagnostics(fixture).size()),
                () -> assertTrue(findClass(fixture, "EnumChildPlain").getScriptConstants().isEmpty()),
                () -> assertConstantValues(findClass(fixture, "EnumBasePlain"), "BASE", 7)
        );
    }

    @Test
    void forwardReferenceToLaterMemberIsRejected() throws IOException {
        var fixture = build("enum_forward_ref.gd", """
                class_name EnumForwardRef
                extends RefCounted

                enum { A = B, B = 1 }
                """);

        assertEnumRejected(fixture, firstEnumStatement(fixture, 0), "unknown identifier 'B'");
        assertTrue(findClass(fixture, "EnumForwardRef").getScriptConstants().isEmpty());
    }

    @Test
    void ancestorNamedEnumGroupNameIsBlockedBeforeGlobalFallback() throws IOException {
        // The ancestor blocker collects named group names too: the child enum must not fall
        // through to global lookup (or a misleading "unknown identifier") for an inherited group.
        var fixture = build(List.of(
                new SourceSpec("enum_child_group_ref.gd", """
                        class_name EnumChildGroupRef
                        extends EnumBaseGroup

                        enum { X = State }
                        """),
                new SourceSpec("enum_base_group.gd", """
                        class_name EnumBaseGroup
                        extends RefCounted

                        enum State { IDLE }
                        """)
        ));

        var skeletonDiagnostics = skeletonDiagnostics(fixture);
        assertAll(
                () -> assertEquals(1, skeletonDiagnostics.size()),
                () -> assertTrue(skeletonDiagnostics.getFirst().message().contains("inherited enum constant")),
                () -> assertTrue(findClass(fixture, "EnumChildGroupRef").getScriptConstants().isEmpty()),
                () -> assertNotNull(findConstant(findClass(fixture, "EnumBaseGroup"), "State"))
        );
    }

    @Test
    void autoIncrementWrapsAtLongBoundary() throws IOException {
        // Auto-increment is plain int64 arithmetic: past Long.MAX_VALUE it wraps, matching
        // Godot's two's-complement int behavior.
        var fixture = build("enum_auto_wrap.gd", """
                class_name EnumAutoWrap
                extends RefCounted

                enum { BIG = 9223372036854775807, WRAPPED }
                """);

        var classDef = findClass(fixture, "EnumAutoWrap");
        assertAll(
                () -> assertConstantValues(classDef, "BIG", Long.MAX_VALUE),
                () -> assertConstantValues(classDef, "WRAPPED", Long.MIN_VALUE),
                () -> assertTrue(fixture.diagnostics().snapshot().isEmpty())
        );
    }

    @Test
    void outOfRangeShiftCountIsRejectedWithSiblingsAlive() throws IOException {
        var fixture = build("enum_bad_shift.gd", """
                class_name EnumBadShift
                extends RefCounted

                enum { BAD = 1 << -1 }
                enum { GOOD = 1 << 3 }
                """);

        var classDef = findClass(fixture, "EnumBadShift");
        assertAll(
                () -> assertEnumRejected(fixture, firstEnumStatement(fixture, 0), "shift count -1"),
                () -> assertTrue(classDef.getScriptConstants().stream()
                        .noneMatch(constant -> constant.name().equals("BAD"))),
                () -> assertConstantValues(classDef, "GOOD", 8)
        );
    }

    @Test
    void emptyEnumIsRejectedForBothNamingForms() throws IOException {
        // gdparser maps an empty enum body to one phantom blank-named member (parse diagnostics
        // stay empty), so the pre-pass owns the "at least one member" error for both forms.
        var fixture = build("enum_empty_reject.gd", """
                class_name EnumEmptyReject
                extends RefCounted

                enum State {}
                enum {}
                var hp: int
                """);

        var skeletonDiagnostics = skeletonDiagnostics(fixture);
        assertAll(
                () -> assertEquals(2, skeletonDiagnostics.size()),
                () -> assertTrue(skeletonDiagnostics.stream().allMatch(diagnostic ->
                        diagnostic.message().contains("at least one member"))),
                () -> assertTrue(findClass(fixture, "EnumEmptyReject").getScriptConstants().isEmpty()),
                () -> assertNotNull(findProperty(findClass(fixture, "EnumEmptyReject"), "hp"))
        );
    }

    @Test
    void failedEnumPublishesNoTypeMetaSoAnnotationsFallBackToVariant() throws IOException {
        var fixture = build("enum_half_baked.gd", """
                class_name EnumHalfBaked
                extends RefCounted

                enum Broken { A = 1 / 0 }
                var x: Broken
                """);

        var classDef = findClass(fixture, "EnumHalfBaked");
        var typeResolutionWarnings = fixture.diagnostics().snapshot().asList().stream()
                .filter(diagnostic -> diagnostic.category().equals("sema.type_resolution"))
                .toList();
        assertAll(
                () -> assertEquals(1, skeletonDiagnostics(fixture).size()),
                () -> assertTrue(classDef.getScriptConstants().isEmpty()),
                // Without the enum type-meta the annotation degrades to Variant plus one warning.
                () -> assertEquals(GdVariantType.VARIANT, findPropertyType(classDef, "x")),
                () -> assertEquals(1, typeResolutionWarnings.size()),
                () -> assertTrue(typeResolutionWarnings.getFirst().message().contains("Broken"))
        );
    }

    private record SourceSpec(@NotNull String fileName, @NotNull String source) {
    }

    private record Fixture(
            @NotNull FrontendModuleSkeleton result,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnostics,
            @NotNull List<FrontendSourceUnit> units
    ) {
    }

    private static @NotNull Fixture build(@NotNull String fileName, @NotNull String source) throws IOException {
        return build(List.of(new SourceSpec(fileName, source)));
    }

    private static @NotNull Fixture build(@NotNull List<SourceSpec> sources) throws IOException {
        var parserService = new GdScriptParserService();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var diagnostics = new DiagnosticManager();
        var analysisData = FrontendAnalysisData.bootstrap();
        var units = sources.stream()
                .map(source -> parserService.parseUnit(Path.of("tmp", source.fileName()), source.source(), diagnostics))
                .toList();
        var result = new FrontendClassSkeletonBuilder().build(
                new FrontendModule("enum_test_module", units),
                registry,
                diagnostics,
                analysisData
        );
        return new Fixture(result, analysisData, diagnostics, units);
    }

    private static @NotNull LirClassDef findClass(@NotNull Fixture fixture, @NotNull String className) {
        return fixture.result().sourceClassRelations().stream()
                .flatMap(relation -> relation.allClassDefs().stream())
                .filter(classDef -> classDef.getName().equals(className))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Class not found: " + className));
    }

    private static @NotNull GdScriptClassConstant findConstant(
            @NotNull LirClassDef classDef,
            @NotNull String name
    ) {
        return classDef.getScriptConstants().stream()
                .filter(constant -> constant.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Constant not found: " + name));
    }

    private static void assertConstantValues(@NotNull LirClassDef classDef, @NotNull String name, long value) {
        var constant = findConstant(classDef, name);
        assertEquals(GdIntType.INT, constant.type());
        var declaration = assertInstanceOf(GdScriptEnumConstant.class, constant.declaration());
        assertEquals(value, declaration.value());
        assertEquals(name, declaration.memberName());
    }

    private static @Nullable Object findProperty(@NotNull LirClassDef classDef, @NotNull String name) {
        return classDef.getProperties().stream()
                .filter(property -> property.getName().equals(name))
                .findFirst()
                .orElse(null);
    }

    private static @NotNull gd.script.gdcc.type.GdType findPropertyType(
            @NotNull LirClassDef classDef,
            @NotNull String name
    ) {
        return classDef.getProperties().stream()
                .filter(property -> property.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Property not found: " + name))
                .getType();
    }

    private static @NotNull EnumDeclaration firstEnumStatement(@NotNull Fixture fixture, int unitIndex) {
        return enumStatement(fixture, unitIndex, 0);
    }

    private static @NotNull EnumDeclaration enumStatement(@NotNull Fixture fixture, int statementIndex) {
        return enumStatement(fixture, 0, statementIndex);
    }

    private static @NotNull EnumDeclaration enumStatement(
            @NotNull Fixture fixture,
            int unitIndex,
            int statementIndex
    ) {
        return (EnumDeclaration) fixture.units().get(unitIndex).ast().statements().stream()
                .filter(EnumDeclaration.class::isInstance)
                .toList()
                .get(statementIndex);
    }

    private static @NotNull List<gd.script.gdcc.frontend.diagnostic.FrontendDiagnostic> skeletonDiagnostics(
            @NotNull Fixture fixture
    ) {
        return fixture.diagnostics().snapshot().asList().stream()
                .filter(diagnostic -> diagnostic.category().equals("sema.class_skeleton"))
                .toList();
    }

    private static void assertEnumRejected(
            @NotNull Fixture fixture,
            @NotNull EnumDeclaration enumNode,
            @NotNull String messagePart
    ) {
        var skeletonDiagnostics = skeletonDiagnostics(fixture);
        assertAll(
                () -> assertEquals(1, skeletonDiagnostics.size()),
                () -> assertTrue(skeletonDiagnostics.getFirst().message().contains(messagePart),
                        "Expected message to contain '" + messagePart + "' but was: "
                                + skeletonDiagnostics.getFirst().message()),
                () -> assertEquals(FrontendDiagnosticSeverity.ERROR, skeletonDiagnostics.getFirst().severity()),
                () -> assertTrue(fixture.analysisData().skippedSubtreeRoots().containsKey(enumNode))
        );
    }

    private static @NotNull GdScriptEnumConstant requireMember(@NotNull GdScriptEnumGroup group, @NotNull String name) {
        var member = group.findMember(name);
        if (member == null) {
            throw new AssertionError("Enum member not found: " + name);
        }
        return member;
    }
}
