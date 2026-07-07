package gd.script.gdcc.frontend.sema;

import dev.superice.gdparser.frontend.ast.Node;
import gd.script.gdcc.type.GdCompilerType;
import gd.script.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

/// Source-facing slot type baseline prepared by the Interface phase.
///
/// It records only parameter and ordinary-local slot facts that the baseline inventory layer has
/// already published. Compiler-only storage types are rejected here because body phases must never
/// expose `GdCompilerType` through source-facing lexical lookup.
public final class FrontendTypedLexicalBaseline {
    private final @NotNull Map<Node, GdType> typesByDeclaration;

    private FrontendTypedLexicalBaseline(@NotNull Map<Node, GdType> typesByDeclaration) {
        this.typesByDeclaration = Collections.unmodifiableMap(new IdentityHashMap<>(typesByDeclaration));
    }

    public static @NotNull Builder builder() {
        return new Builder();
    }

    public @Nullable GdType typeFor(@NotNull Node declaration) {
        return typesByDeclaration.get(Objects.requireNonNull(declaration, "declaration"));
    }

    public boolean containsDeclaration(@NotNull Node declaration) {
        return typesByDeclaration.containsKey(Objects.requireNonNull(declaration, "declaration"));
    }

    public @NotNull Map<Node, GdType> typesByDeclaration() {
        return typesByDeclaration;
    }

    public static final class Builder {
        private final @NotNull Map<Node, GdType> typesByDeclaration = new IdentityHashMap<>();

        private Builder() {
        }

        public @NotNull Builder put(@NotNull Node declaration, @NotNull GdType type) {
            Objects.requireNonNull(declaration, "declaration");
            Objects.requireNonNull(type, "type");
            if (type instanceof GdCompilerType) {
                throw new IllegalArgumentException(
                        "compiler-only type leaked into source-facing typed lexical baseline: "
                                + type.getTypeName()
                );
            }
            typesByDeclaration.put(declaration, type);
            return this;
        }

        public @NotNull FrontendTypedLexicalBaseline build() {
            return new FrontendTypedLexicalBaseline(typesByDeclaration);
        }
    }
}
