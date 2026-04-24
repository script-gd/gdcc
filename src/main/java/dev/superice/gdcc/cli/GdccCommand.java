package dev.superice.gdcc.cli;

import dev.superice.gdcc.api.API;
import dev.superice.gdcc.logger.GdccLogger;
import dev.superice.gdcc.util.ConsoleOutputUtil;
import org.jetbrains.annotations.NotNull;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;

@Command(
        name = "gdcc",
        mixinStandardHelpOptions = true,
        description = "Compile GDScript source files into a GDCC module."
)
public final class GdccCommand implements Callable<Integer> {
    static final int EXIT_USAGE = 2;

    @SuppressWarnings({"FieldCanBeLocal", "unused"})
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
        // Step 1 only installs the annotated command surface. Later steps replace this boundary with
        // API-backed source loading, option normalization, compile-task polling, and result rendering.
        err.println("gdcc CLI compile pipeline is not implemented yet.");
        return EXIT_USAGE;
    }
}
