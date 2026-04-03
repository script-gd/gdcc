package dev.superice.gdcc.frontend.lowering.pass.body;

import dev.superice.gdcc.lir.LirBasicBlock;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/// Exact-type dispatch table for body-lowering processors.
///
/// The registry keeps the routing rules explicit and fail-fast: when a new frontend node reaches
/// lowering without a registered processor, the error points directly at the missing node class
/// instead of silently falling back to a giant `switch`.
final class FrontendInsnLoweringProcessorRegistry<TNode, TContext> {
    private final @NotNull String registryName;
    private final @NotNull Map<Class<?>, FrontendInsnLoweringProcessor<? extends TNode, TContext>> processors;

    @SafeVarargs
    static <TNode, TContext> @NotNull FrontendInsnLoweringProcessorRegistry<TNode, TContext> of(
            @NotNull String registryName,
            FrontendInsnLoweringProcessor<? extends TNode, TContext>... processors
    ) {
        return new FrontendInsnLoweringProcessorRegistry<>(registryName, processors);
    }

    @SafeVarargs
    private FrontendInsnLoweringProcessorRegistry(
            @NotNull String registryName,
            FrontendInsnLoweringProcessor<? extends TNode, TContext>... processors
    ) {
        this.registryName = Objects.requireNonNull(registryName, "registryName must not be null");
        this.processors = copyProcessors(processors);
    }

    <TActual extends TNode> void lower(
            @NotNull FrontendBodyLoweringSession session,
            @NotNull LirBasicBlock block,
            @NotNull TActual node,
            @Nullable TContext context
    ) {
        requireProcessor(node).lower(
                session,
                block,
                node,
                context
        );
    }

    private @NotNull Map<Class<?>, FrontendInsnLoweringProcessor<? extends TNode, TContext>> copyProcessors(
            FrontendInsnLoweringProcessor<? extends TNode, TContext>[] processors
    ) {
        Objects.requireNonNull(processors, "processors must not be null");
        var copied = new LinkedHashMap<Class<?>, FrontendInsnLoweringProcessor<? extends TNode, TContext>>(
                processors.length
        );
        for (var processor : processors) {
            var actualProcessor = Objects.requireNonNull(processor, "processor must not be null");
            var previous = copied.put(actualProcessor.nodeType(), actualProcessor);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate " + registryName + " processor for " + actualProcessor.nodeType().getName()
                );
            }
        }
        return Map.copyOf(copied);
    }

    @SuppressWarnings("unchecked")
    private <TActual extends TNode> @NotNull FrontendInsnLoweringProcessor<TActual, TContext> requireProcessor(
            @NotNull TActual node
    ) {
        var nodeClass = node.getClass();
        var directProcessor = processors.get(nodeClass);
        if (directProcessor != null) {
            return (FrontendInsnLoweringProcessor<TActual, TContext>) directProcessor;
        }
        for (var entry : processors.entrySet()) {
            if (entry.getKey().isAssignableFrom(nodeClass)) {
                return (FrontendInsnLoweringProcessor<TActual, TContext>) entry.getValue();
            }
        }
        throw new IllegalStateException(
                "No " + registryName + " processor registered for " + nodeClass.getName()
        );
    }
}
