package gd.script.gdcc.backend.c.build;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/// Loads the C probe sources that live as classpath resources under `src/test/resources/c/`
/// (keeping multi-hundred-line C fixtures out of the Java text blocks). A missing resource is a
/// hard error — a silently empty probe would compile to nothing and fake-pass its gate.
final class CProbeSources {
    private CProbeSources() {
    }

    /// Reads `c/<name>` from the test classpath as UTF-8.
    static @NotNull String load(@NotNull String name) {
        var resource = "c/" + name;
        try (var stream = CProbeSources.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("missing C probe resource: " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("failed to read C probe resource: " + resource, e);
        }
    }
}
