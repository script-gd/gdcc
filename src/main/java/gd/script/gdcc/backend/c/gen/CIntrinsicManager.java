package gd.script.gdcc.backend.c.gen;

import gd.script.gdcc.backend.c.gen.intrinsic.CIntToFloatIntrinsic;
import gd.script.gdcc.backend.c.gen.intrinsic.CVectorIToVectorIntrinsic;
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
        var vector2iToVector2 = CVectorIToVectorIntrinsic.vector2();
        var vector3iToVector3 = CVectorIToVectorIntrinsic.vector3();
        var vector4iToVector4 = CVectorIToVectorIntrinsic.vector4();
        this.functions = Map.of(
                intToFloat.name(), intToFloat,
                vector2iToVector2.name(), vector2iToVector2,
                vector3iToVector3.name(), vector3iToVector3,
                vector4iToVector4.name(), vector4iToVector4
        );
    }

    public @Nullable CIntrinsicFunction find(@NotNull String name) {
        return functions.get(name);
    }
}
