package dev.superice.gdcc.scope.resolver;

import dev.superice.gdcc.scope.Scope;
import dev.superice.gdcc.type.GdType;
import org.jetbrains.annotations.Nullable;

/// Optional compatibility hook used after strict declared-type resolution misses a non-structured leaf type.
///
/// The mapper does not participate in malformed structured texts such as `Dictionary[String]` or
/// nested structured containers like `Array[Array[int]]`; those remain hard failures handled by the
/// shared strict parser itself.
@FunctionalInterface
public interface UnresolvedTypeMapper {
    @Nullable GdType mapUnresolvedType(Scope scope, String unresolvedTypeText);
}
