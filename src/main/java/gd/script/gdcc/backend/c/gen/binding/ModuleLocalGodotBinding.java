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
    @NotNull GodotBindingSymbol symbol();

    @NotNull String lookupName();

    default @NotNull String familyName() {
        return symbol().family().name();
    }

    default @NotNull String cFunctionName() {
        return symbol().cFunctionName();
    }

    default @NotNull String escapedCFunctionName() {
        return GodotBindingSupport.escapeCString(symbol().cFunctionName());
    }

    default @NotNull String returnType() {
        return symbol().returnType();
    }

    default @NotNull String escapedOwner() {
        return GodotBindingSupport.escapeCString(symbol().owner());
    }

    default @NotNull String escapedLookupName() {
        return GodotBindingSupport.escapeCString(lookupName());
    }

    default @NotNull ModuleLocalGodotBinding mergeCompatible(@NotNull ModuleLocalGodotBinding other) {
        if (this instanceof Singleton && other instanceof Singleton) {
            return checkCommonMetadata(other);
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

    private @NotNull ModuleLocalGodotBinding checkCommonMetadata(@NotNull ModuleLocalGodotBinding other) {
        if (!symbol().signatureKey().equals(other.symbol().signatureKey())
                || !symbol().cFunctionName().equals(other.symbol().cFunctionName())
                || !symbol().owner().equals(other.symbol().owner())
                || !symbol().name().equals(other.symbol().name())
                || !lookupName().equals(other.lookupName())) {
            throw new IllegalStateException(
                    "Incompatible module-local Godot binding metadata for '" + symbol().cFunctionName() + "'"
            );
        }
        return this;
    }

    static @NotNull Singleton singleton(@NotNull String className) {
        var cName = GodotBindingSupport.cIdentifier(className);
        return new Singleton(
                GodotBindingSupport.symbol(
                        GodotBindingSymbol.Family.SINGLETON,
                        className,
                        "singleton",
                        "godot_" + cName + "_singleton",
                        "godot_" + cName + " *",
                        List.of(),
                        false,
                        null,
                        List.of()
                ),
                className
        );
    }

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

    record Singleton(
            @NotNull GodotBindingSymbol symbol,
            @NotNull String lookupName
    ) implements ModuleLocalGodotBinding {
        public Singleton {
            Objects.requireNonNull(symbol);
            Objects.requireNonNull(lookupName);
            if (symbol.family() != GodotBindingSymbol.Family.SINGLETON) {
                throw new IllegalArgumentException("Module-local singleton binding must use SINGLETON family");
            }
        }

        public @NotNull String cacheName() {
            return "gdcc_module_singleton_" + GodotBindingSupport.cIdentifier(symbol.owner());
        }
    }

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
