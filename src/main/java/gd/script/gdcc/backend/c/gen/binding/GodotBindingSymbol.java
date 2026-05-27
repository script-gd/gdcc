package gd.script.gdcc.backend.c.gen.binding;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/// Structural identity for a generated Godot binding wrapper.
///
/// Hash values are lookup metadata, not wrapper identity. The parameter ABI list deliberately
/// keeps pointer constness / destination shape visible so future module-local generators cannot
/// accidentally merge incompatible C call surfaces behind the same public function name.
public record GodotBindingSymbol(
        @NotNull Family family,
        @NotNull String owner,
        @NotNull String name,
        @NotNull String cFunctionName,
        @NotNull String returnType,
        @NotNull List<Parameter> parameters,
        boolean vararg,
        @Nullable Long primaryHash,
        @NotNull List<Long> compatibilityHashes
) {
    public GodotBindingSymbol {
        Objects.requireNonNull(family);
        Objects.requireNonNull(owner);
        Objects.requireNonNull(name);
        Objects.requireNonNull(cFunctionName);
        Objects.requireNonNull(returnType);
        parameters = List.copyOf(parameters);
        compatibilityHashes = List.copyOf(compatibilityHashes);
    }

    public @NotNull String signatureKey() {
        var params = parameters.stream()
                .map(parameter -> parameter.cType() + " " + parameter.abi())
                .toList();
        return returnType + "(" + String.join(", ", params) + ")" + (vararg ? " vararg" : "");
    }

    public enum Family {
        SINGLETON,
        CLASS_CONSTANT,
        BUILTIN,
        UTILITY,
        FIXED
    }

    public record Parameter(@NotNull String name, @NotNull String cType, @NotNull Abi abi) {
        public Parameter {
            Objects.requireNonNull(name);
            Objects.requireNonNull(cType);
            Objects.requireNonNull(abi);
        }
    }

    public enum Abi {
        VALUE,
        CONST_TYPE_PTR,
        MUTABLE_TYPE_PTR,
        UNINITIALIZED_DESTINATION,
        VARIANT_VARARG
    }
}
