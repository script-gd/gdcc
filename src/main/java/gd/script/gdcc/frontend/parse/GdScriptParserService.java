package gd.script.gdcc.frontend.parse;

import dev.superice.gdparser.frontend.ast.*;
import dev.superice.gdparser.frontend.lowering.CstToAstMapper;
import dev.superice.gdparser.infra.treesitter.GdParserFacade;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnostic;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnosticSeverity;
import gd.script.gdcc.frontend.diagnostic.FrontendRange;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/// Parse GDScript source into AST and map tolerant lowering diagnostics into GDCC frontend diagnostics.
public final class GdScriptParserService {
    private final @NotNull GdParserFacade parserFacade;
    private final @NotNull CstToAstMapper cstToAstMapper;

    public GdScriptParserService() {
        this(GdParserFacade.withDefaultLanguage(), new CstToAstMapper());
    }

    public GdScriptParserService(@NotNull GdParserFacade parserFacade, @NotNull CstToAstMapper cstToAstMapper) {
        this.parserFacade = Objects.requireNonNull(parserFacade, "parserFacade must not be null");
        this.cstToAstMapper = Objects.requireNonNull(cstToAstMapper, "cstToAstMapper must not be null");
    }

    /// Parses one source unit and reports this parse call's diagnostics into the shared manager.
    ///
    /// The returned `FrontendSourceUnit` now contains only source text and AST. Parse diagnostics
    /// live exclusively in the shared manager, so callers that need a phase-local parse view must
    /// snapshot the manager themselves at the desired boundary.
    public @NotNull FrontendSourceUnit parseUnit(
            @NotNull Path sourcePath,
            @NotNull String source,
            @NotNull DiagnosticManager diagnosticManager
    ) {
        Objects.requireNonNull(sourcePath, "sourcePath must not be null");
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(diagnosticManager, "diagnosticManager must not be null");
        try {
            var root = parserFacade.parseCstRoot(source);
            var mappingResult = cstToAstMapper.map(source, root);
            var parseDiagnostics = mappingResult.diagnostics().stream()
                    .map(diagnostic -> new FrontendDiagnostic(
                            switch (diagnostic.severity()) {
                                case ERROR -> FrontendDiagnosticSeverity.ERROR;
                                case WARNING -> FrontendDiagnosticSeverity.WARNING;
                            },
                            "parse.lowering",
                            diagnostic.message(),
                            FrontendDiagnostic.sourcePathText(sourcePath),
                            FrontendRange.fromAstRange(diagnostic.range())
                    ))
                    .toList();
            diagnosticManager.reportAll(parseDiagnostics);
            return new FrontendSourceUnit(sourcePath, source, mappingResult.ast());
        } catch (RuntimeException exception) {
            var parseDiagnostics = List.of(FrontendDiagnostic.error(
                    "parse.internal",
                    "Unexpected parser failure: " + exception.getMessage(),
                    FrontendDiagnostic.sourcePathText(sourcePath),
                    null
            ));
            diagnosticManager.reportAll(parseDiagnostics);
            // The unit carries an explicit parse-failed flag so the class skeleton excludes it
            // instead of synthesizing a fictional empty top-level script class
            // (`frontend_lsp_foundation_implementation.md` §2.2.5).
            return new FrontendSourceUnit(sourcePath, source, emptySourceFile(), true);
        }
    }

    private @NotNull SourceFile emptySourceFile() {
        return new SourceFile(
                List.of(),
                new Range(0, 0, new Point(0, 0), new Point(0, 0))
        );
    }
}
