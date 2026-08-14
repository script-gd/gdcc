package gd.script.gdcc.lir.insn;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/// Discriminator for `construct_standalone_callable`.
///
/// These kinds have no instance receiver. Builtin instance method-references stay on
/// `construct_callable` and must never use this enum.
public enum StandaloneCallableKind {
    UTILITY("utility"),
    STATIC_GDCC("static_gdcc"),
    STATIC_ENGINE("static_engine");

    private final @NotNull String token;

    StandaloneCallableKind(@NotNull String token) {
        this.token = token;
    }

    public @NotNull String token() {
        return token;
    }

    public static @NotNull StandaloneCallableKind requireToken(@Nullable String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new IllegalArgumentException("construct_standalone_callable kind must not be blank");
        }
        for (var kind : values()) {
            if (kind.token.equals(rawToken)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("unknown construct_standalone_callable kind '" + rawToken + "'");
    }
}
