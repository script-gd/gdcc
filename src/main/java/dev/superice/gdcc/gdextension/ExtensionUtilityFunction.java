package dev.superice.gdcc.gdextension;

import dev.superice.gdcc.scope.CaptureDef;
import dev.superice.gdcc.scope.FunctionDef;
import dev.superice.gdcc.scope.ParameterDef;
import dev.superice.gdcc.scope.resolver.ScopeTypeParsers;
import dev.superice.gdcc.type.GdType;
import dev.superice.gdcc.type.GdVoidType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnmodifiableView;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ExtensionUtilityFunction(
        String name,
        String returnType,
        String category,
        boolean isVararg,
        int hash,
        List<ExtensionFunctionArgument> arguments
) implements FunctionDef {
    @Override
    public @NotNull String getName() {
        return name;
    }

    @Override
    public boolean isStatic() {
        return true;
    }

    @Override
    public boolean isAbstract() {
        return false;
    }

    @Override
    public boolean isLambda() {
        return false;
    }

    @Override
    public boolean isHidden() {
        return false;
    }

    @Override
    public @NotNull @UnmodifiableView Map<String, String> getAnnotations() {
        return Map.of();
    }

    @Override
    public @Nullable ParameterDef getParameter(int index) {
        if (index < 0 || index >= arguments.size()) {
            return null;
        }
        return arguments.get(index);
    }

    @Override
    public @Nullable ParameterDef getParameter(@NotNull String name) {
        for (var arg : arguments) {
            if (arg.name().equals(name)) {
                return arg;
            }
        }
        return null;
    }

    @Override
    public int getParameterCount() {
        return arguments.size();
    }

    @Override
    public @NotNull @UnmodifiableView List<? extends ParameterDef> getParameters() {
        return arguments;
    }

    @Override
    public @Nullable CaptureDef getCapture(@NotNull String name) {
        return null;
    }

    @Override
    public int getCaptureCount() {
        return 0;
    }

    @Override
    public @UnmodifiableView Map<String, ? extends CaptureDef> getCaptures() {
        return Map.of();
    }

    @Override
    public @NotNull GdType getReturnType() {
        // Godot utility metadata omits `return_type` for void-like calls such as `print(...)`.
        // Shared consumers still need a stable non-null `FunctionDef` contract, so blank metadata
        // is normalized to `void` here instead of leaking a metadata shape difference as an NPE.
        if (returnType == null || returnType.isBlank()) {
            return GdVoidType.VOID;
        }
        return ScopeTypeParsers.parseExtensionTypeMetadata(
                returnType,
                "return type of utility '" + Objects.requireNonNull(name, "name must not be null") + "'"
        );
    }
}
