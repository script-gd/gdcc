package gd.script.gdcc.frontend.sema.patch;

import gd.script.gdcc.exception.FrontendAnalysisPatchException;
import gd.script.gdcc.frontend.sema.FrontendAstSideTable;
import gd.script.gdcc.frontend.sema.FrontendBinding;
import gd.script.gdcc.frontend.sema.FrontendExpressionType;
import gd.script.gdcc.frontend.sema.FrontendForIterationPlan;
import gd.script.gdcc.frontend.sema.FrontendResolvedCall;
import gd.script.gdcc.frontend.sema.FrontendResolvedMember;
import gd.script.gdcc.frontend.sema.FrontendTypeTestTarget;
import gd.script.gdcc.scope.ScopeValue;
import gd.script.gdcc.type.GdCompilerType;
import gd.script.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/// Shared compiler-only guard for every source-facing typed publication surface.
///
/// Overlay writes, overlay flush, stable patch merge, and legacy whole-table publication all use
/// this walker so newly-added type-bearing payload fields do not drift between scratch and export.
public final class FrontendPublishedFactTypeGuard {
    private FrontendPublishedFactTypeGuard() {
    }

    public static void checkOwnerPatch(@NotNull FrontendOwnerPatch patch) {
        checkSymbolBindings(patch.symbolBindings());
        checkResolvedMembers(patch.resolvedMembers());
        checkResolvedCalls(patch.resolvedCalls());
        checkExpressionTypes(patch.expressionTypes());
        checkSlotTypes(patch.slotTypes());
        checkForIterationPlans(patch.forIterationPlans());
        checkTypeTestTargets(patch.typeTestTargets());
        checkLocalSlotTypeUpdates(patch.localSlotTypeUpdates());
    }

    public static void checkSymbolBindings(@NotNull FrontendAstSideTable<FrontendBinding> bindings) {
        for (var binding : bindings.values()) {
            checkBinding(binding);
        }
    }

    public static void checkBinding(@NotNull FrontendBinding binding) {
        var resolvedValue = binding.resolvedValue();
        if (resolvedValue != null) {
            checkScopeValue(resolvedValue, "symbolBindings() resolved value for '" + binding.symbolName() + "'");
        }
    }

    public static void checkResolvedMembers(@NotNull FrontendAstSideTable<FrontendResolvedMember> members) {
        for (var member : members.values()) {
            checkResolvedMember(member);
        }
    }

    public static void checkResolvedMember(@NotNull FrontendResolvedMember member) {
        checkNoCompilerOnlyLeak(member.receiverType(), "resolvedMembers() receiver type for '" + member.memberName() + "'");
        checkNoCompilerOnlyLeak(member.resultType(), "resolvedMembers() result type for '" + member.memberName() + "'");
    }

    public static void checkResolvedCalls(@NotNull FrontendAstSideTable<FrontendResolvedCall> calls) {
        for (var call : calls.values()) {
            checkResolvedCall(call);
        }
    }

    public static void checkResolvedCall(@NotNull FrontendResolvedCall call) {
        checkNoCompilerOnlyLeak(call.receiverType(), "resolvedCalls() receiver type for '" + call.callableName() + "'");
        checkNoCompilerOnlyLeak(call.returnType(), "resolvedCalls() return type for '" + call.callableName() + "'");
        checkTypeList(call.argumentTypes(), "resolvedCalls() argument type for '" + call.callableName() + "'");
        var boundary = call.exactCallableBoundary();
        if (boundary != null) {
            checkTypeList(
                    boundary.fixedParameterTypes(),
                    "resolvedCalls() exact callable boundary parameter type for '" + call.callableName() + "'"
            );
        }
    }

    public static void checkExpressionTypes(@NotNull FrontendAstSideTable<FrontendExpressionType> expressionTypes) {
        for (var expressionType : expressionTypes.values()) {
            checkExpressionType(expressionType);
        }
    }

    public static void checkExpressionType(@NotNull FrontendExpressionType expressionType) {
        checkNoCompilerOnlyLeak(expressionType.publishedType(), "expressionTypes() published type");
    }

    public static void checkSlotTypes(@NotNull FrontendAstSideTable<GdType> slotTypes) {
        for (var slotType : slotTypes.values()) {
            checkNoCompilerOnlyLeak(slotType, "slotTypes() value");
        }
    }

    public static void checkForIterationPlans(@NotNull FrontendAstSideTable<FrontendForIterationPlan> plans) {
        for (var plan : plans.values()) {
            checkForIterationPlan(plan);
        }
    }

    /// The plan is a source-facing semantic fact: both semantic element and exposed iterator types
    /// must remain ordinary source-visible types, never compiler-only iterator state.
    public static void checkForIterationPlan(@NotNull FrontendForIterationPlan plan) {
        checkNoCompilerOnlyLeak(
                plan.semanticElementType(),
                "forIterationPlans() semantic element type for '" + plan.iteratorName() + "'"
        );
        checkNoCompilerOnlyLeak(
                plan.exposedIteratorType(),
                "forIterationPlans() exposed iterator type for '" + plan.iteratorName() + "'"
        );
    }

    public static void checkTypeTestTargets(@NotNull FrontendAstSideTable<FrontendTypeTestTarget> targets) {
        for (var target : targets.values()) {
            checkTypeTestTarget(target);
        }
    }

    /// Known targets must stay source-facing; unresolved object names carry only a string payload.
    public static void checkTypeTestTarget(@NotNull FrontendTypeTestTarget target) {
        if (target instanceof FrontendTypeTestTarget.TargetKnown(var type)) {
            checkNoCompilerOnlyLeak(type, "typeTestTargets() known target type");
        }
    }

    public static void checkLocalSlotTypeUpdates(@NotNull Iterable<FrontendLocalSlotTypeUpdate> updates) {
        for (var update : updates) {
            checkLocalSlotTypeUpdate(update);
        }
    }

    public static void checkLocalSlotTypeUpdate(@NotNull FrontendLocalSlotTypeUpdate update) {
        checkNoCompilerOnlyLeak(update.type(), "local slot update for '" + update.name() + "'");
    }

    public static void checkScopeValue(@NotNull ScopeValue value, @NotNull String fieldName) {
        checkNoCompilerOnlyLeak(value.type(), fieldName);
    }

    public static void checkNoCompilerOnlyLeak(@Nullable GdType type, @NotNull String fieldName) {
        if (type instanceof GdCompilerType compilerOnlyType) {
            throw new FrontendAnalysisPatchException(
                    fieldName
                            + " leaked compiler-only type "
                            + compilerOnlyType.getTypeName()
            );
        }
    }

    private static void checkTypeList(@NotNull Iterable<GdType> types, @NotNull String fieldName) {
        for (var type : types) {
            checkNoCompilerOnlyLeak(type, fieldName);
        }
    }
}
