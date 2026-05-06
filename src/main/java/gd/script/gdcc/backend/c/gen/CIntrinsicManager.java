package gd.script.gdcc.backend.c.gen;

import gd.script.gdcc.backend.c.gen.intrinsic.CIntToFloatIntrinsic;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/// White-list registry for C-backend intrinsic functions.
///
/// Intrinsic names in LIR are data, not raw C symbols. Keeping the dispatch table explicit prevents
/// `call_intrinsic` from becoming an unchecked escape hatch into arbitrary generated C.
public final class CIntrinsicManager {
    private final @NotNull Map<String, CIntrinsicFunction> functions;

    public CIntrinsicManager() {
        var intToFloat = new CIntToFloatIntrinsic();
        this.functions = Map.of(intToFloat.name(), intToFloat);
    }

    public @Nullable CIntrinsicFunction find(@NotNull String name) {
        return functions.get(name);
    }
}
