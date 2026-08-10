package gd.script.gdcc.frontend.sema.analyzer.support;

import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.Node;
import gd.script.gdcc.scope.ClassDef;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVoidType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Shared callable return-slot lookup for suite expected-type propagation and type-check.
///
/// Keeps body publication (`currentCallableReturnType`) and diagnostics-only type-check on one
/// skeleton-backed definition so return expected types cannot drift between owners.
public final class FrontendCallableReturnTypeSupport {
    private FrontendCallableReturnTypeSupport() {
    }

    /// Resolves the published return slot for a callable owner, or null when the owner is not a
    /// function body (property initializer island, unsupported callable shape).
    public static @Nullable GdType resolveReturnTypeOrNull(
            @NotNull Node callableOwner,
            @Nullable ClassDef owningClassOrNull
    ) {
        Objects.requireNonNull(callableOwner, "callableOwner must not be null");
        if (!(callableOwner instanceof FunctionDeclaration functionDeclaration)) {
            return null;
        }
        return resolveFunctionReturnSlot(functionDeclaration, owningClassOrNull);
    }

    /// Same contract as the historical type-check return-slot lookup: `_init` is void; other
    /// functions require a matching published `ClassDef` overload.
    public static @NotNull GdType resolveFunctionReturnSlot(
            @NotNull FunctionDeclaration functionDeclaration,
            @Nullable ClassDef owningClassOrNull
    ) {
        Objects.requireNonNull(functionDeclaration, "functionDeclaration must not be null");
        if (functionDeclaration.name().equals("_init")) {
            return GdVoidType.VOID;
        }
        var currentClassDef = Objects.requireNonNull(
                owningClassOrNull,
                "owningClassOrNull must not be null while resolving function return slot for '"
                        + functionDeclaration.name() + "'"
        );
        return currentClassDef.getFunctions().stream()
                .filter(function -> function.getName().equals(functionDeclaration.name()))
                .filter(function -> function.isStatic() == functionDeclaration.isStatic())
                .filter(function -> function.getParameterCount() == functionDeclaration.parameters().size())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Function skeleton has not been published for: "
                                + currentClassDef.getName() + "." + functionDeclaration.name()
                ))
                .getReturnType();
    }
}
