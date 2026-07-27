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
/// `doc/gdcc_lir_intrinsic.md`; the C backend keeps a matching copy in the for-iter intrinsic classes.
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
        register(FrontendForIterationRoute.FLOAT_SHORTHAND, floatContract());
        registerAllPackedArrayContracts();
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

    private static void registerAllPackedArrayContracts() {
        for (var family : GdccForPackedArrayIterType.all()) {
            register(routeForPackedFamily(family), packedArrayContract(family));
        }
    }

    public static @NotNull FrontendForIterationRoute routeForPackedFamily(
            @NotNull GdccForPackedArrayIterType family
    ) {
        return switch (family.sourceType().getGdExtensionType()) {
            case PACKED_BYTE_ARRAY -> FrontendForIterationRoute.PACKED_BYTE_ARRAY;
            case PACKED_INT32_ARRAY -> FrontendForIterationRoute.PACKED_INT32_ARRAY;
            case PACKED_INT64_ARRAY -> FrontendForIterationRoute.PACKED_INT64_ARRAY;
            case PACKED_FLOAT32_ARRAY -> FrontendForIterationRoute.PACKED_FLOAT32_ARRAY;
            case PACKED_FLOAT64_ARRAY -> FrontendForIterationRoute.PACKED_FLOAT64_ARRAY;
            case PACKED_STRING_ARRAY -> FrontendForIterationRoute.PACKED_STRING_ARRAY;
            case PACKED_VECTOR2_ARRAY -> FrontendForIterationRoute.PACKED_VECTOR2_ARRAY;
            case PACKED_VECTOR3_ARRAY -> FrontendForIterationRoute.PACKED_VECTOR3_ARRAY;
            case PACKED_VECTOR4_ARRAY -> FrontendForIterationRoute.PACKED_VECTOR4_ARRAY;
            case PACKED_COLOR_ARRAY -> FrontendForIterationRoute.PACKED_COLOR_ARRAY;
            default -> throw new IllegalArgumentException(
                    "unsupported packed array family: " + family.sourceType().getTypeName()
            );
        };
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

    /// One contract per Packed*Array family: specialized state type, typed source, typed element get.
    private static @NotNull FrontendForLoweringContract packedArrayContract(
            @NotNull GdccForPackedArrayIterType stateType
    ) {
        var sourceType = stateType.sourceType();
        var elementType = stateType.elementType();
        return new FrontendForLoweringContract(
                stateType,
                new ForIterationOperationDescriptor(
                        stateType.getInitIntrinsicName(),
                        stateType,
                        List.of(sourceType)
                ),
                new ForIterationOperationDescriptor(
                        stateType.getShouldContinueIntrinsicName(),
                        GdBoolType.BOOL,
                        List.of(stateType)
                ),
                new ForIterationOperationDescriptor(
                        stateType.getNextIntrinsicName(),
                        stateType,
                        List.of(stateType)
                ),
                new ForIterationOperationDescriptor(
                        stateType.getGetIntrinsicName(),
                        elementType,
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
