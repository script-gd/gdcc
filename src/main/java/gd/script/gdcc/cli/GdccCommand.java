package gd.script.gdcc.cli;

import gd.script.gdcc.api.API;
import gd.script.gdcc.api.CompileOptions;
import gd.script.gdcc.api.CompileResult;
import gd.script.gdcc.api.CompileTaskEvent;
import gd.script.gdcc.api.CompileTaskSnapshot;
import gd.script.gdcc.backend.c.build.COptimizationLevel;
import gd.script.gdcc.backend.c.build.GdextensionMetadataFile;
import gd.script.gdcc.backend.c.build.TargetPlatform;
import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.exception.GdccException;
import gd.script.gdcc.frontend.FrontendClassNameContract;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnostic;
import gd.script.gdcc.frontend.diagnostic.FrontendRange;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.logger.GdccLogger;
import gd.script.gdcc.util.ConsoleOutputUtil;
import gd.script.gdcc.util.GdccVersion;
import gd.script.gdcc.util.StringUtil;
import dev.superice.gdparser.frontend.ast.ClassNameStatement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;
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
        versionProvider = GdccCommand.VersionProvider.class,
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
            description = "GDScript source files to compile into one module."
    )
    List<Path> files = new ArrayList<>();

    @Option(
            names = {"-o", "--output"},
            paramLabel = "<output>",
            description = "Output target path. Defaults to input filenames without .gd suffixes joined by underscores."
    )
    Path output;

    @Option(
            names = "--prefix",
            paramLabel = "<prefix>",
            description = "Canonical name prefix for top-level source classes."
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
            names = {"--opt", "--optimize"},
            defaultValue = "debug",
            paramLabel = "<level>",
            description = "Optimization level. Supported: debug, release. Defaults to debug."
    )
    String opt = "debug";

    @Option(
            names = "--target",
            paramLabel = "<platform>",
            description = "Target platform. Supported: windows-x86-64, windows-aarch64, linux-x86-64, linux-aarch64, linux-riscv64, android-x86-64, android-aarch64, web-wasm32. Defaults to the native host platform."
    )
    String target;

    @Option(
            names = "--project",
            paramLabel = "<project.godot>",
            description = "Godot project.godot file. When every input belongs to this project, emit <output>/<output-name>.gdextension."
    )
    Path project;

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
            var sourceInputs = sourceInputs();
            var outputTarget = outputTarget(sourceInputs);
            var gdextensionMetadataTarget = gdextensionMetadataTarget(sourceInputs, outputTarget);
            var compileOptions = compileOptions(outputTarget);
            var topLevelCanonicalNameMap = topLevelCanonicalNameMap(sourceInputs);

            api.createModule(outputTarget.moduleId(), outputTarget.moduleName());
            api.setCompileOptions(outputTarget.moduleId(), compileOptions);
            api.setTopLevelCanonicalNameMap(outputTarget.moduleId(), topLevelCanonicalNameMap);
            for (var sourceInput : sourceInputs) {
                api.putFile(outputTarget.moduleId(), sourceInput.virtualPath(), sourceInput.source(), sourceInput.displayPath());
            }

            var taskId = api.compile(outputTarget.moduleId());
            var completedSnapshot = waitForTask(taskId);
            return renderCompletedTask(completedSnapshot, gdextensionMetadataTarget);
        } catch (CliInterruptedException exception) {
            return EXIT_INTERRUPTED;
        } catch (IOException exception) {
            return failUsage("I/O failure: " + exception.getMessage());
        } catch (GdccException | IllegalArgumentException exception) {
            return failUsage(exception.getMessage());
        }
    }

    private @NotNull OutputTarget outputTarget(@NotNull List<SourceInput> sourceInputs) {
        var target = output == null ? Path.of(defaultOutputName(sourceInputs)) : output;
        if (target.toString().isBlank()) {
            throw new IllegalArgumentException("Output path must not be blank");
        }
        var normalizedOutput = target.toAbsolutePath().normalize();
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

    private @NotNull String defaultOutputName(@NotNull List<SourceInput> sourceInputs) {
        var stems = new ArrayList<String>(sourceInputs.size());
        for (var sourceInput : sourceInputs) {
            stems.add(sourceInput.defaultOutputStem());
        }
        return String.join("_", stems);
    }

    private @NotNull CompileOptions compileOptions(@NotNull OutputTarget outputTarget) {
        return new CompileOptions(
                godotVersion(),
                outputTarget.projectPath(),
                optimizationLevel(),
                targetPlatform(),
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

    private @NotNull COptimizationLevel optimizationLevel() {
        return parseEnumOption(COptimizationLevel.class, "--opt", opt);
    }

    private @NotNull TargetPlatform targetPlatform() {
        return target == null ? TargetPlatform.getNativePlatform() : parseEnumOption(TargetPlatform.class, "--target", target);
    }

    private static <T extends Enum<T>> @NotNull T parseEnumOption(
            @NotNull Class<T> enumClass,
            @NotNull String optionName,
            @NotNull String value
    ) {
        var normalizedValue = StringUtil.requireTrimmedNonBlank(value, optionName)
                .replace('-', '_')
                .toUpperCase(Locale.ROOT);
        for (var candidate : enumClass.getEnumConstants()) {
            if (candidate.name().equals(normalizedValue)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException(
                "Unsupported " + optionName + " value '" + value + "'. Supported values: " + supportedEnumValues(enumClass)
        );
    }

    private static <T extends Enum<T>> @NotNull String supportedEnumValues(@NotNull Class<T> enumClass) {
        var values = new ArrayList<String>();
        for (var candidate : enumClass.getEnumConstants()) {
            values.add(candidate.name().toLowerCase(Locale.ROOT).replace('_', '-'));
        }
        return String.join(", ", values);
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
        if (!normalizedInput.getFileName().toString().endsWith(".gd")) {
            throw new IllegalArgumentException("Input file must use .gd extension: " + input);
        }

        var source = Files.readString(normalizedInput, StandardCharsets.UTF_8);
        var virtualPath = virtualSourcePath(index, normalizedInput);
        var fileName = normalizedInput.getFileName().toString();
        var defaultOutputStem = fileName.substring(0, fileName.length() - ".gd".length());
        return new SourceInput(virtualPath, Path.of(virtualPath), normalizedInput, defaultOutputStem, source, input.toString());
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
                var sourceName = resolveTopLevelSourceName(sourceInput);
                if (sourceName == null) {
                    continue;
                }
                mappings.put(sourceName, prefix + sourceName);
            }
        }

        var explicitSourceNames = new HashSet<String>();
        for (var classMap : classMaps) {
            var mapping = explicitClassMap(classMap);
            if (!explicitSourceNames.add(mapping.source())) {
                throw new IllegalArgumentException("Duplicate --class-map source name: " + mapping.source());
            }
            // Reinsert overridden generated entries so explicit mappings appear at their final
            // override position in the stable verbose-output order.
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

    private @Nullable String resolveTopLevelSourceName(@NotNull SourceInput sourceInput) {
        var diagnosticManager = new DiagnosticManager();
        var unit = parserService.parseUnit(sourceInput.logicalPath(), sourceInput.source(), diagnosticManager);
        if (diagnosticManager.snapshot().hasErrors()) {
            return null;
        }
        for (var statement : unit.ast().statements()) {
            if (statement instanceof ClassNameStatement classNameStatement) {
                var className = StringUtil.trimToNull(classNameStatement.name());
                if (className != null) {
                    return className;
                }
                break;
            }
        }
        return FrontendClassNameContract.deriveDefaultTopLevelSourceName(sourceInput.logicalPath());
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

    private @Nullable GdextensionMetadataTarget gdextensionMetadataTarget(
            @NotNull List<SourceInput> sourceInputs,
            @NotNull OutputTarget outputTarget
    ) {
        if (project == null) {
            return null;
        }

        var projectFile = project.toAbsolutePath().normalize();
        var projectFileName = projectFile.getFileName();
        if (projectFileName == null || !"project.godot".equals(projectFileName.toString())) {
            throw new IllegalArgumentException("--project must point to a project.godot file: " + project);
        }
        if (Files.notExists(projectFile)) {
            throw new IllegalArgumentException("--project file does not exist: " + project);
        }
        if (!Files.isRegularFile(projectFile)) {
            throw new IllegalArgumentException("--project path is not a regular file: " + project);
        }
        for (var sourceInput : sourceInputs) {
            if (!Objects.equals(nearestProjectFile(sourceInput.hostPath().getParent()), projectFile)) {
                return null;
            }
        }
        return new GdextensionMetadataTarget(
                outputTarget.projectPath().resolve(outputTarget.moduleName() + ".gdextension")
        );
    }

    private @Nullable Path nearestProjectFile(@NotNull Path sourceParent) {
        for (Path current = sourceParent.toAbsolutePath().normalize(); current != null; current = current.getParent()) {
            var candidate = current.resolve("project.godot");
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        return null;
    }

    private int renderCompletedTask(
            @NotNull CompileTaskSnapshot snapshot,
            @Nullable GdextensionMetadataTarget gdextensionMetadataTarget
    ) throws IOException {
        var result = Objects.requireNonNull(snapshot.result(), "completed compile task must carry a result");
        var metadataPath = result.success() && gdextensionMetadataTarget != null
                ? writeGdextensionMetadata(result, gdextensionMetadataTarget)
                : null;
        renderResult(snapshot.moduleId(), result, metadataPath);
        if (result.success()) {
            return 0;
        }
        return result.outcome() == CompileResult.Outcome.CANCELED ? EXIT_INTERRUPTED : EXIT_COMPILE_FAILED;
    }

    private @Nullable Path writeGdextensionMetadata(
            @NotNull CompileResult result,
            @NotNull GdextensionMetadataTarget target
    ) throws IOException {
        if (result.artifacts().isEmpty()) {
            return null;
        }
        return GdextensionMetadataFile.write(
                target.metadataPath(),
                result.artifacts().getFirst(),
                result.compileOptions().optimizationLevel(),
                result.compileOptions().targetPlatform()
        );
    }

    private void renderResult(
            @NotNull String moduleId,
            @NotNull CompileResult result,
            @Nullable Path gdextensionMetadataPath
    ) {
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
            if (gdextensionMetadataPath != null) {
                out.println("Metadata: " + gdextensionMetadataPath);
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
            @NotNull Path hostPath,
            @NotNull String defaultOutputStem,
            @NotNull String source,
            @NotNull String displayPath
    ) {
    }

    private record ClassMap(@NotNull String source, @NotNull String canonical) {
    }

    private record GdextensionMetadataTarget(@NotNull Path metadataPath) {
    }

    private static final class CliInterruptedException extends RuntimeException {
    }

    public static final class VersionProvider implements IVersionProvider {
        @Override
        public String @NotNull [] getVersion() {
            return new String[]{GdccVersion.displayText()};
        }
    }
}
