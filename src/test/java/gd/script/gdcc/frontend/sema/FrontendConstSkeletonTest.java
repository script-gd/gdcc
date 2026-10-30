package gd.script.gdcc.frontend.sema;

import dev.superice.gdparser.frontend.ast.DeclarationKind;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import gd.script.gdcc.frontend.constant.Evaluation;
import gd.script.gdcc.frontend.constant.GdConstantValue;
import gd.script.gdcc.frontend.constant.GdScriptEvaluatedConstant;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnostic;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.FrontendSourceUnit;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.frontend.scope.ClassScope;
import gd.script.gdcc.frontend.sema.analyzer.FrontendScopeAnalyzer;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.GdScriptClassConstant;
import gd.script.gdcc.scope.ResolveRestriction;
import gd.script.gdcc.type.GdBoolType;
import gd.script.gdcc.type.GdFloatType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdStringType;
import gd.script.gdcc.type.GdFloatVectorType;
import gd.script.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/// Acceptance tests for class-level `const` declarations in the joint const/enum skeleton
/// pre-pass: frozen-value publication, source-order visibility, failure masking, claim
/// conflicts, declared-type conversion, and fact/provenance side tables.
class FrontendConstSkeletonTest {
    @Test
    void scalarConstantsPublishFrozenValuesAndFacts() throws IOException {
        var fixture = build("const_scalars.gd", """
                class_name ConstScalars
                extends RefCounted

                const A = 42
                const B = 1.5
                const C = true
                const S = "hi"
                const NEG = -7
                const SUM = 1 + 2 * 3
                const PICK = "yes" if 1 < 2 else "no"
                """);

        var classDef = findClass(fixture, "ConstScalars");
        assertAll(
                () -> assertScalarConst(classDef, "A", GdIntType.INT, 42L),
                () -> assertScalarConst(classDef, "B", GdFloatType.FLOAT, 1.5),
                () -> assertScalarConst(classDef, "C", GdBoolType.BOOL, true),
                () -> assertScalarConst(classDef, "S", GdStringType.STRING, "hi"),
                () -> assertScalarConst(classDef, "NEG", GdIntType.INT, -7L),
                () -> assertScalarConst(classDef, "SUM", GdIntType.INT, 7L),
                () -> assertScalarConst(classDef, "PICK", GdStringType.STRING, "yes"),
                () -> assertTrue(fixture.diagnostics().snapshot().isEmpty())
        );
        // Every declaration records a Resolved fact keyed by its AST identity.
        for (var decl : constDeclarations(fixture)) {
            assertInstanceOf(Evaluation.Resolved.class, fixture.analysisData().constantFacts().get(decl),
                    "Missing Resolved constant fact for " + decl.name());
        }
    }

    @Test
    void builtinTypedConstantsPublishFrozenValues() throws IOException {
        var fixture = build("const_builtins.gd", """
                class_name ConstBuiltins
                extends RefCounted

                const V = Vector2(1.0, 2.0)
                const ORIGIN = Vector3.ZERO
                const EDGE = Vector2(1, 2).x
                """);

        var classDef = findClass(fixture, "ConstBuiltins");
        var vectorDecl = findEvaluatedConstant(classDef, "V");
        var originDecl = findEvaluatedConstant(classDef, "ORIGIN");
        assertAll(
                () -> assertEquals(GdFloatVectorType.VECTOR2, vectorDecl.value().type()),
                () -> assertEquals(List.of(1.0, 2.0), floatLeaves(vectorDecl.value())),
                () -> assertEquals(List.of(0.0, 0.0, 0.0), floatLeaves(originDecl.value())),
                () -> assertScalarConst(classDef, "EDGE", GdFloatType.FLOAT, 1.0),
                () -> assertTrue(fixture.diagnostics().snapshot().isEmpty())
        );
    }

    @Test
    void earlierConstantsAreVisibleToLaterOnesInSourceOrder() throws IOException {
        var fixture = build("const_source_order.gd", """
                class_name ConstSourceOrder
                extends RefCounted

                const A = 5
                const B = A * 2 + 1
                enum { E = B }
                const D = E + 1
                """);

        var classDef = findClass(fixture, "ConstSourceOrder");
        assertAll(
                () -> assertScalarConst(classDef, "B", GdIntType.INT, 11L),
                () -> assertScalarConst(classDef, "D", GdIntType.INT, 12L),
                () -> assertTrue(fixture.diagnostics().snapshot().isEmpty())
        );
    }

    @Test
    void laterConstantsAndEnumsAreNotVisibleBackwards() throws IOException {
        var fixture = build("const_forward_refs.gd", """
                class_name ConstForwardRefs
                extends RefCounted

                const B = A + 1
                const A = 5
                enum { E = D }
                const D = 4
                """);

        var classDef = findClass(fixture, "ConstForwardRefs");
        var skeletonDiagnostics = skeletonDiagnostics(fixture);
        assertAll(
                () -> assertEquals(2, skeletonDiagnostics.size()),
                () -> assertTrue(skeletonDiagnostics.stream().allMatch(diagnostic ->
                        diagnostic.message().contains("declared later"))),
                // `A`/`D` still publish; the forward consumers `B` and the enum do not.
                () -> assertScalarConst(classDef, "A", GdIntType.INT, 5L),
                () -> assertScalarConst(classDef, "D", GdIntType.INT, 4L),
                () -> assertNull(findConstant(classDef, "B")),
                () -> assertNull(findConstant(classDef, "E")),
                // B joined the failed-const mask; a failed enum leaves no mask entry.
                () -> assertTrue(
                        fixture.analysisData().rejectedClassConstants()
                                .get("ConstForwardRefs").containsKey("B")
                ),
                () -> assertFalse(
                        fixture.analysisData().rejectedClassConstants()
                                .get("ConstForwardRefs").containsKey("E")
                )
        );
    }

    @Test
    void failedConstKeepsNameBlockedForLaterInitializers() throws IOException {
        var fixture = build("const_failed_mask.gd", """
                class_name ConstFailedMask
                extends RefCounted

                const BAD = MISSING + 1
                const DEP = BAD + 1
                const GOOD = 7
                """);

        var classDef = findClass(fixture, "ConstFailedMask");
        var skeletonDiagnostics = skeletonDiagnostics(fixture);
        var mask = fixture.analysisData().rejectedClassConstants().get("ConstFailedMask");
        assertAll(
                () -> assertEquals(2, skeletonDiagnostics.size()),
                // GOOD survives: failed consts never take siblings down.
                () -> assertScalarConst(classDef, "GOOD", GdIntType.INT, 7L),
                () -> assertNull(findConstant(classDef, "BAD")),
                () -> assertNull(findConstant(classDef, "DEP")),
                () -> assertNotNull(mask),
                () -> assertTrue(mask.containsKey("BAD")),
                () -> assertTrue(mask.containsKey("DEP")),
                () -> assertEquals(2, mask.size()),
                // The dependent const reports the poison reason, not "unknown identifier".
                () -> assertTrue(skeletonDiagnostics.get(1).message()
                                .contains("initializer failed to evaluate"),
                        "Expected dependency-invalid reason but was: "
                                + skeletonDiagnostics.get(1).message())
        );
    }

    @Test
    void selfAndNonConstantMemberReferencesAreRejected() throws IOException {
        var fixture = build("const_self_member_refs.gd", """
                class_name ConstSelfMemberRefs
                extends RefCounted

                const SELF_REF = SELF_REF + 1
                var member = 3
                const USES_VAR = member
                """);

        var skeletonDiagnostics = skeletonDiagnostics(fixture);
        assertAll(
                () -> assertEquals(2, skeletonDiagnostics.size()),
                () -> assertTrue(skeletonDiagnostics.get(0).message().contains("own declaration"),
                        "Expected self-reference reason but was: "
                                + skeletonDiagnostics.get(0).message()),
                () -> assertTrue(skeletonDiagnostics.get(1).message().contains("not a compile-time constant"),
                        "Expected non-constant member reason but was: "
                                + skeletonDiagnostics.get(1).message()),
                () -> assertEquals(2, fixture.analysisData().rejectedClassConstants()
                        .get("ConstSelfMemberRefs").size())
        );
    }

    @Test
    void nameConflictsRejectOnlyTheLosingConst() throws IOException {
        var fixture = build("const_conflicts.gd", """
                class_name ConstConflicts
                extends RefCounted

                const C = 1
                var C = 2
                const D = 1
                const D = 2
                const E = 1
                enum { E }
                """);

        var classDef = findClass(fixture, "ConstConflicts");
        var skeletonDiagnostics = skeletonDiagnostics(fixture);
        assertAll(
                () -> assertEquals(3, skeletonDiagnostics.size()),
                () -> assertTrue(skeletonDiagnostics.stream().allMatch(diagnostic ->
                        diagnostic.message().contains("conflicts with an existing class member"))),
                // The first `const D` and `const E` stay published; `C` lost to the var claim.
                () -> assertScalarConst(classDef, "D", GdIntType.INT, 1L),
                () -> assertScalarConst(classDef, "E", GdIntType.INT, 1L),
                () -> assertNull(findConstant(classDef, "C")),
                () -> assertNotNull(findProperty(classDef, "C")),
                // Only the second `const D` is masked by name — and that mask must not shadow
                // the surviving first `const D` row (probe order keeps usable rows visible).
                () -> assertNull(fixture.analysisData().rejectedClassConstants()
                        .get("ConstConflicts")),
                () -> assertEquals(2, classDef.getScriptConstants().size())
        );
    }

    @Test
    void declaredTypeAnnotationsConvertOrReject() throws IOException {
        var fixture = build("const_declared_types.gd", """
                class_name ConstDeclaredTypes
                extends RefCounted

                const F: float = 2
                const V: Vector2 = Vector2i(1, 2)
                const W: Vector2 = Vector3i(1, 2, 3)
                const B: bool = 3
                const N: StringName = "name"
                """);

        var classDef = findClass(fixture, "ConstDeclaredTypes");
        var skeletonDiagnostics = skeletonDiagnostics(fixture);
        assertAll(
                () -> assertScalarConst(classDef, "F", GdFloatType.FLOAT, 2.0),
                () -> {
                    var converted = findEvaluatedConstant(classDef, "V");
                    assertEquals(GdFloatVectorType.VECTOR2, converted.value().type());
                    assertEquals(List.of(1.0, 2.0), floatLeaves(converted.value()));
                },
                // Three failures: wrong-dimension vector conversion, `bool` payload mismatch,
                // and `StringName`, which sits outside the frozen constant-type set entirely.
                () -> assertEquals(3, skeletonDiagnostics.size()),
                () -> assertTrue(skeletonDiagnostics.get(0).message().contains("cannot be converted")
                                || skeletonDiagnostics.get(0).message().contains("declared type"),
                        "Expected conversion failure but was: " + skeletonDiagnostics.get(0).message()),
                () -> assertTrue(skeletonDiagnostics.get(2).message().contains("StringName"),
                        "Expected StringName whitelist rejection but was: "
                                + skeletonDiagnostics.get(2).message()),
                () -> assertNotNull(fixture.analysisData().rejectedClassConstants()
                        .get("ConstDeclaredTypes"))
        );
    }

    @Test
    void inheritedMemberNamesBlockFallbackLookup() throws IOException {
        var fixture = build(List.of(
                new SourceSpec("const_parent.gd", """
                        class_name ConstParent
                        extends RefCounted

                        var shared = 1
                        const PC = 9
                        """),
                new SourceSpec("const_child.gd", """
                        class_name ConstChild
                        extends ConstParent

                        const FROM_VAR = shared
                        const FROM_CONST = PC
                        """)
        ));

        var skeletonDiagnostics = skeletonDiagnostics(fixture);
        var mask = fixture.analysisData().rejectedClassConstants().get("ConstChild");
        assertAll(
                // Both ancestor member kinds are terminal blockers by design: initializers may
                // only see same-class names, never inherited ones.
                () -> assertEquals(2, skeletonDiagnostics.size()),
                () -> assertTrue(skeletonDiagnostics.stream().allMatch(diagnostic ->
                        diagnostic.message().contains("inherited member"))),
                () -> assertNotNull(mask),
                () -> assertEquals(2, mask.size())
        );
    }

    @Test
    void globalsEngineAndLanguageConstantsResolveInInitializers() throws IOException {
        var fixture = build("const_globals.gd", """
                class_name ConstGlobals
                extends RefCounted

                const P = PI
                const MODE = Control.PRESET_CENTER
                const ERR = OK
                """);

        var classDef = findClass(fixture, "ConstGlobals");
        assertAll(
                () -> assertScalarConst(classDef, "P", GdFloatType.FLOAT, Math.PI),
                () -> assertScalarConst(classDef, "MODE", GdIntType.INT, 8L),
                () -> assertScalarConst(classDef, "ERR", GdIntType.INT, 0L),
                () -> assertTrue(fixture.diagnostics().snapshot().isEmpty())
        );
    }

    @Test
    void nonNamespaceTypeMetasRejectAsNonConstant() throws IOException {
        var fixture = build(List.of(
                new SourceSpec("const_other_class.gd", """
                        class_name OtherTop
                        extends RefCounted

                        const C = 1
                        """),
                new SourceSpec("const_namespaces.gd", """
                        class_name ConstNamespaces
                        extends RefCounted

                        const OK_BARE = OK
                        const BAD_CTOR = Error(1)
                        const BAD_BARE = Error
                        const BAD_CLASS = OtherTop
                        const BAD_CROSS = OtherTop.C
                        """)
        ));

        var classDef = findClass(fixture, "ConstNamespaces");
        var skeletonDiagnostics = skeletonDiagnostics(fixture);
        assertAll(
                // A global enum VALUE is a usable bare name; the enum NAME itself is not a
                // namespace: `Error(1)` must not sneak through as an `int` constructor, and a
                // same-module class name cannot descend into cross-class constants.
                () -> assertScalarConst(classDef, "OK_BARE", GdIntType.INT, 0L),
                () -> assertEquals(4, skeletonDiagnostics.size()),
                () -> assertTrue(skeletonDiagnostics.get(0).message().contains("global enum"),
                        "Expected global-enum rejection but was: " + skeletonDiagnostics.get(0).message()),
                () -> assertTrue(skeletonDiagnostics.get(1).message().contains("global enum"),
                        "Expected global-enum rejection but was: " + skeletonDiagnostics.get(1).message()),
                () -> assertTrue(skeletonDiagnostics.get(2).message().contains("script class"),
                        "Expected script-class rejection but was: " + skeletonDiagnostics.get(2).message()),
                () -> assertTrue(skeletonDiagnostics.get(3).message().contains("script class"),
                        "Expected script-class rejection but was: " + skeletonDiagnostics.get(3).message())
        );
    }

    @Test
    void failedConstMaskTerminatesValueEnumerationAcrossLayers() throws IOException {
        var fixture = analyzeScope(List.of(
                new SourceSpec("const_enum_gp.gd", """
                        class_name ConstEnumGp
                        extends RefCounted

                        const GX = 1
                        """),
                new SourceSpec("const_enum_p.gd", """
                        class_name ConstEnumParent
                        extends ConstEnumGp

                        const GX = MISSING
                        var pv = 1
                        """),
                new SourceSpec("const_enum_c.gd", """
                        class_name ConstEnumChild
                        extends ConstEnumParent

                        const PC = MISSING
                        var cv = 2
                        """)
        ));

        var childScope = assertInstanceOf(
                ClassScope.class,
                fixture.analysisData().scopesByAst().get(fixture.units().get(2).ast())
        );
        var valueNames = childScope.valuesHere().stream().map(v -> v.name()).toList();
        var visibleNames = childScope.collectVisibleValues().stream().map(v -> v.name()).toList();
        assertAll(
                // Child's masked `PC` claims the name; parent's masked `GX` claims at its own
                // layer, so grandparent's usable `GX` must not leak into enumeration — exactly
                // the terminal blocking single-name lookup applies.
                () -> assertTrue(valueNames.contains("cv")),
                () -> assertTrue(valueNames.contains("pv")),
                () -> assertFalse(valueNames.contains("PC")),
                () -> assertFalse(valueNames.contains("GX")),
                () -> assertFalse(visibleNames.contains("PC")),
                () -> assertFalse(visibleNames.contains("GX")),
                () -> assertTrue(childScope.blockedValueNamesHere().contains("PC")),
                // Single-name lookup stays terminally blocked for both masks.
                () -> assertTrue(childScope.resolveValue("PC", ResolveRestriction.unrestricted()).isBlocked()),
                () -> assertTrue(childScope.resolveValue("GX", ResolveRestriction.unrestricted()).isBlocked())
        );
    }

    @Test
    void namedEnumGroupsResolveAsNamespacesForClassConsts() throws IOException {
        var fixture = build("const_enum_group.gd", """
                class_name ConstEnumGroup
                extends RefCounted

                enum State { IDLE, JUMP = 5 }
                const SPEED = State.JUMP * 2
                const GROUP = State
                """);

        var classDef = findClass(fixture, "ConstEnumGroup");
        var skeletonDiagnostics = skeletonDiagnostics(fixture);
        assertAll(
                () -> assertScalarConst(classDef, "SPEED", GdIntType.INT, 10L),
                // The bare group name is a namespace token, not a usable const value.
                () -> assertEquals(1, skeletonDiagnostics.size()),
                () -> assertTrue(skeletonDiagnostics.getFirst().message().contains("namespace")
                                || skeletonDiagnostics.getFirst().message().contains("not a value"),
                        "Expected namespace-as-value rejection but was: "
                                + skeletonDiagnostics.getFirst().message())
        );
    }

    @Test
    void evaluatedConstantsRecordDeclarationProvenance() throws IOException {
        var fixture = build("const_provenance.gd", """
                class_name ConstProvenance
                extends RefCounted

                const MARK = 123
                """);

        var classDef = findClass(fixture, "ConstProvenance");
        var declaration = findEvaluatedConstant(classDef, "MARK");
        var origin = fixture.analysisData().declarationOrigins().get(declaration);
        assertAll(
                () -> assertNotNull(origin, "declarationOrigins must carry the const model"),
                () -> assertEquals("ConstProvenance", declaration.ownerCanonicalName()),
                () -> assertEquals("MARK", declaration.name())
        );
    }

    // ------------------------------------------------------------------
    // Fixture + assertion helpers
    // ------------------------------------------------------------------

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
                new FrontendModule("const_test_module", units),
                registry,
                diagnostics,
                analysisData
        );
        return new Fixture(result, analysisData, diagnostics, units);
    }

    /// Parse + skeleton + scope publication for tests that assert `ClassScope` mask behavior.
    private static @NotNull Fixture analyzeScope(@NotNull List<SourceSpec> sources) throws IOException {
        var parserService = new GdScriptParserService();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var diagnostics = new DiagnosticManager();
        var analysisData = FrontendAnalysisData.bootstrap();
        var units = sources.stream()
                .map(source -> parserService.parseUnit(Path.of("tmp", source.fileName()), source.source(), diagnostics))
                .toList();
        var result = new FrontendClassSkeletonBuilder().build(
                new FrontendModule("const_test_module", units),
                registry,
                diagnostics,
                analysisData
        );
        analysisData.updateModuleSkeleton(result);
        analysisData.updateDiagnostics(diagnostics.snapshot());
        new FrontendScopeAnalyzer().analyze(registry, analysisData, diagnostics);
        return new Fixture(result, analysisData, diagnostics, units);
    }

    private static @NotNull LirClassDef findClass(@NotNull Fixture fixture, @NotNull String className) {
        return fixture.result().sourceClassRelations().stream()
                .flatMap(relation -> relation.allClassDefs().stream())
                .filter(classDef -> classDef.getName().equals(className))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Class not found: " + className));
    }

    private static @Nullable GdScriptClassConstant findConstant(
            @NotNull LirClassDef classDef,
            @NotNull String name
    ) {
        return classDef.getScriptConstants().stream()
                .filter(constant -> constant.name().equals(name))
                .findFirst()
                .orElse(null);
    }

    private static @NotNull GdScriptEvaluatedConstant findEvaluatedConstant(
            @NotNull LirClassDef classDef,
            @NotNull String name
    ) {
        var constant = findConstant(classDef, name);
        assertNotNull(constant, "Constant not found: " + name);
        return assertInstanceOf(GdScriptEvaluatedConstant.class, constant.declaration());
    }

    private static void assertScalarConst(
            @NotNull LirClassDef classDef,
            @NotNull String name,
            @NotNull GdType expectedType,
            @NotNull Object expectedScalar
    ) {
        var constant = findConstant(classDef, name);
        assertNotNull(constant, "Constant not found: " + name);
        assertEquals(expectedType, constant.type(), "Constant row type mismatch for " + name);
        var declaration = assertInstanceOf(GdScriptEvaluatedConstant.class, constant.declaration());
        var value = declaration.value();
        switch (value) {
            case GdConstantValue.IntValue intValue -> assertEquals(expectedScalar, intValue.value());
            case GdConstantValue.FloatValue floatValue ->
                    assertEquals(((Number) expectedScalar).doubleValue(), floatValue.value(), 1e-9);
            case GdConstantValue.BoolValue boolValue -> assertEquals(expectedScalar, boolValue.value());
            case GdConstantValue.StringValue stringValue -> assertEquals(expectedScalar, stringValue.value());
            case GdConstantValue.BuiltinValue _ ->
                    fail("Expected scalar constant value for " + name + " but got a builtin payload");
        }
    }

    /// Canonical float leaves of a builtin constant payload (component values in storage order).
    private static @NotNull List<Double> floatLeaves(@NotNull GdConstantValue value) {
        var builtin = assertInstanceOf(GdConstantValue.BuiltinValue.class, value);
        return builtin.components().stream()
                .map(leaf -> assertInstanceOf(GdConstantValue.FloatValue.class, leaf).value())
                .toList();
    }

    /// Top-level `const` declarations of the single-class fixtures, in source order.
    private static @NotNull List<VariableDeclaration> constDeclarations(@NotNull Fixture fixture) {
        return fixture.units().stream()
                .flatMap(unit -> unit.ast().statements().stream())
                .filter(VariableDeclaration.class::isInstance)
                .map(VariableDeclaration.class::cast)
                .filter(declaration -> declaration.kind() == DeclarationKind.CONST)
                .toList();
    }

    private static @Nullable Object findProperty(@NotNull LirClassDef classDef, @NotNull String name) {
        return classDef.getProperties().stream()
                .filter(property -> property.getName().equals(name))
                .findFirst()
                .orElse(null);
    }

    private static @NotNull List<FrontendDiagnostic> skeletonDiagnostics(@NotNull Fixture fixture) {
        return fixture.diagnostics().snapshot().asList().stream()
                .filter(diagnostic -> diagnostic.category().equals("sema.class_skeleton"))
                .toList();
    }

    private record Fixture(
            @NotNull FrontendModuleSkeleton result,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnostics,
            @NotNull List<FrontendSourceUnit> units
    ) {
    }

    private record SourceSpec(@NotNull String fileName, @NotNull String source) {
    }
}
