package gd.script.gdcc.frontend.sema;

import gd.script.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/// One-pass static classification of a `for-in` iterable. Keeping iterability and semantic element
/// type in one sealed result prevents type-check and plan construction from applying divergent rules.
public sealed interface FrontendIterableSemantics {
    /// The iterable is statically known to be valid and exposes the given source-visible element type.
    record StaticIterable(@NotNull GdType elementType) implements FrontendIterableSemantics {
        public StaticIterable {
            Objects.requireNonNull(elementType, "elementType must not be null");
        }
    }

    /// Iterability is decided at runtime; the frontend conservatively exposes `Variant` elements.
    record DynamicIterable() implements FrontendIterableSemantics {
    }

    /// A hard type is statically known not to support Godot's iteration protocol.
    record NonIterable(@NotNull GdType iterableType) implements FrontendIterableSemantics {
        public NonIterable {
            Objects.requireNonNull(iterableType, "iterableType must not be null");
        }
    }
}
