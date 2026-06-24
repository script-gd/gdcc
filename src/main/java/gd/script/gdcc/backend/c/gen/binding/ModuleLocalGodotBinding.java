package gd.script.gdcc.backend.c.gen.binding;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/// Complete generation material for one module-local Godot wrapper.
///
/// Only singleton getters and class constants currently need this module-local path. Exact engine
/// methods and constructors have dedicated backend-owned usage collectors, so they intentionally do
/// not appear here as generic wrapper kinds.
public sealed interface ModuleLocalGodotBinding
        permits ModuleLocalGodotBinding.Singleton, ModuleLocalGodotBinding.ClassConstant {
    /// Structural identity for the generated wrapper.
    ///
    /// `symbol` owns the C ABI shape, C function name, logical owner/name pair, and merge key. It
    /// is intentionally separate from lookup metadata such as singleton registry names.
    @NotNull GodotBindingSymbol symbol();

    /// Godot metadata name used by the generated wrapper to perform the runtime lookup.
    ///
    /// For singleton bindings this comes from `ExtensionSingleton.name()` / `LoadStaticInsn.staticName()`
    /// and is passed to `godot_global_get_singleton(...)`. For class constants this is the constant
    /// member name used for diagnostics and wrapper identity.
    @NotNull String lookupName();

    /// Template-facing family discriminator derived from `GodotBindingSymbol.Family`.
    ///
    /// This selects the module-local renderer branch; it is not a Godot lookup name.
    default @NotNull String familyName() {
        return symbol().family().name();
    }

    /// Public wrapper C function name emitted into generated C.
    ///
    /// The name comes from `GodotBindingSymbol` so provided-symbol filtering and C-name conflict
    /// checks all use the same identity. Singleton wrappers use `godot_<lookupName>_singleton()`;
    /// class constants use `godot_<owner>_<constant>()`.
    default @NotNull String cFunctionName() {
        return symbol().cFunctionName();
    }

    /// `cFunctionName()` escaped for C string literal contexts such as lookup diagnostics.
    default @NotNull String escapedCFunctionName() {
        return GodotBindingSupport.escapeCString(symbol().cFunctionName());
    }

    /// C ABI return type for the wrapper.
    ///
    /// For singleton bindings this is derived from the registry-validated declared return type
    /// (`ExtensionSingleton.type()`), not from the singleton lookup name. For class constants it is
    /// currently `godot_int`.
    default @NotNull String returnType() {
        return symbol().returnType();
    }

    /// Logical owner escaped for C string literal diagnostics.
    ///
    /// Singleton bindings always use `@GlobalScope` as the symbol owner; class constants use the
    /// declaring engine/builtin class name. This value is diagnostic metadata, not the singleton
    /// registry lookup string.
    default @NotNull String escapedOwner() {
        return GodotBindingSupport.escapeCString(symbol().owner());
    }

    /// `lookupName()` escaped for C string literal contexts.
    ///
    /// Singleton templates must use this for `godot_global_get_singleton(...)`; using owner or return
    /// type would break `lookupName != returnTypeName` metadata such as `GameSingleton -> Node`.
    default @NotNull String escapedLookupName() {
        return GodotBindingSupport.escapeCString(lookupName());
    }

    /// Declared singleton return type name escaped for lookup diagnostics.
    ///
    /// Singleton bindings store this explicitly because the Godot registry lookup name and returned
    /// object type are independent metadata fields. Non-singleton bindings fall back to the full C
    /// return type because they do not have a separate Godot type-name component.
    default @NotNull String escapedReturnTypeName() {
        return this instanceof Singleton singleton
                ? GodotBindingSupport.escapeCString(singleton.returnTypeName())
                : GodotBindingSupport.escapeCString(symbol().returnType());
    }

    default @NotNull ModuleLocalGodotBinding mergeCompatible(@NotNull ModuleLocalGodotBinding other) {
        if (this instanceof Singleton singleton && other instanceof Singleton otherSingleton) {
            checkCommonMetadata(other);
            if (!singleton.returnTypeName().equals(otherSingleton.returnTypeName())) {
                throw new IllegalStateException(
                        "Incompatible module-local Godot binding metadata for '" + symbol().cFunctionName() + "'"
                );
            }
            return this;
        }
        if (this instanceof ClassConstant constant && other instanceof ClassConstant otherConstant) {
            checkCommonMetadata(other);
            if (!constant.constantValue().equals(otherConstant.constantValue())) {
                throw new IllegalStateException(
                        "Incompatible module-local Godot binding metadata for '" + symbol().cFunctionName() + "'"
                );
            }
            return this;
        }
        throw new IllegalStateException(
                "Incompatible module-local Godot binding metadata for '" + symbol().cFunctionName() + "'"
        );
    }

    private void checkCommonMetadata(@NotNull ModuleLocalGodotBinding other) {
        if (!symbol().signatureKey().equals(other.symbol().signatureKey())
                || !symbol().cFunctionName().equals(other.symbol().cFunctionName())
                || !symbol().owner().equals(other.symbol().owner())
                || !symbol().name().equals(other.symbol().name())
                || !lookupName().equals(other.lookupName())) {
            throw new IllegalStateException(
                    "Incompatible module-local Godot binding metadata for '" + symbol().cFunctionName() + "'"
            );
        }
    }

    static @NotNull Singleton singleton(@NotNull String className) {
        return singleton(className, className);
    }

    /// Create a singleton getter wrapper from the two independent singleton metadata names.
    ///
    /// `lookupName` is the `@GlobalScope` property / Godot singleton registry name used for lookup
    /// and C symbol identity. `returnTypeName` is the registry-validated declared object type used
    /// for the C ABI return/cast/cache type.
    static @NotNull Singleton singleton(@NotNull String lookupName, @NotNull String returnTypeName) {
        var lookupCName = GodotBindingSupport.cIdentifier(lookupName);
        var returnTypeCName = GodotBindingSupport.cIdentifier(returnTypeName);
        return new Singleton(
                GodotBindingSupport.symbol(
                        GodotBindingSymbol.Family.SINGLETON,
                        "@GlobalScope",
                        lookupName,
                        "godot_" + lookupCName + "_singleton",
                        "godot_" + returnTypeCName + " *",
                        List.of(),
                        false,
                        null,
                        List.of()
                ),
                lookupName,
                returnTypeName
        );
    }

    /// Create a class constant wrapper.
    ///
    /// `className` is the declaring class owner used for C symbol identity; `constantName` is the
    /// metadata member/lookup name; `constantValue` is the literal returned by the wrapper.
    static @NotNull ClassConstant classConstant(
            @NotNull String className,
            @NotNull String constantName,
            @NotNull String constantValue
    ) {
        var cName = GodotBindingSupport.cIdentifier(className);
        var constantCName = GodotBindingSupport.cIdentifier(constantName);
        return new ClassConstant(
                GodotBindingSupport.symbol(
                        GodotBindingSymbol.Family.CLASS_CONSTANT,
                        className,
                        constantName,
                        "godot_" + cName + "_" + constantCName,
                        "godot_int",
                        List.of(),
                        false,
                        null,
                        List.of()
                ),
                constantName,
                constantValue
        );
    }

    /// @param lookupName Godot singleton registry lookup name; source is `ExtensionSingleton.name()`.
    /// @param returnTypeName Registry-validated declared object type name; source is `ExtensionSingleton.type()`.
    record Singleton(
            @NotNull GodotBindingSymbol symbol,
            @NotNull String lookupName,
            @NotNull String returnTypeName
    ) implements ModuleLocalGodotBinding {
        public Singleton {
            Objects.requireNonNull(symbol);
            Objects.requireNonNull(lookupName);
            Objects.requireNonNull(returnTypeName);
            if (symbol.family() != GodotBindingSymbol.Family.SINGLETON) {
                throw new IllegalArgumentException("Module-local singleton binding must use SINGLETON family");
            }
        }

        /// Static cache variable name for one singleton lookup wrapper.
        ///
        /// The cache is keyed by `lookupName`, not by returned type, so two singleton names with the
        /// same declared object type never share cached pointers.
        public @NotNull String cacheName() {
            return "gdcc_module_singleton_" + GodotBindingSupport.cIdentifier(lookupName);
        }
    }

    /// @param lookupName Class constant member name used in diagnostics and module-local wrapper metadata.
    record ClassConstant(
            @NotNull GodotBindingSymbol symbol,
            @NotNull String lookupName,
            @NotNull String constantValue
    ) implements ModuleLocalGodotBinding {
        public ClassConstant {
            Objects.requireNonNull(symbol);
            Objects.requireNonNull(lookupName);
            Objects.requireNonNull(constantValue);
            if (constantValue.isBlank()) {
                throw new IllegalArgumentException("Module-local class constant binding requires a value");
            }
            if (symbol.family() != GodotBindingSymbol.Family.CLASS_CONSTANT) {
                throw new IllegalArgumentException("Module-local class constant binding must use CLASS_CONSTANT family");
            }
        }
    }
}
