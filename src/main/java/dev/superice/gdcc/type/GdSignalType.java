package dev.superice.gdcc.type;

import dev.superice.gdcc.scope.ParameterDef;
import dev.superice.gdcc.scope.SignalDef;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/// Immutable value-side representation of a GDScript signal binding.
///
/// The type intentionally carries only the stable signature surface that shared scope lookup can
/// expose safely today:
/// - the ordered parameter types accepted by the signal
///
/// The owning declaration stays on `ScopeValue.declaration()` as a `SignalDef`, which keeps this
/// type object lightweight while still allowing callers to recover richer metadata when needed.
public record GdSignalType(
        @NotNull List<@NotNull GdType> parameterTypes
) implements GdMetaType {
    public GdSignalType {
        Objects.requireNonNull(parameterTypes, "parameterTypes");
        parameterTypes = List.copyOf(parameterTypes);
    }

    public GdSignalType() {
        this(List.of());
    }

    /// Builds a stable signal type view directly from signal metadata.
    public static @NotNull GdSignalType from(@NotNull SignalDef signal) {
        Objects.requireNonNull(signal, "signal");
        return new GdSignalType(signal.getParameters().stream()
                .map(ParameterDef::getType)
                .toList());
    }

    @Override
    public @NotNull String getTypeName() {
        return "Signal";
    }

    @Override
    public boolean isNullable() {
        return false;
    }

    @Override
    public @NotNull GdExtensionTypeEnum getGdExtensionType() {
        return GdExtensionTypeEnum.SIGNAL;
    }
}
