package gd.script.gdcc.frontend.parse;

import dev.superice.gdparser.frontend.ast.AstMappingResult;
import dev.superice.gdparser.frontend.ast.Point;
import dev.superice.gdparser.frontend.ast.Range;
import dev.superice.gdparser.frontend.ast.SourceFile;
import dev.superice.gdparser.frontend.gdscript.GdScriptParserFacade;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnostic;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnosticSeverity;
import gd.script.gdcc.frontend.diagnostic.FrontendRange;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/// Parses GDScript tolerantly and publishes the parser's diagnostics into the frontend pipeline.
public final class GdScriptParserService {
    private final Function<String, AstMappingResult> parseBackend;

    public GdScriptParserService() {
        this(new GdScriptParserFacade()::parse);
    }

    GdScriptParserService(@NotNull Function<String, AstMappingResult> parseBackend) {
        this.parseBackend = Objects.requireNonNull(parseBackend, "parseBackend must not be null");
    }

    /// Reports each diagnostic and retains its attribution alongside the AST that owns it.
    public @NotNull FrontendSourceUnit parseUnit(
            @NotNull Path sourcePath,
            @NotNull String source,
            @NotNull DiagnosticManager diagnosticManager
    ) {
        Objects.requireNonNull(sourcePath, "sourcePath must not be null");
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(diagnosticManager, "diagnosticManager must not be null");
        var sourcePathText = FrontendDiagnostic.sourcePathText(sourcePath);
        try {
            var mappingResult = parseBackend.apply(source);
            var parseDiagnostics = new ArrayList<FrontendDiagnostic>(mappingResult.diagnostics().size());
            var parseRecoveries = new ArrayList<FrontendParseRecovery>(mappingResult.diagnostics().size());
            for (var diagnostic : mappingResult.diagnostics()) {
                var code = diagnostic.code();
                var severity = switch (diagnostic.severity()) {
                    case ERROR -> FrontendDiagnosticSeverity.ERROR;
                    case WARNING -> FrontendDiagnosticSeverity.WARNING;
                };
                var link = diagnostic.recovery();
                var recovery = new FrontendParseRecovery(
                        code == null || code.isBlank() ? "parse.lowering" : code,
                        severity,
                        diagnostic.message(),
                        FrontendRange.fromAstRange(diagnostic.range()),
                        link == null ? null : link.owner(),
                        link == null ? null : link.facet()
                );
                parseRecoveries.add(recovery);
                parseDiagnostics.add(recovery.toDiagnostic(sourcePathText));
            }
            var unit = new FrontendSourceUnit(sourcePath, source, mappingResult.ast(), false, parseRecoveries);
            diagnosticManager.reportAll(parseDiagnostics);
            return unit;
        } catch (RuntimeException exception) {
            diagnosticManager.report(FrontendDiagnostic.error(
                    FrontendParseRecovery.PARSE_INTERNAL_CATEGORY,
                    "Unexpected parser failure: " + exception.getMessage(),
                    sourcePathText,
                    null
            ));
            return new FrontendSourceUnit(sourcePath, source, emptySourceFile(), true);
        }
    }

    private static @NotNull SourceFile emptySourceFile() {
        return new SourceFile(
                List.of(),
                new Range(0, 0, new Point(0, 0), new Point(0, 0))
        );
    }
}
