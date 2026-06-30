package gd.script.gdcc.lir;

import org.jetbrains.annotations.NotNull;

/// Defines the context in which a type text appears in LIR XML,
/// controlling whether compiler-only types are allowed.
public enum LirTypeUseSite {
    SIGNAL_PARAMETER("signal parameter", false),
    PROPERTY("property", false),
    FUNCTION_PARAMETER("function parameter", false),
    FUNCTION_CAPTURE("function capture", false),
    FUNCTION_RETURN("function return", false),
    FUNCTION_VARIABLE("function variable", true);

    private final @NotNull String displayName;
    private final boolean allowCompilerOnlyType;

    LirTypeUseSite(@NotNull String displayName, boolean allowCompilerOnlyType) {
        this.displayName = displayName;
        this.allowCompilerOnlyType = allowCompilerOnlyType;
    }

    public @NotNull String displayName() {
        return displayName;
    }

    public boolean allowCompilerOnlyType() {
        return allowCompilerOnlyType;
    }
}
