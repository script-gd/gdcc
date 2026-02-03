package dev.superice.gdcc.scope;

import dev.superice.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnmodifiableView;

import java.util.List;
import java.util.Map;

public non-sealed interface FunctionDef extends ParameterEntityDef {
    @NotNull String getName();

    boolean isStatic();

    boolean isAbstract();

    boolean isLambda();

    boolean isVararg();

    boolean isHidden();

    @NotNull
    @UnmodifiableView
    Map<String, String> getAnnotations();

    @Nullable ParameterDef getParameter(int index);

    @Nullable ParameterDef getParameter(@NotNull String name);

    int getParameterCount();

    @NotNull
    @UnmodifiableView
    List<? extends ParameterDef> getParameters();

    @Nullable CaptureDef getCapture(@NotNull String name);

    int getCaptureCount();

    @UnmodifiableView Map<String, ? extends CaptureDef> getCaptures();

    default @UnmodifiableView List<? extends CaptureDef> getCaptureList() {
        return List.copyOf(getCaptures().values());
    }

    @NotNull GdType getReturnType();
}
