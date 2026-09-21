package gd.script.gdcc.frontend.sema;

import dev.superice.gdparser.frontend.ast.CastExpression;
import dev.superice.gdparser.frontend.ast.ClassDeclaration;
import dev.superice.gdparser.frontend.ast.EnumDeclaration;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.Statement;
import dev.superice.gdparser.frontend.ast.TypeTestExpression;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnostic;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.FrontendSourceUnit;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.frontend.scope.BlockScope;
import gd.script.gdcc.frontend.scope.ClassScope;
import gd.script.gdcc.frontend.sema.analyzer.FrontendScopeAnalyzer;
import gd.script.gdcc.frontend.sema.analyzer.FrontendSemanticAnalyzer;
import gd.script.gdcc.frontend.sema.analyzer.FrontendVariableAnalyzer;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.GdScriptEnumConstant;
import gd.script.gdcc.scope.GdScriptEnumGroup;
import gd.script.gdcc.scope.PropertyDef;
import gd.script.gdcc.scope.ResolveRestriction;
import gd.script.gdcc.scope.ScopeTypeMetaKind;
import gd.script.gdcc.scope.ScopeValueKind;
import gd.script.gdcc.scope.resolver.ScopeTypeResolver;
import gd.script.gdcc.type.GdArrayType;
import gd.script.gdcc.type.GdDictionaryType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdVariantType;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Scope-phase tests for the script enum wiring (`frontend_enum_implementation.md`):
/// `ClassScope` indexes `ClassDef.getScriptConstants()` as
/// read-only `CONSTANT` value bindings, the inheritance walk extends to the same table, and named
/// enum groups are re-published as `GDCC_ENUM` type-metas on both class boundaries (top-level
/// `SourceFile` and inner `ClassDeclaration`).
///
/// Most tests here stop after skeleton + scope (+ variable where shadowing needs it), so they
/// cannot accidentally anchor body-phase behavior; `isAndAsEnumTargetsEraseToInt` is the deliberate
/// exception, running the full semantic pipeline to assert published type-test/cast facts.
class FrontendEnumScopeTest {
    @Test
    void classScopeIndexesAnonymousMembersAndNamedGroupAsConstants() throws IOException {
        var fixture = analyze(List.of(new SourceSpec("enum_scope_constants.gd", """
                class_name EnumScopeConstants
                extends RefCounted

                enum { IDLE, JUMP = 5 }
                enum State { S_IDLE, S_JUMP = 3 }
                """)));
        var sourceScope = sourceClassScope(fixture, 0);

        var idle = sourceScope.resolveValue("IDLE");
        assertAll(
                () -> assertNotNull(idle),
                () -> assertEquals(ScopeValueKind.CONSTANT, idle.kind()),
                () -> assertEquals(GdIntType.INT, idle.type()),
                () -> assertTrue(idle.constant()),
                () -> assertFalse(idle.writable()),
                () -> assertTrue(idle.staticMember()),
                () -> {
                    var declaration = assertInstanceOf(GdScriptEnumConstant.class, idle.declaration());
                    assertEquals(0L, declaration.value());
                    assertNull(declaration.groupName());
                    assertEquals("EnumScopeConstants", declaration.ownerClassCanonicalName());
                },
                () -> assertEquals(5L, assertInstanceOf(
                        GdScriptEnumConstant.class,
                        sourceScope.resolveValue("JUMP").declaration()
                ).value())
        );

        var state = sourceScope.resolveValue("State");
        assertAll(
                () -> assertNotNull(state),
                () -> assertEquals(ScopeValueKind.CONSTANT, state.kind()),
                () -> assertInstanceOf(GdDictionaryType.class, state.type()),
                () -> assertTrue(state.constant()),
                () -> assertFalse(state.writable()),
                () -> {
                    var group = assertInstanceOf(GdScriptEnumGroup.class, state.declaration());
                    assertEquals("State", group.name());
                    assertEquals("EnumScopeConstants", group.ownerClassCanonicalName());
                    assertEquals(2, group.members().size());
                    assertEquals(3L, group.findMember("S_JUMP").value());
                }
        );
    }

    @Test
    void namedEnumPublishesGdccEnumTypeMetaOnTopLevelClassScope() throws IOException {
        var fixture = analyze(List.of(new SourceSpec("enum_scope_type_meta.gd", """
                class_name EnumScopeTypeMeta
                extends RefCounted

                enum State { IDLE }
                """)));
        var sourceScope = sourceClassScope(fixture, 0);

        var typeMeta = sourceScope.resolveTypeMeta("State");
        assertAll(
                () -> assertNotNull(typeMeta),
                () -> assertEquals(ScopeTypeMetaKind.GDCC_ENUM, typeMeta.kind()),
                () -> assertEquals(GdIntType.INT, typeMeta.instanceType()),
                () -> assertTrue(typeMeta.pseudoType()),
                () -> assertEquals("EnumScopeTypeMeta.State", typeMeta.canonicalName()),
                () -> assertEquals("State", typeMeta.sourceName()),
                // The value binding and the type-meta must share the same group fact published
                // by the skeleton enum pre-pass.
                () -> assertSame(sourceScope.resolveValue("State").declaration(), typeMeta.declaration())
        );
    }

    @Test
    void enumBindingsResolveFromCallableAndBlockScopes() throws IOException {
        var fixture = analyze(List.of(new SourceSpec("enum_scope_callable.gd", """
                class_name EnumScopeCallable
                extends RefCounted

                enum { IDLE }
                enum State { S_A }

                func pick():
                    pass
                """)));
        var scopesByAst = fixture.analysisData().scopesByAst();
        var pickFunction = findStatement(
                fixture.units().getFirst().ast().statements(),
                FunctionDeclaration.class,
                function -> function.name().equals("pick")
        );
        var bodyScope = assertInstanceOf(BlockScope.class, scopesByAst.get(pickFunction.body()));

        assertAll(
                () -> assertEquals(
                        ScopeValueKind.CONSTANT,
                        bodyScope.resolveValue("IDLE").kind()
                ),
                () -> assertEquals(
                        ScopeValueKind.CONSTANT,
                        bodyScope.resolveValue("State").kind()
                ),
                () -> assertEquals(
                        ScopeTypeMetaKind.GDCC_ENUM,
                        bodyScope.resolveTypeMeta("State").kind()
                )
        );
    }

    @Test
    void staticContextAllowsEnumConstantLookup() throws IOException {
        var fixture = analyze(List.of(new SourceSpec("enum_scope_static.gd", """
                class_name EnumScopeStatic
                extends RefCounted

                enum { IDLE }
                enum State { S_A }

                static func pick():
                    pass
                """)));
        var sourceScope = sourceClassScope(fixture, 0);

        assertAll(
                () -> assertTrue(sourceScope.resolveValue("IDLE", ResolveRestriction.staticContext()).isAllowed()),
                () -> assertTrue(sourceScope.resolveValue("State", ResolveRestriction.staticContext()).isAllowed()),
                () -> assertTrue(sourceScope.resolveValue("IDLE", ResolveRestriction.instanceContext()).isAllowed())
        );
    }

    @Test
    void subclassInheritsParentEnumConstantsWithNearestLayerWinning() throws IOException {
        var fixture = analyze(List.of(
                new SourceSpec("enum_scope_base.gd", """
                        class_name EnumScopeBase
                        extends RefCounted

                        enum { SHADOWED = 7 }
                        enum Mode { M_IDLE = 2 }
                        """),
                new SourceSpec("enum_scope_child.gd", """
                        class_name EnumScopeChild
                        extends EnumScopeBase

                        enum { SHADOWED = 9 }
                        """)
        ));
        var childScope = sourceClassScope(fixture, 1);

        var shadowed = childScope.resolveValue("SHADOWED");
        var inheritedGroup = childScope.resolveValue("Mode");
        assertAll(
                // Nearest class layer wins: the child's own constant shadows the parent's.
                () -> assertEquals(9L, assertInstanceOf(
                        GdScriptEnumConstant.class,
                        shadowed.declaration()
                ).value()),
                () -> assertNotNull(inheritedGroup),
                () -> assertEquals(ScopeValueKind.CONSTANT, inheritedGroup.kind()),
                () -> {
                    var group = assertInstanceOf(GdScriptEnumGroup.class, inheritedGroup.declaration());
                    assertEquals("EnumScopeBase", group.ownerClassCanonicalName());
                    assertEquals(2L, group.findMember("M_IDLE").value());
                },
                // Value-side inheritance does not leak into the type-meta namespace: type-metas
                // stay lexical-only and are not inherited along the superclass chain.
                () -> assertNull(childScope.resolveTypeMeta("Mode"))
        );
    }

    @Test
    void innerClassKeepsEnumTypeMetaVisibleButIsolatesBareEnumValues() throws IOException {
        var fixture = analyze(List.of(new SourceSpec("enum_scope_inner.gd", """
                class_name EnumScopeInner
                extends RefCounted

                enum State { IDLE }
                enum { ANON = 3 }

                class Inner:
                    pass
                """)));
        var scopesByAst = fixture.analysisData().scopesByAst();
        var innerDeclaration = findStatement(
                fixture.units().getFirst().ast().statements(),
                ClassDeclaration.class,
                declaration -> declaration.name().equals("Inner")
        );
        var innerScope = assertInstanceOf(ClassScope.class, scopesByAst.get(innerDeclaration));

        assertAll(
                // Inner-class value isolation: neither the named group nor anonymous members are
                // reachable as bare values.
                () -> assertNull(innerScope.resolveValue("State")),
                () -> assertNull(innerScope.resolveValue("ANON")),
                // The lexical type-meta chain still exposes the named enum for type positions and
                // the `State.IDLE` static route.
                () -> {
                    var typeMeta = innerScope.resolveTypeMeta("State");
                    assertNotNull(typeMeta);
                    assertEquals(ScopeTypeMetaKind.GDCC_ENUM, typeMeta.kind());
                    assertEquals("EnumScopeInner.State", typeMeta.canonicalName());
                    assertEquals(GdIntType.INT, typeMeta.instanceType());
                }
        );
    }

    @Test
    void enumTypeAnnotationErasesToIntAtBothClassBoundaries() throws IOException {
        var fixture = analyze(List.of(new SourceSpec("enum_scope_annotation.gd", """
                class_name EnumScopeAnnotation
                extends RefCounted

                enum State { IDLE }

                var top_prop: State

                func use():
                    var local: State
                    pass

                class Inner:
                    var inner_prop: State
                    func use_inner():
                        pass
                """)));
        var scopesByAst = fixture.analysisData().scopesByAst();
        var sourceFile = fixture.units().getFirst().ast();
        var topClass = topLevelClassDef(fixture, 0);
        var innerDeclaration = findStatement(
                sourceFile.statements(),
                ClassDeclaration.class,
                declaration -> declaration.name().equals("Inner")
        );
        var innerClass = innerClassDef(fixture, innerDeclaration);
        var useBody = assertInstanceOf(BlockScope.class, scopesByAst.get(findStatement(
                sourceFile.statements(),
                FunctionDeclaration.class,
                function -> function.name().equals("use")
        ).body()));
        var useInnerBody = assertInstanceOf(BlockScope.class, scopesByAst.get(findStatement(
                innerDeclaration.body().statements(),
                FunctionDeclaration.class,
                function -> function.name().equals("use_inner")
        ).body()));

        assertAll(
                // Property declared types were resolved against the skeleton scaffold and stay int.
                () -> assertEquals(GdIntType.INT, findProperty(topClass, "top_prop").getType()),
                () -> assertEquals(GdIntType.INT, findProperty(innerClass, "inner_prop").getType()),
                // Function-body declared types resolve through the real ClassScope chain; this is
                // the same strict path that `is`/`as` targets consume, so `x is State` erases to int.
                () -> assertEquals(GdIntType.INT, ScopeTypeResolver.tryResolveDeclaredType(useBody, "State")),
                () -> {
                    var arrayType = assertInstanceOf(
                            GdArrayType.class,
                            ScopeTypeResolver.tryResolveDeclaredType(useBody, "Array[State]")
                    );
                    assertEquals(GdIntType.INT, arrayType.getValueType());
                },
                // Inner class bodies see the outer enum type-meta through the lexical chain.
                () -> assertEquals(GdIntType.INT, ScopeTypeResolver.tryResolveDeclaredType(useInnerBody, "State"))
        );
    }

    @Test
    void rejectedEnumPublishesNoScopeFactsAndSiblingsSurvive() throws IOException {
        var fixture = analyze(List.of(new SourceSpec("enum_scope_rejected.gd", """
                class_name EnumScopeRejected
                extends RefCounted

                enum Bad { DUP, DUP }
                enum { GOOD = 4 }
                var sibling_prop := 1
                """)));
        var sourceFile = fixture.units().getFirst().ast();
        var scopesByAst = fixture.analysisData().scopesByAst();
        var sourceScope = sourceClassScope(fixture, 0);
        var badEnum = findStatement(sourceFile.statements(), EnumDeclaration.class, declaration -> true);

        assertAll(
                () -> assertEquals(
                        1,
                        diagnosticsOf(fixture, "sema.class_skeleton").size()
                ),
                () -> assertTrue(fixture.analysisData().skippedSubtreeRoots().containsKey(badEnum)),
                () -> assertFalse(scopesByAst.containsKey(badEnum)),
                () -> assertNull(sourceScope.resolveValue("Bad")),
                () -> assertNull(sourceScope.resolveValue("DUP")),
                () -> assertNull(sourceScope.resolveTypeMeta("Bad")),
                // Siblings keep their facts.
                () -> assertEquals(4L, assertInstanceOf(
                        GdScriptEnumConstant.class,
                        sourceScope.resolveValue("GOOD").declaration()
                ).value()),
                () -> assertEquals(
                        ScopeValueKind.PROPERTY,
                        sourceScope.resolveValue("sibling_prop").kind()
                )
        );
    }

    @Test
    void localVariableShadowsEnumConstant() throws IOException {
        var fixture = analyze(List.of(new SourceSpec("enum_scope_shadow.gd", """
                class_name EnumScopeShadow
                extends RefCounted

                enum { IDLE }

                func f():
                    var IDLE = 99
                """)));
        var scopesByAst = fixture.analysisData().scopesByAst();
        var function = findStatement(
                fixture.units().getFirst().ast().statements(),
                FunctionDeclaration.class,
                declaration -> declaration.name().equals("f")
        );
        var localDeclaration = findStatement(
                function.body().statements(),
                VariableDeclaration.class,
                declaration -> declaration.name().equals("IDLE")
        );
        var bodyScope = assertInstanceOf(BlockScope.class, scopesByAst.get(function.body()));

        assertAll(
                () -> assertEquals(ScopeValueKind.LOCAL, bodyScope.resolveValue("IDLE").kind()),
                () -> assertSame(localDeclaration, bodyScope.resolveValue("IDLE").declaration()),
                // The class-layer binding itself is untouched by the callable-local shadow.
                () -> assertEquals(
                        ScopeValueKind.CONSTANT,
                        sourceClassScope(fixture, 0).resolveValue("IDLE").kind()
                )
        );
    }

    @Test
    void enumNameRejectedAsSuperclassTarget() throws IOException {
        var fixture = analyze(List.of(new SourceSpec("enum_scope_super.gd", """
                class_name EnumSuperTarget
                extends RefCounted

                enum State { IDLE }

                class Inner extends State:
                    pass
                """)));
        var sourceFile = fixture.units().getFirst().ast();
        var scopesByAst = fixture.analysisData().scopesByAst();
        var innerDeclaration = findStatement(
                sourceFile.statements(),
                ClassDeclaration.class,
                declaration -> declaration.name().equals("Inner")
        );

        var skeletonDiagnostics = diagnosticsOf(fixture, "sema.class_skeleton");
        assertAll(
                () -> assertEquals(1, skeletonDiagnostics.size()),
                () -> assertTrue(skeletonDiagnostics.getFirst().message().contains("unsupported superclass 'State'")),
                () -> assertTrue(fixture.analysisData().skippedSubtreeRoots().containsKey(innerDeclaration)),
                () -> assertFalse(scopesByAst.containsKey(innerDeclaration)),
                // The sibling enum is unaffected by the rejected inner class.
                () -> assertEquals(
                        ScopeValueKind.CONSTANT,
                        sourceClassScope(fixture, 0).resolveValue("State").kind()
                )
        );
    }

    @Test
    void parentEnumTypeAnnotationFallsBackToVariantInSubclass() throws IOException {
        // Behavior-unchanged anchor: type-meta lookup is lexical-only, so a parent enum name does
        // not resolve as a declared type inside the subclass (matching inner-class types).
        var fixture = analyze(List.of(
                new SourceSpec("enum_annot_base.gd", """
                        class_name EnumAnnotBase
                        extends RefCounted

                        enum State { IDLE }
                        """),
                new SourceSpec("enum_annot_child.gd", """
                        class_name EnumAnnotChild
                        extends EnumAnnotBase

                        var x: State
                        """)
        ));
        var childScope = sourceClassScope(fixture, 1);

        assertAll(
                () -> assertEquals(
                        GdVariantType.VARIANT,
                        findProperty(topLevelClassDef(fixture, 1), "x").getType()
                ),
                () -> assertTrue(diagnosticsOf(fixture, "sema.type_resolution").stream()
                        .anyMatch(diagnostic -> diagnostic.message().contains("State"))),
                () -> assertNull(childScope.resolveTypeMeta("State")),
                // The value side still inherits the group; only the type position falls back.
                () -> assertEquals(ScopeValueKind.CONSTANT, childScope.resolveValue("State").kind())
        );
    }

    @Test
    void enumSubtreePublishesNoScopeFactsForMemberValueExpressions() throws IOException {
        // Member values were already constant-folded by the skeleton enum pre-pass; scope analysis
        // skips the whole enum subtree instead of recording useless per-expression scope entries.
        var fixture = analyze(List.of(new SourceSpec("enum_scope_no_facts.gd", """
                class_name EnumScopeNoFacts
                extends RefCounted

                enum { A = 1 + 2, B = A }
                """)));
        var scopesByAst = fixture.analysisData().scopesByAst();
        var enumDeclaration = findStatement(
                fixture.units().getFirst().ast().statements(),
                EnumDeclaration.class,
                declaration -> true
        );

        assertAll(
                () -> assertFalse(scopesByAst.containsKey(enumDeclaration)),
                () -> {
                    for (var member : enumDeclaration.members()) {
                        if (member.value() != null) {
                            assertFalse(scopesByAst.containsKey(member.value()));
                        }
                    }
                },
                // The enum facts themselves are still published through the constant table.
                () -> assertEquals(3L, assertInstanceOf(
                        GdScriptEnumConstant.class,
                        sourceClassScope(fixture, 0).resolveValue("A").declaration()
                ).value()),
                () -> assertEquals(3L, assertInstanceOf(
                        GdScriptEnumConstant.class,
                        sourceClassScope(fixture, 0).resolveValue("B").declaration()
                ).value())
        );
    }

    @Test
    void innerClassPublishesOwnEnumConstantsAndTypeMeta() throws IOException {
        // Distinguishing anchor for the `handleClassDeclaration` registration: the enum lives
        // only on the inner class, so the type-meta can only come from the inner boundary's own
        // `defineEnumTypeMetas` call, not from the lexical outer chain.
        var fixture = analyze(List.of(new SourceSpec("enum_scope_inner_own.gd", """
                class_name EnumScopeInnerOwn
                extends RefCounted

                class Inner:
                    enum InnerState { IDLE, JUMP = 4 }
                    var prop: InnerState
                    func use():
                        pass
                """)));
        var scopesByAst = fixture.analysisData().scopesByAst();
        var innerDeclaration = findStatement(
                fixture.units().getFirst().ast().statements(),
                ClassDeclaration.class,
                declaration -> declaration.name().equals("Inner")
        );
        var innerScope = assertInstanceOf(ClassScope.class, scopesByAst.get(innerDeclaration));
        var innerClass = innerClassDef(fixture, innerDeclaration);
        var useBody = assertInstanceOf(BlockScope.class, scopesByAst.get(findStatement(
                innerDeclaration.body().statements(),
                FunctionDeclaration.class,
                function -> function.name().equals("use")
        ).body()));

        var typeMeta = innerScope.resolveTypeMeta("InnerState");
        assertAll(
                () -> assertEquals(ScopeValueKind.CONSTANT, innerScope.resolveValue("InnerState").kind()),
                () -> assertNotNull(typeMeta),
                () -> assertEquals(ScopeTypeMetaKind.GDCC_ENUM, typeMeta.kind()),
                () -> assertEquals("EnumScopeInnerOwn__sub__Inner.InnerState", typeMeta.canonicalName()),
                () -> assertEquals("InnerState", typeMeta.sourceName()),
                () -> assertTrue(typeMeta.pseudoType()),
                () -> assertSame(innerScope.resolveValue("InnerState").declaration(), typeMeta.declaration()),
                () -> assertEquals(4L, assertInstanceOf(GdScriptEnumGroup.class, typeMeta.declaration())
                        .findMember("JUMP").value()),
                // Declared types inside the inner class erase to int at both skeleton and
                // scope-phase resolution points.
                () -> assertEquals(GdIntType.INT, findProperty(innerClass, "prop").getType()),
                () -> assertEquals(GdIntType.INT, ScopeTypeResolver.tryResolveDeclaredType(useBody, "InnerState")),
                // The outer class must not see the inner enum in either namespace.
                () -> assertNull(sourceClassScope(fixture, 0).resolveTypeMeta("InnerState")),
                () -> assertNull(sourceClassScope(fixture, 0).resolveValue("InnerState"))
        );
    }

    @Test
    void isAndAsEnumTargetsEraseToInt() throws IOException {
        // `is`/`as` consume the same declared-type path as annotations; this test runs the full
        // semantic pipeline so the published expression facts (not just the bare resolver) prove
        // the enum name erases to an int target.
        var fixture = analyzeFull(List.of(new SourceSpec("enum_scope_is_as.gd", """
                class_name EnumScopeIsAs
                extends RefCounted

                enum State { IDLE }

                func check(value):
                    var tested := value is State
                    var casted := value as State
                """)));
        var function = findStatement(
                fixture.units().getFirst().ast().statements(),
                FunctionDeclaration.class,
                declaration -> declaration.name().equals("check")
        );
        var typeTest = assertInstanceOf(
                TypeTestExpression.class,
                findStatement(function.body().statements(), VariableDeclaration.class,
                        declaration -> declaration.name().equals("tested")).value()
        );
        var cast = assertInstanceOf(
                CastExpression.class,
                findStatement(function.body().statements(), VariableDeclaration.class,
                        declaration -> declaration.name().equals("casted")).value()
        );

        assertAll(
                () -> {
                    var target = assertInstanceOf(
                            FrontendTypeTestTarget.TargetKnown.class,
                            fixture.analysisData().typeTestTargets().get(typeTest)
                    );
                    assertEquals(GdIntType.INT, target.type());
                },
                () -> assertEquals(
                        "bool",
                        fixture.analysisData().expressionTypes().get(typeTest).publishedType().getTypeName()
                ),
                () -> assertEquals(
                        GdIntType.INT,
                        fixture.analysisData().expressionTypes().get(cast).publishedType()
                ),
                () -> assertTrue(diagnosticsOf(fixture, "sema.type_resolution").isEmpty()),
                () -> assertTrue(diagnosticsOf(fixture, "sema.class_skeleton").isEmpty())
        );
    }

    /// Runs parse + skeleton + scope + variable phases only, mirroring the phase input shape used
    /// by `FrontendInterfacePhaseTest`: body owner publication stays out of scope for these
    /// scope-phase tests.
    private static @NotNull Fixture analyze(@NotNull List<SourceSpec> sources) throws IOException {
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();
        var units = new java.util.ArrayList<FrontendSourceUnit>(sources.size());
        for (var source : sources) {
            units.add(parserService.parseUnit(Path.of("tmp", source.fileName()), source.source(), diagnostics));
        }
        assertTrue(diagnostics.isEmpty(), () -> "Unexpected parse diagnostics: " + diagnostics.snapshot());
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var analysisData = FrontendAnalysisData.bootstrap();
        var module = new FrontendModule("test_module", List.copyOf(units));
        var moduleSkeleton = new FrontendClassSkeletonBuilder().build(module, registry, diagnostics, analysisData);
        analysisData.updateModuleSkeleton(moduleSkeleton);
        analysisData.updateDiagnostics(diagnostics.snapshot());
        new FrontendScopeAnalyzer().analyze(registry, analysisData, diagnostics);
        analysisData.updateDiagnostics(diagnostics.snapshot());
        new FrontendVariableAnalyzer().analyze(analysisData, diagnostics);
        analysisData.updateDiagnostics(diagnostics.snapshot());
        return new Fixture(List.copyOf(units), analysisData, diagnostics);
    }

    /// Runs the full semantic pipeline (including body owner publication) for tests that assert
    /// published expression facts such as type-test targets and cast result types.
    private static @NotNull Fixture analyzeFull(@NotNull List<SourceSpec> sources) throws IOException {
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();
        var units = new java.util.ArrayList<FrontendSourceUnit>(sources.size());
        for (var source : sources) {
            units.add(parserService.parseUnit(Path.of("tmp", source.fileName()), source.source(), diagnostics));
        }
        assertTrue(diagnostics.isEmpty(), () -> "Unexpected parse diagnostics: " + diagnostics.snapshot());
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var analysisData = new FrontendSemanticAnalyzer().analyze(
                new FrontendModule("test_module", List.copyOf(units)),
                registry,
                diagnostics
        );
        return new Fixture(List.copyOf(units), analysisData, diagnostics);
    }

    private static @NotNull ClassScope sourceClassScope(@NotNull Fixture fixture, int unitIndex) {
        return assertInstanceOf(
                ClassScope.class,
                fixture.analysisData().scopesByAst().get(fixture.units().get(unitIndex).ast())
        );
    }

    private static @NotNull LirClassDef topLevelClassDef(@NotNull Fixture fixture, int unitIndex) {
        return fixture.analysisData().moduleSkeleton().sourceClassRelations().get(unitIndex).topLevelClassDef();
    }

    private static @NotNull LirClassDef innerClassDef(
            @NotNull Fixture fixture,
            @NotNull ClassDeclaration innerDeclaration
    ) {
        return fixture.analysisData().moduleSkeleton().sourceClassRelations().stream()
                .flatMap(relation -> relation.innerClassRelations().stream())
                .filter(relation -> relation.declaration() == innerDeclaration)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Inner class relation not found"))
                .classDef();
    }

    private static @NotNull PropertyDef findProperty(
            @NotNull LirClassDef classDef,
            @NotNull String name
    ) {
        return classDef.getProperties().stream()
                .filter(property -> property.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Property not found: " + name));
    }

    private static @NotNull List<FrontendDiagnostic> diagnosticsOf(
            @NotNull Fixture fixture,
            @NotNull String category
    ) {
        return fixture.diagnostics().snapshot().asList().stream()
                .filter(diagnostic -> diagnostic.category().equals(category))
                .toList();
    }

    private static <T extends Statement> T findStatement(
            @NotNull List<Statement> statements,
            @NotNull Class<T> statementType,
            @NotNull Predicate<T> predicate
    ) {
        return statements.stream()
                .filter(statementType::isInstance)
                .map(statementType::cast)
                .filter(predicate)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Statement not found: " + statementType.getSimpleName()));
    }

    private record SourceSpec(@NotNull String fileName, @NotNull String source) {
    }

    private record Fixture(
            @NotNull List<FrontendSourceUnit> units,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnostics
    ) {
    }
}
