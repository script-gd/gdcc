package gd.script.gdcc.backend.c.build;

import org.jetbrains.annotations.Nullable;

/// LTO granularity for one native build round; `cliFlag()` is the exact token appended to zig
/// compile and link commands (`null` = LTO fully omitted from both command kinds). The decision
/// itself — which mode applies to a target/optimization-level pair — lives in
/// `ZigCcCompiler.resolveLtoMode(...)`.
public enum CLtoMode {
    NONE(null),
    FULL("-flto"),
    THIN("-flto=thin");

    private final @Nullable String cliFlag;

    CLtoMode(@Nullable String cliFlag) {
        this.cliFlag = cliFlag;
    }

    @Nullable String cliFlag() {
        return cliFlag;
    }
}
