package gd.script.gdcc.backend.c.build;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Zig-gated boundary lock for `pow_int` (`gdcc/gdcc_operator.h`): the accumulator intermediates
/// are `uint64_t`, so multiplication wraps modulo 2^64 and the final conversion to `godot_int`
/// keeps the low 64 bits — exactly Java `long` overflow semantics. Expected values are computed
/// here with a Java reference port of the same square-and-multiply algorithm, so the C probe and
/// this oracle must agree bit-for-bit on wrap-around cases (2^63, 2^64, INT64_MIN/MAX bases,
/// negative bases/exponents). Skipped via assumption when no zig is on the machine.
class GdccOperatorPowSmokeTest {
    private static final Path GODOT_INCLUDE_DIR = Path.of("src/main/c/codegen/include_451/godot").toAbsolutePath().normalize();
    private static final Path GDCC_INCLUDE_DIR = Path.of("src/main/c/codegen/include_451/gdcc").toAbsolutePath().normalize();

    @TempDir
    private static Path sharedDir;

    @Test
    void powIntShouldMatchTwoComplementWrapSemanticsOnBoundaries() throws IOException, InterruptedException {
        var zig = ZigUtil.findZig();
        Assumptions.assumeTrue(zig != null, "Zig executable is required for the pow_int boundary smoke test");

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

        var probeSource = renderProbe(cases);
        var sourceFile = sharedDir.resolve("pow_int_boundary_probe.c");
        Files.writeString(sourceFile, probeSource, StandardCharsets.UTF_8);
        var executable = sharedDir.resolve("pow_int_boundary_probe");

        var command = List.of(
                zig.toString(), "cc", "-std=c23", "-D_DEFAULT_SOURCE",
                "-I" + GODOT_INCLUDE_DIR, "-I" + GDCC_INCLUDE_DIR,
                sourceFile.toString(), "-o", executable.toString());
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        var buildOutput = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), () -> String.join(" ", command) + "\n" + buildOutput);

        var run = new ProcessBuilder(executable.toString()).redirectErrorStream(true).start();
        var runOutput = new String(run.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        var exitCode = run.waitFor();
        assertEquals(0, exitCode, () -> "pow_int boundary mismatches:\n" + runOutput);
        assertTrue(runOutput.contains("POW_INT_OK"), () -> "probe did not complete:\n" + runOutput);
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

    private static String renderProbe(List<long[]> cases) {
        var sb = new StringBuilder();
        sb.append("""
                #include <godot_binding.h>
                #include "gdcc_operator.h"
                #include <stdio.h>
                #include <stdint.h>

                static int failures = 0;
                static void check(const char *label, godot_int actual, godot_int expected) {
                    if (actual != expected) {
                        printf("FAIL %s: actual=%lld expected=%lld\\n",
                               label, (long long)actual, (long long)expected);
                        failures++;
                    }
                }

                int main(void) {
                """);
        for (var c : cases) {
            sb.append("    check(\"")
                    .append(c[0]).append('^').append(c[1])
                    .append("\", pow_int(")
                    .append(cLiteral(c[0])).append(", ")
                    .append(cLiteral(c[1])).append("), ")
                    .append(cLiteral(referencePowInt(c[0], c[1])))
                    .append(");\n");
        }
        sb.append("""
                    if (failures == 0) {
                        puts("POW_INT_OK");
                    }
                    return failures;
                }
                """);
        return sb.toString();
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
