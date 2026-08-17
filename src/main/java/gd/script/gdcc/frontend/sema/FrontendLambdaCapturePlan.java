package gd.script.gdcc.frontend.sema;

import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/// Ordered capture list frozen for one `LambdaExpression`.
///
/// Ordinary captures keep first-use source order. When this lambda captures enclosing `self`,
/// that entry is always first and `capturesSelf` is true; remaining entries stay first-use order.
/// Lowering uses the flag for `object_id` and reads `_capture->self` from the same first slot.
///
/// @param captures     unique-by-name capture entries; if `capturesSelf`, index 0 is `self`
/// @param capturesSelf true iff the list starts with the enclosing-instance `self` capture
public record FrontendLambdaCapturePlan(
        @NotNull List<LambdaCaptureEntry> captures,
        boolean capturesSelf
) {
    public static final @NotNull String SELF_CAPTURE_NAME = "self";

    public FrontendLambdaCapturePlan {
        captures = List.copyOf(Objects.requireNonNull(captures, "captures must not be null"));
        var seen = new HashSet<String>();
        for (var capture : captures) {
            Objects.requireNonNull(capture, "captures must not contain null");
            if (!seen.add(capture.name())) {
                throw new IllegalArgumentException("duplicate capture name '" + capture.name() + "'");
            }
        }
        var startsWithSelf = !captures.isEmpty() && SELF_CAPTURE_NAME.equals(captures.getFirst().name());
        if (capturesSelf != startsWithSelf) {
            throw new IllegalArgumentException(
                    "capturesSelf must match a leading '" + SELF_CAPTURE_NAME + "' capture"
            );
        }
        for (var i = 1; i < captures.size(); i++) {
            if (SELF_CAPTURE_NAME.equals(captures.get(i).name())) {
                throw new IllegalArgumentException(
                        "'" + SELF_CAPTURE_NAME + "' capture must be the first entry"
                );
            }
        }
    }

    /// Derives `capturesSelf` from whether the ordered list starts with `self`.
    public static @NotNull FrontendLambdaCapturePlan of(@NotNull List<LambdaCaptureEntry> captures) {
        var copied = List.copyOf(Objects.requireNonNull(captures, "captures must not be null"));
        return new FrontendLambdaCapturePlan(copied, startsWithSelfCapture(copied));
    }

    static boolean startsWithSelfCapture(@NotNull List<LambdaCaptureEntry> captures) {
        return !captures.isEmpty() && SELF_CAPTURE_NAME.equals(captures.getFirst().name());
    }

    public static boolean samePlan(
            @NotNull FrontendLambdaCapturePlan first,
            @NotNull FrontendLambdaCapturePlan second
    ) {
        Objects.requireNonNull(first, "first must not be null");
        Objects.requireNonNull(second, "second must not be null");
        if (first.capturesSelf() != second.capturesSelf()) {
            return false;
        }
        if (first.captures().size() != second.captures().size()) {
            return false;
        }
        for (var i = 0; i < first.captures().size(); i++) {
            if (!LambdaCaptureEntry.sameEntry(first.captures().get(i), second.captures().get(i))) {
                return false;
            }
        }
        return true;
    }
}
