package dev.superice.gdcc.gdextension;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import dev.superice.gdcc.scope.*;
import dev.superice.gdcc.scope.resolver.ScopeTypeParsers;
import dev.superice.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnmodifiableView;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public record ExtensionGdClass(
        @SerializedName("name") String name,
        @SerializedName("is_refcounted") boolean isRefcounted,
        @SerializedName("is_instantiable") boolean isInstantiable,
        @SerializedName("inherits") String inherits,
        @SerializedName("api_type") String apiType,
        @SerializedName("enums") List<ClassEnum> enums,
        @SerializedName("methods") List<ClassMethod> methods,
        @SerializedName("signals") List<SignalInfo> signals,
        @SerializedName("properties") List<PropertyInfo> properties,
        @SerializedName("constants") List<ConstantInfo> constants
) implements ClassDef {
    @Override
    public @NotNull String getName() {
        return name;
    }

    @Override
    public @NotNull String getSuperName() {
        return inherits == null ? "" : inherits;
    }

    @Override
    public boolean isAbstract() {
        return false;
    }

    @Override
    public boolean isTool() {
        return false;
    }

    @Override
    public @NotNull @UnmodifiableView Map<String, String> getAnnotations() {
        return Map.of();
    }

    @Override
    public boolean hasAnnotation(@NotNull String key) {
        return false;
    }

    @Override
    public String getAnnotation(@NotNull String key) {
        return null;
    }

    @Override
    public @NotNull @UnmodifiableView List<? extends SignalDef> getSignals() {
        return Collections.unmodifiableList(signals);
    }

    @Override
    public @NotNull @UnmodifiableView List<? extends PropertyDef> getProperties() {
        return Collections.unmodifiableList(properties);
    }

    @Override
    public @NotNull @UnmodifiableView List<? extends FunctionDef> getFunctions() {
        return Collections.unmodifiableList(methods);
    }

    @Override
    public boolean hasFunction(@NotNull String functionName) {
        for (var function : methods) {
            if (function.getName().equals(functionName)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isGdccClass() {
        return false;
    }

    public record ClassEnum(String name, boolean isBitfield, List<ExtensionEnumValue> values) {
    }

    public record ClassMethod(
            String name,
            boolean isConst,
            boolean isVararg,
            boolean isStatic,
            boolean isVirtual,
            long hash,
            List<Long> hashCompatibility,
            ClassMethodReturn returnValue,
            List<ExtensionFunctionArgument> arguments
    ) implements FunctionDef {
        @Override
        public @NotNull String getName() {
            return name;
        }

        @Override
        public boolean isAbstract() {
            return isVirtual;
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
            return Collections.unmodifiableList(arguments);
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
            var rawReturnType = returnValue == null || returnValue.type() == null ? "void" : returnValue.type();
            return ScopeTypeParsers.parseExtensionTypeMetadata(
                    rawReturnType,
                    "return type of engine method '" + name + "'"
            );
        }

        public record ClassMethodReturn(String type) {
        }
    }

    /// Signal model for extension API classes.
    public record SignalInfo(String name, List<SignalArgument> arguments) implements SignalDef {
        @Override
        public @NotNull String getName() {
            return name;
        }

        @Override
        public @NotNull @UnmodifiableView Map<String, String> getAnnotations() {
            return Map.of();
        }

        @Override
        public int getParameterCount() {
            return arguments.size();
        }

        @Override
        public @Nullable ParameterDef getParameter(int index) {
            if (index < 0 || index >= arguments.size()) return null;
            return arguments.get(index);
        }

        @Override
        public @Nullable ParameterDef getParameter(@NotNull String name) {
            for (var a : arguments) {
                if (a.name().equals(name)) return a;
            }
            return null;
        }

        @Override
        public @NotNull @UnmodifiableView List<? extends ParameterDef> getParameters() {
            return Collections.unmodifiableList(arguments);
        }

        /// Parameter record for signals.
        public record SignalArgument(String name, String type,
                                     @Expose(serialize = false, deserialize = false)
                                     @NotNull SignalInfo definedIn) implements ParameterDef {
            @Override
            public @NotNull String getName() {
                return name;
            }

            @Override
            public @NotNull GdType getType() {
                var parameterName = name == null || name.isBlank() ? "<unnamed>" : name;
                return ScopeTypeParsers.parseExtensionTypeMetadata(
                        type,
                        "type of engine signal parameter '" + parameterName + "' in '" + definedIn.getName() + "'"
                );
            }

            @Override
            public @Nullable String getDefaultValueFunc() {
                return null;
            }

            @Override
            public @NotNull ParameterEntityDef getDefinedIn() {
                return definedIn;
            }
        }
    }

    public record PropertyInfo(String name, String type, boolean isReadable, boolean isWritable,
                               String defaultValue) implements PropertyDef {
        @Override
        public @NotNull String getName() {
            return name;
        }

        @Override
        public @NotNull GdType getType() {
            return ScopeTypeParsers.parseExtensionTypeMetadata(
                    type,
                    "type of engine property '" + name + "'"
            );
        }

        @Override
        public boolean isStatic() {
            return false;
        }

        @Override
        public @Nullable String getInitFunc() {
            return null;
        }

        @Override
        public @Nullable String getGetterFunc() {
            return null;
        }

        @Override
        public @Nullable String getSetterFunc() {
            return null;
        }

        @Override
        public @NotNull @UnmodifiableView Map<String, String> getAnnotations() {
            return Map.of();
        }
    }

    public record ConstantInfo(String name, String value) {
    }
}
