package dev.superice.gdcc.lir;

import dev.superice.gdcc.lir.parser.SimpleLirBlockInsnSerializer;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record LirBasicBlock(@NotNull String id, @NotNull List<LirInstruction> instructions) {
    public LirBasicBlock(String id, List<LirInstruction> instructions) {
        this.id = Objects.requireNonNull(id);
        this.instructions = new ArrayList<>(Objects.requireNonNull(instructions));
    }

    public LirBasicBlock(@NotNull String id) {
        this(id, new ArrayList<>());
    }

    @Override
    public @NotNull String toString() {
        var serializer = new SimpleLirBlockInsnSerializer();
        var stringWriter = new StringWriter();
        try {
            serializer.serialize(this.instructions, stringWriter);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return "Block \"" + id + "\":\n" + stringWriter;
    }
}
