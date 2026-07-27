package gd.script.gdcc.backend.c.gen;

import gd.script.gdcc.backend.c.gen.intrinsic.CIntToFloatIntrinsic;
import gd.script.gdcc.backend.c.gen.intrinsic.CForArrayIterIntrinsic;
import gd.script.gdcc.backend.c.gen.intrinsic.CForArrayIterRawInitIntrinsic;
import gd.script.gdcc.backend.c.gen.intrinsic.CForDictionaryIterIntrinsic;
import gd.script.gdcc.backend.c.gen.intrinsic.CForDictionaryIterRawInitIntrinsic;
import gd.script.gdcc.backend.c.gen.intrinsic.CForFloatIterIntrinsic;
import gd.script.gdcc.backend.c.gen.intrinsic.CForFloatIterRawInitIntrinsic;
import gd.script.gdcc.backend.c.gen.intrinsic.CForPackedArrayIterIntrinsic;
import gd.script.gdcc.backend.c.gen.intrinsic.CForPackedArrayIterRawInitIntrinsic;
import gd.script.gdcc.backend.c.gen.intrinsic.CForRangeIterIntrinsic;
import gd.script.gdcc.backend.c.gen.intrinsic.CForRangeIterRawInitIntrinsic;
import gd.script.gdcc.backend.c.gen.intrinsic.CForStringIterIntrinsic;
import gd.script.gdcc.backend.c.gen.intrinsic.CForStringIterRawInitIntrinsic;
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
        var stringIterRawInit = new CForStringIterRawInitIntrinsic();
        var arrayIterRawInit = new CForArrayIterRawInitIntrinsic();
        var dictionaryIterRawInit = new CForDictionaryIterRawInitIntrinsic();
        var packedArrayIterRawInit = new CForPackedArrayIterRawInitIntrinsic();
        var floatIterRawInit = new CForFloatIterRawInitIntrinsic();
        var forRangeIterInit = CForRangeIterIntrinsic.init();
        var forRangeIterShouldContinue = CForRangeIterIntrinsic.shouldContinue();
        var forRangeIterNext = CForRangeIterIntrinsic.next();
        var forRangeIterGet = CForRangeIterIntrinsic.get();
        var forVariantIterInit = CForVariantIterIntrinsic.init();
        var forVariantIterShouldContinue = CForVariantIterIntrinsic.shouldContinue();
        var forVariantIterNext = CForVariantIterIntrinsic.next();
        var forVariantIterGet = CForVariantIterIntrinsic.get();
        var forStringIterInit = CForStringIterIntrinsic.init();
        var forStringIterShouldContinue = CForStringIterIntrinsic.shouldContinue();
        var forStringIterNext = CForStringIterIntrinsic.next();
        var forStringIterGet = CForStringIterIntrinsic.get();
        var forArrayIterInit = CForArrayIterIntrinsic.init();
        var forArrayIterShouldContinue = CForArrayIterIntrinsic.shouldContinue();
        var forArrayIterNext = CForArrayIterIntrinsic.next();
        var forArrayIterGet = CForArrayIterIntrinsic.get();
        var forDictionaryIterInit = CForDictionaryIterIntrinsic.init();
        var forDictionaryIterShouldContinue = CForDictionaryIterIntrinsic.shouldContinue();
        var forDictionaryIterNext = CForDictionaryIterIntrinsic.next();
        var forDictionaryIterGet = CForDictionaryIterIntrinsic.get();
        var forPackedArrayIterInit = CForPackedArrayIterIntrinsic.init();
        var forPackedArrayIterShouldContinue = CForPackedArrayIterIntrinsic.shouldContinue();
        var forPackedArrayIterNext = CForPackedArrayIterIntrinsic.next();
        var forPackedArrayIterGet = CForPackedArrayIterIntrinsic.get();
        var forFloatIterInit = CForFloatIterIntrinsic.init();
        var forFloatIterShouldContinue = CForFloatIterIntrinsic.shouldContinue();
        var forFloatIterNext = CForFloatIterIntrinsic.next();
        var forFloatIterGet = CForFloatIterIntrinsic.get();
        var vector2iToVector2 = CVectorIToVectorIntrinsic.vector2();
        var vector3iToVector3 = CVectorIToVectorIntrinsic.vector3();
        var vector4iToVector4 = CVectorIToVectorIntrinsic.vector4();
        this.functions = Map.ofEntries(
                Map.entry(intToFloat.name(), intToFloat),
                Map.entry(rangeIterInit.name(), rangeIterInit),
                Map.entry(variantIterInit.name(), variantIterInit),
                Map.entry(stringIterRawInit.name(), stringIterRawInit),
                Map.entry(arrayIterRawInit.name(), arrayIterRawInit),
                Map.entry(dictionaryIterRawInit.name(), dictionaryIterRawInit),
                Map.entry(packedArrayIterRawInit.name(), packedArrayIterRawInit),
                Map.entry(floatIterRawInit.name(), floatIterRawInit),
                Map.entry(forRangeIterInit.name(), forRangeIterInit),
                Map.entry(forRangeIterShouldContinue.name(), forRangeIterShouldContinue),
                Map.entry(forRangeIterNext.name(), forRangeIterNext),
                Map.entry(forRangeIterGet.name(), forRangeIterGet),
                Map.entry(forVariantIterInit.name(), forVariantIterInit),
                Map.entry(forVariantIterShouldContinue.name(), forVariantIterShouldContinue),
                Map.entry(forVariantIterNext.name(), forVariantIterNext),
                Map.entry(forVariantIterGet.name(), forVariantIterGet),
                Map.entry(forStringIterInit.name(), forStringIterInit),
                Map.entry(forStringIterShouldContinue.name(), forStringIterShouldContinue),
                Map.entry(forStringIterNext.name(), forStringIterNext),
                Map.entry(forStringIterGet.name(), forStringIterGet),
                Map.entry(forArrayIterInit.name(), forArrayIterInit),
                Map.entry(forArrayIterShouldContinue.name(), forArrayIterShouldContinue),
                Map.entry(forArrayIterNext.name(), forArrayIterNext),
                Map.entry(forArrayIterGet.name(), forArrayIterGet),
                Map.entry(forDictionaryIterInit.name(), forDictionaryIterInit),
                Map.entry(forDictionaryIterShouldContinue.name(), forDictionaryIterShouldContinue),
                Map.entry(forDictionaryIterNext.name(), forDictionaryIterNext),
                Map.entry(forDictionaryIterGet.name(), forDictionaryIterGet),
                Map.entry(forPackedArrayIterInit.name(), forPackedArrayIterInit),
                Map.entry(forPackedArrayIterShouldContinue.name(), forPackedArrayIterShouldContinue),
                Map.entry(forPackedArrayIterNext.name(), forPackedArrayIterNext),
                Map.entry(forPackedArrayIterGet.name(), forPackedArrayIterGet),
                Map.entry(forFloatIterInit.name(), forFloatIterInit),
                Map.entry(forFloatIterShouldContinue.name(), forFloatIterShouldContinue),
                Map.entry(forFloatIterNext.name(), forFloatIterNext),
                Map.entry(forFloatIterGet.name(), forFloatIterGet),
                Map.entry(vector2iToVector2.name(), vector2iToVector2),
                Map.entry(vector3iToVector3.name(), vector3iToVector3),
                Map.entry(vector4iToVector4.name(), vector4iToVector4)
        );
    }

    public @Nullable CIntrinsicFunction find(@NotNull String name) {
        return functions.get(name);
    }
}
