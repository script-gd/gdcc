package gd.script.gdcc.backend.c.gen;

import gd.script.gdcc.backend.c.gen.intrinsic.foriter.CForArrayIterIntrinsic;
import gd.script.gdcc.backend.c.gen.intrinsic.foriter.CForArrayIterRawInitIntrinsic;
import gd.script.gdcc.backend.c.gen.intrinsic.foriter.CForDictionaryIterIntrinsic;
import gd.script.gdcc.backend.c.gen.intrinsic.foriter.CForDictionaryIterRawInitIntrinsic;
import gd.script.gdcc.backend.c.gen.intrinsic.foriter.CForFloatIterIntrinsic;
import gd.script.gdcc.backend.c.gen.intrinsic.foriter.CForFloatIterRawInitIntrinsic;
import gd.script.gdcc.backend.c.gen.intrinsic.foriter.CForRangeIterIntrinsic;
import gd.script.gdcc.backend.c.gen.intrinsic.foriter.CForRangeIterRawInitIntrinsic;
import gd.script.gdcc.backend.c.gen.intrinsic.foriter.CForStringIterIntrinsic;
import gd.script.gdcc.backend.c.gen.intrinsic.foriter.CForStringIterRawInitIntrinsic;
import gd.script.gdcc.backend.c.gen.intrinsic.foriter.CForVariantIterIntrinsic;
import gd.script.gdcc.backend.c.gen.intrinsic.foriter.CForVariantIterRawInitIntrinsic;
import gd.script.gdcc.backend.c.gen.intrinsic.conversion.CIntToFloatIntrinsic;
import gd.script.gdcc.backend.c.gen.intrinsic.conversion.CVectorIToVectorIntrinsic;
import gd.script.gdcc.backend.c.gen.intrinsic.coro.CCoroStateRawInitIntrinsic;
import gd.script.gdcc.backend.c.gen.intrinsic.foriter.packed.CForPackedArrayIterIntrinsic;
import gd.script.gdcc.backend.c.gen.intrinsic.foriter.packed.CForPackedArrayIterRawInitIntrinsic;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/// White-list registry for C-backend intrinsic functions.
///
/// Intrinsic names in LIR are data, not raw C symbols. Keeping the dispatch table explicit prevents
/// `call_intrinsic` from becoming an unchecked escape hatch into arbitrary generated C.
public final class CIntrinsicManager {
    private final @NotNull Map<String, CIntrinsicFunction> functions;

    public CIntrinsicManager() {
        var entries = new HashMap<String, CIntrinsicFunction>();
        put(entries, new CIntToFloatIntrinsic());
        put(entries, new CForRangeIterRawInitIntrinsic());
        put(entries, new CForVariantIterRawInitIntrinsic());
        put(entries, new CForStringIterRawInitIntrinsic());
        put(entries, new CForArrayIterRawInitIntrinsic());
        put(entries, new CForDictionaryIterRawInitIntrinsic());
        put(entries, new CForFloatIterRawInitIntrinsic());
        put(entries, new CCoroStateRawInitIntrinsic());
        put(entries, CForRangeIterIntrinsic.init());
        put(entries, CForRangeIterIntrinsic.shouldContinue());
        put(entries, CForRangeIterIntrinsic.next());
        put(entries, CForRangeIterIntrinsic.get());
        put(entries, CForVariantIterIntrinsic.init());
        put(entries, CForVariantIterIntrinsic.shouldContinue());
        put(entries, CForVariantIterIntrinsic.next());
        put(entries, CForVariantIterIntrinsic.get());
        put(entries, CForStringIterIntrinsic.init());
        put(entries, CForStringIterIntrinsic.shouldContinue());
        put(entries, CForStringIterIntrinsic.next());
        put(entries, CForStringIterIntrinsic.get());
        put(entries, CForArrayIterIntrinsic.init());
        put(entries, CForArrayIterIntrinsic.shouldContinue());
        put(entries, CForArrayIterIntrinsic.next());
        put(entries, CForArrayIterIntrinsic.get());
        put(entries, CForDictionaryIterIntrinsic.init());
        put(entries, CForDictionaryIterIntrinsic.shouldContinue());
        put(entries, CForDictionaryIterIntrinsic.next());
        put(entries, CForDictionaryIterIntrinsic.get());
        put(entries, CForFloatIterIntrinsic.init());
        put(entries, CForFloatIterIntrinsic.shouldContinue());
        put(entries, CForFloatIterIntrinsic.next());
        put(entries, CForFloatIterIntrinsic.get());
        put(entries, CVectorIToVectorIntrinsic.vector2());
        put(entries, CVectorIToVectorIntrinsic.vector3());
        put(entries, CVectorIToVectorIntrinsic.vector4());
        for (var rawInit : CForPackedArrayIterRawInitIntrinsic.all()) {
            put(entries, rawInit);
        }
        for (var op : CForPackedArrayIterIntrinsic.allOperations()) {
            put(entries, op);
        }
        this.functions = Map.copyOf(entries);
    }

    private static void put(
            @NotNull Map<String, CIntrinsicFunction> entries,
            @NotNull CIntrinsicFunction function
    ) {
        if (entries.put(function.name(), function) != null) {
            throw new IllegalStateException("duplicate C intrinsic registration: " + function.name());
        }
    }

    public @Nullable CIntrinsicFunction find(@NotNull String name) {
        return functions.get(name);
    }
}
