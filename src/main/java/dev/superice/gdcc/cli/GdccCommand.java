package dev.superice.gdcc.cli;

import dev.superice.gdcc.api.API;
import dev.superice.gdcc.api.CompileOptions;
import dev.superice.gdcc.api.CompileResult;
import dev.superice.gdcc.api.CompileTaskEvent;
import dev.superice.gdcc.api.CompileTaskSnapshot;
import dev.superice.gdcc.backend.c.build.COptimizationLevel;
import dev.superice.gdcc.backend.c.build.TargetPlatform;
import dev.superice.gdcc.enums.GodotVersion;
import dev.superice.gdcc.exception.GdccException;
import dev.superice.gdcc.frontend.FrontendClassNameContract;
import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.diagnostic.FrontendDiagnostic;
import dev.superice.gdcc.frontend.diagnostic.FrontendRange;
import dev.superice.gdcc.frontend.parse.GdScriptParserService;
import dev.superice.gdcc.logger.GdccLogger;
import dev.superice.gdcc.util.ConsoleOutputUtil;
import dev.superice.gdparser.frontend.ast.ClassNameStatement;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;

@Command(
        name = "gdcc",
        mixinStandardHelpOptions = true,
        description = "Compile GDScript source files into a GDCC module."
)
public final class GdccCommand implements Callable<Integer> {
    static final int EXIT_COMPILE_FAILED = 1;
    static final int EXIT_USAGE = 2;
    static final int EXIT_INTERRUPTED = 130;
    private static final long POLL_INTERVAL_MILLIS = 10;

    private final @NotNull API api;
    private final @NotNull GdScriptParserService parserService;
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
        parserService = new GdScriptParserService();
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
            var topLevelCanonicalNameMap = topLevelCanonicalNameMap(sourceInputs);

            api.createModule(outputTarget.moduleId(), outputTarget.moduleName());
            api.setCompileOptions(outputTarget.moduleId(), compileOptions);
            api.setTopLevelCanonicalNameMap(outputTarget.moduleId(), topLevelCanonicalNameMap);
            for (var sourceInput : sourceInputs) {
                api.putFile(outputTarget.moduleId(), sourceInput.virtualPath(), sourceInput.source(), sourceInput.displayPath());
            }

            var taskId = api.compile(outputTarget.moduleId());
            var completedSnapshot = waitForTask(taskId);
            return renderCompletedTask(completedSnapshot);
        } catch (CliInterruptedException exception) {
            return EXIT_INTERRUPTED;
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
        return new OutputTarget(moduleName, moduleName, normalizedOutput);
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
        var virtualPath = virtualSourcePath(index, normalizedInput);
        return new SourceInput(virtualPath, Path.of(virtualPath), source, input.toString());
    }

    private @NotNull String virtualSourcePath(int index, @NotNull Path normalizedInput) {
        // The index segment keeps duplicate host basenames distinct while preserving the frontend's
        // filename-based default class-name behavior.
        return String.format(Locale.ROOT, "/src/%04d/%s", index, normalizedInput.getFileName());
    }

    private @NotNull Map<String, String> topLevelCanonicalNameMap(@NotNull List<SourceInput> sourceInputs) {
        var mappings = new LinkedHashMap<String, String>();
        if (prefix != null) {
            for (var sourceInput : sourceInputs) {
                if (hasExplicitTopLevelClassName(sourceInput)) {
                    continue;
                }
                var sourceName = FrontendClassNameContract.deriveDefaultTopLevelSourceName(sourceInput.logicalPath());
                mappings.put(sourceName, prefix + sourceName);
            }
        }

        var explicitSourceNames = new HashSet<String>();
        for (var classMap : classMaps) {
            var mapping = explicitClassMap(classMap);
            if (!explicitSourceNames.add(mapping.source())) {
                throw new IllegalArgumentException("Duplicate --class-map source name: " + mapping.source());
            }
            // Explicit entries are the second merge phase. Reinsert overridden generated entries so
            // the final LinkedHashMap order reflects that phase boundary as well as the value.
            mappings.remove(mapping.source());
            mappings.put(mapping.source(), mapping.canonical());
        }
        return mappings;
    }

    private @NotNull ClassMap explicitClassMap(@NotNull String classMap) {
        var separatorIndex = classMap.indexOf('=');
        if (separatorIndex < 0 || separatorIndex != classMap.lastIndexOf('=')) {
            throw new IllegalArgumentException("--class-map must use Source=Canonical syntax: " + classMap);
        }
        return new ClassMap(classMap.substring(0, separatorIndex), classMap.substring(separatorIndex + 1));
    }

    private boolean hasExplicitTopLevelClassName(@NotNull SourceInput sourceInput) {
        var diagnosticManager = new DiagnosticManager();
        var unit = parserService.parseUnit(sourceInput.logicalPath(), sourceInput.source(), diagnosticManager);
        if (diagnosticManager.snapshot().hasErrors()) {
            return true;
        }
        return unit.ast().statements().stream().anyMatch(ClassNameStatement.class::isInstance);
    }

    private @NotNull CompileTaskSnapshot waitForTask(long taskId) {
        var nextEventIndex = 0L;
        var lastRevision = 0L;
        while (true) {
            var snapshot = api.getCompileTask(taskId);
            if (verbosityLevel() > 0 && snapshot.revision() != lastRevision) {
                renderTaskProgress(snapshot);
                lastRevision = snapshot.revision();
            }
            if (verbosityLevel() > 0) {
                nextEventIndex = drainEvents(taskId, nextEventIndex);
            }
            if (snapshot.completed()) {
                if (verbosityLevel() > 0) {
                    drainEvents(taskId, nextEventIndex);
                }
                return snapshot;
            }
            sleepBeforeNextPoll(taskId);
        }
    }

    private void sleepBeforeNextPoll(long taskId) {
        try {
            Thread.sleep(POLL_INTERVAL_MILLIS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            api.cancelCompileTask(taskId);
            throw new CliInterruptedException();
        }
    }

    private long drainEvents(long taskId, long nextEventIndex) {
        var currentIndex = nextEventIndex;
        while (true) {
            var page = api.listCompileTaskEvents(taskId, currentIndex, API.MAX_COMPILE_TASK_EVENT_PAGE_SIZE);
            if (page.isEmpty()) {
                return currentIndex;
            }
            for (var indexedEvent : page) {
                renderEvent(indexedEvent);
                currentIndex = indexedEvent.index() + 1;
            }
            if (page.size() < API.MAX_COMPILE_TASK_EVENT_PAGE_SIZE) {
                return currentIndex;
            }
        }
    }

    private int renderCompletedTask(@NotNull CompileTaskSnapshot snapshot) {
        var result = Objects.requireNonNull(snapshot.result(), "completed compile task must carry a result");
        renderResult(snapshot.moduleId(), result);
        if (result.success()) {
            return 0;
        }
        return result.outcome() == CompileResult.Outcome.CANCELED ? EXIT_INTERRUPTED : EXIT_COMPILE_FAILED;
    }

    private void renderResult(@NotNull String moduleId, @NotNull CompileResult result) {
        for (var diagnostic : result.diagnostics().asList()) {
            err.println(formatDiagnostic(diagnostic));
        }
        // Keep verbosity levels additive: -v exposes published VFS outputs, while -vv adds
        // compile inputs and backend file details useful for debugging the API boundary.
        if (verbosityLevel() >= 2) {
            renderDetailedResultInputs(result);
        }
        if (result.success()) {
            out.println("Compiled module " + moduleId);
            for (var artifact : result.artifacts()) {
                out.println("Artifact: " + artifact);
            }
            if (verbosityLevel() >= 1) {
                renderOutputLinks(result);
            }
            if (verbosityLevel() >= 2 && !result.buildLog().isBlank()) {
                renderBuildLog(out, result.buildLog());
            }
            return;
        }

        err.println("Compile failed: " + result.outcome());
        if (result.failureMessage() != null) {
            err.println(result.failureMessage());
        }
        if (result.outcome() == CompileResult.Outcome.BUILD_FAILED || verbosityLevel() >= 2) {
            if (!result.buildLog().isBlank()) {
                renderBuildLog(err, result.buildLog());
            }
        }
    }

    private void renderDetailedResultInputs(@NotNull CompileResult result) {
        var options = result.compileOptions();
        out.println("Compile option: godotVersion=" + options.godotVersion().version);
        out.println("Compile option: projectPath=" + options.projectPath());
        out.println("Compile option: optimizationLevel=" + options.optimizationLevel());
        out.println("Compile option: targetPlatform=" + options.targetPlatform());
        out.println("Compile option: strictMode=" + options.strictMode());
        out.println("Compile option: outputMountRoot=" + options.outputMountRoot());
        for (var sourcePath : result.sourcePaths()) {
            out.println("Source: " + sourcePath);
        }
        if (result.topLevelCanonicalNameMap().isEmpty()) {
            out.println("Top-level canonical map: <empty>");
        } else {
            for (var mapping : result.topLevelCanonicalNameMap().entrySet()) {
                out.println("Top-level canonical map: " + mapping.getKey() + "=" + mapping.getValue());
            }
        }
        for (var generatedFile : result.generatedFiles()) {
            out.println("Generated file: " + generatedFile);
        }
    }

    private void renderOutputLinks(@NotNull CompileResult result) {
        for (var outputLink : result.outputLinks()) {
            out.println("Output link: " + outputLink.virtualPath() + " -> " + outputLink.target());
        }
    }

    private void renderBuildLog(@NotNull PrintWriter writer, @NotNull String buildLog) {
        writer.println("Build log:");
        writer.println(buildLog);
    }

    private @NotNull String formatDiagnostic(@NotNull FrontendDiagnostic diagnostic) {
        return diagnostic.severity()
                + " "
                + diagnostic.category()
                + " "
                + Objects.requireNonNullElse(diagnostic.sourcePath(), "<unknown>")
                + formatRange(diagnostic.range())
                + ": "
                + diagnostic.message();
    }

    private @NotNull String formatRange(FrontendRange range) {
        if (range == null) {
            return "";
        }
        return ":" + range.start().line() + ":" + range.start().column();
    }

    private void renderTaskProgress(@NotNull CompileTaskSnapshot snapshot) {
        err.println("Task "
                + snapshot.taskId()
                + " "
                + snapshot.state()
                + " "
                + snapshot.stage()
                + " "
                + Objects.requireNonNullElse(snapshot.stageMessage(), ""));
    }

    private void renderEvent(@NotNull CompileTaskEvent.Indexed indexedEvent) {
        var event = indexedEvent.event();
        err.println("Event " + indexedEvent.index() + " " + event.category() + ": " + event.detail());
    }

    private int failUsage(@NotNull String message) {
        err.println("gdcc: " + message);
        return EXIT_USAGE;
    }

    private record OutputTarget(@NotNull String moduleId, @NotNull String moduleName, @NotNull Path projectPath) {
    }

    private record SourceInput(
            @NotNull String virtualPath,
            @NotNull Path logicalPath,
            @NotNull String source,
            @NotNull String displayPath
    ) {
    }

    private record ClassMap(@NotNull String source, @NotNull String canonical) {
    }

    private static final class CliInterruptedException extends RuntimeException {
    }
}
