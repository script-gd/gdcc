package gd.script.gdcc.frontend.sema.analyzer.support;

import gd.script.gdcc.frontend.constant.ConstCallResult;
import gd.script.gdcc.frontend.constant.GdConstantValue;
import gd.script.gdcc.type.GdBasisType;
import gd.script.gdcc.type.GdExtensionTypeEnum;
import gd.script.gdcc.type.GdFloatType;
import gd.script.gdcc.type.GdFloatVectorType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdIntVectorType;
import gd.script.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// Read-only member projections for frozen builtin constant values (const plan §5.4).
///
/// The whitelist names real builtin members by their exact metadata names — coordinate leaves
/// (`x`/`y`/`z`/`w`, `r`/`g`/`b`/`a`, `d`), stored member slices (`position`/`size`, `normal`,
/// `basis`, `origin`), and the calculated `end` property — never synthesized pseudo-members.
/// Everything else (`h`/`s`/`v`, `r8`/`g8`/`b8`/`a8`, `ok_hsl_*`, methods, subscripts) stays
/// rejected, and scalar receivers have no projections at all.
final class FrontendConstMemberProjectionSupport {
    private FrontendConstMemberProjectionSupport() {
    }

    /// The exact projected type for `memberName` on `receiverType`, or null when the pair is
    /// outside the projection whitelist. Used by the validation pass to publish result types
    /// without computing values.
    static @Nullable GdType projectedType(
            @NotNull GdType receiverType,
            @NotNull String memberName
    ) {
        var projection = projectionsOf(receiverType).get(memberName);
        return projection == null ? null : projection.type();
    }

    /// Computes one read-only projection on a frozen value. Unknown members, non-builtin
    /// receivers, and computed projections that leave the component range all reject — the
    /// evaluator attaches the AST anchor.
    static @NotNull ConstCallResult project(
            @NotNull GdConstantValue receiver,
            @NotNull String memberName
    ) {
        var projections = projectionsOf(receiver.type());
        if (projections.isEmpty()) {
            return new ConstCallResult.Rejected(
                    "type '" + receiver.type().getTypeName() + "' has no supported constant members");
        }
        var projection = projections.get(memberName);
        if (projection == null) {
            return new ConstCallResult.Rejected(
                    "member '" + memberName + "' on type '" + receiver.type().getTypeName()
                            + "' is not a supported constant member");
        }
        var builtin = (GdConstantValue.BuiltinValue) receiver;
        return switch (projection) {
            case Projection.Leaf leaf ->
                    new ConstCallResult.Resolved(builtin.components().get(leaf.index()));
            case Projection.Slice slice -> new ConstCallResult.Resolved(new GdConstantValue.BuiltinValue(
                    slice.type(),
                    builtin.components().subList(
                            slice.offset(),
                            slice.offset() + GdConstantValue.BuiltinValue.leafCountOf(slice.type()))
            ));
            case Projection.End end -> computeEnd(builtin, end.type());
        };
    }

    /// `end` is `position + size` per component — a calculated metadata member, not a stored
    /// leaf. `Rect2i.end` keeps the int32 component contract fail-closed; the float variants
    /// re-narrow through `BuiltinValue` like every projected leaf.
    private static @NotNull ConstCallResult computeEnd(
            @NotNull GdConstantValue.BuiltinValue builtin,
            @NotNull GdType endType
    ) {
        var leaves = builtin.components();
        var leafCount = GdConstantValue.BuiltinValue.leafCountOf(endType);
        var result = new ArrayList<GdConstantValue>(leafCount);
        if (GdConstantValue.BuiltinValue.leafKindOf(endType) == GdConstantValue.BuiltinValue.LeafKind.INT) {
            for (var index = 0; index < leafCount; index++) {
                var sum = ((GdConstantValue.IntValue) leaves.get(index)).value()
                        + ((GdConstantValue.IntValue) leaves.get(index + leafCount)).value();
                if (sum < Integer.MIN_VALUE || sum > Integer.MAX_VALUE) {
                    return new ConstCallResult.Rejected(
                            "member 'end' on '" + builtin.type().getTypeName()
                                    + "' overflows the int32 component range");
                }
                result.add(new GdConstantValue.IntValue(sum));
            }
        } else {
            for (var index = 0; index < leafCount; index++) {
                result.add(new GdConstantValue.FloatValue(
                        ((GdConstantValue.FloatValue) leaves.get(index)).value()
                                + ((GdConstantValue.FloatValue) leaves.get(index + leafCount)).value()));
            }
        }
        return new ConstCallResult.Resolved(new GdConstantValue.BuiltinValue(endType, result));
    }

    private static @NotNull Map<String, Projection> projectionsOf(@NotNull GdType receiverType) {
        var extensionType = receiverType.getGdExtensionType();
        return extensionType == null
                ? Map.of()
                : PROJECTIONS.getOrDefault(extensionType, Map.of());
    }

    /// One projection recipe: which value the member yields for the receiver.
    private sealed interface Projection {
        @NotNull GdType type();

        /// One flat leaf — scalar members such as `x`, `d`, or `r`.
        record Leaf(@NotNull GdType type, int index) implements Projection {
        }

        /// A contiguous leaf slice forming a sub-builtin member — `position`, `normal`,
        /// `basis`, `origin`, and the axis column vectors.
        record Slice(@NotNull GdType type, int offset) implements Projection {
        }

        /// The calculated `end` member — `position + size` per component.
        record End(@NotNull GdType type) implements Projection {
        }
    }

    private static final Map<GdExtensionTypeEnum, Map<String, Projection>> PROJECTIONS =
            buildProjections();

    private static @NotNull Map<GdExtensionTypeEnum, Map<String, Projection>> buildProjections() {
        var table = new EnumMap<GdExtensionTypeEnum, Map<String, Projection>>(GdExtensionTypeEnum.class);
        table.put(GdExtensionTypeEnum.VECTOR2, axisMembers(GdFloatType.FLOAT, 2));
        table.put(GdExtensionTypeEnum.VECTOR3, axisMembers(GdFloatType.FLOAT, 3));
        table.put(GdExtensionTypeEnum.VECTOR4, axisMembers(GdFloatType.FLOAT, 4));
        table.put(GdExtensionTypeEnum.VECTOR2I, axisMembers(GdIntType.INT, 2));
        table.put(GdExtensionTypeEnum.VECTOR3I, axisMembers(GdIntType.INT, 3));
        table.put(GdExtensionTypeEnum.VECTOR4I, axisMembers(GdIntType.INT, 4));
        table.put(GdExtensionTypeEnum.RECT2, Map.of(
                "position", new Projection.Slice(GdFloatVectorType.VECTOR2, 0),
                "size", new Projection.Slice(GdFloatVectorType.VECTOR2, 2),
                "end", new Projection.End(GdFloatVectorType.VECTOR2)));
        table.put(GdExtensionTypeEnum.RECT2I, Map.of(
                "position", new Projection.Slice(GdIntVectorType.VECTOR2I, 0),
                "size", new Projection.Slice(GdIntVectorType.VECTOR2I, 2),
                "end", new Projection.End(GdIntVectorType.VECTOR2I)));
        table.put(GdExtensionTypeEnum.AABB, Map.of(
                "position", new Projection.Slice(GdFloatVectorType.VECTOR3, 0),
                "size", new Projection.Slice(GdFloatVectorType.VECTOR3, 3),
                "end", new Projection.End(GdFloatVectorType.VECTOR3)));
        table.put(GdExtensionTypeEnum.PLANE, Map.of(
                "x", new Projection.Leaf(GdFloatType.FLOAT, 0),
                "y", new Projection.Leaf(GdFloatType.FLOAT, 1),
                "z", new Projection.Leaf(GdFloatType.FLOAT, 2),
                "d", new Projection.Leaf(GdFloatType.FLOAT, 3),
                "normal", new Projection.Slice(GdFloatVectorType.VECTOR3, 0)));
        table.put(GdExtensionTypeEnum.QUATERNION, axisMembers(GdFloatType.FLOAT, 4));
        table.put(GdExtensionTypeEnum.BASIS, Map.of(
                "x", new Projection.Slice(GdFloatVectorType.VECTOR3, 0),
                "y", new Projection.Slice(GdFloatVectorType.VECTOR3, 3),
                "z", new Projection.Slice(GdFloatVectorType.VECTOR3, 6)));
        table.put(GdExtensionTypeEnum.TRANSFORM2D, Map.of(
                "x", new Projection.Slice(GdFloatVectorType.VECTOR2, 0),
                "y", new Projection.Slice(GdFloatVectorType.VECTOR2, 2),
                "origin", new Projection.Slice(GdFloatVectorType.VECTOR2, 4)));
        table.put(GdExtensionTypeEnum.TRANSFORM3D, Map.of(
                "basis", new Projection.Slice(GdBasisType.BASIS, 0),
                "origin", new Projection.Slice(GdFloatVectorType.VECTOR3, 9)));
        table.put(GdExtensionTypeEnum.PROJECTION, Map.of(
                "x", new Projection.Slice(GdFloatVectorType.VECTOR4, 0),
                "y", new Projection.Slice(GdFloatVectorType.VECTOR4, 4),
                "z", new Projection.Slice(GdFloatVectorType.VECTOR4, 8),
                "w", new Projection.Slice(GdFloatVectorType.VECTOR4, 12)));
        table.put(GdExtensionTypeEnum.COLOR, Map.of(
                "r", new Projection.Leaf(GdFloatType.FLOAT, 0),
                "g", new Projection.Leaf(GdFloatType.FLOAT, 1),
                "b", new Projection.Leaf(GdFloatType.FLOAT, 2),
                "a", new Projection.Leaf(GdFloatType.FLOAT, 3)));
        return Collections.unmodifiableMap(table);
    }

    /// Coordinate leaf members `x`/`y`/`z`/`w` — shared by the vector, `*i` vector, and
    /// quaternion tables.
    private static @NotNull Map<String, Projection> axisMembers(
            @NotNull GdType leafType,
            int dimension
    ) {
        var names = List.of("x", "y", "z", "w");
        var members = new HashMap<String, Projection>(dimension);
        for (var index = 0; index < dimension; index++) {
            members.put(names.get(index), new Projection.Leaf(leafType, index));
        }
        return Map.copyOf(members);
    }
}
