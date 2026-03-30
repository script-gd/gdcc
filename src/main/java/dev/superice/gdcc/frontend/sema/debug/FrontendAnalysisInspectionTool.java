package dev.superice.gdcc.frontend.sema.debug;

import dev.superice.gdparser.frontend.ast.AttributeCallStep;
import dev.superice.gdparser.frontend.ast.Block;
import dev.superice.gdparser.frontend.ast.CallExpression;
import dev.superice.gdparser.frontend.ast.ClassDeclaration;
import dev.superice.gdparser.frontend.ast.ConstructorDeclaration;
import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.LambdaExpression;
import dev.superice.gdparser.frontend.ast.MatchStatement;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.Parameter;
import dev.superice.gdparser.frontend.ast.Range;
import dev.superice.gdparser.frontend.ast.SourceFile;
import dev.superice.gdparser.frontend.ast.Statement;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.diagnostic.FrontendDiagnostic;
import dev.superice.gdcc.frontend.diagnostic.FrontendDiagnosticSeverity;
import dev.superice.gdcc.frontend.diagnostic.FrontendRange;
import dev.superice.gdcc.frontend.parse.FrontendModule;
import dev.superice.gdcc.frontend.parse.FrontendSourceUnit;
import dev.superice.gdcc.frontend.parse.GdScriptParserService;
import dev.superice.gdcc.frontend.sema.FrontendAnalysisData;
import dev.superice.gdcc.frontend.sema.FrontendBinding;
import dev.superice.gdcc.frontend.sema.FrontendBindingKind;
import dev.superice.gdcc.frontend.sema.FrontendExpressionType;
import dev.superice.gdcc.frontend.sema.analyzer.FrontendSemanticAnalyzer;
import dev.superice.gdcc.scope.ClassDef;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.scope.FunctionDef;
import dev.superice.gdcc.scope.ParameterDef;
import dev.superice.gdcc.scope.PropertyDef;
import dev.superice.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

/// Read-only inspection helper that renders frontend semantic facts into a human-auditable text
/// report.
///
/// The tool intentionally consumes the published parse/analyze pipeline as-is. It never writes back
/// into `FrontendAnalysisData` and it keeps display-only derivations explicit in the rendered text.
public final class FrontendAnalysisInspectionTool {
    private static final @NotNull String REPORT_FORMAT = "frontend-analysis-text-v1";

    private final @NotNull GdScriptParserService parserService;
    private final @NotNull FrontendSemanticAnalyzer semanticAnalyzer;
    private final @NotNull ClassRegistry classRegistry;

    public FrontendAnalysisInspectionTool(@NotNull ClassRegistry classRegistry) {
        this(new GdScriptParserService(), new FrontendSemanticAnalyzer(), classRegistry);
    }

    public FrontendAnalysisInspectionTool(
            @NotNull GdScriptParserService parserService,
            @NotNull FrontendSemanticAnalyzer semanticAnalyzer,
            @NotNull ClassRegistry classRegistry
    ) {
        this.parserService = Objects.requireNonNull(parserService, "parserService must not be null");
        this.semanticAnalyzer = Objects.requireNonNull(semanticAnalyzer, "semanticAnalyzer must not be null");
        this.classRegistry = Objects.requireNonNull(classRegistry, "classRegistry must not be null");
    }

    public @NotNull InspectionResult inspectSingleScript(
            @NotNull String moduleName,
            @NotNull Path sourcePath,
            @NotNull String source
    ) {
        Objects.requireNonNull(moduleName, "moduleName must not be null");
        Objects.requireNonNull(sourcePath, "sourcePath must not be null");
        Objects.requireNonNull(source, "source must not be null");

        var diagnosticManager = new DiagnosticManager();
        var unit = parserService.parseUnit(sourcePath, source, diagnosticManager);
        var module = FrontendModule.singleUnit(moduleName, unit);
        var analysisData = semanticAnalyzer.analyze(module, classRegistry, diagnosticManager);
        return new InspectionResult(module, unit, analysisData, renderSingleUnitReport(module, unit, analysisData));
    }

    public @NotNull String renderSingleUnitReport(
            @NotNull FrontendModule module,
            @NotNull FrontendSourceUnit unit,
            @NotNull FrontendAnalysisData analysisData
    ) {
        return new ReportRenderer(module, unit, analysisData).render();
    }

    public record InspectionResult(
            @NotNull FrontendModule module,
            @NotNull FrontendSourceUnit unit,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull String report
    ) {
        public InspectionResult {
            Objects.requireNonNull(module, "module must not be null");
            Objects.requireNonNull(unit, "unit must not be null");
            Objects.requireNonNull(analysisData, "analysisData must not be null");
            Objects.requireNonNull(report, "report must not be null");
        }
    }

    private static final class ReportRenderer {
        private static final @NotNull Comparator<Range> RANGE_COMPARATOR = Comparator
                .comparingInt(Range::startByte)
                .thenComparingInt(Range::endByte);

        private final @NotNull FrontendModule module;
        private final @NotNull FrontendSourceUnit unit;
        private final @NotNull FrontendAnalysisData analysisData;
        private final @NotNull SourceTextIndex sourceTextIndex;
        private final @NotNull IdentityHashMap<Node, Node> parents = new IdentityHashMap<>();
        private final @NotNull IdentityHashMap<Node, Integer> visitOrder = new IdentityHashMap<>();
        private final @NotNull List<Expression> expressions = new ArrayList<>();
        private final @NotNull List<Node> callSites = new ArrayList<>();
        private final @NotNull Map<Expression, ExpressionDisplayFact> expressionFacts = new IdentityHashMap<>();
        private final @NotNull Map<Node, CallDisplayFact> callFacts = new IdentityHashMap<>();
        private final @NotNull Map<Node, List<ExpressionDisplayFact>> expressionsByAnchor = new IdentityHashMap<>();
        private final @NotNull Map<Expression, List<CallDisplayFact>> callsByOwnerExpression = new IdentityHashMap<>();
        private final @NotNull Map<Node, List<String>> attachmentDiagnostics = new IdentityHashMap<>();
        private final @NotNull Map<RangeKey, List<Node>> callSitesByRange = new HashMap<>();
        private final @NotNull Map<RangeKey, List<Expression>> expressionsByRange = new HashMap<>();
        private final @NotNull List<DiagnosticDisplayFact> diagnostics = new ArrayList<>();
        private int nextVisitIndex;

        private ReportRenderer(
                @NotNull FrontendModule module,
                @NotNull FrontendSourceUnit unit,
                @NotNull FrontendAnalysisData analysisData
        ) {
            this.module = Objects.requireNonNull(module, "module must not be null");
            this.unit = Objects.requireNonNull(unit, "unit must not be null");
            this.analysisData = Objects.requireNonNull(analysisData, "analysisData must not be null");
            sourceTextIndex = new SourceTextIndex(unit.source());
            indexAst(unit.ast(), null);
            buildExpressionFacts();
            buildCallFacts();
            buildDiagnosticFacts();
        }

        public @NotNull String render() {
            var report = new StringBuilder(8_192);
            report.append("FORMAT ").append(REPORT_FORMAT).append('\n');
            report.append("FILE ").append(unit.path()).append('\n');
            report.append("MODULE ").append(module.moduleName()).append('\n');
            report.append("SUMMARY diagnostics=").append(diagnostics.size())
                    .append(" expressions=").append(expressions.size())
                    .append(" publishedExpressions=").append(countPublishedExpressions())
                    .append(" callSites=").append(callSites.size())
                    .append(" publishedCalls=").append(countPublishedCalls())
                    .append(" derivedCalls=").append(countDerivedCalls())
                    .append('\n')
                    .append('\n');

            report.append("DIAGNOSTICS\n");
            if (diagnostics.isEmpty()) {
                report.append("<none>\n");
            } else {
                for (var diagnostic : diagnostics) {
                    report.append(diagnostic.id())
                            .append(' ')
                            .append(diagnostic.diagnostic().severity().name())
                            .append(' ')
                            .append(diagnostic.diagnostic().category())
                            .append(' ')
                            .append(diagnostic.rangeText())
                            .append(' ')
                            .append(diagnostic.diagnostic().message())
                            .append('\n');
                }
            }
            report.append('\n');

            report.append("SOURCE\n");
            renderSourceFile(unit.ast(), report);
            return report.toString();
        }

        private void renderSourceFile(@NotNull SourceFile sourceFile, @NotNull StringBuilder report) {
            report.append("== source file ==\n");
            renderAttachmentDiagnostics(sourceFile, report, 0);
            renderDirectAnchors(sourceFile.statements(), report, 0);
            for (var statement : sourceFile.statements()) {
                if (statement instanceof ClassDeclaration classDeclaration) {
                    renderClassDeclaration(classDeclaration, report, 0);
                    continue;
                }
                if (statement instanceof FunctionDeclaration functionDeclaration) {
                    renderFunctionDeclaration(functionDeclaration, report, 0);
                    continue;
                }
                if (statement instanceof ConstructorDeclaration constructorDeclaration) {
                    renderConstructorDeclaration(constructorDeclaration, report, 0);
                }
            }
        }

        private void renderClassDeclaration(@NotNull ClassDeclaration classDeclaration, @NotNull StringBuilder report, int indent) {
            appendIndent(report, indent);
            report.append("== class ").append(classDeclaration.name()).append(" ==\n");
            renderAttachmentDiagnostics(classDeclaration, report, indent + 2);

            var propertyStatements = classDeclaration.body().statements().stream()
                    .filter(statement -> !(statement instanceof ClassDeclaration)
                            && !(statement instanceof FunctionDeclaration)
                            && !(statement instanceof ConstructorDeclaration))
                    .filter(this::hasAnchorContent)
                    .toList();
            if (!propertyStatements.isEmpty()) {
                appendIndent(report, indent);
                report.append("== class body property initializers ==\n");
                for (var propertyStatement : propertyStatements) {
                    renderAnchor(propertyStatement, report, indent);
                }
            }

            for (var statement : classDeclaration.body().statements()) {
                if (statement instanceof ClassDeclaration innerClass) {
                    renderClassDeclaration(innerClass, report, indent + 2);
                    continue;
                }
                if (statement instanceof FunctionDeclaration functionDeclaration) {
                    renderFunctionDeclaration(functionDeclaration, report, indent + 2);
                    continue;
                }
                if (statement instanceof ConstructorDeclaration constructorDeclaration) {
                    renderConstructorDeclaration(constructorDeclaration, report, indent + 2);
                }
            }
        }

        private void renderFunctionDeclaration(
                @NotNull FunctionDeclaration functionDeclaration,
                @NotNull StringBuilder report,
                int indent
        ) {
            appendIndent(report, indent);
            report.append("== func ")
                    .append(functionDeclaration.name())
                    .append(renderParameterList(functionDeclaration.parameters()))
                    .append(" ==\n");
            renderAttachmentDiagnostics(functionDeclaration, report, indent + 2);
            renderCallableBody(functionDeclaration.parameters(), functionDeclaration.body(), report, indent + 2);
        }

        private void renderConstructorDeclaration(
                @NotNull ConstructorDeclaration constructorDeclaration,
                @NotNull StringBuilder report,
                int indent
        ) {
            appendIndent(report, indent);
            report.append("== ctor _init")
                    .append(renderParameterList(constructorDeclaration.parameters()))
                    .append(" ==\n");
            renderAttachmentDiagnostics(constructorDeclaration, report, indent + 2);
            renderCallableBody(constructorDeclaration.parameters(), constructorDeclaration.body(), report, indent + 2);
        }

        private void renderCallableBody(
                @NotNull List<Parameter> parameters,
                @Nullable Block body,
                @NotNull StringBuilder report,
                int indent
        ) {
            for (var parameter : parameters) {
                if (parameter.defaultValue() != null && hasAnchorContent(parameter)) {
                    renderAnchor(parameter, report, indent);
                }
            }
            if (body == null) {
                return;
            }
            renderDirectAnchors(body.statements(), report, indent);
        }

        private void renderDirectAnchors(
                @NotNull List<? extends Statement> statements,
                @NotNull StringBuilder report,
                int indent
        ) {
            for (var statement : statements) {
                if (statement instanceof ClassDeclaration
                        || statement instanceof FunctionDeclaration
                        || statement instanceof ConstructorDeclaration) {
                    continue;
                }
                if (!hasAnchorContent(statement)) {
                    continue;
                }
                renderAnchor(statement, report, indent);
            }
        }

        private boolean hasAnchorContent(@NotNull Node anchor) {
            return expressionsByAnchor.containsKey(anchor)
                    || (attachmentDiagnostics.containsKey(anchor) && !attachmentDiagnostics.get(anchor).isEmpty());
        }

        private void renderAnchor(@NotNull Node anchor, @NotNull StringBuilder report, int indent) {
            var anchorSnippet = sourceTextIndex.snippet(anchor.range());
            appendIndent(report, indent);
            if (anchorSnippet.isInline()) {
                report.append("[L")
                        .append(anchor.range().startPoint().row() + 1)
                        .append("] ")
                        .append(anchorSnippet.inlineText())
                        .append('\n');
            } else {
                report.append("[L")
                        .append(anchor.range().startPoint().row() + 1)
                        .append("-L")
                        .append(anchor.range().endPoint().row() + 1)
                        .append("]\n");
                appendSnippetLines(report, anchorSnippet.blockLines(), indent + 2);
            }
            renderAttachmentDiagnostics(anchor, report, indent + 2);
            for (var expressionFact : expressionsByAnchor.getOrDefault(anchor, List.of())) {
                renderExpressionFact(expressionFact, report, indent + 2);
            }
        }

        private void renderExpressionFact(
                @NotNull ExpressionDisplayFact expressionFact,
                @NotNull StringBuilder report,
                int indent
        ) {
            appendIndent(report, indent);
            report.append(expressionFact.id())
                    .append(' ')
                    .append(expressionFact.rangeText())
                    .append(' ')
                    .append(expressionFact.expression().getClass().getSimpleName());
            if (expressionFact.snippet().isInline()) {
                report.append(' ').append('`').append(expressionFact.snippet().inlineText()).append('`');
            }
            report.append('\n');
            if (!expressionFact.snippet().isInline()) {
                appendIndent(report, indent + 2);
                report.append("snippet:\n");
                appendSnippetLines(report, expressionFact.snippet().blockLines(), indent + 4);
            }
            appendIndent(report, indent + 2);
            report.append("type.source = ").append(expressionFact.typeSource()).append('\n');
            appendIndent(report, indent + 2);
            report.append("type.status = ").append(expressionFact.typeStatus()).append('\n');
            if (expressionFact.typeValue() != null) {
                appendIndent(report, indent + 2);
                report.append("type.value = ").append(expressionFact.typeValue()).append('\n');
            }
            if (expressionFact.typeReason() != null) {
                appendIndent(report, indent + 2);
                report.append("type.reason = ").append(expressionFact.typeReason()).append('\n');
            }
            renderAttachmentDiagnostics(expressionFact.expression(), report, indent + 2);
            for (var callFact : callsByOwnerExpression.getOrDefault(expressionFact.expression(), List.of())) {
                renderCallFact(callFact, report, indent + 2);
            }
        }

        private void renderCallFact(
                @NotNull CallDisplayFact callFact,
                @NotNull StringBuilder report,
                int indent
        ) {
            appendIndent(report, indent);
            report.append(callFact.id())
                    .append(' ')
                    .append(callFact.rangeText())
                    .append(' ');
            if (callFact.snippet().isInline()) {
                report.append('`').append(callFact.snippet().inlineText()).append('`').append('\n');
            } else {
                report.append(callFact.node().getClass().getSimpleName()).append('\n');
                appendIndent(report, indent + 2);
                report.append("snippet:\n");
                appendSnippetLines(report, callFact.snippet().blockLines(), indent + 4);
            }
            appendIndent(report, indent + 2);
            report.append("call.source = ").append(callFact.source()).append('\n');
            appendIndent(report, indent + 2);
            report.append("status = ").append(callFact.status()).append('\n');
            appendIndent(report, indent + 2);
            report.append("callKind = ").append(callFact.callKind()).append('\n');
            appendIndent(report, indent + 2);
            report.append("receiverKind = ").append(callFact.receiverKind()).append('\n');
            if (callFact.calleeBinding() != null) {
                appendIndent(report, indent + 2);
                report.append("calleeBinding = ").append(callFact.calleeBinding()).append('\n');
            }
            if (callFact.receiverType() != null) {
                appendIndent(report, indent + 2);
                report.append("receiverType = ").append(callFact.receiverType()).append('\n');
            }
            appendIndent(report, indent + 2);
            report.append("argumentTypes = ").append(callFact.argumentTypes()).append('\n');
            if (callFact.returnType() != null) {
                appendIndent(report, indent + 2);
                report.append("returnType = ").append(callFact.returnType()).append('\n');
            }
            if (callFact.declarationSite() != null) {
                appendIndent(report, indent + 2);
                report.append("declarationSite = ").append(callFact.declarationSite()).append('\n');
            }
            if (callFact.detailReason() != null) {
                appendIndent(report, indent + 2);
                report.append("detailReason = ").append(callFact.detailReason()).append('\n');
            }
            renderAttachmentDiagnostics(callFact.node(), report, indent + 2);
        }

        private void renderAttachmentDiagnostics(@NotNull Node node, @NotNull StringBuilder report, int indent) {
            var ids = attachmentDiagnostics.get(node);
            if (ids == null || ids.isEmpty()) {
                return;
            }
            appendIndent(report, indent);
            report.append("diagnostics = ").append(ids).append('\n');
        }

        private void buildExpressionFacts() {
            expressions.sort(this::compareNodesBySourceOrder);
            for (var index = 0; index < expressions.size(); index++) {
                var expression = expressions.get(index);
                var id = "E%04d".formatted(index + 1);
                var published = analysisData.expressionTypes().get(expression);
                var fact = new ExpressionDisplayFact(
                        id,
                        expression,
                        sourceTextIndex.formatRange(expression.range()),
                        sourceTextIndex.snippet(expression.range()),
                        published != null ? "published" : "display",
                        published != null ? published.status().name() : "UNPUBLISHED",
                        published != null ? formatTypeName(published.publishedType()) : null,
                        published != null ? published.detailReason() : inferUnpublishedReason(expression)
                );
                expressionFacts.put(expression, fact);
                expressionsByRange.computeIfAbsent(RangeKey.from(expression.range()), _ -> new ArrayList<>()).add(expression);
                expressionsByAnchor.computeIfAbsent(anchorForExpression(expression), _ -> new ArrayList<>()).add(fact);
            }
        }

        private void buildCallFacts() {
            callSites.sort(this::compareNodesBySourceOrder);
            for (var index = 0; index < callSites.size(); index++) {
                var callSite = callSites.get(index);
                var id = "C%04d".formatted(index + 1);
                var fact = switch (callSite) {
                    case AttributeCallStep attributeCallStep -> buildAttributeCallFact(id, attributeCallStep);
                    case CallExpression callExpression -> buildCallExpressionFact(id, callExpression);
                    default -> throw new IllegalStateException(
                            "Unexpected call-site node: " + callSite.getClass().getSimpleName()
                    );
                };
                callFacts.put(callSite, fact);
                callSitesByRange.computeIfAbsent(RangeKey.from(callSite.range()), _ -> new ArrayList<>()).add(callSite);
                callsByOwnerExpression.computeIfAbsent(fact.ownerExpression(), _ -> new ArrayList<>()).add(fact);
            }
        }

        private void buildDiagnosticFacts() {
            var orderedDiagnostics = new ArrayList<>(analysisData.diagnostics().asList());
            orderedDiagnostics.sort(this::compareDiagnostics);
            for (var index = 0; index < orderedDiagnostics.size(); index++) {
                var diagnostic = orderedDiagnostics.get(index);
                var id = "D%04d".formatted(index + 1);
                var displayFact = new DiagnosticDisplayFact(id, diagnostic, formatDiagnosticRange(diagnostic.range()));
                diagnostics.add(displayFact);
                var attachment = determineAttachmentTarget(diagnostic.range());
                attachmentDiagnostics.computeIfAbsent(attachment, _ -> new ArrayList<>()).add(id);
            }
        }

        private @NotNull CallDisplayFact buildAttributeCallFact(
                @NotNull String id,
                @NotNull AttributeCallStep attributeCallStep
        ) {
            var published = analysisData.resolvedCalls().get(attributeCallStep);
            var ownerExpression = requireOwnerExpression(attributeCallStep);
            return new CallDisplayFact(
                    id,
                    attributeCallStep,
                    ownerExpression,
                    sourceTextIndex.formatRange(attributeCallStep.range()),
                    sourceTextIndex.snippet(attributeCallStep.range()),
                    published != null ? "published" : "display",
                    published != null ? published.status().name() : "UNPUBLISHED",
                    published != null ? published.callKind().name() : "UNPUBLISHED_CALL_FACT",
                    published != null ? published.receiverKind().name() : "UNKNOWN",
                    null,
                    published != null ? formatTypeName(published.receiverType()) : null,
                    published != null
                            ? formatTypeList(published.argumentTypes())
                            : formatExpressionArgumentTypes(attributeCallStep.arguments()),
                    published != null ? formatTypeName(published.returnType()) : null,
                    published != null ? formatDeclarationSite(published.declarationSite()) : null,
                    published != null ? published.detailReason() : "No published call fact for attribute call step"
            );
        }

        private @NotNull CallDisplayFact buildCallExpressionFact(
                @NotNull String id,
                @NotNull CallExpression callExpression
        ) {
            var published = analysisData.resolvedCalls().get(callExpression);
            var derivedCallKind = callExpression.callee() instanceof IdentifierExpression
                    ? "BARE_CALL_DERIVED"
                    : "CALL_DERIVED";
            var expressionType = analysisData.expressionTypes().get(callExpression);
            var calleeBinding = callExpression.callee() instanceof IdentifierExpression identifierExpression
                    ? analysisData.symbolBindings().get(identifierExpression)
                    : null;
            return new CallDisplayFact(
                    id,
                    callExpression,
                    callExpression,
                    sourceTextIndex.formatRange(callExpression.range()),
                    sourceTextIndex.snippet(callExpression.range()),
                    published != null ? "published" : "derived",
                    published != null
                            ? published.status().name()
                            : expressionType != null ? expressionType.status().name() : "UNPUBLISHED",
                    published != null ? published.callKind().name() : derivedCallKind,
                    published != null ? published.receiverKind().name() : "UNKNOWN",
                    calleeBinding != null ? calleeBinding.kind().name() : null,
                    published != null ? formatTypeName(published.receiverType()) : null,
                    published != null ? formatTypeList(published.argumentTypes()) : formatExpressionArgumentTypes(callExpression.arguments()),
                    published != null
                            ? formatTypeName(published.returnType())
                            : expressionType != null ? formatTypeName(expressionType.publishedType()) : null,
                    published != null
                            ? formatDeclarationSite(published.declarationSite())
                            : calleeBinding != null ? formatDeclarationSite(calleeBinding.declarationSite()) : null,
                    published != null
                            ? Objects.requireNonNullElse(published.detailReason(), "Published bare call fact")
                            : deriveCallExpressionReason(callExpression, expressionType, calleeBinding)
            );
        }

        private @NotNull String deriveCallExpressionReason(
                @NotNull CallExpression callExpression,
                @Nullable FrontendExpressionType expressionType,
                @Nullable FrontendBinding calleeBinding
        ) {
            if (expressionType != null && expressionType.detailReason() != null) {
                return expressionType.detailReason();
            }
            if (calleeBinding != null) {
                return "Derived from bare callee binding kind '" + calleeBinding.kind().name() + "'";
            }
            if (callExpression.callee() instanceof IdentifierExpression identifierExpression) {
                return "No published binding or expression type is available for bare call '"
                        + identifierExpression.name() + "(...)'";
            }
            return "Call expression is rendered from display-only facts because it has no published call fact";
        }

        private @NotNull Node determineAttachmentTarget(@Nullable FrontendRange diagnosticRange) {
            if (diagnosticRange == null) {
                return unit.ast();
            }
            var exactCallTarget = firstNodeForExactRange(callSitesByRange, diagnosticRange);
            if (exactCallTarget != null) {
                return exactCallTarget;
            }
            var exactExpressionTarget = firstNodeForExactRange(expressionsByRange, diagnosticRange);
            if (exactExpressionTarget != null) {
                return exactExpressionTarget;
            }

            var statementTarget = smallestContainingNode(diagnosticRange, Statement.class);
            if (statementTarget != null) {
                return statementTarget;
            }
            var parameterTarget = smallestContainingNode(diagnosticRange, Parameter.class);
            if (parameterTarget != null) {
                return parameterTarget;
            }
            var callableTarget = smallestContainingCallable(diagnosticRange);
            if (callableTarget != null) {
                return callableTarget;
            }
            var classTarget = smallestContainingNode(diagnosticRange, ClassDeclaration.class);
            return Objects.requireNonNullElseGet(classTarget, unit::ast);
        }

        private @Nullable <T extends Node> T firstNodeForExactRange(
                @NotNull Map<RangeKey, List<T>> nodesByRange,
                @NotNull FrontendRange diagnosticRange
        ) {
            var candidates = nodesByRange.get(RangeKey.from(diagnosticRange));
            if (candidates == null || candidates.isEmpty()) {
                return null;
            }
            return candidates.stream().min(this::compareNodesBySourceOrder).orElse(null);
        }

        private @Nullable <T extends Node> T smallestContainingNode(
                @NotNull FrontendRange diagnosticRange,
                @NotNull Class<T> targetType
        ) {
            T winner = null;
            for (var entry : visitOrder.entrySet()) {
                var node = entry.getKey();
                if (!targetType.isInstance(node)) {
                    continue;
                }
                if (!contains(node.range(), diagnosticRange)) {
                    continue;
                }
                if (winner == null
                        || span(node.range()) < span(winner.range())
                        || (span(node.range()) == span(winner.range()) && compareNodesBySourceOrder(node, winner) < 0)) {
                    winner = targetType.cast(node);
                }
            }
            return winner;
        }

        private @Nullable Node smallestContainingCallable(@NotNull FrontendRange diagnosticRange) {
            Node winner = null;
            for (var node : visitOrder.keySet()) {
                if (!(node instanceof FunctionDeclaration) && !(node instanceof ConstructorDeclaration)) {
                    continue;
                }
                if (!contains(node.range(), diagnosticRange)) {
                    continue;
                }
                if (winner == null
                        || span(node.range()) < span(winner.range())
                        || (span(node.range()) == span(winner.range()) && compareNodesBySourceOrder(node, winner) < 0)) {
                    winner = node;
                }
            }
            return winner;
        }

        private @NotNull Node anchorForExpression(@NotNull Expression expression) {
            var current = parents.get(expression);
            while (current != null) {
                if (current instanceof Statement || current instanceof Parameter) {
                    return current;
                }
                current = parents.get(current);
            }
            return expression;
        }

        private @NotNull Expression requireOwnerExpression(@NotNull Node node) {
            var current = parents.get(node);
            while (current != null) {
                if (current instanceof Expression expression) {
                    return expression;
                }
                current = parents.get(current);
            }
            throw new IllegalStateException(
                    "Call-site node has no enclosing expression: " + node.getClass().getSimpleName()
            );
        }

        private @NotNull String inferUnpublishedReason(@NotNull Expression expression) {
            if (expression instanceof IdentifierExpression identifierExpression && isRouteHeadTypeMeta(identifierExpression)) {
                return "route-head TYPE_META is intentionally not published as ordinary value expression";
            }
            if (hasUnsupportedOrDeferredAncestor(expression)) {
                return "expression belongs to a subtree that is intentionally skipped, deferred, or unsupported";
            }
            return "expression is not published by the current frontend phase";
        }

        private boolean isRouteHeadTypeMeta(@NotNull IdentifierExpression identifierExpression) {
            var binding = analysisData.symbolBindings().get(identifierExpression);
            if (binding == null || binding.kind() != FrontendBindingKind.TYPE_META) {
                return false;
            }
            var parent = parents.get(identifierExpression);
            if (parent instanceof CallExpression) {
                return false;
            }
            return parent instanceof dev.superice.gdparser.frontend.ast.AttributeExpression attributeExpression
                    && attributeExpression.base() == identifierExpression;
        }

        private boolean hasUnsupportedOrDeferredAncestor(@NotNull Expression expression) {
            var current = parents.get(expression);
            while (current != null) {
                if (current instanceof Parameter parameter && parameter.defaultValue() != null) {
                    return true;
                }
                if (current instanceof LambdaExpression || current instanceof MatchStatement) {
                    return true;
                }
                if (current instanceof dev.superice.gdparser.frontend.ast.ForStatement) {
                    return true;
                }
                if (current instanceof VariableDeclaration variableDeclaration
                        && variableDeclaration.kind() == dev.superice.gdparser.frontend.ast.DeclarationKind.CONST) {
                    return true;
                }
                current = parents.get(current);
            }
            return false;
        }

        private void indexAst(@NotNull Node node, @Nullable Node parent) {
            parents.put(node, parent);
            visitOrder.put(node, nextVisitIndex++);
            if (node instanceof Expression expression) {
                expressions.add(expression);
            }
            if (node instanceof CallExpression || node instanceof AttributeCallStep) {
                callSites.add(node);
            }
            for (var child : node.getChildren()) {
                indexAst(child, node);
            }
        }

        private int compareNodesBySourceOrder(@NotNull Node left, @NotNull Node right) {
            var byRange = RANGE_COMPARATOR.compare(left.range(), right.range());
            if (byRange != 0) {
                return byRange;
            }
            return Integer.compare(visitOrder.get(left), visitOrder.get(right));
        }

        private int compareDiagnostics(@NotNull FrontendDiagnostic left, @NotNull FrontendDiagnostic right) {
            var leftRange = left.range();
            var rightRange = right.range();
            if (leftRange == null && rightRange != null) {
                return 1;
            }
            if (leftRange != null && rightRange == null) {
                return -1;
            }
            if (leftRange != null) {
                var byStart = Integer.compare(leftRange.startByte(), rightRange.startByte());
                if (byStart != 0) {
                    return byStart;
                }
                var byEnd = Integer.compare(leftRange.endByte(), rightRange.endByte());
                if (byEnd != 0) {
                    return byEnd;
                }
            }
            var bySeverity = Integer.compare(severityRank(left.severity()), severityRank(right.severity()));
            if (bySeverity != 0) {
                return bySeverity;
            }
            var byCategory = left.category().compareTo(right.category());
            if (byCategory != 0) {
                return byCategory;
            }
            return left.message().compareTo(right.message());
        }

        private int countPublishedExpressions() {
            return (int) expressionFacts.values().stream()
                    .filter(fact -> fact.typeSource().equals("published"))
                    .count();
        }

        private int countPublishedCalls() {
            return (int) callFacts.values().stream()
                    .filter(fact -> fact.source().equals("published"))
                    .count();
        }

        private int countDerivedCalls() {
            return (int) callFacts.values().stream()
                    .filter(fact -> fact.source().equals("derived"))
                    .count();
        }

        private static int severityRank(@NotNull FrontendDiagnosticSeverity severity) {
            return switch (severity) {
                case ERROR -> 0;
                case WARNING -> 1;
            };
        }

        private static boolean contains(@NotNull Range nodeRange, @NotNull FrontendRange diagnosticRange) {
            return nodeRange.startByte() <= diagnosticRange.startByte()
                    && nodeRange.endByte() >= diagnosticRange.endByte();
        }

        private static int span(@NotNull Range range) {
            return range.endByte() - range.startByte();
        }

        private static @Nullable String formatTypeName(@Nullable GdType type) {
            return type != null ? type.getTypeName() : null;
        }

        private @NotNull String formatExpressionArgumentTypes(@NotNull List<? extends Expression> arguments) {
            var parts = new ArrayList<String>(arguments.size());
            for (var argument : arguments) {
                var published = analysisData.expressionTypes().get(argument);
                if (published == null) {
                    parts.add("<UNPUBLISHED>");
                    continue;
                }
                var typeName = formatTypeName(published.publishedType());
                parts.add(typeName != null ? typeName : "<" + published.status().name() + ">");
            }
            return parts.toString();
        }

        private static @NotNull String formatTypeList(@NotNull List<GdType> types) {
            var parts = new ArrayList<String>(types.size());
            for (var type : types) {
                parts.add(type.getTypeName());
            }
            return parts.toString();
        }

        private @NotNull String formatDiagnosticRange(@Nullable FrontendRange range) {
            if (range == null) {
                return "<no-range>";
            }
            return sourceTextIndex.formatRange(range);
        }

        private @Nullable String formatDeclarationSite(@Nullable Object declarationSite) {
            if (declarationSite == null) {
                return null;
            }
            return switch (declarationSite) {
                case String text -> text;
                case ClassDeclaration classDeclaration -> classDeclaration.name();
                case FunctionDeclaration functionDeclaration ->
                        qualifyAstName(functionDeclaration.name(), functionDeclaration);
                case ConstructorDeclaration constructorDeclaration -> qualifyAstName("_init", constructorDeclaration);
                case VariableDeclaration variableDeclaration ->
                        qualifyAstName(variableDeclaration.name(), variableDeclaration);
                case Parameter parameter -> {
                    var owner = nearestCallableName(parameter);
                    yield owner != null ? owner + "." + parameter.name() : parameter.name();
                }
                case ClassDef classDef -> classDef.getName();
                case FunctionDef functionDef -> functionDef.getName();
                case PropertyDef propertyDef -> propertyDef.getName();
                case ParameterDef parameterDef -> parameterDef.getName();
                default -> declarationSite.getClass().getSimpleName();
            };
        }

        private @NotNull String qualifyAstName(@NotNull String name, @NotNull Node declaration) {
            var className = nearestClassName(declaration);
            return className != null ? className + "." + name : name;
        }

        private @Nullable String nearestClassName(@NotNull Node node) {
            var current = node;
            while (current != null) {
                if (current instanceof ClassDeclaration classDeclaration) {
                    return classDeclaration.name();
                }
                current = parents.get(current);
            }
            return null;
        }

        private @Nullable String nearestCallableName(@NotNull Node node) {
            var current = node;
            while (current != null) {
                if (current instanceof FunctionDeclaration functionDeclaration) {
                    return qualifyAstName(functionDeclaration.name(), functionDeclaration);
                }
                if (current instanceof ConstructorDeclaration constructorDeclaration) {
                    return qualifyAstName("_init", constructorDeclaration);
                }
                current = parents.get(current);
            }
            return null;
        }

        private static @NotNull String renderParameterList(@NotNull List<Parameter> parameters) {
            var joiner = new StringJoiner(", ", "(", ")");
            for (var parameter : parameters) {
                joiner.add(parameter.name());
            }
            return joiner.toString();
        }

        private static void appendIndent(@NotNull StringBuilder report, int indent) {
            report.repeat(" ", Math.max(indent, 0));
        }

        private static void appendSnippetLines(
                @NotNull StringBuilder report,
                @NotNull List<String> lines,
                int indent
        ) {
            for (var line : lines) {
                appendIndent(report, indent);
                report.append("| ").append(line).append('\n');
            }
        }
    }

    private record ExpressionDisplayFact(
            @NotNull String id,
            @NotNull Expression expression,
            @NotNull String rangeText,
            @NotNull Snippet snippet,
            @NotNull String typeSource,
            @NotNull String typeStatus,
            @Nullable String typeValue,
            @Nullable String typeReason
    ) {
    }

    private record CallDisplayFact(
            @NotNull String id,
            @NotNull Node node,
            @NotNull Expression ownerExpression,
            @NotNull String rangeText,
            @NotNull Snippet snippet,
            @NotNull String source,
            @NotNull String status,
            @NotNull String callKind,
            @NotNull String receiverKind,
            @Nullable String calleeBinding,
            @Nullable String receiverType,
            @NotNull String argumentTypes,
            @Nullable String returnType,
            @Nullable String declarationSite,
            @Nullable String detailReason
    ) {
    }

    private record DiagnosticDisplayFact(
            @NotNull String id,
            @NotNull FrontendDiagnostic diagnostic,
            @NotNull String rangeText
    ) {
    }

    private record RangeKey(int startByte, int endByte) {
        private static @NotNull RangeKey from(@NotNull Range range) {
            return new RangeKey(range.startByte(), range.endByte());
        }

        private static @NotNull RangeKey from(@NotNull FrontendRange range) {
            return new RangeKey(range.startByte(), range.endByte());
        }
    }

    private record Snippet(
            boolean isInline,
            @Nullable String inlineText,
            @NotNull List<String> blockLines
    ) {
    }

    /// Keeps source slicing consistent with byte-based AST ranges.
    private static final class SourceTextIndex {
        private final @NotNull String source;
        private final int @NotNull [] byteToCharIndex;

        private SourceTextIndex(@NotNull String source) {
            this.source = Objects.requireNonNull(source, "source must not be null");
            byteToCharIndex = buildByteToCharIndex(source);
        }

        public @NotNull String formatRange(@NotNull Range range) {
            return formatRange(Objects.requireNonNull(FrontendRange.fromAstRange(range), "range must not be null"));
        }

        public @NotNull String formatRange(@NotNull FrontendRange range) {
            return (range.start().line() + 1) + ":" + (range.start().column() + 1)
                    + "-" + (range.end().line() + 1) + ":" + (range.end().column() + 1);
        }

        public @NotNull Snippet snippet(@NotNull Range range) {
            return snippet(Objects.requireNonNull(FrontendRange.fromAstRange(range), "range must not be null"));
        }

        public @NotNull Snippet snippet(@NotNull FrontendRange range) {
            var raw = slice(range.startByte(), range.endByte());
            var normalized = normalizeSnippet(raw);
            if (!normalized.contains("\n") && normalized.length() <= 120) {
                return new Snippet(true, normalized, List.of());
            }
            return new Snippet(false, null, splitSnippetLines(normalized));
        }

        private @NotNull String slice(int startByte, int endByte) {
            if (startByte < 0 || endByte < startByte || endByte >= byteToCharIndex.length) {
                throw new IllegalArgumentException(
                        "Invalid byte range: " + startByte + "-" + endByte + " for source length " + source.length()
                );
            }
            var startChar = byteToCharIndex[startByte];
            var endChar = byteToCharIndex[endByte];
            return source.substring(startChar, endChar);
        }

        private static int @NotNull [] buildByteToCharIndex(@NotNull String source) {
            var totalBytes = source.getBytes(StandardCharsets.UTF_8).length;
            var mapping = new int[totalBytes + 1];
            var byteOffset = 0;
            for (var charOffset = 0; charOffset < source.length(); ) {
                var codePoint = source.codePointAt(charOffset);
                var charCount = Character.charCount(codePoint);
                var utf8Length = utf8Length(codePoint);
                for (var index = 0; index < utf8Length; index++) {
                    mapping[byteOffset + index] = charOffset;
                }
                byteOffset += utf8Length;
                charOffset += charCount;
                mapping[byteOffset] = charOffset;
            }
            return mapping;
        }

        private static int utf8Length(int codePoint) {
            if (codePoint <= 0x7F) {
                return 1;
            }
            if (codePoint <= 0x7FF) {
                return 2;
            }
            if (codePoint <= 0xFFFF) {
                return 3;
            }
            return 4;
        }

        private static @NotNull String normalizeSnippet(@NotNull String rawSnippet) {
            var normalized = rawSnippet.replace("\r\n", "\n").replace('\r', '\n').strip();
            if (normalized.isEmpty()) {
                return rawSnippet.trim();
            }
            var lines = normalized.lines().toList();
            var commonIndent = Integer.MAX_VALUE;
            for (var line : lines) {
                if (line.isBlank()) {
                    continue;
                }
                var indent = 0;
                while (indent < line.length() && Character.isWhitespace(line.charAt(indent))) {
                    indent++;
                }
                commonIndent = Math.min(commonIndent, indent);
            }
            if (commonIndent == Integer.MAX_VALUE || commonIndent == 0) {
                return normalized;
            }
            var strippedLines = new ArrayList<String>(lines.size());
            for (var line : lines) {
                strippedLines.add(line.isBlank() ? "" : line.substring(Math.min(commonIndent, line.length())));
            }
            return String.join("\n", strippedLines);
        }

        private static @NotNull List<String> splitSnippetLines(@NotNull String snippet) {
            return snippet.lines().toList();
        }
    }
}
