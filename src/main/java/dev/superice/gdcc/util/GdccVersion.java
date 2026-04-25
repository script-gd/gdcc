package dev.superice.gdcc.util;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Properties;

public final class GdccVersion {
    private static final String RESOURCE_PATH = "/gdcc-version.properties";
    private static final String UNKNOWN = "unknown";
    private static volatile Info cached;

    private GdccVersion() {
    }

    public static @NotNull Info current() {
        var info = cached;
        if (info != null) {
            return info;
        }
        synchronized (GdccVersion.class) {
            info = cached;
            if (info == null) {
                info = load();
                cached = info;
            }
            return info;
        }
    }

    public static @NotNull String displayText() {
        var info = current();
        return "gdcc " + info.version() + " (" + info.branch() + " " + info.commit() + ")";
    }

    private static @NotNull Info load() {
        try (var stream = GdccVersion.class.getResourceAsStream(RESOURCE_PATH)) {
            if (stream == null) {
                return new Info(UNKNOWN, UNKNOWN, UNKNOWN);
            }
            var properties = new Properties();
            properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
            return new Info(property(properties, "version"), property(properties, "branch"), property(properties, "commit"));
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to read GDCC version resource", exception);
        }
    }

    private static @NotNull String property(@NotNull Properties properties, @NotNull String key) {
        return Objects.requireNonNullElse(StringUtil.trimToNull(properties.getProperty(key)), UNKNOWN);
    }

    public record Info(@NotNull String version, @NotNull String branch, @NotNull String commit) {
    }
}
