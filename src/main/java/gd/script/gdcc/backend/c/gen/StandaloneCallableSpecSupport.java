package gd.script.gdcc.backend.c.gen;

import gd.script.gdcc.gdextension.ExtensionGdClass;
import gd.script.gdcc.lir.insn.StandaloneCallableKind;
import gd.script.gdcc.scope.ClassDef;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.FunctionDef;
import gd.script.gdcc.type.GdVoidType;
import org.jetbrains.annotations.NotNull;

/// Shared resolution of `construct_standalone_callable` identities into call metadata
/// (argument count / vararg / returnness / utility hash). Consumed by both the insn-level
/// emission (`ConstructInsnGen`) and the module-level HRX identity catalog
/// (`CHrxIdentityCatalog`) so the creation site and the rebind table can never drift apart.
public final class StandaloneCallableSpecSupport {

    private StandaloneCallableSpecSupport() {
    }

    public record StandaloneCallableSpec(
            @NotNull String ownerName,
            @NotNull String callableName,
            long utilityHash,
            int argumentCount,
            boolean vararg,
            boolean returnsValue
    ) {
    }

    /// Resolves the identity to its spec or throws IllegalStateException with the detailed
    /// reason (callers adapt the error to their own context: insn-level vs module-level).
    public static @NotNull StandaloneCallableSpec resolve(
            @NotNull ClassRegistry classRegistry,
            @NotNull StandaloneCallableKind kind,
            @NotNull String ownerName,
            @NotNull String callableName
    ) {
        return switch (kind) {
            case UTILITY -> resolveUtility(classRegistry, callableName);
            case STATIC_GDCC -> resolveGdccStatic(classRegistry, ownerName, callableName);
            case STATIC_ENGINE -> resolveEngineStatic(classRegistry, ownerName, callableName);
        };
    }

    private static @NotNull StandaloneCallableSpec resolveUtility(
            @NotNull ClassRegistry classRegistry,
            @NotNull String callableName
    ) {
        var utility = classRegistry.findUtilityFunction(callableName);
        if (utility == null) {
            throw new IllegalStateException(
                    "construct_standalone_callable utility '" + callableName + "' is not registered"
            );
        }
        return new StandaloneCallableSpec(
                "",
                utility.name(),
                Integer.toUnsignedLong(utility.hash()),
                utility.getParameterCount(),
                utility.isVararg(),
                !(utility.getReturnType() instanceof GdVoidType)
        );
    }

    private static @NotNull StandaloneCallableSpec resolveGdccStatic(
            @NotNull ClassRegistry classRegistry,
            @NotNull String ownerName,
            @NotNull String callableName
    ) {
        var startClass = classRegistry.resolveClassDefByName(ownerName);
        if (startClass == null || !startClass.isGdccClass()) {
            throw new IllegalStateException(
                    "construct_standalone_callable static_gdcc owner '" + ownerName + "' is not a GDCC class"
            );
        }
        var lookup = requireStaticFunctionInHierarchy(classRegistry, ownerName, callableName, "static_gdcc");
        if (!lookup.ownerClass().isGdccClass()) {
            throw new IllegalStateException(
                    "construct_standalone_callable static_gdcc '" + ownerName
                            + "." + callableName + "' is not a generated static function"
            );
        }
        var function = requireStaticFunction(lookup.ownerClass(), callableName, "static_gdcc");
        return new StandaloneCallableSpec(
                lookup.ownerClass().getName(),
                function.getName(),
                0L,
                function.getParameterCount(),
                function.isVararg(),
                !(function.getReturnType() instanceof GdVoidType)
        );
    }

    private static @NotNull StandaloneCallableSpec resolveEngineStatic(
            @NotNull ClassRegistry classRegistry,
            @NotNull String ownerName,
            @NotNull String callableName
    ) {
        var lookup = requireStaticFunctionInHierarchy(classRegistry, ownerName, callableName, "static_engine");
        if (!(lookup.ownerClass() instanceof ExtensionGdClass engineClass)) {
            throw new IllegalStateException(
                    "construct_standalone_callable static_engine owner '" + ownerName + "' is not an engine class"
            );
        }
        var function = requireStaticFunction(engineClass, callableName, "static_engine");
        return new StandaloneCallableSpec(
                engineClass.getName(),
                function.getName(),
                0L,
                function.getParameterCount(),
                function.isVararg(),
                !(function.getReturnType() instanceof GdVoidType)
        );
    }

    private static @NotNull ClassRegistry.ClassStaticFunctionLookup requireStaticFunctionInHierarchy(
            @NotNull ClassRegistry classRegistry,
            @NotNull String ownerName,
            @NotNull String callableName,
            @NotNull String kindToken
    ) {
        var lookup = classRegistry.findStaticFunctionInHierarchy(ownerName, callableName);
        if (lookup == null) {
            throw new IllegalStateException(
                    "construct_standalone_callable " + kindToken + " '" + ownerName
                            + "." + callableName + "' is not a generated static function"
            );
        }
        return lookup;
    }

    private static @NotNull FunctionDef requireStaticFunction(
            @NotNull ClassDef classDef,
            @NotNull String callableName,
            @NotNull String kindToken
    ) {
        FunctionDef found = null;
        for (var function : classDef.getFunctions()) {
            if (!function.getName().equals(callableName) || !function.isStatic()) {
                continue;
            }
            if (found != null) {
                throw new IllegalStateException(
                        "construct_standalone_callable " + kindToken + " '" + classDef.getName()
                                + "." + callableName + "' is overloaded"
                );
            }
            found = function;
        }
        if (found == null) {
            throw new IllegalStateException(
                    "construct_standalone_callable " + kindToken + " '" + classDef.getName()
                            + "." + callableName + "' is not a generated static function"
            );
        }
        return found;
    }
}
