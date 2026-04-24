package dev.superice.gdcc.api;

import dev.superice.gdcc.util.StringUtil;
import org.jetbrains.annotations.NotNull;

/// Frozen compile-task event recorded from code executing on the task thread.
///
/// Events intentionally stay minimal so frontend/backend code can emit human-readable progress
/// notes without depending on RPC transport metadata. They are retained together with the owning task
/// snapshot and disappear when that task ages out of the API retention window.
public record CompileTaskEvent(@NotNull String category, @NotNull String detail) {
    public CompileTaskEvent {
        category = StringUtil.requireTrimmedNonBlank(category, "category");
        detail = StringUtil.requireTrimmedNonBlank(detail, "detail");
    }
}
