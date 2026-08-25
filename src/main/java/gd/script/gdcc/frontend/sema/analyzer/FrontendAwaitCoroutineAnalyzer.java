package gd.script.gdcc.frontend.sema.analyzer;

import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.sema.FrontendAnalysisData;
import gd.script.gdcc.frontend.sema.FrontendAwaitCallPending;
import gd.script.gdcc.frontend.sema.FrontendCallResolutionKind;
import gd.script.gdcc.lir.LirFunctionDef;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Objects;

/// Post-suite await coroutine resolution pass (`frontend_await_minicoro_plan.md` 第六步).
///
/// During `EXPR_TYPE`, `await <call>` operands are recorded as pendings because the callee's own
/// body — and therefore its coroutine marking — may be resolved after the caller's. This pass runs
/// after all callable owners resolved, when every signal/dynamic-route marking is frozen, and
/// propagates transitive markings to a fixed point: coroutine markings are monotonic, so iterating
/// until no progress terminates after at most call-chain-depth rounds regardless of source order.
///
/// Afterwards every remaining pending has a statically known non-coroutine callee:
/// - instance/constructor call → `sema.redundant_await` warning (aligns Godot `REDUNDANT_AWAIT`);
/// - static call to a coroutine → consumed silently, the compile gate owns that diagnosis (§3.5).
///
/// The pass mutates only the monotonic coroutine set and emits warnings; it never rewrites
/// published expression facts (await result types were already published at `EXPR_TYPE`).
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
                    nextRound.add(pending);
                    continue;
                }
                if (pending.callKind() == FrontendCallResolutionKind.STATIC_METHOD) {
                    continue;
                }
                progressed |= analysisData.markCoroutineFunction(pending.enclosingFunction());
            }
            remaining = nextRound;
        }
        for (var pending : remaining) {
            FrontendBodyOwnerProcedures.reportRedundantAwait(
                    diagnosticManager,
                    pending.sourcePath(),
                    pending.awaitExpression(),
                    pending.calleeFunction().getName()
            );
        }
    }

    private static boolean isMarkedCoroutine(
            @NotNull FrontendAnalysisData analysisData,
            @NotNull FrontendAwaitCallPending pending
    ) {
        return pending.calleeFunction() instanceof LirFunctionDef lirCallee
                && analysisData.coroutineFunctions().contains(lirCallee);
    }
}
