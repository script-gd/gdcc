package gd.script.gdcc.frontend.sema;

import dev.superice.gdparser.frontend.ast.DeclarationKind;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.Point;
import dev.superice.gdparser.frontend.ast.Range;
import dev.superice.gdparser.frontend.ast.TypeRef;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import gd.script.gdcc.exception.FrontendAnalysisPatchException;
import gd.script.gdcc.frontend.scope.BlockScope;
import gd.script.gdcc.frontend.scope.BlockScopeKind;
import gd.script.gdcc.frontend.scope.CallableScope;
import gd.script.gdcc.frontend.scope.CallableScopeKind;
import gd.script.gdcc.frontend.scope.ClassScope;
import gd.script.gdcc.frontend.sema.patch.FrontendLocalSlotTypeUpdate;
import gd.script.gdcc.frontend.sema.patch.FrontendOwnerPatch;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.ScopeLookupStatus;
import gd.script.gdcc.scope.ScopeOwnerKind;
import gd.script.gdcc.scope.ScopeValue;
import gd.script.gdcc.scope.ScopeValueKind;
import gd.script.gdcc.type.GdccForRangeIterType;
import gd.script.gdcc.type.GdFloatType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdVariantType;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendTypedLexicalEnvironmentTest {
    private static final Range RANGE = new Range(0, 1, new Point(0, 0), new Point(0, 1));

    @Test
    void pendingFlushExportAndApplyPreserveStableDataUntilTransactionApply() throws Exception {
        var analysisData = FrontendAnalysisData.bootstrap();
        var bodyScope = newBodyScope();
        var declaration = variable("local");
        bodyScope.defineLocal("local", GdVariantType.VARIANT, declaration);
        var bindingNode = identifier("local_use");
        var originalBinding = localBinding("local", declaration, requireLocal(bodyScope, "local"));
        analysisData.symbolBindings().put(bindingNode, originalBinding);
        var environment = new FrontendTypedLexicalEnvironment(bodyScope, analysisData);

        environment.addLocalSlotTypeUpdate(
                FrontendSemanticStage.LOCAL_TYPE_STABILIZATION,
                new FrontendLocalSlotTypeUpdate(bodyScope, "local", declaration, GdIntType.INT)
        );

        assertTrue(environment.hasPendingFacts());
        assertSame(GdIntType.INT, environment.localSlotType(bodyScope, "local", declaration));
        assertSame(GdVariantType.VARIANT, requireLocal(bodyScope, "local").type());
        assertSame(GdVariantType.VARIANT, Objects.requireNonNull(originalBinding.resolvedValue()).type());

        environment.flushPendingFacts();

        assertFalse(environment.hasPendingFacts());
        assertTrue(environment.hasCommittedFacts());
        assertSame(GdIntType.INT, environment.localSlotType(bodyScope, "local", declaration));
        assertSame(GdVariantType.VARIANT, requireLocal(bodyScope, "local").type());
        assertSame(originalBinding, analysisData.symbolBindings().get(bindingNode));

        var transaction = environment.exportPatchTransaction();
        assertEquals(List.of(FrontendSemanticStage.LOCAL_TYPE_STABILIZATION), transaction.patches().stream()
                .map(FrontendOwnerPatch::stage)
                .toList());
        transaction.applyTo(analysisData);

        var refreshedBinding = analysisData.symbolBindings().get(bindingNode);
        assertSame(GdIntType.INT, requireLocal(bodyScope, "local").type());
        var refreshedValue = Objects.requireNonNull(Objects.requireNonNull(refreshedBinding).resolvedValue());
        assertSame(GdIntType.INT, refreshedValue.type());
    }

    @Test
    void childEnvironmentReadsParentCommittedLocalSlotOverlay() throws Exception {
        var analysisData = FrontendAnalysisData.bootstrap();
        var parentScope = newBodyScope();
        var declaration = variable("parent_local");
        parentScope.defineLocal("parent_local", GdVariantType.VARIANT, declaration);
        var parentEnvironment = new FrontendTypedLexicalEnvironment(parentScope, analysisData);
        parentEnvironment.addLocalSlotTypeUpdate(
                FrontendSemanticStage.LOCAL_TYPE_STABILIZATION,
                new FrontendLocalSlotTypeUpdate(parentScope, "parent_local", declaration, GdIntType.INT)
        );
        parentEnvironment.flushPendingFacts();
        var childScope = new BlockScope(parentScope, BlockScopeKind.IF_BODY);
        var childEnvironment = new FrontendTypedLexicalEnvironment(childScope, analysisData, parentEnvironment);

        var effectiveValue = childEnvironment.effectiveScopeValue(requireLocal(parentScope, "parent_local"), parentScope);

        assertSame(GdIntType.INT, effectiveValue.type());
        assertSame(GdVariantType.VARIANT, requireLocal(parentScope, "parent_local").type());
    }

    @Test
    void typedBaselineSuppliesInitialLocalTypeUntilPublishedFactOverridesIt() throws Exception {
        var analysisData = FrontendAnalysisData.bootstrap();
        var bodyScope = newBodyScope();
        var declaration = variable("local");
        bodyScope.defineLocal("local", GdVariantType.VARIANT, declaration);
        var baseline = FrontendTypedLexicalBaseline.builder()
                .put(declaration, GdIntType.INT)
                .build();
        var environment = new FrontendTypedLexicalEnvironment(bodyScope, analysisData, null, baseline);

        assertSame(GdIntType.INT, environment.slotType(declaration));
        assertSame(GdIntType.INT, environment.localSlotType(bodyScope, "local", declaration));
        assertSame(GdIntType.INT, environment.effectiveScopeValue(requireLocal(bodyScope, "local"), bodyScope).type());

        environment.putSlotType(FrontendSemanticStage.VAR_TYPE_POST, declaration, GdFloatType.FLOAT);

        assertSame(GdFloatType.FLOAT, environment.slotType(declaration));
        assertSame(GdFloatType.FLOAT, environment.localSlotType(bodyScope, "local", declaration));
    }

    @Test
    void overlayRejectsWrongOwnerAndCompilerOnlyPayloadsAcrossAllSurfaces() throws Exception {
        var analysisData = FrontendAnalysisData.bootstrap();
        var bodyScope = newBodyScope();
        var declaration = variable("local");
        bodyScope.defineLocal("local", GdVariantType.VARIANT, declaration);
        var environment = new FrontendTypedLexicalEnvironment(bodyScope, analysisData);

        assertThrows(FrontendAnalysisPatchException.class, () -> environment.putExpressionType(
                FrontendSemanticStage.TOP_BINDING,
                identifier("wrong_owner"),
                FrontendExpressionType.resolved(GdIntType.INT)
        ));
        assertThrows(FrontendAnalysisPatchException.class, () -> environment.putSymbolBinding(
                FrontendSemanticStage.TOP_BINDING,
                identifier("binding"),
                localBinding("local", declaration, compilerOnlyLocal("local", declaration))
        ));
        assertThrows(FrontendAnalysisPatchException.class, () -> environment.putResolvedMember(
                FrontendSemanticStage.CHAIN_BINDING,
                identifier("member"),
                FrontendResolvedMember.resolved(
                        "member",
                        FrontendBindingKind.PROPERTY,
                        FrontendReceiverKind.INSTANCE,
                        ScopeOwnerKind.GDCC,
                        new GdObjectType("Owner"),
                        GdccForRangeIterType.FOR_RANGE_ITER,
                        "Owner.member"
                )
        ));
        assertThrows(FrontendAnalysisPatchException.class, () -> environment.putResolvedCall(
                FrontendSemanticStage.EXPR_TYPE,
                identifier("call"),
                FrontendResolvedCall.resolved(
                        "call",
                        FrontendCallResolutionKind.STATIC_METHOD,
                        FrontendReceiverKind.TYPE_META,
                        ScopeOwnerKind.GDCC,
                        new GdObjectType("Owner"),
                        GdIntType.INT,
                        List.of(GdccForRangeIterType.FOR_RANGE_ITER),
                        "Owner.call"
                )
        ));
        assertThrows(FrontendAnalysisPatchException.class, () -> environment.putExpressionType(
                FrontendSemanticStage.EXPR_TYPE,
                identifier("expression"),
                FrontendExpressionType.resolved(GdccForRangeIterType.FOR_RANGE_ITER)
        ));
        assertThrows(FrontendAnalysisPatchException.class, () -> environment.putSlotType(
                FrontendSemanticStage.VAR_TYPE_POST,
                variable("slot"),
                GdccForRangeIterType.FOR_RANGE_ITER
        ));
        assertThrows(FrontendAnalysisPatchException.class, () -> environment.addLocalSlotTypeUpdate(
                FrontendSemanticStage.LOCAL_TYPE_STABILIZATION,
                new FrontendLocalSlotTypeUpdate(bodyScope, "local", declaration, GdccForRangeIterType.FOR_RANGE_ITER)
        ));
    }

    @Test
    void overlayRejectsExactLocalSlotRewriteAndExpressionFactNarrowing() throws Exception {
        var analysisData = FrontendAnalysisData.bootstrap();
        var bodyScope = newBodyScope();
        var exactDeclaration = variable("exact_local");
        bodyScope.defineLocal("exact_local", GdIntType.INT, exactDeclaration);
        var environment = new FrontendTypedLexicalEnvironment(bodyScope, analysisData);

        assertThrows(FrontendAnalysisPatchException.class, () -> environment.addLocalSlotTypeUpdate(
                FrontendSemanticStage.LOCAL_TYPE_STABILIZATION,
                new FrontendLocalSlotTypeUpdate(bodyScope, "exact_local", exactDeclaration, GdFloatType.FLOAT)
        ));

        var expressionNode = identifier("expression");
        environment.putExpressionType(
                FrontendSemanticStage.EXPR_TYPE,
                expressionNode,
                FrontendExpressionType.resolved(GdVariantType.VARIANT)
        );
        assertThrows(FrontendAnalysisPatchException.class, () -> environment.putExpressionType(
                FrontendSemanticStage.EXPR_TYPE,
                expressionNode,
                FrontendExpressionType.resolved(GdIntType.INT)
        ));
        environment.flushPendingFacts();
        assertThrows(FrontendAnalysisPatchException.class, () -> environment.putExpressionType(
                FrontendSemanticStage.EXPR_TYPE,
                expressionNode,
                FrontendExpressionType.resolved(GdIntType.INT)
        ));
    }

    @Test
    void overlayRejectsWrongOwnersAcrossEveryPublicationSurface() throws Exception {
        var analysisData = FrontendAnalysisData.bootstrap();
        var bodyScope = newBodyScope();
        var declaration = variable("local");
        bodyScope.defineLocal("local", GdVariantType.VARIANT, declaration);
        var environment = new FrontendTypedLexicalEnvironment(bodyScope, analysisData);

        assertThrows(FrontendAnalysisPatchException.class, () -> environment.putSymbolBinding(
                FrontendSemanticStage.CHAIN_BINDING,
                identifier("binding"),
                localBinding("local", declaration, requireLocal(bodyScope, "local"))
        ));
        assertThrows(FrontendAnalysisPatchException.class, () -> environment.putResolvedMember(
                FrontendSemanticStage.EXPR_TYPE,
                identifier("member"),
                FrontendResolvedMember.resolved(
                        "member",
                        FrontendBindingKind.PROPERTY,
                        FrontendReceiverKind.INSTANCE,
                        ScopeOwnerKind.GDCC,
                        new GdObjectType("Owner"),
                        GdIntType.INT,
                        "Owner.member"
                )
        ));
        assertThrows(FrontendAnalysisPatchException.class, () -> environment.putResolvedCall(
                FrontendSemanticStage.TOP_BINDING,
                identifier("call"),
                FrontendResolvedCall.resolved(
                        "call",
                        FrontendCallResolutionKind.STATIC_METHOD,
                        FrontendReceiverKind.TYPE_META,
                        ScopeOwnerKind.GDCC,
                        new GdObjectType("Owner"),
                        GdIntType.INT,
                        List.of(),
                        "Owner.call"
                )
        ));
        assertThrows(FrontendAnalysisPatchException.class, () -> environment.putSlotType(
                FrontendSemanticStage.LOCAL_TYPE_STABILIZATION,
                declaration,
                GdIntType.INT
        ));
        assertThrows(FrontendAnalysisPatchException.class, () -> environment.addLocalSlotTypeUpdate(
                FrontendSemanticStage.VAR_TYPE_POST,
                new FrontendLocalSlotTypeUpdate(bodyScope, "local", declaration, GdIntType.INT)
        ));
    }

    @Test
    void overlayRejectsConflictingStableSlotFactWithoutMutation() throws Exception {
        var analysisData = FrontendAnalysisData.bootstrap();
        var bodyScope = newBodyScope();
        var declaration = variable("local");
        bodyScope.defineLocal("local", GdIntType.INT, declaration);
        analysisData.slotTypes().put(declaration, GdIntType.INT);
        var environment = new FrontendTypedLexicalEnvironment(bodyScope, analysisData);

        assertThrows(FrontendAnalysisPatchException.class, () -> environment.putSlotType(
                FrontendSemanticStage.VAR_TYPE_POST,
                declaration,
                GdFloatType.FLOAT
        ));

        assertSame(GdIntType.INT, analysisData.slotTypes().get(declaration));
        assertFalse(environment.hasPendingFacts());
    }

    private static @NotNull IdentifierExpression identifier(@NotNull String name) {
        return new IdentifierExpression(name, RANGE);
    }

    private static @NotNull VariableDeclaration variable(@NotNull String name) {
        return new VariableDeclaration(
                DeclarationKind.VAR,
                name,
                new TypeRef(":=", RANGE),
                null,
                false,
                "variable_declaration",
                RANGE
        );
    }

    private static @NotNull ScopeValue compilerOnlyLocal(@NotNull String name, @NotNull Object declaration) {
        return new ScopeValue(
                name,
                GdccForRangeIterType.FOR_RANGE_ITER,
                ScopeValueKind.LOCAL,
                declaration,
                false,
                true,
                false
        );
    }

    private static @NotNull FrontendBinding localBinding(
            @NotNull String name,
            @NotNull Object declaration,
            @NotNull ScopeValue value
    ) {
        return new FrontendBinding(
                name,
                FrontendBindingKind.LOCAL_VAR,
                declaration,
                value,
                ScopeLookupStatus.FOUND_ALLOWED
        );
    }

    private static @NotNull ScopeValue requireLocal(@NotNull BlockScope scope, @NotNull String name) {
        var value = scope.resolveValueHere(name);
        if (value == null) {
            throw new IllegalStateException("missing local '" + name + "'");
        }
        return value;
    }

    private static @NotNull BlockScope newBodyScope() throws Exception {
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var ownerClass = new LirClassDef("SyntheticOwner", "RefCounted");
        var classScope = new ClassScope(registry, registry, ownerClass);
        var callableScope = new CallableScope(classScope, CallableScopeKind.FUNCTION_DECLARATION);
        return new BlockScope(callableScope, BlockScopeKind.FUNCTION_BODY);
    }
}
