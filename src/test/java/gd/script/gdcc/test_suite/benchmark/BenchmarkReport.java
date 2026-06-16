package gd.script.gdcc.test_suite.benchmark;

import com.google.gson.annotations.SerializedName;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertTrue;

public record BenchmarkReport(
        @SerializedName("schema_version") int schemaVersion,
        @SerializedName("generated_at") @NotNull String generatedAt,
        @NotNull BenchmarkReport.EnvironmentSummary environment,
        @NotNull List<BenchmarkReport.CaseSummary> cases
) {
    public BenchmarkReport {
        assertTrue(schemaVersion > 0, "Benchmark report schemaVersion must be > 0");
        Objects.requireNonNull(generatedAt);
        Objects.requireNonNull(environment);
        cases = List.copyOf(Objects.requireNonNull(cases));
    }

    public record EnvironmentSummary(
            @Nullable String os,
            @Nullable String arch,
            @SerializedName("java_version") @Nullable String javaVersion,
            @SerializedName("godot_bin") @Nullable String godotBin,
            @SerializedName("godot_version") @Nullable String godotVersion,
            @Nullable String zig,
            @SerializedName("target_platform") @Nullable String targetPlatform,
            @Nullable String optimization
    ) {
    }

    public record ReportConfig(
            int warmups,
            int samples,
            int iterations,
            @SerializedName("min_batch_us") int minBatchUs
    ) {
    }

    public record CaseSummary(
            @SerializedName("case") @NotNull String casePath,
            @NotNull String name,
            @NotNull BenchmarkReport.ReportConfig config,
            @NotNull String status,
            @NotNull List<String> warnings,
            @SerializedName("failure") @Nullable String failure,
            @Nullable BenchmarkReport.PathStatistics compiled,
            @Nullable BenchmarkReport.PathStatistics interpreter,
            @Nullable BenchmarkReport.RatioSummary ratio,
            @SerializedName("pass_marker_seen") boolean passMarkerSeen,
            @NotNull List<String> command,
            @SerializedName("combined_output") @NotNull String combinedOutput
    ) {
        public CaseSummary {
            casePath = casePath.replace('\\', '/');
            Objects.requireNonNull(casePath);
            Objects.requireNonNull(name);
            Objects.requireNonNull(config);
            Objects.requireNonNull(status);
            warnings = List.copyOf(Objects.requireNonNull(warnings));
            command = List.copyOf(Objects.requireNonNull(command));
            Objects.requireNonNull(combinedOutput);
        }
    }

    public record PathStatistics(
            int samples,
            @SerializedName("mean_body_ns") double meanBodyNs,
            @SerializedName("stddev_body_ns") double stddevBodyNs,
            @SerializedName("min_body_ns") long minBodyNs,
            @SerializedName("max_body_ns") long maxBodyNs,
            @SerializedName("mean_overhead_ns") double meanOverheadNs,
            @SerializedName("raw_samples") @NotNull List<BenchmarkReport.RawSample> rawSamples,
            @NotNull List<String> warnings
    ) {
        public PathStatistics {
            rawSamples = List.copyOf(Objects.requireNonNull(rawSamples));
            warnings = List.copyOf(Objects.requireNonNull(warnings));
        }
    }

    public record RawSample(
            int sample,
            int iterations,
            @SerializedName("baseline_us") long baselineUs,
            @SerializedName("benchmark_us") long benchmarkUs,
            @SerializedName("body_ns") long bodyNs
    ) {
    }

    public record RatioSummary(
            @SerializedName("compiled_to_interpreter_mean") double compiledToInterpreterMean,
            @SerializedName("interpreter_to_compiled_mean") double interpreterToCompiledMean
    ) {
    }
}
