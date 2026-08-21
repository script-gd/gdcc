package gd.script.gdcc.backend.c.gen;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/// Shared float literal normalization for C code generation.
///
/// This helper is the single source of truth for turning non-finite float payloads into valid C
/// literals. It serves both producer routes so they never drift apart:
/// - IEEE double values from `LiteralFloatInsn` (consumed by `NewDataInsnGen` in the `insn` subpackage)
/// - source-text literals from engine metadata (consumed by `CBuiltinBuilder` static constant and
///   constructor materialization)
///
/// The class must stay public because the two callers live in different packages
/// (`gd.script.gdcc.backend.c.gen` and `gd.script.gdcc.backend.c.gen.insn`).
///
/// `godot_inf` is provided by the generated builtin-type header (`#define godot_inf INFINITY`);
/// `NAN` is the standard `<math.h>` macro that the same header already includes.
public final class CFloatLiteralSupport {
    private CFloatLiteralSupport() {
    }

    /// Renders an IEEE double value as a valid C float literal.
    ///
    /// `Double.toString(...)` emits `Infinity` / `NaN` for non-finite values, which are not valid
    /// C literals, so those values are mapped onto the header-provided macros instead.
    public static @NotNull String renderFloatLiteral(double value) {
        if (Double.isNaN(value)) {
            return "NAN";
        }
        if (value == Double.POSITIVE_INFINITY) {
            return "godot_inf";
        }
        if (value == Double.NEGATIVE_INFINITY) {
            return "-godot_inf";
        }
        return Double.toString(value);
    }

    /// Normalizes a source-text float literal, mapping recognized non-finite spellings onto the
    /// same C macros used by [renderFloatLiteral]. Every other literal passes through unchanged.
    public static @NotNull String normalizeSourceFloatLiteral(@NotNull String literal) {
        var mapped = mapNonFiniteFloatLiteral(literal);
        return mapped != null ? mapped : literal;
    }

    /// Answers whether the source text is one of the recognized non-finite float literals
    /// (`inf` family or `nan`), independent of value sign spelling.
    public static boolean isNonFiniteFloatLiteral(@NotNull String literal) {
        return mapNonFiniteFloatLiteral(literal) != null;
    }

    private static @Nullable String mapNonFiniteFloatLiteral(@NotNull String literal) {
        return switch (literal.trim().toLowerCase(Locale.ROOT)) {
            case "inf", "+inf", "infinity", "+infinity" -> "godot_inf";
            case "-inf", "-infinity" -> "-godot_inf";
            case "nan" -> "NAN";
            default -> null;
        };
    }
}
