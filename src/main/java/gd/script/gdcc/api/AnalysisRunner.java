package gd.script.gdcc.api;

import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.diagnostic.DiagnosticSnapshot;
import gd.script.gdcc.frontend.lowering.FrontendLoweringPassManager;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.FrontendSourceUnit;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.frontend.sema.FrontendAnalysisData;
import gd.script.gdcc.frontend.sema.analyzer.FrontendSemanticAnalyzer;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.lir.LirModule;
import gd.script.gdcc.scope.ClassRegistry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/// Executes one synchronous analyze-only request against a frozen module snapshot.
///
/// The runner reuses the compiler's parser, shared semantic pipeline, and (on request) the
/// lowering pipeline, but never enters the C backend: no C code is generated and no native build
/// starts. Parse and semantic problems surface as diagnostics instead of aborting the request,
/// matching editor-plugin flows that show warnings and errors without producing artifacts.
///
/// Semantic analyzers and lowering passes keep per-run state (for example lambda name counters and
/// scope reverse indexes), so every request constructs fresh pipeline instances and never shares a
/// `FrontendLoweringPassManager` with compile tasks, which also construct their own per task.
final class AnalysisRunner {
    private static final @NotNull DiagnosticSnapshot EMPTY_DIAGNOSTICS = new DiagnosticSnapshot(List.of());

    private final @NotNull GdScriptParserService parserService;
    private final @NotNull SemanticRun semanticRun;
    private final @NotNull LoweringRun loweringRun;

    AnalysisRunner(@NotNull GdScriptParserService parserService) {
        this(
                parserService,
                (module, registry, manager) -> new FrontendSemanticAnalyzer().analyze(module, registry, manager),
                (module, registry, manager) -> new FrontendLoweringPassManager().lower(module, registry, manager)
        );
    }

    /// Package-private seam constructor: tests inject failing pipeline stages to pin the
    /// unexpected-exception containment contract (R14) without touching production behavior.
    /// Both stages still construct fresh pipeline instances per call in production.
    AnalysisRunner(
            @NotNull GdScriptParserService parserService,
            @NotNull SemanticRun semanticRun,
            @NotNull LoweringRun loweringRun
    ) {
        this.parserService = Objects.requireNonNull(parserService, "parserService must not be null");
        this.semanticRun = Objects.requireNonNull(semanticRun, "semanticRun must not be null");
        this.loweringRun = Objects.requireNonNull(loweringRun, "loweringRun must not be null");
    }

    /// One shared semantic pipeline run. Per-run state stays inside the constructed analyzer.
    @FunctionalInterface
    interface SemanticRun {
        @NotNull FrontendAnalysisData run(
                @NotNull FrontendModule module,
                @NotNull ClassRegistry registry,
                @NotNull DiagnosticManager diagnostics
        );
    }

    /// One lowering-verification run against an isolated registry/diagnostic generation.
    @FunctionalInterface
    interface LoweringRun {
        @Nullable LirModule run(
                @NotNull FrontendModule module,
                @NotNull ClassRegistry registry,
                @NotNull DiagnosticManager diagnostics
        );
    }

    @NotNull AnalysisResult analyze(
            @NotNull ModuleState.CompileRequest request,
            @NotNull AnalyzeOptions analyzeOptions
    ) {
        return analyzeRich(request, analyzeOptions).result();
    }

    /// Runs the analysis pipeline and returns the public result plus the semantic payload used for
    /// snapshot publication. The payload is present exactly when the shared semantic pipeline
    /// completed, including when parse errors were tolerated into partial semantic facts.
    ///
    /// Fault-tolerance contract (`frontend_lsp_foundation_implementation.md` §2.2):
    /// - parse errors no longer short-circuit the pipeline; the shared semantic analysis runs on
    ///   the surviving AST and its facts become the snapshot content;
    /// - unexpected exceptions from parsing or the shared semantic run collapse the whole run into
    ///   `INTERNAL_FAILED` with no half-committed payload (patch transactions are not atomic, R14);
    /// - with `includeLowering`, parse errors skip lowering entirely (`FAILED`, no
    ///   `sema.compile_check`), while a clean parse runs lowering verification on an isolated fresh
    ///   `ClassRegistry`/`DiagnosticManager` generation so it can never rewrite shared-run facts;
    ///   lowering failures or exceptions only degrade `loweringStatus`, never the shared payload.
    @NotNull AnalysisRunResult analyzeRich(
            @NotNull ModuleState.CompileRequest request,
            @NotNull AnalyzeOptions analyzeOptions
    ) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(analyzeOptions, "analyzeOptions must not be null");
        var sourcePaths = request.sourceSnapshots().stream()
                .map(ModuleState.SourceSnapshot::displayPath)
                .toList();
        if (request.failure() != null) {
            // Frozen source collection only fails on broken or cyclic virtual links.
            return new AnalysisRunResult(
                    failureResult(
                            AnalysisResult.Outcome.SOURCE_COLLECTION_FAILED,
                            request,
                            analyzeOptions,
                            sourcePaths,
                            EMPTY_DIAGNOSTICS,
                            request.failure().message()
                    ),
                    null
            );
        }
        if (request.sourceSnapshots().isEmpty()) {
            return new AnalysisRunResult(
                    failureResult(
                            AnalysisResult.Outcome.SOURCE_COLLECTION_FAILED,
                            request,
                            analyzeOptions,
                            sourcePaths,
                            EMPTY_DIAGNOSTICS,
                            "Module '" + request.moduleId() + "' has no .gd/.gd3 source files to analyze"
                    ),
                    null
            );
        }

        var diagnostics = new DiagnosticManager();
        var units = new ArrayList<FrontendSourceUnit>(request.sourceSnapshots().size());
        try {
            for (var sourceSnapshot : request.sourceSnapshots()) {
                units.add(parserService.parseUnit(
                        sourceSnapshot.logicalPath(),
                        sourceSnapshot.source(),
                        diagnostics
                ));
            }
        } catch (RuntimeException exception) {
            // Per-unit parser failures are recovered inside parseUnit as `parse.internal`; an
            // escaped exception means the parser service contract itself broke.
            return new AnalysisRunResult(
                    failureResult(
                            AnalysisResult.Outcome.INTERNAL_FAILED,
                            request,
                            analyzeOptions,
                            sourcePaths,
                            remapDiagnosticSourcePaths(request, diagnostics.snapshot()),
                            "Source parsing failed unexpectedly: " + exception.getMessage()
                    ),
                    null
            );
        }
        // Parse-phase diagnostics captured once, before semantic phases append into the same
        // manager; the lowering verification run imports exactly this prefix (see below).
        var parsePhaseDiagnostics = diagnostics.snapshot();
        var remappedParseDiagnostics = remapDiagnosticSourcePaths(request, parsePhaseDiagnostics);

        final ClassRegistry classRegistry;
        try {
            classRegistry = new ClassRegistry(ExtensionApiLoader.loadVersion(request.compileOptions().godotVersion()));
        } catch (IOException exception) {
            return new AnalysisRunResult(
                    failureResult(
                            AnalysisResult.Outcome.INTERNAL_FAILED,
                            request,
                            analyzeOptions,
                            sourcePaths,
                            remappedParseDiagnostics,
                            "Godot extension metadata for "
                                    + request.compileOptions().godotVersion()
                                    + " could not be loaded: "
                                    + exception.getMessage()
                    ),
                    null
            );
        }
        var frontendModule = new FrontendModule(
                request.moduleName(),
                units,
                request.topLevelCanonicalNameMap()
        );

        // The shared semantic entrypoint tolerates parser-damaged subtrees (they are skipped, not
        // fatal) and reports warnings/errors without the compile-only gate, so editor callers never
        // see `sema.compile_check` diagnostics from this path. Its result feeds the snapshot.
        final FrontendAnalysisData analysisData;
        try {
            analysisData = semanticRun.run(frontendModule, classRegistry, diagnostics);
        } catch (RuntimeException exception) {
            return new AnalysisRunResult(
                    failureResult(
                            AnalysisResult.Outcome.INTERNAL_FAILED,
                            request,
                            analyzeOptions,
                            sourcePaths,
                            remapDiagnosticSourcePaths(request, diagnostics.snapshot()),
                            "Semantic analysis failed unexpectedly: " + exception.getMessage()
                    ),
                    null
            );
        }
        var sharedDiagnostics = remapDiagnosticSourcePaths(request, diagnostics.snapshot());

        if (!analyzeOptions.includeLowering()) {
            return new AnalysisRunResult(
                    completedResult(
                            request,
                            analyzeOptions,
                            sourcePaths,
                            sharedDiagnostics,
                            AnalysisResult.LoweringStatus.NOT_REQUESTED
                    ),
                    new AnalysisRunResult.Payload(frontendModule, analysisData, classRegistry, sharedDiagnostics)
            );
        }
        if (remappedParseDiagnostics.hasErrors()) {
            // Lowering requires a well-formed AST; with parse errors the tolerated semantic facts
            // still publish, but lowering verification is meaningless and must not run.
            return new AnalysisRunResult(
                    completedResult(
                            request,
                            analyzeOptions,
                            sourcePaths,
                            sharedDiagnostics,
                            AnalysisResult.LoweringStatus.FAILED
                    ),
                    new AnalysisRunResult.Payload(frontendModule, analysisData, classRegistry, sharedDiagnostics)
            );
        }

        // Lowering verification reruns semantic analysis through the compile-ready gate, which
        // would mutate the shared registry via `ClassRegistry.addGdccClass` replacement semantics.
        // It therefore runs on an isolated fresh registry/diagnostic manager generation: the shared
        // run's object identities stay intact, and the lowering manager imports only the parse-phase
        // diagnostics once (no parse errors here, but warnings must not be lost) before rerunning
        // semantic analysis itself.
        var loweringStatus = AnalysisResult.LoweringStatus.FAILED;
        var loweringDiagnostics = new DiagnosticManager();
        try {
            var loweringRegistry = new ClassRegistry(
                    ExtensionApiLoader.loadVersion(request.compileOptions().godotVersion())
            );
            loweringDiagnostics.reportAll(parsePhaseDiagnostics.asList());
            var lowered = loweringRun.run(frontendModule, loweringRegistry, loweringDiagnostics);
            if (lowered != null && !remapDiagnosticSourcePaths(request, loweringDiagnostics.snapshot()).hasErrors()) {
                loweringStatus = AnalysisResult.LoweringStatus.SUCCEEDED;
            }
        } catch (IOException | RuntimeException exception) {
            // A failed or crashed lowering verification only degrades `loweringStatus`; the shared
            // analysis payload above stays valid and publishes normally. Keep the crash observable:
            // without this diagnostic a crashed verification would surface as `FAILED` with empty
            // lowering-side diagnostics, indistinguishable from an ordinary validation failure.
            loweringDiagnostics.error(
                    "sema.lowering",
                    "Lowering verification failed unexpectedly: " + exception.getMessage(),
                    null,
                    null
            );
            loweringStatus = AnalysisResult.LoweringStatus.FAILED;
        }
        return new AnalysisRunResult(
                completedResult(
                        request,
                        analyzeOptions,
                        sourcePaths,
                        remapDiagnosticSourcePaths(request, loweringDiagnostics.snapshot()),
                        loweringStatus
                ),
                new AnalysisRunResult.Payload(frontendModule, analysisData, classRegistry, sharedDiagnostics)
        );
    }

    private static @NotNull AnalysisResult.LoweringStatus unverifiedLoweringStatus(@NotNull AnalyzeOptions analyzeOptions) {
        return analyzeOptions.includeLowering()
                ? AnalysisResult.LoweringStatus.FAILED
                : AnalysisResult.LoweringStatus.NOT_REQUESTED;
    }

    private static @NotNull AnalysisResult completedResult(
            @NotNull ModuleState.CompileRequest request,
            @NotNull AnalyzeOptions analyzeOptions,
            @NotNull List<String> sourcePaths,
            @NotNull DiagnosticSnapshot diagnostics,
            @NotNull AnalysisResult.LoweringStatus loweringStatus
    ) {
        return new AnalysisResult(
                AnalysisResult.Outcome.COMPLETED,
                analyzeOptions,
                request.compileOptions().godotVersion(),
                request.topLevelCanonicalNameMap(),
                sourcePaths,
                diagnostics,
                null,
                loweringStatus,
                request.moduleGeneration(),
                request.contentVersion()
        );
    }

    private static @NotNull AnalysisResult failureResult(
            @NotNull AnalysisResult.Outcome outcome,
            @NotNull ModuleState.CompileRequest request,
            @NotNull AnalyzeOptions analyzeOptions,
            @NotNull List<String> sourcePaths,
            @NotNull DiagnosticSnapshot diagnostics,
            @NotNull String failureMessage
    ) {
        return new AnalysisResult(
                outcome,
                analyzeOptions,
                request.compileOptions().godotVersion(),
                request.topLevelCanonicalNameMap(),
                sourcePaths,
                diagnostics,
                failureMessage,
                unverifiedLoweringStatus(analyzeOptions),
                request.moduleGeneration(),
                request.contentVersion()
        );
    }

    private static @NotNull DiagnosticSnapshot remapDiagnosticSourcePaths(
            @NotNull ModuleState.CompileRequest request,
            @NotNull DiagnosticSnapshot diagnostics
    ) {
        var displayPathsByLogicalPath = request.sourceSnapshots().stream()
                .collect(Collectors.toMap(
                        sourceSnapshot -> DiagnosticSourcePathRemapper.logicalPathKey(sourceSnapshot.logicalPath()),
                        ModuleState.SourceSnapshot::displayPath,
                        (first, _) -> first
                ));
        return DiagnosticSourcePathRemapper.remap(displayPathsByLogicalPath, diagnostics);
    }
}
