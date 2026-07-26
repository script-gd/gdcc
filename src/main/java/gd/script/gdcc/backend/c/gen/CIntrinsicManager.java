package gd.script.gdcc.backend.c.gen;

import gd.script.gdcc.backend.c.gen.intrinsic.CIntToFloatIntrinsic;
import gd.script.gdcc.backend.c.gen.intrinsic.CForRangeIterIntrinsic;
import gd.script.gdcc.backend.c.gen.intrinsic.CForRangeIterRawInitIntrinsic;
import gd.script.gdcc.backend.c.gen.intrinsic.CForVariantIterIntrinsic;
import gd.script.gdcc.backend.c.gen.intrinsic.CForVariantIterRawInitIntrinsic;
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
        var rangeIterInit = new CForRangeIterRawInitIntrinsic();
        var variantIterInit = new CForVariantIterRawInitIntrinsic();
        var forRangeIterInit = CForRangeIterIntrinsic.init();
        var forRangeIterShouldContinue = CForRangeIterIntrinsic.shouldContinue();
        var forRangeIterNext = CForRangeIterIntrinsic.next();
        var forRangeIterGet = CForRangeIterIntrinsic.get();
        var forVariantIterInit = CForVariantIterIntrinsic.init();
        var forVariantIterShouldContinue = CForVariantIterIntrinsic.shouldContinue();
        var forVariantIterNext = CForVariantIterIntrinsic.next();
        var forVariantIterGet = CForVariantIterIntrinsic.get();
        var vector2iToVector2 = CVectorIToVectorIntrinsic.vector2();
        var vector3iToVector3 = CVectorIToVectorIntrinsic.vector3();
        var vector4iToVector4 = CVectorIToVectorIntrinsic.vector4();
        this.functions = Map.ofEntries(
                Map.entry(intToFloat.name(), intToFloat),
                Map.entry(rangeIterInit.name(), rangeIterInit),
                Map.entry(variantIterInit.name(), variantIterInit),
                Map.entry(forRangeIterInit.name(), forRangeIterInit),
                Map.entry(forRangeIterShouldContinue.name(), forRangeIterShouldContinue),
                Map.entry(forRangeIterNext.name(), forRangeIterNext),
                Map.entry(forRangeIterGet.name(), forRangeIterGet),
                Map.entry(forVariantIterInit.name(), forVariantIterInit),
                Map.entry(forVariantIterShouldContinue.name(), forVariantIterShouldContinue),
                Map.entry(forVariantIterNext.name(), forVariantIterNext),
                Map.entry(forVariantIterGet.name(), forVariantIterGet),
                Map.entry(vector2iToVector2.name(), vector2iToVector2),
                Map.entry(vector3iToVector3.name(), vector3iToVector3),
                Map.entry(vector4iToVector4.name(), vector4iToVector4)
        );
    }

    public @Nullable CIntrinsicFunction find(@NotNull String name) {
        return functions.get(name);
    }
}
