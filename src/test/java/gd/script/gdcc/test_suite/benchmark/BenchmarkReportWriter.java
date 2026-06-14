package gd.script.gdcc.test_suite.benchmark;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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

    public static @NotNull String renderReportJson(@NotNull BenchmarkReport report) {
        return GSON.toJson(report);
    }
}
