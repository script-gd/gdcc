package gd.script.gdcc.frontend.sema.analyzer.support;

import gd.script.gdcc.type.GdType;
import gd.script.gdcc.util.StringUtil;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/// Exact signature key of one registered const member call (const plan §5.5).
///
/// The owner is the *selected* builtin method's owner type and the parameter types are the
/// selected declared fixed parameter types — matching is by the frozen selection result, never
/// by name or Godot hash alone.
///
/// @param ownerType      builtin type that owns the selected method
/// @param methodName     metadata method name
/// @param invocationKind dispatch-kind identity marker; only `INSTANCE_METHOD` exists in v1
/// @param parameterTypes selected declared fixed-parameter types (stored immutably)
public record ConstCallableKey(
        @NotNull GdType ownerType,
        @NotNull String methodName,
        @NotNull InvocationKind invocationKind,
        @NotNull List<GdType> parameterTypes
) {
    public ConstCallableKey {
        Objects.requireNonNull(ownerType, "ownerType must not be null");
        StringUtil.requireNonBlank(methodName, "methodName");
        Objects.requireNonNull(invocationKind, "invocationKind must not be null");
        parameterTypes = List.copyOf(
                Objects.requireNonNull(parameterTypes, "parameterTypes must not be null")
        );
    }

    /// Dispatch-kind identity marker — v1 admits only controlled builtin instance methods;
    /// static/utility/user/Object/Variant dispatch stays unsupported.
    public enum InvocationKind {
        INSTANCE_METHOD
    }
}
