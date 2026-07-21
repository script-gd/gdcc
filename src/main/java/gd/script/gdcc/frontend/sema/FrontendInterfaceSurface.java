package gd.script.gdcc.frontend.sema;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/// Complete Interface-phase surface consumed by future body-suite orchestration.
public record FrontendInterfaceSurface(
        @NotNull FrontendBodyDeclarationIndex bodyDeclarationIndex,
        @NotNull FrontendTypedLexicalBaseline typedLexicalBaseline,
        @NotNull FrontendSuiteEntryRoots suiteEntryRoots
) {
    public FrontendInterfaceSurface {
        Objects.requireNonNull(bodyDeclarationIndex, "bodyDeclarationIndex");
        Objects.requireNonNull(typedLexicalBaseline, "typedLexicalBaseline");
        Objects.requireNonNull(suiteEntryRoots, "suiteEntryRoots");
    }
}
