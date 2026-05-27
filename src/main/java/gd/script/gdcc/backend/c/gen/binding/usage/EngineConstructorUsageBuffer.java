package gd.script.gdcc.backend.c.gen.binding.usage;

import gd.script.gdcc.type.GdObjectType;
import org.jetbrains.annotations.NotNull;

/// Function-scope buffer for engine constructor wrapper usage.
/// The module snapshot only sees entries from bodies that render successfully.
final class EngineConstructorUsageBuffer extends AbstractUsageBuffer<String, EngineConstructorUsage> {
    private static final @NotNull EngineConstructorUsageBuffer NO_OP = new EngineConstructorUsageBuffer(true);

    private EngineConstructorUsageBuffer() {
        this(false);
    }

    private EngineConstructorUsageBuffer(boolean noOp) {
        super(noOp);
    }

    static @NotNull EngineConstructorUsageBuffer noOp() {
        return NO_OP;
    }

    void record(@NotNull GdObjectType constructedType) {
        if (isNoOp()) {
            return;
        }
        var className = constructedType.getTypeName();
        putIfAbsent(className, EngineConstructorUsage.fromClassName(className));
    }

    static @NotNull EngineConstructorUsageBuffer create() {
        return new EngineConstructorUsageBuffer();
    }
}
