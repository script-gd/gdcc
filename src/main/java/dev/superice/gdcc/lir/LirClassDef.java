package dev.superice.gdcc.lir;

import dev.superice.gdcc.scope.ClassDef;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnmodifiableView;

import java.util.*;

/// XML entity: <class_def ...> ... </class_def>.
public final class LirClassDef implements ClassDef {
    private @NotNull String name;
    /// Canonical superclass identity written into backend-facing LIR.
    private @NotNull String superName;
    private boolean isAbstract;
    private boolean isTool;
    private final Map<String, String> annotations;
    private final List<LirSignalDef> signals;
    private final List<LirPropertyDef> properties;
    private final List<LirFunctionDef> functions;
    private @Nullable String sourceFile;

    public LirClassDef(
            @NotNull String name,
            @NotNull String superName,
            boolean isAbstract,
            boolean isTool,
            Map<String, String> annotations,
            List<LirSignalDef> signals,
            List<LirPropertyDef> properties,
            List<LirFunctionDef> functions
    ) {
        this.name = name;
        this.superName = superName;
        this.isAbstract = isAbstract;
        this.isTool = isTool;
        this.annotations = new HashMap<>(annotations);
        this.signals = new ArrayList<>(signals);
        this.properties = new ArrayList<>(properties);
        this.functions = new ArrayList<>(functions);
    }

    public LirClassDef(
            @NotNull String name,
            @NotNull String superName
    ) {
        this.name = name;
        this.superName = superName;
        this.isAbstract = false;
        this.isTool = false;
        this.annotations = new HashMap<>();
        this.signals = new ArrayList<>();
        this.properties = new ArrayList<>();
        this.functions = new ArrayList<>();
    }

    /// Canonical class identity used by the registry and downstream phases.
    public @NotNull String getName() {
        return name;
    }

    public void setName(@NotNull String name) {
        this.name = name;
    }

    public @NotNull String getSuperName() {
        return superName;
    }

    public void setSuperName(@NotNull String superName) {
        this.superName = superName;
    }

    public boolean isAbstract() {
        return isAbstract;
    }

    public void setAbstract(boolean anAbstract) {
        isAbstract = anAbstract;
    }

    public boolean isTool() {
        return isTool;
    }

    public void setTool(boolean tool) {
        isTool = tool;
    }

    public @UnmodifiableView @NotNull Map<String, String> getAnnotations() {
        return Collections.unmodifiableMap(annotations);
    }

    public boolean hasAnnotation(@NotNull String key) {
        return annotations.containsKey(key);
    }

    public String getAnnotation(@NotNull String key) {
        return annotations.get(key);
    }

    public String setAnnotation(String key, String value) {
        return annotations.put(key, value);
    }

    public void clearAnnotations() {
        annotations.clear();
    }

    public boolean removeAnnotation(String key) {
        return annotations.remove(key) != null;
    }

    public @UnmodifiableView @NotNull List<LirSignalDef> getSignals() {
        return Collections.unmodifiableList(signals);
    }

    public void addSignal(@NotNull LirSignalDef signal) {
        signals.add(signal);
    }

    public boolean removeSignal(@NotNull LirSignalDef signal) {
        return signals.remove(signal);
    }

    public @UnmodifiableView @NotNull List<LirPropertyDef> getProperties() {
        return Collections.unmodifiableList(properties);
    }

    public void addProperty(@NotNull LirPropertyDef property) {
        properties.add(property);
    }

    public boolean removeProperty(@NotNull LirPropertyDef property) {
        return properties.remove(property);
    }

    public @UnmodifiableView @NotNull List<LirFunctionDef> getFunctions() {
        return Collections.unmodifiableList(functions);
    }

    @Override
    public boolean hasFunction(@NotNull String functionName) {
        for (var function : functions) {
            if (function.getName().equals(functionName)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isGdccClass() {
        return true;
    }

    public void addFunction(@NotNull LirFunctionDef function) {
        functions.add(function);
    }

    public boolean removeFunction(@NotNull LirFunctionDef function) {
        return functions.remove(function);
    }

    public @Nullable String getSourceFile() {
        return sourceFile;
    }

    public void setSourceFile(@Nullable String sourceFile) {
        this.sourceFile = sourceFile;
    }
}
