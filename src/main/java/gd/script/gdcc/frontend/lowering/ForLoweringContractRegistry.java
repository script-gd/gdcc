package gd.script.gdcc.frontend.lowering;

import gd.script.gdcc.frontend.sema.FrontendForIterationRoute;
import gd.script.gdcc.type.GdBoolType;
import gd.script.gdcc.type.GdFloatType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdStringType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdccForArrayIterType;
import gd.script.gdcc.type.GdccForDictionaryIterType;
import gd.script.gdcc.type.GdccForFloatIterType;
import gd.script.gdcc.type.GdccForPackedArrayIterType;
import gd.script.gdcc.type.GdccForRangeIterType;
import gd.script.gdcc.type.GdccForStringIterType;
import gd.script.gdcc.type.GdccForVariantIterType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// Compile-time static registry mapping iteration routes to their lowering contracts.
///
/// A non-null lookup means the route is compile-ready; a null lookup means route-not-ready and the
/// compile gate blocks with a diagnostic. The registry is monotonic: a contract is registered once,
/// after its helper/intrinsic/backend chain is frozen, and is never removed or replaced.
///
/// Intrinsic names are the frontend copy of the frozen contract documented in
/// `doc/gdcc_lir_intrinsic.md`; the C backend keeps a matching copy in `CForRangeIterIntrinsic`.
public final class ForLoweringContractRegistry {
    public static final @NotNull String RANGE_INIT_INTRINSIC = "gdcc.for_range_iter.init";
    public static final @NotNull String RANGE_SHOULD_CONTINUE_INTRINSIC = "gdcc.for_range_iter.should_continue";
    public static final @NotNull String RANGE_NEXT_INTRINSIC = "gdcc.for_range_iter.next";
    public static final @NotNull String RANGE_GET_INTRINSIC = "gdcc.for_range_iter.get";

    public static final @NotNull String VARIANT_INIT_INTRINSIC = "gdcc.for_variant_iter.init";
    public static final @NotNull String VARIANT_SHOULD_CONTINUE_INTRINSIC = "gdcc.for_variant_iter.should_continue";
    public static final @NotNull String VARIANT_NEXT_INTRINSIC = "gdcc.for_variant_iter.next";
    public static final @NotNull String VARIANT_GET_INTRINSIC = "gdcc.for_variant_iter.get";

    public static final @NotNull String STRING_INIT_INTRINSIC = "gdcc.for_string_iter.init";
    public static final @NotNull String STRING_SHOULD_CONTINUE_INTRINSIC = "gdcc.for_string_iter.should_continue";
    public static final @NotNull String STRING_NEXT_INTRINSIC = "gdcc.for_string_iter.next";
    public static final @NotNull String STRING_GET_INTRINSIC = "gdcc.for_string_iter.get";

    public static final @NotNull String ARRAY_INIT_INTRINSIC = "gdcc.for_array_iter.init";
    public static final @NotNull String ARRAY_SHOULD_CONTINUE_INTRINSIC = "gdcc.for_array_iter.should_continue";
    public static final @NotNull String ARRAY_NEXT_INTRINSIC = "gdcc.for_array_iter.next";
    public static final @NotNull String ARRAY_GET_INTRINSIC = "gdcc.for_array_iter.get";

    public static final @NotNull String DICTIONARY_INIT_INTRINSIC = "gdcc.for_dictionary_iter.init";
    public static final @NotNull String DICTIONARY_SHOULD_CONTINUE_INTRINSIC = "gdcc.for_dictionary_iter.should_continue";
    public static final @NotNull String DICTIONARY_NEXT_INTRINSIC = "gdcc.for_dictionary_iter.next";
    public static final @NotNull String DICTIONARY_GET_INTRINSIC = "gdcc.for_dictionary_iter.get";

    public static final @NotNull String PACKED_ARRAY_INIT_INTRINSIC = "gdcc.for_packed_array_iter.init";
    public static final @NotNull String PACKED_ARRAY_SHOULD_CONTINUE_INTRINSIC =
            "gdcc.for_packed_array_iter.should_continue";
    public static final @NotNull String PACKED_ARRAY_NEXT_INTRINSIC = "gdcc.for_packed_array_iter.next";
    public static final @NotNull String PACKED_ARRAY_GET_INTRINSIC = "gdcc.for_packed_array_iter.get";

    public static final @NotNull String FLOAT_INIT_INTRINSIC = "gdcc.for_float_iter.init";
    public static final @NotNull String FLOAT_SHOULD_CONTINUE_INTRINSIC = "gdcc.for_float_iter.should_continue";
    public static final @NotNull String FLOAT_NEXT_INTRINSIC = "gdcc.for_float_iter.next";
    public static final @NotNull String FLOAT_GET_INTRINSIC = "gdcc.for_float_iter.get";

    private static final Map<FrontendForIterationRoute, FrontendForLoweringContract> CONTRACTS =
            new EnumMap<>(FrontendForIterationRoute.class);

    static {
        var rangeContract = rangeContract();
        register(FrontendForIterationRoute.RANGE_CALL, rangeContract);
        register(FrontendForIterationRoute.INT_SHORTHAND, rangeContract);
        register(FrontendForIterationRoute.GENERIC_VARIANT, variantContract());
        register(FrontendForIterationRoute.STRING, stringContract());
        register(FrontendForIterationRoute.ARRAY, arrayContract());
        register(FrontendForIterationRoute.DICTIONARY_KEYS, dictionaryContract());
        register(FrontendForIterationRoute.PACKED_ARRAY, packedArrayContract());
        register(FrontendForIterationRoute.FLOAT_SHORTHAND, floatContract());
    }

    private ForLoweringContractRegistry() {
    }

    /// Returns the lowering contract for a route, or null when the route is not compile-ready yet.
    public static @Nullable FrontendForLoweringContract get(@NotNull FrontendForIterationRoute route) {
        Objects.requireNonNull(route, "route must not be null");
        return CONTRACTS.get(route);
    }

    private static void register(
            @NotNull FrontendForIterationRoute route,
            @NotNull FrontendForLoweringContract contract
    ) {
        if (CONTRACTS.putIfAbsent(route, contract) != null) {
            throw new IllegalStateException("for-in lowering contract already registered for route " + route);
        }
    }

    private static @NotNull FrontendForLoweringContract rangeContract() {
        var stateType = GdccForRangeIterType.FOR_RANGE_ITER;
        var intType = GdIntType.INT;
        return new FrontendForLoweringContract(
                stateType,
                new ForIterationOperationDescriptor(
                        RANGE_INIT_INTRINSIC,
                        stateType,
                        List.of(intType, intType, intType)
                ),
                new ForIterationOperationDescriptor(
                        RANGE_SHOULD_CONTINUE_INTRINSIC,
                        GdBoolType.BOOL,
                        List.of(stateType)
                ),
                new ForIterationOperationDescriptor(
                        RANGE_NEXT_INTRINSIC,
                        stateType,
                        List.of(stateType)
                ),
                new ForIterationOperationDescriptor(
                        RANGE_GET_INTRINSIC,
                        intType,
                        List.of(stateType)
                )
        );
    }

    private static @NotNull FrontendForLoweringContract variantContract() {
        var stateType = GdccForVariantIterType.FOR_VARIANT_ITER;
        var variantType = GdVariantType.VARIANT;
        return new FrontendForLoweringContract(
                stateType,
                new ForIterationOperationDescriptor(
                        VARIANT_INIT_INTRINSIC,
                        stateType,
                        List.of(variantType)
                ),
                new ForIterationOperationDescriptor(
                        VARIANT_SHOULD_CONTINUE_INTRINSIC,
                        GdBoolType.BOOL,
                        List.of(stateType)
                ),
                new ForIterationOperationDescriptor(
                        VARIANT_NEXT_INTRINSIC,
                        stateType,
                        List.of(stateType)
                ),
                new ForIterationOperationDescriptor(
                        VARIANT_GET_INTRINSIC,
                        variantType,
                        List.of(stateType)
                )
        );
    }

    private static @NotNull FrontendForLoweringContract stringContract() {
        var stateType = GdccForStringIterType.FOR_STRING_ITER;
        var stringType = GdStringType.STRING;
        return new FrontendForLoweringContract(
                stateType,
                new ForIterationOperationDescriptor(
                        STRING_INIT_INTRINSIC,
                        stateType,
                        List.of(stringType)
                ),
                new ForIterationOperationDescriptor(
                        STRING_SHOULD_CONTINUE_INTRINSIC,
                        GdBoolType.BOOL,
                        List.of(stateType)
                ),
                new ForIterationOperationDescriptor(
                        STRING_NEXT_INTRINSIC,
                        stateType,
                        List.of(stateType)
                ),
                new ForIterationOperationDescriptor(
                        STRING_GET_INTRINSIC,
                        stringType,
                        List.of(stateType)
                )
        );
    }

    private static @NotNull FrontendForLoweringContract arrayContract() {
        var stateType = GdccForArrayIterType.FOR_ARRAY_ITER;
        var variantType = GdVariantType.VARIANT;
        return new FrontendForLoweringContract(
                stateType,
                new ForIterationOperationDescriptor(
                        ARRAY_INIT_INTRINSIC,
                        stateType,
                        List.of(variantType)
                ),
                new ForIterationOperationDescriptor(
                        ARRAY_SHOULD_CONTINUE_INTRINSIC,
                        GdBoolType.BOOL,
                        List.of(stateType)
                ),
                new ForIterationOperationDescriptor(
                        ARRAY_NEXT_INTRINSIC,
                        stateType,
                        List.of(stateType)
                ),
                new ForIterationOperationDescriptor(
                        ARRAY_GET_INTRINSIC,
                        variantType,
                        List.of(stateType)
                )
        );
    }

    private static @NotNull FrontendForLoweringContract dictionaryContract() {
        var stateType = GdccForDictionaryIterType.FOR_DICTIONARY_ITER;
        var variantType = GdVariantType.VARIANT;
        return new FrontendForLoweringContract(
                stateType,
                new ForIterationOperationDescriptor(
                        DICTIONARY_INIT_INTRINSIC,
                        stateType,
                        List.of(variantType)
                ),
                new ForIterationOperationDescriptor(
                        DICTIONARY_SHOULD_CONTINUE_INTRINSIC,
                        GdBoolType.BOOL,
                        List.of(stateType)
                ),
                new ForIterationOperationDescriptor(
                        DICTIONARY_NEXT_INTRINSIC,
                        stateType,
                        List.of(stateType)
                ),
                new ForIterationOperationDescriptor(
                        DICTIONARY_GET_INTRINSIC,
                        variantType,
                        List.of(stateType)
                )
        );
    }

    /// Init argumentTypes uses Variant as the family-wide wildcard marker (same as ARRAY/DICTIONARY):
    /// C backend accepts any GdPackedArrayType and dispatches to a typed `gdcc_for_packed_*_iter_from`.
    private static @NotNull FrontendForLoweringContract packedArrayContract() {
        var stateType = GdccForPackedArrayIterType.FOR_PACKED_ARRAY_ITER;
        var variantType = GdVariantType.VARIANT;
        return new FrontendForLoweringContract(
                stateType,
                new ForIterationOperationDescriptor(
                        PACKED_ARRAY_INIT_INTRINSIC,
                        stateType,
                        List.of(variantType)
                ),
                new ForIterationOperationDescriptor(
                        PACKED_ARRAY_SHOULD_CONTINUE_INTRINSIC,
                        GdBoolType.BOOL,
                        List.of(stateType)
                ),
                new ForIterationOperationDescriptor(
                        PACKED_ARRAY_NEXT_INTRINSIC,
                        stateType,
                        List.of(stateType)
                ),
                new ForIterationOperationDescriptor(
                        PACKED_ARRAY_GET_INTRINSIC,
                        variantType,
                        List.of(stateType)
                )
        );
    }

    private static @NotNull FrontendForLoweringContract floatContract() {
        var stateType = GdccForFloatIterType.FOR_FLOAT_ITER;
        var floatType = GdFloatType.FLOAT;
        return new FrontendForLoweringContract(
                stateType,
                new ForIterationOperationDescriptor(
                        FLOAT_INIT_INTRINSIC,
                        stateType,
                        List.of(floatType)
                ),
                new ForIterationOperationDescriptor(
                        FLOAT_SHOULD_CONTINUE_INTRINSIC,
                        GdBoolType.BOOL,
                        List.of(stateType)
                ),
                new ForIterationOperationDescriptor(
                        FLOAT_NEXT_INTRINSIC,
                        stateType,
                        List.of(stateType)
                ),
                new ForIterationOperationDescriptor(
                        FLOAT_GET_INTRINSIC,
                        floatType,
                        List.of(stateType)
                )
        );
    }
}
