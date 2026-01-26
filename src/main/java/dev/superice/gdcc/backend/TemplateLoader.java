package dev.superice.gdcc.backend;

import freemarker.cache.ClassTemplateLoader;
import freemarker.cache.FileTemplateLoader;
import freemarker.template.Configuration;
import freemarker.template.DefaultObjectWrapperBuilder;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import freemarker.template.TemplateExceptionHandler;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.StringWriter;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class TemplateLoader {
    /// Cache of compiled FreeMarker Template objects. Keys are location-prefixed
    /// strings, e.g. "classpath:tpl/foo.ftl" or "file:C:/abs/path/to/tpl.ftl".
    private static final ConcurrentHashMap<String, WeakReference<Template>> TEMPLATE_CACHE = new ConcurrentHashMap<>();

    /// Cache for directory -> Configuration used for file-based loading.
    private static final ConcurrentHashMap<Path, WeakReference<Configuration>> FILE_CONFIGURATION_CACHE = new ConcurrentHashMap<>();

    /// Weak reference holder for a shared classpath Configuration.
    private static volatile WeakReference<Configuration> CLASSPATH_CONFIGURATION_REF = new WeakReference<>(null);

    private TemplateLoader() {
        // utility
    }

    /// Renders a FreeMarker template located on the classpath.
    /// @param resourcePath path within the classpath (no leading '/').
    /// @param context variables used during rendering.
    /// @return rendered template as UTF-8 string.
    public static @NotNull String renderFromClasspath(@NotNull String resourcePath, @NotNull Map<String, Object> context) throws IOException, TemplateException {
        Objects.requireNonNull(resourcePath, "resourcePath");
        Objects.requireNonNull(context, "context");

        var key = "classpath:" + resourcePath;
        var template = getOrLoadTemplate(key, () -> {
            var cfg = getOrCreateClasspathConfiguration();
            return cfg.getTemplate(resourcePath, StandardCharsets.UTF_8.name());
        });

        return processTemplate(template, context);
    }

    /// Renders a FreeMarker template located on the filesystem.
    /// @param templateFile absolute or relative path to the .ftl file.
    /// @param context variables used during rendering.
    /// @return rendered template as UTF-8 string.
    public static @NotNull String renderFromFile(@NotNull Path templateFile, @NotNull Map<String, Object> context) throws IOException, TemplateException {
        Objects.requireNonNull(templateFile, "templateFile");
        Objects.requireNonNull(context, "context");

        if (!Files.exists(templateFile) || !Files.isRegularFile(templateFile)) {
            throw new IOException("Template file not found: " + templateFile);
        }

        var abs = templateFile.toAbsolutePath().normalize();
        var dir = abs.getParent();
        var name = abs.getFileName().toString();
        var key = "file:" + abs;

        var template = getOrLoadTemplate(key, () -> {
            var cfg = getOrCreateFileConfiguration(dir);
            return cfg.getTemplate(name, StandardCharsets.UTF_8.name());
        });

        return processTemplate(template, context);
    }

    private static @NotNull Template getOrLoadTemplate(@NotNull String key, @NotNull Loader loader) throws IOException {
        var ref = TEMPLATE_CACHE.get(key);
        var t = ref == null ? null : ref.get();
        if (t != null) return t;

        synchronized (TEMPLATE_CACHE) {
            ref = TEMPLATE_CACHE.get(key);
            t = ref == null ? null : ref.get();
            if (t == null) {
                t = loader.load();
                TEMPLATE_CACHE.put(key, new WeakReference<>(t));
            }
            return t;
        }
    }

    private static @NotNull Configuration getOrCreateClasspathConfiguration() throws IOException {
        var cfg = CLASSPATH_CONFIGURATION_REF.get();
        if (cfg != null) return cfg;

        synchronized (TemplateLoader.class) {
            cfg = CLASSPATH_CONFIGURATION_REF.get();
            if (cfg != null) return cfg;

            var created = new Configuration(Configuration.VERSION_2_3_31);
            // Use ClassTemplateLoader rooted at classpath root so relative includes/imports
            // inside templates (e.g. "<#include 'partial.ftl'>") resolve correctly.
            created.setTemplateLoader(new ClassTemplateLoader(TemplateLoader.class, "/"));
            created.setDefaultEncoding(StandardCharsets.UTF_8.name());
            created.setLocale(Locale.ROOT);
            created.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
            created.setLogTemplateExceptions(false);
            created.setWrapUncheckedExceptions(true);
            created.setObjectWrapper(new DefaultObjectWrapperBuilder(Configuration.VERSION_2_3_31).build());

            CLASSPATH_CONFIGURATION_REF = new WeakReference<>(created);
            return created;
        }
    }

    private static @NotNull Configuration getOrCreateFileConfiguration(@NotNull Path dir) throws IOException {
        var normalized = dir.toAbsolutePath().normalize();
        var ref = FILE_CONFIGURATION_CACHE.get(normalized);
        var cfg = ref == null ? null : ref.get();
        if (cfg != null) return cfg;

        synchronized (FILE_CONFIGURATION_CACHE) {
            ref = FILE_CONFIGURATION_CACHE.get(normalized);
            cfg = ref == null ? null : ref.get();
            if (cfg == null) {
                var loader = new FileTemplateLoader(normalized.toFile());
                var created = new Configuration(Configuration.VERSION_2_3_31);
                created.setTemplateLoader(loader);
                created.setDefaultEncoding(StandardCharsets.UTF_8.name());
                created.setLocale(Locale.ROOT);
                created.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
                created.setLogTemplateExceptions(false);
                created.setWrapUncheckedExceptions(true);
                created.setObjectWrapper(new DefaultObjectWrapperBuilder(Configuration.VERSION_2_3_31).build());

                FILE_CONFIGURATION_CACHE.put(normalized, new WeakReference<>(created));
                return created;
            }
            return cfg;
        }
    }

    private static @NotNull String processTemplate(@NotNull Template tpl, @NotNull Map<String, Object> context) throws IOException, TemplateException {
        var out = new StringWriter();
        tpl.process(context, out);
        return out.toString();
    }

    @FunctionalInterface
    private interface Loader {
        Template load() throws IOException;
    }

}
