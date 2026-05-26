package gd.script.gdcc.backend.c.gen.binding;

import gd.script.gdcc.type.GdObjectType;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.Map;

/// Function-scope buffer for engine constructor wrapper usage.
/// The module snapshot only sees entries from bodies that render successfully.
public final class EngineConstructorUsageBuffer {
    private static final @NotNull EngineConstructorUsageBuffer NO_OP = new EngineConstructorUsageBuffer(true);

    private final boolean noOp;
    private final LinkedHashMap<String, EngineConstructorUsage> constructorsByClassName = new LinkedHashMap<>();

    private EngineConstructorUsageBuffer() {
        this(false);
    }

    private EngineConstructorUsageBuffer(boolean noOp) {
        this.noOp = noOp;
    }

    public static @NotNull EngineConstructorUsageBuffer noOp() {
        return NO_OP;
    }

    public void record(@NotNull GdObjectType constructedType) {
        if (noOp) {
            return;
        }
        var className = constructedType.getTypeName();
        constructorsByClassName.putIfAbsent(className, EngineConstructorUsage.fromClassName(className));
    }

    @NotNull Map<String, EngineConstructorUsage> snapshot() {
        return new LinkedHashMap<>(constructorsByClassName);
    }

    static @NotNull EngineConstructorUsageBuffer create() {
        return new EngineConstructorUsageBuffer();
    }
}
