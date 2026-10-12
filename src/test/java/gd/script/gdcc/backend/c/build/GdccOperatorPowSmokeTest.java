package gd.script.gdcc.backend.c.build;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Boundary lock for `pow_int` (`gdcc/gdcc_operator.h`): the accumulator intermediates are
/// `uint64_t`, so multiplication wraps modulo 2^64 and the final conversion to `godot_int` keeps
/// the low 64 bits — exactly Java `long` overflow semantics. Expected values are computed here
/// with a Java reference port of the same square-and-multiply algorithm, so the C probe and this
/// oracle must agree bit-for-bit on wrap-around cases (2^63, 2^64, INT64_MIN/MAX bases, negative
/// bases/exponents). The same probe runs through both compiler legs (zig baseline and tcc) via
/// [RuntimeProbeToolchain]; each leg is assumption-gated on its own toolchain, and oracle-based
/// assertions make the two artifacts behaviorally interchangeable by construction.
class GdccOperatorPowSmokeTest {
    @TempDir
    private static Path sharedDir;

    @Test
    void powIntShouldMatchTwoComplementWrapSemanticsOnBoundaries() throws IOException, InterruptedException {
        var toolchain = RuntimeProbeToolchain.zig();
        Assumptions.assumeTrue(toolchain != null, "Zig executable is required for the pow_int boundary smoke test");
        assertPowBoundaries(toolchain);
    }

    /// tcc leg of the same boundary lock: identical probe source, identical oracle expectations —
    /// the uint64_t wrap semantics must not depend on the compiler.
    @Test
    void powIntBoundariesMatchTheOracleOnTccArtifact() throws IOException, InterruptedException {
        var toolchain = RuntimeProbeToolchain.tcc();
        Assumptions.assumeTrue(toolchain != null, "tinycc CLI is required for the tcc pow boundary leg");
        assertPowBoundaries(toolchain);
    }

    private static void assertPowBoundaries(@NotNull RuntimeProbeToolchain toolchain) throws IOException, InterruptedException {
        var probeSource = renderProbe(boundaryCases());
        var run = toolchain.compileLinkAndRun(sharedDir, "pow_int_boundary_probe", probeSource, List.of(), List.of());
        assertEquals(0, run.exitCode(), () -> "pow_int boundary mismatches:\n" + run.diagnostic());
        assertTrue(run.output().contains("POW_INT_OK"), () -> "probe did not complete:\n" + run.diagnostic());
    }

    private static @NotNull List<long[]> boundaryCases() {
        var cases = new ArrayList<long[]>();
        // Overflow/wrap boundary: results must wrap at 64 bits, never UB or clamp.
        cases.add(new long[]{2, 63});
        cases.add(new long[]{2, 64});
        cases.add(new long[]{2, 1000});
        cases.add(new long[]{3, 40});
        cases.add(new long[]{-3, 41});
        cases.add(new long[]{-2, 63});
        cases.add(new long[]{-2, 64});
        cases.add(new long[]{Long.MAX_VALUE, 2});
        cases.add(new long[]{Long.MIN_VALUE, 2});
        cases.add(new long[]{Long.MIN_VALUE, 3});
        cases.add(new long[]{Long.MAX_VALUE, Long.MAX_VALUE});
        // Negative-exponent contract: only bases 1/-1 survive, everything else collapses to 0.
        cases.add(new long[]{1, -7});
        cases.add(new long[]{-1, -3});
        cases.add(new long[]{-1, -4});
        cases.add(new long[]{5, -1});
        cases.add(new long[]{Long.MIN_VALUE, -1});
        // Degenerate bases.
        cases.add(new long[]{0, 0});
        cases.add(new long[]{0, 5});
        cases.add(new long[]{0, -2});
        cases.add(new long[]{-1, 0});
        return cases;
    }

    /// Java oracle mirroring the C algorithm; `long` multiplication wraps mod 2^64 exactly like
    /// the `uint64_t` intermediates followed by the truncating conversion to `godot_int`.
    private static long referencePowInt(long base, long exp) {
        if (exp == 0) {
            return 1;
        }
        if (exp < 0) {
            if (base == 1) {
                return 1;
            }
            if (base == -1) {
                return (exp % 2 != 0) ? -1 : 1;
            }
            return 0;
        }
        long result = 1;
        long factor = base;
        var positiveExp = exp;
        while (positiveExp > 0) {
            if ((positiveExp & 1) != 0) {
                result *= factor;
            }
            positiveExp >>= 1;
            if (positiveExp > 0) {
                factor *= factor;
            }
        }
        return result;
    }

    /// The probe skeleton lives in `src/test/resources/c/pow_int_boundary_probe.c`; only the
    /// per-case check lines are generated (expected values come from the Java oracle).
    private static String renderProbe(List<long[]> cases) {
        var checks = new StringBuilder();
        for (var c : cases) {
            checks.append("    check(\"")
                    .append(c[0]).append('^').append(c[1])
                    .append("\", pow_int(")
                    .append(cLiteral(c[0])).append(", ")
                    .append(cLiteral(c[1])).append("), ")
                    .append(cLiteral(referencePowInt(c[0], c[1])))
                    .append(");\n");
        }
        return CProbeSources.load("pow_int_boundary_probe.c").replace("// @@POW_CASES@@", checks.toString().stripTrailing());
    }

    /// int64 extremes are not expressible as decimal C literals, so they go through the macros.
    private static String cLiteral(long value) {
        if (value == Long.MIN_VALUE) {
            return "INT64_MIN";
        }
        if (value == Long.MAX_VALUE) {
            return "INT64_MAX";
        }
        return value + "LL";
    }
}
