package dev.superice.gdcc.type;

import org.jetbrains.annotations.NotNull;

import java.util.List;

import static dev.superice.gdcc.type.GdBasisType.BASIS;
import static dev.superice.gdcc.type.GdFloatVectorType.VECTOR3;

public final class GdTransform3DType implements GdMatrixType {
    public static final GdTransform3DType TRANSFORM3D = new GdTransform3DType();
    @Override
    public @NotNull String getTypeName() {
        return "Transform3D";
    }

    @Override
    public @NotNull GdExtensionTypeEnum getGdExtensionType() {
        return GdExtensionTypeEnum.TRANSFORM3D;
    }

    @Override
    public @NotNull List<GdVectorType> getBaseComponentTypes() {
        return List.of(BASIS, VECTOR3);
    }
}
