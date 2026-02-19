package dev.superice.gdcc.logger;

import org.jetbrains.annotations.NotNull;
import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;

import java.util.concurrent.ConcurrentHashMap;

public final class GdccLoggerFactory implements ILoggerFactory {
    private final ConcurrentHashMap<String, Logger> loggers = new ConcurrentHashMap<>();

    @Override
    public @NotNull Logger getLogger(@NotNull String name) {
        return loggers.computeIfAbsent(name, GdccLogger::new);
    }
}
