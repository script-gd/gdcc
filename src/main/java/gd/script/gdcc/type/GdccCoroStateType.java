package gd.script.gdcc.type;

import org.jetbrains.annotations.NotNull;

/// Compiler-only storage type for an OWNED coroutine state object reference
/// (contract: `gdcc_low_ir.md` §Coroutine Instructions, `gdcc_ownership_lifecycle_spec.md`
/// §3.10). Produced solely by a static `call_method` on a coroutine callee and consumed
/// solely by `await` or `destruct` (single-consumer move semantics).
///
/// This is a deliberately erased marker type: it carries no callee identity or return-type
/// knowledge. The await dispatch channel only needs the "owned state reference" fact; typed
/// value extraction is delegated to the state class's runtime descriptor callbacks, so no
/// cross-instruction bookkeeping is required at the LIR level.
///
/// Storage/lifecycle contract:
/// - C storage is the engine pointer `godot_Object*` — the sole sanctioned exception to the
///   `gdcc_*` storage namespace, because the value genuinely wraps an engine object
///   reference. Slot init/destroy stay in the `gdcc_*` helper namespace
///   (`gdcc_coro_state_slot_init` is nullary call-and-assign; `gdcc_coro_state_slot_destroy`
///   takes the slot address).
/// - The slot is initialized to `NULL` and a consumed (moved-from) slot is reset to `NULL`
///   by `await`; `destruct` on a live reference releases it via the runtime RefCounted
///   release primitive.
/// - Move-only: no copy operation exists at all, so `isCopyable()` is `false` and both
///   direct struct assignment and copy helpers are forbidden.
public final class GdccCoroStateType implements GdCompilerType {
    public static final @NotNull GdccCoroStateType CORO_STATE = new GdccCoroStateType();

    public static final @NotNull String LIR_TYPE_TEXT = "compiler::GdccCoroState";
    public static final @NotNull String C_STORAGE_TYPE_NAME = "godot_Object*";
    public static final @NotNull String C_INIT_HELPER_NAME = "gdcc_coro_state_slot_init";
    public static final @NotNull String C_DESTROY_HELPER_NAME = "gdcc_coro_state_slot_destroy";

    @Override
    public @NotNull String getTypeName() {
        return "GdccCoroState";
    }

    @Override
    public @NotNull String getLirTypeText() {
        return LIR_TYPE_TEXT;
    }

    @Override
    public @NotNull String getCStorageTypeName() {
        return C_STORAGE_TYPE_NAME;
    }

    @Override
    public @NotNull String getCInitHelperName() {
        return C_INIT_HELPER_NAME;
    }

    @Override
    public @NotNull String getCDestroyHelperName() {
        return C_DESTROY_HELPER_NAME;
    }

    /// Move-only single-consumer ownership: no copy operation exists.
    @Override
    public boolean isCopyable() {
        return false;
    }

    /// Direct struct assignment would duplicate the OWNED pointer and double-release;
    /// overridden explicitly because the default derives `true` from the blank copy helper.
    @Override
    public boolean isDirectStructAssignmentSafe() {
        return false;
    }
}
