package gd.script.gdcc.type;

public sealed interface GdPureVectorType extends GdVectorType permits GdFloatVectorType, GdIntVectorType {
    int getDimension();

    default boolean isValidBuiltinDim() {
        return switch (getDimension()) {
            case 2, 3, 4 -> true;
            default -> false;
        };
    }

    default void ensureValidBuiltinDim() {
        if (!isValidBuiltinDim()) {
            throw new IllegalStateException("Dimension " + getDimension() + " is not valid for builtin GDScript vectors");
        }
    }
}
