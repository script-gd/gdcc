package gd.script.gdcc.backend.c.gen;

import gd.script.gdcc.lir.LirVariable;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/// Codegen context of one coroutine body function (`is_coroutine="true"`); present on
/// `CBodyBuilder` only while rendering a `__coro_body` function. Contract:
/// `doc/module_impl/frontend/frontend_await_implementation.md` §5 and
/// `doc/gdcc_ownership_lifecycle_spec.md` §3.10.
///
/// The C spellings held here are the single source of truth shared by the generated body
/// (`CBodyBuilder`) and the `entry.c.ftl` / `entry.h.ftl` coroutine sections (via
/// `CGenHelper`): the state wrapper struct field names, the `_coro_state` frame pointer
/// local, and the `_co` minicoro parameter of the body function.
///
/// Frame layout recap: typed parameter fields are the only owning storage for parameters
/// (no parameter C slots exist in the body), coroutine-lambda capture fields follow the same
/// discipline (copied per call by the start thunk, destroyed by `free_instance`), and the typed
/// return slot plus its initialized flag live next to the common `gdcc_coro_state_header`.
public record CCoroutineFrameContext(@NotNull String stateStructName) {
    /// Body-function local holding the state wrapper pointer (`mco_get_user_data(_co)`).
    public static final String FRAME_LOCAL = "_coro_state";
    /// Body-function minicoro parameter.
    public static final String CO_PARAM = "_co";
    /// Common header field of the wrapper struct.
    public static final String HEADER_FIELD = "_coro_header";
    /// Typed return slot field of the wrapper struct (non-void coroutines only).
    public static final String RET_FIELD = "_coro_ret";
    /// Written-flag guarding the typed return slot (non-void coroutines only).
    public static final String RET_INITIALIZED_FIELD = "_coro_ret_initialized";
    /// Typed parameter field prefix; the LIR parameter id (= source name) follows verbatim.
    public static final String PARAM_FIELD_PREFIX = "_coro_param_";
    /// Typed capture field prefix of a coroutine lambda: the start thunk copies
    /// each `_capture->name` field into its own owning frame field at the call boundary, and the
    /// body addresses those fields exactly like parameter fields. The LIR capture id (= source
    /// name) follows verbatim.
    public static final String CAPTURE_FIELD_PREFIX = "_coro_capture_";

    public CCoroutineFrameContext {
        Objects.requireNonNull(stateStructName, "stateStructName must not be null");
    }

    /// C expression of the typed parameter frame field for `variable` (must be a parameter).
    public static @NotNull String paramFieldAccessExpr(@NotNull LirVariable variable) {
        return FRAME_LOCAL + "->" + PARAM_FIELD_PREFIX + variable.id();
    }

    /// C expression of the typed capture frame field for `variable` (must be a lambda capture).
    public static @NotNull String captureFieldAccessExpr(@NotNull LirVariable variable) {
        return FRAME_LOCAL + "->" + CAPTURE_FIELD_PREFIX + variable.id();
    }

    /// C expression of the coroutine's own common header, used by await/cancel rendering.
    public static @NotNull String selfHeaderExpr() {
        return "&" + FRAME_LOCAL + "->" + HEADER_FIELD;
    }

    /// C expression of the cancel flag polled right after every await resume point: a
    /// cancel-resume returns from the runtime helper without writing the result channel, so
    /// the generated body jumps straight to `__finally__` when this is set (ownership spec
    /// §3.10 cancel-resume; `gdcc_coro_cancel` never finalizes).
    public static @NotNull String cancelFlagExpr() {
        return FRAME_LOCAL + "->" + HEADER_FIELD + ".cancel";
    }

    /// C expression of the typed return slot field.
    public static @NotNull String retFieldExpr() {
        return FRAME_LOCAL + "->" + RET_FIELD;
    }

    /// C expression of the return-slot written flag.
    public static @NotNull String retInitializedExpr() {
        return FRAME_LOCAL + "->" + RET_INITIALIZED_FIELD;
    }
}
