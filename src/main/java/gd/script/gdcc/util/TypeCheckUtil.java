package gd.script.gdcc.util;

import gd.script.gdcc.type.GdCompilerType;
import gd.script.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;

/// Shared type-checking utilities used across the compiler pipeline.
public final class TypeCheckUtil {
    private TypeCheckUtil() {
    }

    /// Checks that the given type is not a compiler-only type and throws
    /// {@link IllegalArgumentException} if it is.
    ///
    /// @param type        the type to check
    /// @param description a human-readable description of where the type appeared
    ///                    (included in the exception message)
    public static void requireNonCompilerOnly(@NotNull GdType type, @NotNull String description) {
        if (type instanceof GdCompilerType compilerType) {
            throw new IllegalArgumentException(
                    "compiler-only type leaked into " + description + ": " + compilerType.getTypeName()
            );
        }
    }
}
