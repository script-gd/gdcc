package gd.script.gdcc.util.type;

/// Lowering / static-validity decision for GDScript `value as T` explicit casts.
///
/// Shared by frontend type-check / body lowering and C backend defensive re-check so the
/// explicit-cast matrix stays a single truth source (see
/// `frontend_cast_expression_implementation.md`). Unsafe-warning policy for
/// `Variant` / `DYNAMIC` sources is orthogonal and must not appear here.
public enum ExplicitCastDecision {
    /// Exact same static type, or representation-compatible container identity (e.g.
    /// `Array[int] as Array`). Lowering emits {@code AssignInsn}.
    IDENTITY,
    /// Non-Variant source cast to {@code Variant}. Lowering emits {@code PackVariantInsn}.
    PACK_TO_VARIANT,
    /// Godot {@code Variant::can_convert} / {@code Variant::construct} builtin path, including
    /// parameterized {@code Array[T]} / {@code Dictionary[K, V]} base-only casts.
    BUILTIN_RUNTIME_CAST,
    /// Object source is a proper subtype assignable to the target (strict upcast). Exact
    /// same object type is {@link #IDENTITY}. Lowering emits {@code AssignInsn}.
    OBJECT_UPCAST,
    /// Object target requiring runtime class check (downcast, {@code Nil}, or {@code Variant}
    /// operand). Failure yields canonical null.
    OBJECT_RUNTIME_CAST,
    /// Statically impossible hard cast; type-check owns the user diagnostic. Lowering/backend
    /// must fail-fast if this decision still reaches them.
    INVALID
}
