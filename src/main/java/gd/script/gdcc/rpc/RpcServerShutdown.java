package gd.script.gdcc.rpc;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/// Exit-intent latch behind the `server.shutdown` RPC method, split into two parties by the
/// write-back timing contract of `JsonRpcHttpHandler`:
///
/// - The method handler only RECORDS the intent (`requestExit`, idempotent — repeated calls
///   return the same `{}` result) and must never start a thread or exit itself: the dispatcher
///   invokes handlers synchronously on a request-executor thread, and the HTTP layer writes the
///   response only after the handler returns. Exiting (or even starting the exit thread) inside
///   the handler would race the response write, and exiting on the request thread itself would
///   deadlock the JVM shutdown hook — its `server.stop(0)`/`executor.close()` wait for the very
///   request thread that is trying to exit.
/// - The HTTP transport calls `exitProcess()` strictly AFTER the response exchange that
///   SERVED the shutdown request has been written and closed (end of the `try (exchange)`
///   scope), so the client is guaranteed to observe the `{}` response before the process goes
///   away. Restricting the trigger to the serving exchange also prevents an unrelated
///   concurrent exchange from racing ahead and starting the exit before the shutdown caller's
///   own response was written. The exit action runs on a dedicated platform thread that does
///   not belong to the request executor, exactly once no matter how many requests arrive
///   afterwards. The existing JVM shutdown hook in `RpcServeCommand` then performs the
///   graceful tail (`server.close()` + `API.close()`).
///
/// Trade-offs by design: if the serving exchange breaks before its response is fully written,
/// the process intentionally stays up (the client never saw a confirmation), and the caller's
/// own fallback (the editor launcher's port-probe + kill guard) covers the cleanup. And while
/// every repeated/concurrent shutdown request marks its own exchange — so each of them may
/// trigger the exit right after its own write — the once-only `exitStarted` guard means the
/// FIRST completed serving exchange wins; a concurrently in-flight second shutdown caller may
/// observe a closed connection instead of its `{}` (both asked for the same exit, so the
/// outcome is equivalent). The sequential idempotency contract ("same `{}` result while the
/// connection is usable") is unaffected.
public final class RpcServerShutdown {
    private final @NotNull AtomicBoolean exitRequested = new AtomicBoolean();
    private final @NotNull AtomicBoolean exitStarted = new AtomicBoolean();
    private final @NotNull Runnable exitAction;
    /// Per-request-thread marker set by the method handler and consumed by the HTTP transport on
    /// the SAME thread: the exchange that actually invoked `server.shutdown` — and only that
    /// exchange — earns the right to trigger the exit after its response is written and closed.
    /// A global-latch check cannot express this: an unrelated request could snapshot the latch
    /// before/after another thread's registration and race ahead of the shutdown response. The
    /// request executor is virtual-thread-per-task, so each exchange owns its thread for the
    /// whole handle() call and the marker can never leak between requests.
    private final @NotNull ThreadLocal<Boolean> servingExchange = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private RpcServerShutdown(@NotNull Runnable exitAction) {
        this.exitAction = Objects.requireNonNull(exitAction, "exitAction must not be null");
    }

    /// Production exit action: terminate the serve process with success status so the SIGINT
    /// shutdown hook performs the graceful tail.
    public static @NotNull RpcServerShutdown systemExit() {
        return new RpcServerShutdown(() -> System.exit(0));
    }

    /// Test exit action: lets transport-level tests observe the post-response exit trigger
    /// without terminating the test JVM.
    static @NotNull RpcServerShutdown forTesting(@NotNull Runnable exitAction) {
        return new RpcServerShutdown(exitAction);
    }

    /// Method-handler side (runs on the request thread inside the dispatcher): records the
    /// process-wide exit intent (idempotent) and marks this thread's exchange as the serving
    /// one. Returns `true` only for the first call.
    public boolean requestExit() {
        servingExchange.set(Boolean.TRUE);
        return exitRequested.compareAndSet(false, true);
    }

    public boolean isExitRequested() {
        return exitRequested.get();
    }

    /// Transport side (same thread as the dispatch): returns — and clears — whether THIS
    /// exchange invoked `server.shutdown`. Repeated shutdown requests each mark their own
    /// exchange; only the triggering exchange is guaranteed to complete its response first
    /// (a concurrently in-flight second caller may observe a dropped connection once the exit
    /// starts — see the class doc for the narrowed concurrency contract).
    public boolean consumeServingExchange() {
        var served = servingExchange.get();
        servingExchange.set(Boolean.FALSE);
        return served;
    }

    /// Transport side, called only by the exchange that SERVED the shutdown request, after its
    /// response is fully written and the exchange closed. Runs the exit action once on a
    /// platform thread outside the request executor; later calls are no-ops.
    public void exitProcess() {
        // Defense in depth: the caller already proved this exchange served the shutdown
        // request; the latch check keeps a stray call from ever exiting an unrequested server.
        if (!isExitRequested() || !exitStarted.compareAndSet(false, true)) {
            return;
        }
        Thread.ofPlatform().name("gdcc-rpc-exit").start(exitAction);
    }
}
