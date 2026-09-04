package gd.script.gdcc.backend.c.gen.binding;

import gd.script.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/// Shared bound-method surface descriptor used by template binding generation.
/// Instance wrappers carry owner class identity so self fat materialization is owner-specific.
///
/// `defaultSlotCount` counts the trailing parameters that carry a source `defaultValueFunc`
/// (a contiguous suffix by the frontend ordering rule). It participates in record equality and
/// the `_K_defslot` bind-name encoding so same-shape methods with different default-slot counts
/// never share a wrapper. It deliberately stays OUT of `method_info.default_argument_count`:
/// the bind-time Variant channel (`defaultVariables`) is always empty for GDCC source functions,
/// and runtime completion happens in the argc-aware callee-prologue wrapper keyed off this count.
public record BindingData(
        @Nullable String ownerClassName,
        @NotNull List<GdType> paramTypes,
        @NotNull GdType returnType,
        @NotNull List<GdType> defaultVariables,
        boolean staticMethod,
        int defaultSlotCount
) {
    public BindingData {
        paramTypes = List.copyOf(paramTypes);
        defaultVariables = List.copyOf(defaultVariables);
        if (!defaultVariables.isEmpty()) {
            // The bind-time Variant channel is closed by contract: the legacy `_N_default_`
            // template branches stay unreachable and can never resurface the uncompilable
            // helper/registration skew.
            throw new IllegalArgumentException(
                    "bind-time default Variant channel is closed for GDCC source functions; "
                            + "runtime default completion flows through defaultSlotCount userdata"
            );
        }
        if (defaultSlotCount < 0 || defaultSlotCount > paramTypes.size()) {
            throw new IllegalArgumentException(
                    "defaultSlotCount " + defaultSlotCount + " out of range for " + paramTypes.size() + " parameters"
            );
        }
        if (!staticMethod) {
            Objects.requireNonNull(ownerClassName, "instance BindingData requires ownerClassName");
            if (ownerClassName.isBlank()) {
                throw new IllegalArgumentException("instance BindingData ownerClassName must not be blank");
            }
        }
    }

    public boolean isInstanceMethod() {
        return !staticMethod;
    }
}
