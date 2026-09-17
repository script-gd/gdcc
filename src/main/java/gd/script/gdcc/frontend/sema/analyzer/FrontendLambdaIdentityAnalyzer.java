package gd.script.gdcc.frontend.sema.analyzer;

import dev.superice.gdparser.frontend.ast.ArrayExpression;
import dev.superice.gdparser.frontend.ast.AssignmentExpression;
import dev.superice.gdparser.frontend.ast.AttributeCallStep;
import dev.superice.gdparser.frontend.ast.AttributeExpression;
import dev.superice.gdparser.frontend.ast.AwaitExpression;
import dev.superice.gdparser.frontend.ast.CallExpression;
import dev.superice.gdparser.frontend.ast.ConstructorDeclaration;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.LambdaExpression;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.Range;
import dev.superice.gdparser.frontend.ast.ReturnStatement;
import dev.superice.gdparser.frontend.ast.Statement;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import gd.script.gdcc.frontend.sema.FrontendAnalysisData;
import gd.script.gdcc.frontend.sema.FrontendAstSideTable;
import gd.script.gdcc.frontend.sema.FrontendLambdaIdentity;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/// Lambda identity pass (contract: `hot_reload_implementation_plan.md` §5.11). Assigns every
/// lambda inside a supported executable body (a) its source pre-order ordinal within the
/// outermost NAMED function/constructor body — the traversal is isomorphic to
/// `FrontendLoweringFunctionPreparationPass.collectLambdaContexts` (pre-order over
/// `Node.getChildren()`; number the lambda on encounter, then recurse into its `body()` only),
/// so the ordinal domain matches the HRX rebind table exactly — and (b) the normalized
/// call-site context descriptor computed from the ancestor stack.
///
/// Lambdas outside supported executable bodies (property initializers, parameter defaults)
/// never get a `FrontendLambdaPlan` upstream, so they are deliberately NOT numbered: the
/// ordinal domain and the rebind table stay the same set. `FrontendSemanticAnalyzer` runs this
/// pass after interface analysis and before suite resolution, publishing the result into
/// `FrontendAnalysisData.lambdaIdentities()`; `FrontendSuiteResolver` fails fast when a recorded
/// lambda has no identity entry.
public final class FrontendLambdaIdentityAnalyzer {
    private FrontendLambdaIdentityAnalyzer() {
    }

    /// Computes lambda identities for the whole module, keyed by lambda AST identity.
    public static @NotNull FrontendAstSideTable<FrontendLambdaIdentity> analyze(@NotNull FrontendAnalysisData analysisData) {
        Objects.requireNonNull(analysisData, "analysisData must not be null");
        var identities = new FrontendAstSideTable<FrontendLambdaIdentity>();
        for (var relation : analysisData.moduleSkeleton().sourceClassRelations()) {
            var unit = relation.unit();
            new Walker(unit.source().getBytes(StandardCharsets.UTF_8), identities).walkSourceFile(unit.ast());
        }
        return identities;
    }

    /// One source file walker: ordinals reset at every outermost named function boundary;
    /// the ancestor stack feeds the context descriptor of each encountered lambda.
    private static final class Walker {
        private final byte @NotNull [] sourceBytes;
        private final @NotNull FrontendAstSideTable<FrontendLambdaIdentity> identities;
        /// Path from the SourceFile down to the parent of the currently visited node.
        private final @NotNull ArrayDeque<Node> ancestors = new ArrayDeque<>();
        private int ordinalCounter;
        private boolean insideNamedFunction;

        private Walker(byte @NotNull [] sourceBytes, @NotNull FrontendAstSideTable<FrontendLambdaIdentity> identities) {
            this.sourceBytes = sourceBytes;
            this.identities = identities;
        }

        private void walkSourceFile(@NotNull Node sourceFile) {
            walkChildren(sourceFile);
        }

        private void walkChildren(@NotNull Node node) {
            for (var child : node.getChildren()) {
                ancestors.addLast(node);
                walkNode(child);
                ancestors.removeLast();
            }
        }

        private void walkNode(@NotNull Node node) {
            if (!insideNamedFunction && (node instanceof FunctionDeclaration || node instanceof ConstructorDeclaration)) {
                enterNamedFunction(node);
                return;
            }
            if (node instanceof LambdaExpression lambda) {
                if (insideNamedFunction) {
                    identities.put(lambda, new FrontendLambdaIdentity(ordinalCounter++, computeContext(lambda)));
                    ancestors.addLast(lambda);
                    // Traversal parity with collectLambdaContexts: recurse into the body ONLY
                    // (parameter defaults / return type never carry recorded lambdas).
                    walkChildren(lambda.body());
                    ancestors.removeLast();
                }
                // Outside a supported executable body the lambda is never recorded and never
                // gets a plan: no identity entry, and no descent (nothing inside is recorded
                // either).
                return;
            }
            walkChildren(node);
        }

        private void enterNamedFunction(@NotNull Node function) {
            var savedCounter = ordinalCounter;
            var savedFlag = insideNamedFunction;
            ordinalCounter = 0;
            insideNamedFunction = true;
            ancestors.addLast(function);
            var body = switch (function) {
                case FunctionDeclaration functionDeclaration -> functionDeclaration.body();
                case ConstructorDeclaration constructorDeclaration -> constructorDeclaration.body();
                default -> throw new IllegalStateException(
                        "Named function boundary must be a function or constructor: "
                                + function.getClass().getSimpleName()
                );
            };
            walkChildren(body);
            ancestors.removeLast();
            ordinalCounter = savedCounter;
            insideNamedFunction = savedFlag;
        }

        /// Walks the ancestor chain nearest-first and anchors at the nearest semantic parent
        /// (hot_reload_implementation_plan.md §5.11 descriptor table). The search never escapes
        /// the enclosing callable (a nested lambda's context is computed within its own lambda
        /// boundary); unanchored forms fall back to `stmt(<nearest statement type>)`.
        private @NotNull String computeContext(@NotNull LambdaExpression lambda) {
            var chain = ancestors.toArray(new Node[0]);
            String statementFallback = null;
            for (var i = chain.length - 1; i >= 0; i--) {
                var node = chain[i];
                var child = i + 1 < chain.length ? chain[i + 1] : lambda;
                if (node instanceof AttributeCallStep callStep) {
                    var argIndex = identityIndexOf(callStep.arguments(), child);
                    if (argIndex >= 0 && i >= 1 && chain[i - 1] instanceof AttributeExpression attribute
                            && identityContains(attribute.steps(), callStep)) {
                        // The base is the whole chain prefix before the enclosing call step
                        // (e.g. `self.sig_a` of `self.sig_a.connect(func...)`), NOT
                        // `AttributeExpression.base()` (the chain head `self`).
                        var prefix = sliceNormalized(attribute.range().startByte(), callStep.range().startByte());
                        while (prefix.endsWith(".")) {
                            prefix = prefix.substring(0, prefix.length() - 1);
                        }
                        return "call(base=" + prefix + ", method=" + callStep.name() + ", arg=" + argIndex + ")";
                    }
                } else if (node instanceof CallExpression callExpression) {
                    var argIndex = identityIndexOf(callExpression.arguments(), child);
                    if (argIndex >= 0) {
                        return "call(callee=" + sliceNormalized(callExpression.callee().range()) + ", arg=" + argIndex + ")";
                    }
                } else if (node instanceof VariableDeclaration variableDeclaration) {
                    if (child == variableDeclaration.value()) {
                        return "assign(var=" + variableDeclaration.name() + ", kind="
                                + variableDeclaration.kind().name().toLowerCase(Locale.ROOT) + ")";
                    }
                } else if (node instanceof AssignmentExpression assignmentExpression) {
                    if (child == assignmentExpression.right()) {
                        return "assign_expr(lhs=" + sliceNormalized(assignmentExpression.left().range())
                                + ", op=" + assignmentExpression.operator() + ")";
                    }
                } else if (node instanceof AwaitExpression awaitExpression) {
                    if (child == awaitExpression.value()) {
                        // The operand IS the lambda: slicing it would make the descriptor
                        // body-dependent, so `await` carries no operand text.
                        return "await";
                    }
                } else if (node instanceof ArrayExpression arrayExpression) {
                    var index = identityIndexOf(arrayExpression.elements(), child);
                    if (index >= 0) {
                        return "array(idx=" + index + ")";
                    }
                } else if (node instanceof ReturnStatement returnStatement) {
                    if (child == returnStatement.value()) {
                        return "return";
                    }
                }
                if (statementFallback == null && node instanceof Statement) {
                    statementFallback = node.getClass().getSimpleName();
                }
                if (node instanceof FunctionDeclaration
                        || node instanceof ConstructorDeclaration
                        || node instanceof LambdaExpression) {
                    break;
                }
            }
            return "stmt(" + (statementFallback != null ? statementFallback : "unknown") + ")";
        }

        private static int identityIndexOf(@NotNull List<? extends Node> nodes, @NotNull Node child) {
            for (var i = 0; i < nodes.size(); i++) {
                if (nodes.get(i) == child) {
                    return i;
                }
            }
            return -1;
        }

        private static boolean identityContains(@NotNull List<? extends Node> nodes, @NotNull Node child) {
            return identityIndexOf(nodes, child) >= 0;
        }

        private @NotNull String sliceNormalized(@NotNull Range range) {
            return sliceNormalized(range.startByte(), range.endByte());
        }

        private @NotNull String sliceNormalized(int startByte, int endByte) {
            if (startByte < 0 || endByte > sourceBytes.length || startByte > endByte) {
                throw new IllegalStateException(
                        "Invalid byte range [" + startByte + ", " + endByte + ") for source length " + sourceBytes.length
                );
            }
            return normalize(new String(sourceBytes, startByte, endByte - startByte, StandardCharsets.UTF_8));
        }

        /// Descriptor whitespace/comment normalization: drops `#` comments and ALL whitespace
        /// outside string literals; string contents are preserved verbatim (both generations run
        /// the same normalization, so only equality matters). Backslash escapes are honored only
        /// inside single-line strings — raw/triple-quoted forms are rare in call-site chains and
        /// mis-lexing them is still deterministic on both sides.
        private static @NotNull String normalize(@NotNull String text) {
            var out = new StringBuilder(text.length());
            var i = 0;
            while (i < text.length()) {
                var c = text.charAt(i);
                if (c == '#') {
                    while (i < text.length() && text.charAt(i) != '\n') {
                        i++;
                    }
                    continue;
                }
                if (c == '"' || c == '\'') {
                    var triple = i + 2 < text.length() && text.charAt(i + 1) == c && text.charAt(i + 2) == c;
                    out.append(c);
                    i++;
                    if (triple) {
                        out.append(c).append(c);
                        i += 2;
                    }
                    while (i < text.length()) {
                        var s = text.charAt(i);
                        if (!triple && s == '\\' && i + 1 < text.length()) {
                            out.append(s).append(text.charAt(i + 1));
                            i += 2;
                            continue;
                        }
                        out.append(s);
                        i++;
                        if (triple) {
                            if (s == c && i + 1 < text.length() && text.charAt(i) == c && text.charAt(i + 1) == c) {
                                out.append(c).append(c);
                                i += 2;
                                break;
                            }
                        } else if (s == c) {
                            break;
                        }
                    }
                    continue;
                }
                if (Character.isWhitespace(c)) {
                    i++;
                    continue;
                }
                out.append(c);
                i++;
            }
            return out.toString();
        }
    }
}
