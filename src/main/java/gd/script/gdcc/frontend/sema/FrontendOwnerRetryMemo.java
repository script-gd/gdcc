package gd.script.gdcc.frontend.sema;

import dev.superice.gdparser.frontend.ast.Node;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Owner-local transient retry facts for chain/expression reduction.
///
/// These facts are intentionally not part of `FrontendTypedLexicalEnvironment`: they are readable
/// only by the current owner procedure and are discarded before statement flush or suite export.
public final class FrontendOwnerRetryMemo {
    private final @NotNull FrontendAstSideTable<FrontendExpressionType> expressionTypes = new FrontendAstSideTable<>();
    private final @NotNull FrontendAstSideTable<FrontendResolvedMember> resolvedMembers = new FrontendAstSideTable<>();
    private final @NotNull FrontendAstSideTable<FrontendResolvedCall> resolvedCalls = new FrontendAstSideTable<>();

    public void putExpressionType(@NotNull Node astNode, @NotNull FrontendExpressionType expressionType) {
        expressionTypes.put(
                Objects.requireNonNull(astNode, "astNode must not be null"),
                Objects.requireNonNull(expressionType, "expressionType must not be null")
        );
    }

    public @Nullable FrontendExpressionType expressionType(@NotNull Node astNode) {
        return expressionTypes.get(Objects.requireNonNull(astNode, "astNode must not be null"));
    }

    public void putResolvedMember(@NotNull Node astNode, @NotNull FrontendResolvedMember member) {
        resolvedMembers.put(
                Objects.requireNonNull(astNode, "astNode must not be null"),
                Objects.requireNonNull(member, "member must not be null")
        );
    }

    public @Nullable FrontendResolvedMember resolvedMember(@NotNull Node astNode) {
        return resolvedMembers.get(Objects.requireNonNull(astNode, "astNode must not be null"));
    }

    public void putResolvedCall(@NotNull Node astNode, @NotNull FrontendResolvedCall call) {
        resolvedCalls.put(
                Objects.requireNonNull(astNode, "astNode must not be null"),
                Objects.requireNonNull(call, "call must not be null")
        );
    }

    public @Nullable FrontendResolvedCall resolvedCall(@NotNull Node astNode) {
        return resolvedCalls.get(Objects.requireNonNull(astNode, "astNode must not be null"));
    }

    public void clear() {
        expressionTypes.clear();
        resolvedMembers.clear();
        resolvedCalls.clear();
    }
}
