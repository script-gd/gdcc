package gd.script.gdcc.lir;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import gd.script.gdcc.scope.PropertyDef;
import gd.script.gdcc.type.GdType;

public final class LirPropertyDef implements PropertyDef {
    private @NotNull String name;
    private @NotNull GdType type;
    private boolean isStatic;
    private @Nullable String initFunc;
    private @Nullable String getterFunc;
    private @Nullable String setterFunc;
    /// True when initFunc was synthesized by the backend rather than lowered from source.
    private transient boolean initializerSynthesizedByBackend;
    private Map<String, String> annotations;

    public LirPropertyDef(
            @NotNull String name,
            @NotNull GdType type,
            boolean isStatic,
            @Nullable String initFunc,
            @Nullable String getterFunc,
            @Nullable String setterFunc,
            @NotNull Map<String, String> annotations
    ) {
        this.name = Objects.requireNonNull(name);
        this.type = Objects.requireNonNull(type);
        this.isStatic = isStatic;
        this.initFunc = initFunc;
        this.getterFunc = getterFunc;
        this.setterFunc = setterFunc;
        this.annotations = new HashMap<>(Objects.requireNonNull(annotations));
    }

    public LirPropertyDef(
            @NotNull String name,
            @NotNull GdType type
    ) {
        this.name = Objects.requireNonNull(name);
        this.type = Objects.requireNonNull(type);
        this.isStatic = false;
        this.initFunc = null;
        this.getterFunc = null;
        this.setterFunc = null;
        this.annotations = new HashMap<>();
    }

    public @NotNull String getName() {
        return name;
    }

    public void setName(@NotNull String name) {
        this.name = name;
    }

    public @NotNull GdType getType() {
        return type;
    }

    public void setType(@NotNull GdType type) {
        this.type = type;
    }

    public boolean isStatic() {
        return isStatic;
    }

    public void setStatic(boolean aStatic) {
        isStatic = aStatic;
    }

    public @Nullable String getInitFunc() {
        return initFunc;
    }

    /// Assigns a source/explicit init function; clears the synthesized marker left by any
    /// earlier backend synthesis so the property counts as source again.
    public void setInitFunc(@Nullable String initFunc) {
        this.initFunc = initFunc;
        this.initializerSynthesizedByBackend = false;
    }

    /// True when `initFunc` was produced by backend synthesis rather than source.
    public boolean isInitializerSynthesizedByBackend() {
        return initializerSynthesizedByBackend;
    }

    /// Publishes a backend-synthesized default init function together with its provenance
    /// marker, so readers can tell it apart from a source initializer.
    public void setBackendSynthesizedInitFunc(@NotNull String initFunc) {
        this.initFunc = Objects.requireNonNull(initFunc);
        this.initializerSynthesizedByBackend = true;
    }

    public @Nullable String getGetterFunc() {
        return getterFunc;
    }

    public void setGetterFunc(@Nullable String getterFunc) {
        this.getterFunc = getterFunc;
    }

    public @Nullable String getSetterFunc() {
        return setterFunc;
    }

    public void setSetterFunc(@Nullable String setterFunc) {
        this.setterFunc = setterFunc;
    }

    public @NotNull Map<String, String> getAnnotations() {
        return annotations;
    }

    public void setAnnotations(Map<String, String> annotations) {
        this.annotations = annotations;
    }
}
