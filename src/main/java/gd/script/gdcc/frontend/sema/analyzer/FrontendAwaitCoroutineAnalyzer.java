package gd.script.gdcc.frontend.sema.analyzer;

import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.sema.FrontendAnalysisData;
import gd.script.gdcc.frontend.sema.FrontendAwaitCallPending;
import gd.script.gdcc.frontend.sema.analyzer.support.FrontendExpressionSemanticSupport;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.type.GdSignalType;
import gd.script.gdcc.type.GdVariantType;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Objects;

/// Post-suite await coroutine resolution pass (`frontend_await_implementation.md` §8).
///
/// During `EXPR_TYPE`, `await <call>` operands are recorded as pendings because the callee's own
/// body — and therefore its coroutine marking — may be resolved after the caller's. This pass runs
/// after all callable owners resolved, when every signal/dynamic-route marking is frozen, and
/// propagates transitive markings to a fixed point: coroutine markings are monotonic, so iterating
/// until no progress terminates after at most call-chain-depth rounds regardless of source order.
///
/// Signal/Variant-returning calls are suspension-capable even when the callee is not a coroutine,
/// so they mark their caller during the fixed point and remain pending only long enough to determine
/// whether a Signal result needs refinement. Afterwards only hard-typed non-coroutine calls produce
/// `sema.redundant_await`. Static coroutine callees propagate to their callers exactly like instance
/// ones: the static call is a legal await operand / statement root, so the caller
/// suspends through it and must be compiled as a coroutine too.
///
/// The pass mutates the monotonic coroutine set, refines non-coroutine Signal-call await results,
/// and emits warnings. Variant-call results stay `Variant`; only hard-typed non-coroutine calls
/// become redundant awaits. Caller marking dispatches through `markCoroutineOwner`: lambda callers
/// join the identity-keyed owner set and are bridged to their shell during lowering preparation.
public final class FrontendAwaitCoroutineAnalyzer {
    public void analyze(
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnosticManager
    ) {
        Objects.requireNonNull(analysisData, "analysisData must not be null");
        Objects.requireNonNull(diagnosticManager, "diagnosticManager must not be null");
        var remaining = new ArrayList<>(analysisData.drainAwaitCallPendings());
        var progressed = true;
        while (progressed) {
            progressed = false;
            var nextRound = new ArrayList<FrontendAwaitCallPending>();
            for (var pending : remaining) {
                if (!isMarkedCoroutine(analysisData, pending)) {
                    if (hasRuntimeAwaitableReturn(pending)) {
                        progressed |= analysisData.markCoroutineOwner(pending.enclosingOwner());
                    }
                    nextRound.add(pending);
                    continue;
                }
                progressed |= analysisData.markCoroutineOwner(pending.enclosingOwner());
            }
            remaining = nextRound;
        }
        for (var pending : remaining) {
            var returnType = pending.calleeFunction().getReturnType();
            if (returnType instanceof GdSignalType signalType) {
                analysisData.refineResolvedAwaitExpressionType(
                        pending.awaitExpression(),
                        FrontendExpressionSemanticSupport.signalAwaitResultType(signalType)
                );
                continue;
            }
            if (returnType instanceof GdVariantType) {
                continue;
            }
            FrontendBodyOwnerProcedures.reportRedundantAwait(
                    diagnosticManager,
                    pending.sourcePath(),
                    pending.awaitExpression(),
                    pending.calleeFunction().getName()
            );
        }
    }

    /// Callee-side coroutine check. Exact-call callees are always named `LirFunctionDef` shells:
    /// lambda invocations go through the dynamic Callable route (`AwaitRoute.DYNAMIC`), never the
    /// exact-call pending route, so no lambda-owner dispatch is needed here.
    private static boolean isMarkedCoroutine(
            @NotNull FrontendAnalysisData analysisData,
            @NotNull FrontendAwaitCallPending pending
    ) {
        return pending.calleeFunction() instanceof LirFunctionDef lirCallee
                && analysisData.coroutineFunctions().contains(lirCallee);
    }

    private static boolean hasRuntimeAwaitableReturn(@NotNull FrontendAwaitCallPending pending) {
        var returnType = pending.calleeFunction().getReturnType();
        return returnType instanceof GdSignalType || returnType instanceof GdVariantType;
    }
}
