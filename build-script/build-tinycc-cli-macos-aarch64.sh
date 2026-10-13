#!/usr/bin/env bash
# Builds the vendored tinycc CLI (plus the runtime archive and headers it needs to compile and
# link) from src/main/c/tinycc/ into build/tinycc/macos-aarch64/. This is the test-facing entry
# point: the Gradle `buildTinyccCli` task runs it before `test` when the CLI is missing or stale
# (e.g. after `gradlew clean`); tests only look up the result and never spawn this script
# themselves. The distribution artifact (libtcc bundle) remains the job of
# build-tinycc-bundle-macos-aarch64.sh; this script is a strict subset of that pipeline.
#
# The CLI is only ever run as a subprocess, so it does NOT need the two-level namespace rebuild
# the bundle's in-process dylib requires (upstream's flat namespace default only bites libraries
# loaded into a symbol-rich host process).
#
# Output: build/tinycc/macos-aarch64/
#   tcc                    the CLI binary (includes the local u8"..." prefix patch)
#   libtcc1.a              runtime support archive at the work-tree ROOT — the Mach-O backend
#                          expands {B} itself for libraries, so the work tree doubles as a `-B` root
#   include/               tinycc's own headers (tccdefs.h, stddef.h, stdarg.h, ...)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
SRC_DIR="$REPO_ROOT/src/main/c/tinycc"
WORK_DIR="$REPO_ROOT/build/tinycc/macos-aarch64"
TINYCC_COMMIT="43c7708b85681a2fd4451c8a541af4494a8919b2"
NPROC="$(sysctl -n hw.ncpu 2>/dev/null || echo 4)"

# ---- preconditions ---------------------------------------------------------
[ "$(uname -s)" = "Darwin" ] && [ "$(uname -m)" = "arm64" ] || {
    echo "error: this script builds the macos-aarch64 CLI and must run on macOS arm64" >&2
    exit 1
}
command -v clang >/dev/null || { echo "error: clang is required (install the Xcode command line tools)" >&2; exit 1; }
grep -q "$TINYCC_COMMIT" "$SRC_DIR/GDCC_PIN.md" || {
    echo "error: $SRC_DIR/GDCC_PIN.md does not record the pinned commit $TINYCC_COMMIT — vendored tree and script disagree" >&2
    exit 1
}

# ---- pristine build tree ----------------------------------------------------
# Never configure or build inside the vendored source tree.
rm -rf "$WORK_DIR"
mkdir -p "$WORK_DIR"
cp -a "$SRC_DIR/." "$WORK_DIR/"
rm -f "$WORK_DIR/GDCC_PIN.md"

# Deterministic toolchain environment. Search paths (LIBRARY_PATH, C_INCLUDE_PATH, ...) would
# leak site-specific directories into the build, and configure/Makefile honor ambient
# CFLAGS/CPPFLAGS/LDFLAGS and MAKEFLAGS/GNUMAKEFLAGS variable overrides. Build flags must come
# from the vendored build system alone.
export LC_ALL=C
unset LIBRARY_PATH LD_LIBRARY_PATH C_INCLUDE_PATH CPLUS_INCLUDE_PATH CPATH || true
unset CFLAGS CPPFLAGS CXXFLAGS LDFLAGS LDLIBS MAKEFLAGS GNUMAKEFLAGS || true

cd "$WORK_DIR"

# ---- configure & build ------------------------------------------------------
# --disable-static skips the unused static libtcc; configure probes and bakes the host SDK
# paths, so the CLI requires a machine with the Xcode command line tools.
./configure --disable-static --libdir="$PWD"
make -j"$NPROC" tcc
# Self-bootstrapped by the just-built tcc via lib/Makefile.
make -j"$NPROC" libtcc1.a

# ---- verify -----------------------------------------------------------------
[ -x "$WORK_DIR/tcc" ] || { echo "error: $WORK_DIR/tcc was not produced" >&2; exit 1; }
[ -f "$WORK_DIR/libtcc1.a" ] || { echo "error: $WORK_DIR/libtcc1.a was not produced" >&2; exit 1; }
[ -f "$WORK_DIR/include/tccdefs.h" ] || { echo "error: $WORK_DIR/include/tccdefs.h missing" >&2; exit 1; }
# The local u8 prefix patch must be live in the built compiler; a compiler without it is not
# the vendored one and must never reach the tests.
echo 'const char *s = u8"patch-probe"; int main(void) { return s[0] != 112 || s[5] != 45; }' > .u8-probe.c
./tcc -B"$WORK_DIR" -std=c11 -o .u8-probe .u8-probe.c || {
    echo "error: the built tcc rejected u8\"...\" — the vendored u8 patch is missing" >&2
    exit 1
}
./.u8-probe || { echo "error: u8 probe binary returned a wrong result" >&2; exit 1; }
rm -f .u8-probe .u8-probe.c

# Written last: the Gradle task declares this marker as an output, so a build killed before this
# point (binary present, probe not passed) is rebuilt instead of trusted.
: > "$WORK_DIR/cli-verified"

echo
echo "tinycc CLI built and verified at: $WORK_DIR/tcc"
