package gd.script.gdcc.test_suite.benchmark;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/// Keeps benchmark report serialization isolated from the runner so result modeling and JSON
/// persistence can evolve without growing the execution harness further.
public final class BenchmarkReportWriter {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private BenchmarkReportWriter() {
    }

    public static void writeReport(@NotNull Path reportPath, @NotNull BenchmarkReport report) throws IOException {
        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, renderReportJson(report), StandardCharsets.UTF_8);
    }

    public static void writeReports(@NotNull Path reportPath, @NotNull BenchmarkReport report) throws IOException {
        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, renderReportJson(report), StandardCharsets.UTF_8);
        Files.writeString(reportMinPath(reportPath), renderMinimalReportJson(report), StandardCharsets.UTF_8);
    }

    public static @NotNull String renderReportJson(@NotNull BenchmarkReport report) {
        return GSON.toJson(report);
    }

    public static @NotNull String renderMinimalReportJson(@NotNull BenchmarkReport report) {
        var root = JsonParser.parseString(renderReportJson(report)).getAsJsonObject();
        var cases = root.getAsJsonArray("cases");
        for (var caseElement : cases) {
            var caseObject = caseElement.getAsJsonObject();
            caseObject.remove("pass_marker_seen");
            caseObject.remove("command");
            caseObject.remove("combined_output");
            removeRawSamples(caseObject, "compiled");
            removeRawSamples(caseObject, "interpreter");
        }
        return GSON.toJson(root);
    }

    public static @NotNull BenchmarkReport readReport(@NotNull Path reportPath) throws IOException {
        return GSON.fromJson(Files.readString(reportPath, StandardCharsets.UTF_8), BenchmarkReport.class);
    }

    private static void removeRawSamples(@NotNull JsonObject caseObject, @NotNull String fieldName) {
        var value = caseObject.get(fieldName);
        if (value == null || value.isJsonNull()) {
            return;
        }
        var stats = value.getAsJsonObject();
        stats.remove("raw_samples");
    }

    private static @NotNull Path reportMinPath(@NotNull Path reportPath) {
        var reportFileName = reportPath.getFileName().toString();
        var minFileName = reportFileName.endsWith(".json")
                ? reportFileName.substring(0, reportFileName.length() - 5) + "-min.json"
                : reportFileName + "-min.json";
        return reportPath.resolveSibling(minFileName);
    }
}
