#!/usr/bin/env bash
# Builds the macos-aarch64 tinycc bundle from the vendored sources in src/main/c/tinycc/
# and verifies it with structural assertions plus a relocation smoke test.
#
# Output: build/tinycc-bundle/macos-aarch64/
#   bin/libtcc.dylib       FFM loading target (exports the tcc_* API; checked below). MUST carry
#                          the two-level namespace flag: upstream's Makefile links the host dylib
#                          with -flat_namespace, and a flat-namespace libtcc mis-binds its global
#                          symbols (e.g. parse_flags) to same-named symbols of other libraries
#                          inside a JVM process, crashing the first FFM compile
#   include/               tinycc's own headers (tccdefs.h, stddef.h, stdarg.h, ...) — the host
#                          C library headers do not provide these, so the bundle must carry them
#   libtcc1.a              runtime support archive at the bundle ROOT: the Mach-O backend
#                          expands {B} itself for libraries, not {B}/lib
#   VERSION                bundle format version + pinned tinycc commit + platform key
#   MANIFEST               sha256sum-format lines over every other bundle file (install
#                          integrity); produced with shasum because macOS has no sha256sum
#   COPYING, RELICENSING   upstream licenses, shipped with every bundle
#
# The bundle deliberately relies on the HOST Xcode CLT/SDK for CRT objects, libSystem and the
# system headers: configure probes the SDK paths and bakes them in, so a bundle is only valid
# on machines with a compatible SDK (a recorded distribution limitation). The relocation smoke
# at the end proves the bundle directory itself is movable.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
SRC_DIR="$REPO_ROOT/src/main/c/tinycc"
WORK_DIR="$REPO_ROOT/build/tinycc/macos-aarch64"
BUNDLE_DIR="$REPO_ROOT/build/tinycc-bundle/macos-aarch64"
TINYCC_COMMIT="43c7708b85681a2fd4451c8a541af4494a8919b2"
BUNDLE_FORMAT_VERSION="1"
PLATFORM_KEY="macos-aarch64"
NPROC="$(sysctl -n hw.ncpu 2>/dev/null || echo 4)"

# ---- preconditions ---------------------------------------------------------
[ "$(uname -s)" = "Darwin" ] && [ "$(uname -m)" = "arm64" ] || {
    echo "error: this script builds the macos-aarch64 bundle and must run on macOS arm64" >&2
    exit 1
}
# Host clang plus the Apple toolchain utilities come from the Xcode command line tools.
command -v clang >/dev/null || { echo "error: clang is required (install the Xcode command line tools)" >&2; exit 1; }
command -v otool >/dev/null || { echo "error: otool is required for the Mach-O structural assertions" >&2; exit 1; }
command -v codesign >/dev/null || { echo "error: codesign is required for the signature assertion" >&2; exit 1; }
command -v shasum >/dev/null || { echo "error: shasum is required for the MANIFEST" >&2; exit 1; }
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

# Deterministic toolchain environment. Search paths (LIBRARY_PATH, C_INCLUDE_PATH, ...) would
# leak site-specific directories into both the libtcc build and the smoke's link search, and
# configure/Makefile honor ambient CFLAGS/CPPFLAGS/LDFLAGS and MAKEFLAGS/GNUMAKEFLAGS variable
# overrides — e.g. an inherited CFLAGS=-fvisibility=hidden would silently hide every tcc_*
# export. Build flags must come from the vendored build system alone.
export LC_ALL=C
unset LIBRARY_PATH LD_LIBRARY_PATH C_INCLUDE_PATH CPLUS_INCLUDE_PATH CPATH || true
unset CFLAGS CPPFLAGS CXXFLAGS LDFLAGS LDLIBS MAKEFLAGS GNUMAKEFLAGS || true

cd "$WORK_DIR"

# ---- build ------------------------------------------------------------------
# --disable-static skips the unused static libtcc; --libdir="$PWD" keeps the dylib in the work
# root. configure bakes the host SDK paths (headers, CRT) into the library.
./configure --disable-static --libdir="$PWD"
# Order matters: the native tcc CLI bootstraps libtcc1.a via lib/Makefile.
make -j"$NPROC" tcc libtcc.dylib libtcc1.a

# Two-level namespace rebuild. Upstream adds -flat_namespace to the host dylib's LDFLAGS on
# Darwin; passing LDFLAGS on the command line overrides that += assignment, and -B forces the
# relink. A flat-namespace libtcc is unusable inside the JVM (see the header comment), so this
# leg is mandatory, not cosmetic.
make -B -j"$NPROC" LDFLAGS=-Wl,-twolevel_namespace tcc libtcc.dylib

# ---- assemble the bundle ----------------------------------------------------
mkdir -p "$BUNDLE_DIR/bin" "$BUNDLE_DIR/include"
cp libtcc.dylib "$BUNDLE_DIR/bin/"
cp libtcc1.a "$BUNDLE_DIR/"
cp include/*.h tcclib.h "$BUNDLE_DIR/include/"
cp COPYING RELICENSING "$BUNDLE_DIR/"

cat > "$BUNDLE_DIR/VERSION" <<EOF
bundleFormat=$BUNDLE_FORMAT_VERSION
tinyccCommit=$TINYCC_COMMIT
platform=$PLATFORM_KEY
EOF

# Sorted sha256sum-format manifest over every other bundle file; consumed by the installer to
# verify a staged tree before it is published. BSD find has no -printf, so the leading ./ is
# stripped with sed instead.
( cd "$BUNDLE_DIR" && find . -type f ! -name MANIFEST | LC_ALL=C sort | sed 's|^\./||' | xargs shasum -a 256 > MANIFEST )

# ---- verify: Mach-O structure ----------------------------------------------
# Note: under `set -o pipefail`, `tool ... | grep -q` is a SIGPIPE race (grep exits on the
# first match while the tool is still writing); consume the whole stream instead.
otool -hv "$BUNDLE_DIR/bin/libtcc.dylib" | grep 'ARM64' >/dev/null || {
    echo "error: $BUNDLE_DIR/bin/libtcc.dylib is not a Mach-O arm64 image" >&2
    exit 1
}
otool -hv "$BUNDLE_DIR/bin/libtcc.dylib" | grep 'TWOLEVEL' >/dev/null || {
    echo "error: $BUNDLE_DIR/bin/libtcc.dylib lacks the two-level namespace flag — the rebuild leg did not take" >&2
    exit 1
}
# Apple Silicon links ad-hoc-sign by default; this asserts the signature, it does not add one.
codesign -v "$BUNDLE_DIR/bin/libtcc.dylib" || {
    echo "error: $BUNDLE_DIR/bin/libtcc.dylib fails codesign verification" >&2
    exit 1
}

# ---- verify: exports --------------------------------------------------------
# Mach-O decorates C symbols with one leading underscore.
nm -gU "$BUNDLE_DIR/bin/libtcc.dylib" | grep ' _tcc_new$' >/dev/null || {
    echo "error: $BUNDLE_DIR/bin/libtcc.dylib does not export tcc_new" >&2
    exit 1
}

# ---- verify: relocation smoke ----------------------------------------------
# The bundle is copied to a fresh directory and used ONLY through -B (the CLI equivalent of
# tcc_set_lib_path): the compiler's own headers and libtcc1.a come from the relocated bundle,
# while CRT/libSystem and the SDK headers resolve through the paths baked in by configure. A
# real shared-library link is required — compiling to an object would skip the CRT/libSystem
# chain entirely.
SMOKE_DIR="$(mktemp -d)"
trap 'rm -rf "$SMOKE_DIR"' EXIT
cp -a "$BUNDLE_DIR" "$SMOKE_DIR/bundle"
cat > "$SMOKE_DIR/smoke.c" <<'EOF'
#include <stdio.h>
#include <sys/mman.h>

__attribute__((visibility("default")))
int gdcc_tinycc_smoke(void) {
    void *page = mmap(NULL, 4096, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (page == MAP_FAILED) {
        return 1;
    }
    munmap(page, 4096);
    printf("tinycc relocation smoke ok\n");
    return 0;
}
EOF
"$WORK_DIR/tcc" -B "$SMOKE_DIR/bundle" -shared -o "$SMOKE_DIR/libsmoke.dylib" "$SMOKE_DIR/smoke.c"
nm -gU "$SMOKE_DIR/libsmoke.dylib" | grep ' _gdcc_tinycc_smoke$' >/dev/null || {
    echo "error: the smoke library does not export gdcc_tinycc_smoke" >&2
    exit 1
}
echo "relocation smoke: otool -L output for the tcc-linked smoke library:"
otool -L "$SMOKE_DIR/libsmoke.dylib"
otool -L "$SMOKE_DIR/libsmoke.dylib" | grep -q 'libSystem' || {
    echo "error: the smoke library does not dynamically link libSystem" >&2
    exit 1
}

echo
echo "bundle assembled and verified at: $BUNDLE_DIR"
