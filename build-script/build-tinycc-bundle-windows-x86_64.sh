#!/usr/bin/env bash
# Builds the windows-x86_64 tinycc bundle by CROSS-COMPILING from a Linux host, using the
# vendored sources in src/main/c/tinycc/. (On a Windows host, upstream's win32/build-tcc.bat
# is the supported entry point instead; this script is the CI-friendly path.)
#
# Output: build/tinycc-bundle/windows-x86_64/
#   bin/libtcc.dll         FFM loading target
#   include/               merged tree of tinycc's own headers AND win32/include (the Windows
#     include/winapi/     CRT headers: windows.h depends on _mingw.h sitting next to it, so
#                          the merge must be flat — no include/win32/ level)
#   lib/libtcc1.a          runtime support archive: the PE library search path expands {B}/lib,
#                          so the archive lives under lib/ (renamed from x86_64-win32-libtcc1.a)
#   lib/*.def              import definitions (msvcrt, kernel32, user32, gdi32, ws2_32) — a PE
#                          link resolves the runtime against these
#   VERSION, MANIFEST, COPYING, RELICENSING — same contract as the Linux bundle
#
# Why --config-predefs=no: with predefs enabled the Makefile generates tccdefs_.h from
# include/tccdefs.h via a freshly built host tool (c2str); disabling predefs makes the
# compiler read tccdefs.h from the bundle include/ tree at runtime instead, which removes
# that host-tool dependency from the PE library build.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
SRC_DIR="$REPO_ROOT/src/main/c/tinycc"
WORK_DIR="$REPO_ROOT/build/tinycc/windows-x86_64"
BUNDLE_DIR="$REPO_ROOT/build/tinycc-bundle/windows-x86_64"
TINYCC_COMMIT="43c7708b85681a2fd4451c8a541af4494a8919b2"
BUNDLE_FORMAT_VERSION="1"
PLATFORM_KEY="windows-x86_64"
NPROC="$(nproc 2>/dev/null || echo 4)"

# ---- preconditions ---------------------------------------------------------
[ "$(uname -s)" = "Linux" ] || { echo "error: this cross-build script must run on a Linux host" >&2; exit 1; }
command -v zig >/dev/null || { echo "error: zig is required (build tool and PE cross linker)" >&2; exit 1; }
grep -q "$TINYCC_COMMIT" "$SRC_DIR/GDCC_PIN.md" || {
    echo "error: $SRC_DIR/GDCC_PIN.md does not record the pinned commit $TINYCC_COMMIT — vendored tree and script disagree" >&2
    exit 1
}

# ---- pristine build tree ----------------------------------------------------
# Never configure or build inside the vendored source tree.
rm -rf "$WORK_DIR" "$BUNDLE_DIR"
mkdir -p "$WORK_DIR" "$BUNDLE_DIR"
cp -a "$SRC_DIR/." "$WORK_DIR/"
rm -f "$WORK_DIR/GDCC_PIN.md"

# Deterministic toolchain environment: see the Linux script for why ambient search paths and
# CFLAGS/CPPFLAGS/LDFLAGS/MAKEFLAGS must not leak into the build.
export LC_ALL=C
unset LIBRARY_PATH LD_LIBRARY_PATH C_INCLUDE_PATH CPLUS_INCLUDE_PATH CPATH || true
unset CFLAGS CPPFLAGS CXXFLAGS LDFLAGS LDLIBS MAKEFLAGS GNUMAKEFLAGS || true

cd "$WORK_DIR"

# ---- configure --------------------------------------------------------------
# tcc.h includes config.h unconditionally, so configure must run before ANY direct zig cc
# invocation — including the libtcc.dll link below, which never goes through the Makefile.
./configure --cc="zig cc" --config-predefs=no

# ---- build ------------------------------------------------------------------
# Upstream cross target: builds x86_64-win32-tcc (a Linux-hosted compiler that EMITS PE) and
# runs it to self-compile the win32 runtime archive x86_64-win32-libtcc1.a.
make -j"$NPROC" cross-x86_64-win32

# The FFM-loaded library for the Windows host. -fno-sanitize=undefined is required: zig's
# default UBSan trips an alignment panic in the PE import-generation path (pe_build_imports).
zig cc -target x86_64-windows-gnu -fno-sanitize=undefined -shared \
    -DTCC_TARGET_PE -DTCC_TARGET_X86_64 -DLIBTCC_AS_DLL \
    libtcc.c -o libtcc.dll

# ---- assemble the bundle (mirrors upstream install-win semantics) ------------
mkdir -p "$BUNDLE_DIR/bin" "$BUNDLE_DIR/include" "$BUNDLE_DIR/lib"
cp libtcc.dll "$BUNDLE_DIR/bin/"
cp include/*.h tcclib.h "$BUNDLE_DIR/include/"
# Recursive merge: win32/include content lands INSIDE include/ (winapi -> include/winapi/).
cp -r win32/include/. "$BUNDLE_DIR/include/"
cp x86_64-win32-libtcc1.a "$BUNDLE_DIR/lib/libtcc1.a"
cp win32/lib/*.def "$BUNDLE_DIR/lib/"
cp COPYING RELICENSING "$BUNDLE_DIR/"

cat > "$BUNDLE_DIR/VERSION" <<EOF
bundleFormat=$BUNDLE_FORMAT_VERSION
tinyccCommit=$TINYCC_COMMIT
platform=$PLATFORM_KEY
EOF

( cd "$BUNDLE_DIR" && find . -type f ! -name MANIFEST -printf '%P\n' | LC_ALL=C sort | xargs sha256sum > MANIFEST )

# ---- verify: structural ------------------------------------------------------
# A PE artifact cannot run on this host, so verification stops at structure: the PE magic and
# every layout entry the runtime contract requires.
head -c 2 "$BUNDLE_DIR/bin/libtcc.dll" | grep -q 'MZ' || {
    echo "error: $BUNDLE_DIR/bin/libtcc.dll is not a PE artifact (missing MZ magic)" >&2
    exit 1
}
for entry in \
    VERSION COPYING RELICENSING MANIFEST \
    bin/libtcc.dll lib/libtcc1.a \
    include/tccdefs.h include/stddef.h include/stdarg.h \
    include/_mingw.h include/winapi/windows.h \
    lib/msvcrt.def lib/kernel32.def lib/user32.def lib/gdi32.def lib/ws2_32.def; do
    [ -e "$BUNDLE_DIR/$entry" ] || { echo "error: bundle is missing $entry" >&2; exit 1; }
done

echo
echo "bundle assembled and verified at: $BUNDLE_DIR"
