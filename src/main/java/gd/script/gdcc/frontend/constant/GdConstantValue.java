package gd.script.gdcc.frontend.constant;

import gd.script.gdcc.type.GdBoolType;
import gd.script.gdcc.type.GdFloatType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdStringType;
import gd.script.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// Frozen compile-time `const` payload — the single source of truth for evaluated constants
/// across frontend evaluation and use-site materialization (const plan §4.1).
///
/// Payloads are pure immutable Java data: never AST nodes, Scope objects, C strings, engine
/// Objects, or any mutable structure, so one frozen value may be materialized repeatedly without
/// re-running the original expression.
public sealed interface GdConstantValue {

    /// The exact GDScript type this frozen value carries; `BuiltinValue` returns its own `GdType`.
    @NotNull GdType type();

    /// Whether `type` belongs to the exact-type set a constant value can carry: the four scalars
    /// (`bool`/`int`/`float`/`String`) plus the controlled builtin types `BuiltinValue` admits.
    /// `Variant`, `Nil`, `Object`, containers, and every other type are not constant-capable.
    static boolean isSupportedConstantType(@NotNull GdType type) {
        var extensionType = type.getGdExtensionType();
        if (extensionType == null) {
            return false;
        }
        return switch (extensionType) {
            case BOOL, INT, FLOAT, STRING -> true;
            default -> BuiltinValue.isControlledBuiltinType(type);
        };
    }

    /// Frozen `bool` payload.
    record BoolValue(boolean value) implements GdConstantValue {
        @Override
        public @NotNull GdType type() {
            return GdBoolType.BOOL;
        }
    }

    /// Frozen `int` payload; int64 semantics with any wraparound already applied by the evaluator.
    record IntValue(long value) implements GdConstantValue {
        @Override
        public @NotNull GdType type() {
            return GdIntType.INT;
        }
    }

    /// Frozen `float` payload; the scalar float type is a double at the ABI boundary.
    record FloatValue(double value) implements GdConstantValue {
        @Override
        public @NotNull GdType type() {
            return GdFloatType.FLOAT;
        }
    }

    /// Frozen `String` payload.
    ///
    /// @param value decoded string content — never the raw source lexeme and never C-string text
    record StringValue(@NotNull String value) implements GdConstantValue {
        public StringValue {
            Objects.requireNonNull(value, "value must not be null");
        }

        @Override
        public @NotNull GdType type() {
            return GdStringType.STRING;
        }
    }

    /// Frozen controlled-builtin payload stored as flattened scalar leaves (const plan §4.1).
    ///
    /// The canonical flat component layout (order and count) is fixed by plan §5.3: `Rect2` keeps
    /// position then size, `Basis` keeps its three logical column vectors, `Transform3D` keeps the
    /// nine basis leaves then the three origin leaves, and so on. Float leaves are normalized
    /// through a `(float)` narrowing round-trip so the stored double already matches the float32
    /// geometry ABI; `*i`/`Rect2i` leaves are checked against the int32 component range.
    ///
    /// @param type       one of the controlled builtin types admitted for constants (vectors,
    ///                   `Rect2`/`Rect2i`, `Plane`, `Quaternion`, `AABB`, `Basis`,
    ///                   `Transform2D`/`Transform3D`, `Projection`, `Color`)
    /// @param components flattened `FloatValue`/`IntValue` scalar leaves in canonical order
    record BuiltinValue(@NotNull GdType type, @NotNull List<GdConstantValue> components)
            implements GdConstantValue {

        public BuiltinValue {
            Objects.requireNonNull(type, "type must not be null");
            var spec = ComponentSpec.of(type);
            if (spec == null) {
                throw new IllegalArgumentException(
                        "BuiltinValue type '" + type.getTypeName()
                                + "' is not a controlled builtin constant type"
                );
            }
            Objects.requireNonNull(components, "components must not be null");
            if (components.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException(
                        "BuiltinValue '" + type.getTypeName() + "' components must not contain null leaves"
                );
            }
            var leaves = List.copyOf(components);
            if (leaves.size() != spec.leafCount()) {
                throw new IllegalArgumentException(
                        "BuiltinValue '" + type.getTypeName() + "' requires exactly "
                                + spec.leafCount() + " scalar leaves, but got " + leaves.size()
                );
            }
            var normalized = new ArrayList<GdConstantValue>(leaves.size());
            for (var leaf : leaves) {
                normalized.add(switch (spec.leafKind()) {
                    case FLOAT -> {
                        if (!(leaf instanceof FloatValue floatLeaf)) {
                            throw new IllegalArgumentException(
                                    "BuiltinValue '" + type.getTypeName()
                                            + "' requires float scalar leaves, but got "
                                            + leafTypeName(leaf)
                            );
                        }
                        // Geometry float leaves store the value already narrowed to float32 and
                        // widened back to double — the canonical form required by §4.1.
                        yield new FloatValue((double) (float) floatLeaf.value());
                    }
                    case INT -> {
                        if (!(leaf instanceof IntValue intLeaf)) {
                            throw new IllegalArgumentException(
                                    "BuiltinValue '" + type.getTypeName()
                                            + "' requires int scalar leaves, but got "
                                            + leafTypeName(leaf)
                            );
                        }
                        if (intLeaf.value() < Integer.MIN_VALUE
                                || intLeaf.value() > Integer.MAX_VALUE) {
                            throw new IllegalArgumentException(
                                    "BuiltinValue '" + type.getTypeName() + "' int leaf "
                                            + intLeaf.value() + " is out of the int32 component range"
                            );
                        }
                        yield intLeaf;
                    }
                });
            }
            components = List.copyOf(normalized);
        }

        /// Builds a float-leaf builtin value; each leaf is normalized through float32 narrowing.
        public static @NotNull BuiltinValue ofFloats(@NotNull GdType type, double... leaves) {
            var components = new ArrayList<GdConstantValue>(leaves.length);
            for (var leaf : leaves) {
                components.add(new FloatValue(leaf));
            }
            return new BuiltinValue(type, components);
        }

        /// Builds an int-leaf builtin value; each leaf must fit the int32 component range.
        public static @NotNull BuiltinValue ofInts(@NotNull GdType type, long... leaves) {
            var components = new ArrayList<GdConstantValue>(leaves.length);
            for (var leaf : leaves) {
                components.add(new IntValue(leaf));
            }
            return new BuiltinValue(type, components);
        }

        /// Whether `type` is one of the controlled builtin types this record admits.
        static boolean isControlledBuiltinType(@NotNull GdType type) {
            return ComponentSpec.of(type) != null;
        }

        private static @NotNull String leafTypeName(@Nullable GdConstantValue leaf) {
            return leaf == null ? "null" : leaf.type().getTypeName();
        }

        /// Canonical flat-leaf contract of one controlled builtin type: exact scalar-leaf count
        /// and which scalar family the leaves belong to (plan §5.3 layout table).
        private record ComponentSpec(int leafCount, @NotNull LeafKind leafKind) {

            private enum LeafKind {
                FLOAT,
                INT
            }

            private static @Nullable ComponentSpec of(@NotNull GdType type) {
                var extensionType = type.getGdExtensionType();
                if (extensionType == null) {
                    return null;
                }
                return switch (extensionType) {
                    case VECTOR2 -> new ComponentSpec(2, LeafKind.FLOAT);
                    case VECTOR3 -> new ComponentSpec(3, LeafKind.FLOAT);
                    case VECTOR4 -> new ComponentSpec(4, LeafKind.FLOAT);
                    case RECT2 -> new ComponentSpec(4, LeafKind.FLOAT);
                    case PLANE -> new ComponentSpec(4, LeafKind.FLOAT);
                    case QUATERNION -> new ComponentSpec(4, LeafKind.FLOAT);
                    case AABB -> new ComponentSpec(6, LeafKind.FLOAT);
                    case BASIS -> new ComponentSpec(9, LeafKind.FLOAT);
                    case TRANSFORM2D -> new ComponentSpec(6, LeafKind.FLOAT);
                    case TRANSFORM3D -> new ComponentSpec(12, LeafKind.FLOAT);
                    case PROJECTION -> new ComponentSpec(16, LeafKind.FLOAT);
                    case COLOR -> new ComponentSpec(4, LeafKind.FLOAT);
                    case VECTOR2I -> new ComponentSpec(2, LeafKind.INT);
                    case VECTOR3I -> new ComponentSpec(3, LeafKind.INT);
                    case VECTOR4I -> new ComponentSpec(4, LeafKind.INT);
                    case RECT2I -> new ComponentSpec(4, LeafKind.INT);
                    default -> null;
                };
            }
        }
    }
}
