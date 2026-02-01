package dev.superice.gdcc.backend;

import freemarker.cache.ClassTemplateLoader;
import freemarker.cache.FileTemplateLoader;
import freemarker.template.*;
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
import java.util.regex.Pattern;

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
    ///
    /// @param resourcePath path within the classpath (no leading '/').
    /// @param context      variables used during rendering.
    /// @return rendered template as UTF-8 string.
    public static @NotNull String renderFromClasspath(@NotNull String resourcePath, @NotNull Map<String, Object> context) throws IOException, TemplateException {
        return renderFromClasspath(resourcePath, context, true);
    }

    /// Renders a FreeMarker template located on the classpath.
    ///
    /// @param resourcePath path within the classpath (no leading '/').
    /// @param context      variables used during rendering.
    /// @return rendered template as UTF-8 string.
    public static @NotNull String renderFromClasspath(@NotNull String resourcePath, @NotNull Map<String, Object> context, boolean trim) throws IOException, TemplateException {
        Objects.requireNonNull(resourcePath, "resourcePath");
        Objects.requireNonNull(context, "context");

        var key = "classpath:" + resourcePath;
        var template = getOrLoadTemplate(key, () -> {
            var cfg = getOrCreateClasspathConfiguration();
            return cfg.getTemplate(resourcePath, StandardCharsets.UTF_8.name());
        });

        return processTemplate(template, context, trim);
    }

    /// Renders a FreeMarker template located on the filesystem.
    ///
    /// @param templateFile absolute or relative path to the .ftl file.
    /// @param context      variables used during rendering.
    /// @return rendered template as UTF-8 string.
    public static @NotNull String renderFromFile(@NotNull Path templateFile, @NotNull Map<String, Object> context) throws IOException, TemplateException {
        return renderFromFile(templateFile, context, true);
    }

    /// Renders a FreeMarker template located on the filesystem.
    ///
    /// @param templateFile absolute or relative path to the .ftl file.
    /// @param context      variables used during rendering.
    /// @return rendered template as UTF-8 string.
    public static @NotNull String renderFromFile(@NotNull Path templateFile, @NotNull Map<String, Object> context, boolean trim) throws IOException, TemplateException {
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

        return processTemplate(template, context, trim);
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

    private static @NotNull Configuration getOrCreateClasspathConfiguration() {
        var cfg = CLASSPATH_CONFIGURATION_REF.get();
        if (cfg != null) return cfg;

        synchronized (TemplateLoader.class) {
            cfg = CLASSPATH_CONFIGURATION_REF.get();
            if (cfg != null) return cfg;

            var created = new Configuration(Configuration.VERSION_2_3_34);
            // Use ClassTemplateLoader rooted at classpath root so relative includes/imports
            // inside templates (e.g. "<#include 'partial.ftl'>") resolve correctly.
            created.setTemplateLoader(new ClassTemplateLoader(TemplateLoader.class, "/"));
            created.setDefaultEncoding(StandardCharsets.UTF_8.name());
            created.setLocale(Locale.ROOT);
            created.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
            created.setLogTemplateExceptions(false);
            created.setWrapUncheckedExceptions(true);
            created.setObjectWrapper(new DefaultObjectWrapperBuilder(Configuration.VERSION_2_3_34).build());

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
                var created = new Configuration(Configuration.VERSION_2_3_34);
                created.setTemplateLoader(loader);
                created.setDefaultEncoding(StandardCharsets.UTF_8.name());
                created.setLocale(Locale.ROOT);
                created.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
                created.setLogTemplateExceptions(false);
                created.setWrapUncheckedExceptions(true);
                created.setObjectWrapper(new DefaultObjectWrapperBuilder(Configuration.VERSION_2_3_34).build());

                FILE_CONFIGURATION_CACHE.put(normalized, new WeakReference<>(created));
                return created;
            }
            return cfg;
        }
    }

    private static @NotNull String processTemplate(@NotNull Template tpl, @NotNull Map<String, Object> context, boolean trim) throws IOException, TemplateException {
        var out = new StringWriter();
        tpl.process(context, out);
        var rendered = out.toString();

        if (!trim) {
            return rendered;
        }

        // Post-process trim markers like __trim<3>__ which indicate removing up to 3
        // preceding spaces/tabs and the marker itself.
        return processTrimMarkers(rendered);
    }

    // Pattern matches markers like __trim<3>__ or plain __trim__ (group 1 captures digits when present).
    static Pattern TRIM_PATTERN = Pattern.compile("__trim(?:<(\\d+)>)?__");

    static @NotNull String processTrimMarkers(@NotNull String text) {
        var sb = new StringBuilder(text);

        for (; ; ) {
            var matcher = TRIM_PATTERN.matcher(sb);
            if (!matcher.find()) break;

            var numStr = matcher.group(1);
            if (numStr != null) {
                // numeric form: remove up to N preceding spaces/tabs and the marker itself.
                int toRemove;
                try {
                    toRemove = Integer.parseInt(numStr);
                } catch (NumberFormatException e) {
                    // Shouldn't happen because regex restricts to digits; skip this marker.
                    sb.delete(matcher.start(), matcher.end());
                    continue;
                }

                var removalStart = matcher.start();
                // Remove up to `toRemove` preceding spaces or tabs (do not remove newlines).
                while (toRemove > 0 && removalStart > 0) {
                    var ch = sb.charAt(removalStart - 1);
                    if (ch == ' ' || ch == '\t') {
                        removalStart--;
                        toRemove--;
                    } else {
                        break;
                    }
                }

                // Delete from removalStart up to the end of the marker.
                sb.delete(removalStart, matcher.end());
            } else {
                // plain __trim__ form: make this line's leading whitespace equal to previous line's leading whitespace.
                var markerStart = matcher.start();

                // Compute start of current line (index after previous '\n').
                var lastNl = sb.lastIndexOf("\n", markerStart - 1);
                var lineStart = lastNl == -1 ? 0 : lastNl + 1;

                // Compute previous line's start and end positions.
                //                var prevLineEnd = lineStart - 1;
                //                String prevIndent = "";
                //                if (prevLineEnd >= 0) {
                //                    var prevPrevNl = sb.lastIndexOf("\n", prevLineEnd - 1);
                //                    var prevLineStart = prevPrevNl == -1 ? 0 : prevPrevNl + 1;
                //                    var pi = prevLineStart;
                //                    while (pi <= prevLineEnd && pi < sb.length()) {
                //                        var c = sb.charAt(pi);
                //                        if (c == ' ' || c == '\t') pi++; else break;
                //                    }
                //                    prevIndent = sb.substring(prevLineStart, pi);
                //                }
                // Find the previous non-blank line by searching upward and skipping blank lines.
                String prevIndent = "";
                var searchEnd = lineStart - 1; // inclusive index of the line ending we're inspecting
                while (searchEnd >= 0) {
                    var prevPrevNl = sb.lastIndexOf("\n", searchEnd - 1);
                    var prevLineStart = prevPrevNl == -1 ? 0 : prevPrevNl + 1;
                    var prevLineEnd = searchEnd;

                    // Extract the previous line's content and check if it's blank.
                    var lineContent = sb.substring(prevLineStart, Math.min(prevLineEnd + 1, sb.length()));
                    if (!lineContent.isBlank()) {
                        // compute its leading whitespace
                        var pi = prevLineStart;
                        while (pi <= prevLineEnd && pi < sb.length()) {
                            var c = sb.charAt(pi);
                            if (c == ' ' || c == '\t') pi++;
                            else break;
                        }
                        prevIndent = sb.substring(prevLineStart, pi);
                        break;
                    }

                    // Otherwise skip this blank line and continue searching above it.
                    searchEnd = prevLineStart - 1;
                }

                // Determine current line's leading whitespace region.
                var ci = lineStart;
                while (ci < sb.length()) {
                    var c = sb.charAt(ci);
                    if (c == ' ' || c == '\t') ci++;
                    else break;
                }
                var currentIndentEnd = ci;

                // Delete the marker first (it's after the current indent), then replace the current indent with prevIndent.
                sb.delete(matcher.start(), matcher.end());

                // Replace current indentation with prevIndent
                sb.replace(lineStart, currentIndentEnd, prevIndent);
            }
        }

        return sb.toString();
    }

    @FunctionalInterface
    private interface Loader {
        Template load() throws IOException;
    }

}
