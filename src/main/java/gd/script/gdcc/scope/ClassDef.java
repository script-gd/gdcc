package gd.script.gdcc.scope;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.UnmodifiableView;

import java.util.List;
import java.util.Map;

public interface ClassDef {
    /// Returns the canonical class name used by shared registry/backends.
    ///
    /// For top-level classes this still matches the source declaration name. Inner classes may use
    /// a canonicalized form such as `Outer__sub__Inner`, while source-facing aliases are carried by
    /// frontend relation/type-meta objects instead of `ClassDef` itself.
    @NotNull String getName();

    /// Returns the canonical superclass name consumed by registry, LIR, and backend inheritance walks.
    ///
    /// Source-facing superclass spellings now live only on frontend relation objects. `ClassDef`
    /// intentionally keeps the old method name to reduce churn, but callers must treat the returned
    /// string as canonical identity, not raw header text.
    @NotNull String getSuperName();

    boolean isAbstract();

    boolean isTool();

    @NotNull
    @UnmodifiableView
    Map<String, String> getAnnotations();

    boolean hasAnnotation(@NotNull String key);

    String getAnnotation(@NotNull String key);

    @NotNull
    @UnmodifiableView
    List<? extends SignalDef> getSignals();

    @NotNull
    @UnmodifiableView
    List<? extends PropertyDef> getProperties();

    @NotNull
    @UnmodifiableView
    List<? extends FunctionDef> getFunctions();

    boolean hasFunction(@NotNull String functionName);

    /// Returns script-source constants declared by this class, in declaration order.
    ///
    /// This table is the GDScript-side constant channel only (currently script enum members and
    /// named enum groups; user-level `const` is deferred but will reuse it). Engine/builtin class
    /// constants stay on the extension metadata channel
    /// (`ClassRegistry.findEngineClassConstantInHierarchy(...)` and friends) and never appear
    /// here, so non-GDCC class definitions keep the default empty table. The backend does not
    /// consume this table.
    @NotNull
    @UnmodifiableView
    default List<? extends GdScriptClassConstant> getScriptConstants() {
        return List.of();
    }

    /**
     * For LIR-described user classes this is always true.
     */
    boolean isGdccClass();
}
