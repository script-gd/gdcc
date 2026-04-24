package dev.superice.gdcc.cli;

import dev.superice.gdcc.api.API;
import dev.superice.gdcc.api.CompileOptions;
import dev.superice.gdcc.backend.c.build.COptimizationLevel;
import dev.superice.gdcc.backend.c.build.TargetPlatform;
import dev.superice.gdcc.enums.GodotVersion;
import dev.superice.gdcc.exception.GdccException;
import dev.superice.gdcc.logger.GdccLogger;
import dev.superice.gdcc.util.ConsoleOutputUtil;
import org.jetbrains.annotations.NotNull;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Callable;

@Command(
        name = "gdcc",
        mixinStandardHelpOptions = true,
        description = "Compile GDScript source files into a GDCC module."
)
public final class GdccCommand implements Callable<Integer> {
    static final int EXIT_USAGE = 2;

    private final @NotNull API api;
    private final @NotNull PrintWriter out;
    private final @NotNull PrintWriter err;

    @Parameters(
            index = "0..*",
            arity = "1..*",
            paramLabel = "files",
            description = "GDScript source files to compile into one virtual module."
    )
    List<Path> files = new ArrayList<>();

    @Option(
            names = {"-o", "--output"},
            required = true,
            paramLabel = "<output>",
            description = "Output target path. The final path segment becomes the module name."
    )
    Path output;

    @Option(
            names = "--prefix",
            paramLabel = "<prefix>",
            description = "Canonical name prefix for filename-derived top-level classes."
    )
    String prefix;

    @Option(
            names = "--class-map",
            paramLabel = "Source=Canonical",
            description = "Explicit top-level source-to-canonical class mapping. May be repeated."
    )
    List<String> classMaps = new ArrayList<>();

    @Option(
            names = "--gde",
            defaultValue = "4.5.1",
            paramLabel = "<version>",
            description = "Godot GDExtension API version to compile against. Supported: 4.5.1."
    )
    String gde = "4.5.1";

    @Option(
            names = {"-v", "--verbose"},
            description = "Increase output verbosity. Repeat for more detail."
    )
    boolean[] verbosity = new boolean[0];

    public GdccCommand() {
        this(new API(), ConsoleOutputUtil.stdoutWriter(), ConsoleOutputUtil.stderrWriter());
    }

    GdccCommand(@NotNull API api, @NotNull PrintWriter out, @NotNull PrintWriter err) {
        this.api = Objects.requireNonNull(api, "api must not be null");
        this.out = Objects.requireNonNull(out, "out must not be null");
        this.err = Objects.requireNonNull(err, "err must not be null");
    }

    public static int execute(@NotNull String[] args) {
        var previousPlainOutput = GdccLogger.isPlainOutput();
        GdccLogger.setPlainOutput(true);
        try {
            return new GdccCommand().commandLine().execute(args);
        } finally {
            GdccLogger.setPlainOutput(previousPlainOutput);
        }
    }

    public @NotNull CommandLine commandLine() {
        var commandLine = new CommandLine(this);
        commandLine.setOut(out);
        commandLine.setErr(err);
        return commandLine;
    }

    int verbosityLevel() {
        return verbosity.length;
    }

    @Override
    public @NotNull Integer call() {
        try {
            var outputTarget = outputTarget();
            var compileOptions = compileOptions(outputTarget);
            var sourceInputs = sourceInputs();

            api.createModule(outputTarget.moduleId(), outputTarget.moduleName());
            api.setCompileOptions(outputTarget.moduleId(), compileOptions);
            for (var sourceInput : sourceInputs) {
                api.putFile(outputTarget.moduleId(), sourceInput.virtualPath(), sourceInput.source(), sourceInput.displayPath());
            }

            // Step 3 intentionally stops after preparing module inputs and compile options. Later
            // steps configure mappings, task polling, and result rendering on top of this boundary.
            err.println("gdcc CLI compile task execution is not implemented yet.");
            return EXIT_USAGE;
        } catch (IOException exception) {
            return failUsage("Failed to read input file: " + exception.getMessage());
        } catch (GdccException | IllegalArgumentException exception) {
            return failUsage(exception.getMessage());
        }
    }

    private @NotNull OutputTarget outputTarget() {
        if (output.toString().isBlank()) {
            throw new IllegalArgumentException("Output path must not be blank");
        }
        var normalizedOutput = output.toAbsolutePath().normalize();
        var moduleNamePath = normalizedOutput.getFileName();
        if (moduleNamePath == null) {
            throw new IllegalArgumentException("Output path must include a module name");
        }
        var moduleName = moduleNamePath.toString();
        if (moduleName.isBlank()) {
            throw new IllegalArgumentException("Output path module name must not be blank");
        }
        var outputDirectory = normalizedOutput.getParent();
        if (outputDirectory == null) {
            outputDirectory = Path.of("").toAbsolutePath().normalize();
        }
        return new OutputTarget(moduleName, moduleName, outputDirectory);
    }

    private @NotNull CompileOptions compileOptions(@NotNull OutputTarget outputTarget) {
        return new CompileOptions(
                godotVersion(),
                outputTarget.projectPath(),
                COptimizationLevel.DEBUG,
                TargetPlatform.getNativePlatform(),
                false,
                CompileOptions.DEFAULT_OUTPUT_MOUNT_ROOT
        );
    }

    private @NotNull GodotVersion godotVersion() {
        var normalizedGde = gde.strip();
        if (GodotVersion.V451.version.equals(normalizedGde)) {
            return GodotVersion.V451;
        }
        throw new IllegalArgumentException(
                "Unsupported --gde value '" + gde + "'. Supported versions: " + GodotVersion.V451.version
        );
    }

    private @NotNull List<SourceInput> sourceInputs() throws IOException {
        var normalizedInputs = new ArrayList<SourceInput>(files.size());
        for (var index = 0; index < files.size(); index++) {
            normalizedInputs.add(sourceInput(index, files.get(index)));
        }
        return normalizedInputs;
    }

    private @NotNull SourceInput sourceInput(int index, @NotNull Path input) throws IOException {
        if (input.toString().isBlank()) {
            throw new IllegalArgumentException("Input path must not be blank");
        }

        var normalizedInput = input.toAbsolutePath().normalize();
        if (Files.notExists(normalizedInput)) {
            throw new IllegalArgumentException("Input file does not exist: " + input);
        }
        if (Files.isDirectory(normalizedInput)) {
            throw new IllegalArgumentException("Input path is a directory: " + input);
        }
        if (!Files.isRegularFile(normalizedInput)) {
            throw new IllegalArgumentException("Input path is not a regular file: " + input);
        }

        var source = Files.readString(normalizedInput, StandardCharsets.UTF_8);
        return new SourceInput(virtualSourcePath(index, normalizedInput), source, input.toString());
    }

    private @NotNull String virtualSourcePath(int index, @NotNull Path normalizedInput) {
        // The index segment keeps duplicate host basenames distinct while preserving the frontend's
        // filename-based default class-name behavior.
        return String.format(Locale.ROOT, "/src/%04d/%s", index, normalizedInput.getFileName());
    }

    private int failUsage(@NotNull String message) {
        err.println("gdcc: " + message);
        return EXIT_USAGE;
    }

    private record OutputTarget(@NotNull String moduleId, @NotNull String moduleName, @NotNull Path projectPath) {
    }

    private record SourceInput(@NotNull String virtualPath, @NotNull String source, @NotNull String displayPath) {
    }
}
