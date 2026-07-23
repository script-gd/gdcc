package gd.script.gdcc.frontend.lowering;

import gd.script.gdcc.frontend.sema.FrontendForIterationRoute;
import gd.script.gdcc.type.GdBoolType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdccForRangeIterType;
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

    private static final Map<FrontendForIterationRoute, FrontendForLoweringContract> CONTRACTS =
            new EnumMap<>(FrontendForIterationRoute.class);

    static {
        var rangeContract = rangeContract();
        register(FrontendForIterationRoute.RANGE_CALL, rangeContract);
        register(FrontendForIterationRoute.INT_SHORTHAND, rangeContract);
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
}
