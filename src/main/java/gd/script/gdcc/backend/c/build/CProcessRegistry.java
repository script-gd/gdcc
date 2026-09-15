package gd.script.gdcc.backend.c.build;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/// Synchronized registry with a closed state tracking every child process started by one
/// native build round. It exists to close the cancel race: `start()`-then-register and
/// cancel-then-sweep are inherently racy, so [register] and [cancelAndSnapshot] are mutually
/// exclusive operations under one monitor:
///
/// - a process registered before the close lands in the cancel snapshot and is destroyed by
///   the cancelling thread;
/// - a process registered after the close is destroyed forcibly inside [register] itself
///   ("register means destroy"), never merely enqueued;
///
/// The cancelling thread must additionally wait for all worker threads to finish before
/// returning, otherwise an embedding `API.close()` could return while zig children are still
/// alive. One registry instance serves exactly one build round; it is not reused.
final class CProcessRegistry {
    private final Object monitor = new Object();
    private final List<Process> registered = new ArrayList<>();
    private boolean closed;

    /// Registers a process that has just been started. When the registry is already closed the
    /// process is destroyed forcibly inside the monitor (destroyForcibly is non-blocking), so a
    /// late worker can never leak a live child past a completed cancellation.
    void register(@NotNull Process process) {
        synchronized (monitor) {
            if (closed) {
                process.destroyForcibly();
                return;
            }
            registered.add(process);
        }
    }

    boolean isClosed() {
        synchronized (monitor) {
            return closed;
        }
    }

    /// Closes the registry and returns every process registered so far. The first call wins:
    /// later calls return an empty list because anything registered after the close was already
    /// destroyed by [register] and nothing new can accumulate.
    @NotNull List<Process> cancelAndSnapshot() {
        synchronized (monitor) {
            if (closed) {
                return List.of();
            }
            closed = true;
            var snapshot = List.copyOf(registered);
            registered.clear();
            return snapshot;
        }
    }
}
