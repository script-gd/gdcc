#!/usr/bin/env bash
# Builds the linux-x86_64 tinycc bundle from the vendored sources in src/main/c/tinycc/
# and verifies it with a relocation smoke test.
#
# Output: build/tinycc-bundle/linux-x86_64/
#   bin/libtcc.so          FFM loading target (exports the tcc_* API; checked below)
#   include/               tinycc's own headers (tccdefs.h, stddef.h, stdarg.h, ...) — the host
#                          C library headers do not provide these, so the bundle must carry them
#   libtcc1.a              runtime support archive at the bundle ROOT: on Linux the library
#                          search path expands {B} itself, not {B}/lib
#   VERSION                bundle format version + pinned tinycc commit + platform key
#   MANIFEST               sha256sum lines over every other bundle file (install integrity)
#   COPYING, RELICENSING   upstream licenses, shipped with every bundle
#
# The bundle deliberately relies on the HOST for crti/crtn objects, glibc and the system
# headers (unlike zig's bundled sysroot): the CRT prefix and the multiarch triplet paths are
# probed by configure and baked into the library. The relocation smoke at the end proves the
# bundle directory itself is movable.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
SRC_DIR="$REPO_ROOT/src/main/c/tinycc"
WORK_DIR="$REPO_ROOT/build/tinycc/linux-x86_64"
BUNDLE_DIR="$REPO_ROOT/build/tinycc-bundle/linux-x86_64"
TINYCC_COMMIT="43c7708b85681a2fd4451c8a541af4494a8919b2"
BUNDLE_FORMAT_VERSION="1"
PLATFORM_KEY="linux-x86_64"
NPROC="$(nproc 2>/dev/null || echo 4)"

# ---- preconditions ---------------------------------------------------------
[ "$(uname -s)" = "Linux" ] && [ "$(uname -m)" = "x86_64" ] || {
    echo "error: this script builds the linux-x86_64 bundle and must run on Linux x86_64" >&2
    exit 1
}
command -v zig >/dev/null || { echo "error: zig is required (used as the C compiler via 'zig cc')" >&2; exit 1; }
command -v ldd >/dev/null || { echo "error: ldd is required for the relocation smoke record" >&2; exit 1; }
# tcc links against the host glibc and needs the multiarch CRT objects at their default
# location; configure only detects the x86_64-linux-gnu triplet when crti.o sits there.
[ -f /usr/lib/x86_64-linux-gnu/crti.o ] || {
    echo "error: /usr/lib/x86_64-linux-gnu/crti.o not found — install the libc development package (e.g. libc6-dev)" >&2
    exit 1
}
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
./configure --cc="zig cc"
# The multiarch triplet decides where libtcc looks for crti.o/crtn.o and libc at runtime.
# Fail loudly instead of silently producing a bundle with wrong host paths.
grep -q '#define CONFIG_TRIPLET "x86_64-linux-gnu"' config.h || {
    echo "error: configure did not detect the x86_64-linux-gnu multiarch triplet (see config.h)" >&2
    exit 1
}

# Order matters: the native tcc CLI bootstraps libtcc1.a via lib/Makefile.
make -j"$NPROC" tcc
# Upstream target: -shared -Wl,-soname,libtcc.so -fPIC, linked against -lm -ldl -lpthread.
# Visibility must stay default so the FFM layer can bind tcc_* symbols.
make -j"$NPROC" libtcc.so
make -j"$NPROC" libtcc1.a

# ---- assemble the bundle ----------------------------------------------------
mkdir -p "$BUNDLE_DIR/bin" "$BUNDLE_DIR/include"
cp libtcc.so "$BUNDLE_DIR/bin/"
cp libtcc1.a "$BUNDLE_DIR/"
cp include/*.h tcclib.h "$BUNDLE_DIR/include/"
cp COPYING RELICENSING "$BUNDLE_DIR/"

cat > "$BUNDLE_DIR/VERSION" <<EOF
bundleFormat=$BUNDLE_FORMAT_VERSION
tinyccCommit=$TINYCC_COMMIT
platform=$PLATFORM_KEY
EOF

# Sorted sha256sum manifest over every other bundle file; consumed by the installer to verify
# a staged tree before it is published.
( cd "$BUNDLE_DIR" && find . -type f ! -name MANIFEST -printf '%P\n' | LC_ALL=C sort | xargs sha256sum > MANIFEST )

# ---- verify: exports --------------------------------------------------------
# Note: under `set -o pipefail`, `nm ... | grep -q` is a SIGPIPE race (grep exits on the first
# match while nm is still writing); consume the whole stream instead.
nm -D "$BUNDLE_DIR/bin/libtcc.so" | grep ' T tcc_new$' >/dev/null || {
    echo "error: $BUNDLE_DIR/bin/libtcc.so does not export tcc_new" >&2
    exit 1
}

# ---- verify: relocation smoke ----------------------------------------------
# The bundle is copied to a fresh directory and used ONLY through -B (the CLI equivalent of
# tcc_set_lib_path): the compiler's own headers and libtcc1.a come from the relocated bundle,
# while crt/libc and the system headers resolve through the host triplet paths baked in by
# configure. A real shared-library link is required — compiling to an object would skip the
# crt/libc chain entirely.
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
"$WORK_DIR/tcc" -B "$SMOKE_DIR/bundle" -shared -o "$SMOKE_DIR/libsmoke.so" "$SMOKE_DIR/smoke.c"
nm -D "$SMOKE_DIR/libsmoke.so" | grep ' T gdcc_tinycc_smoke$' >/dev/null || {
    echo "error: the smoke library does not export gdcc_tinycc_smoke" >&2
    exit 1
}
echo "relocation smoke: ldd output for the tcc-linked smoke library:"
ldd "$SMOKE_DIR/libsmoke.so"
ldd "$SMOKE_DIR/libsmoke.so" | grep -q 'libc\.so\.' || {
    echo "error: the smoke library does not dynamically link the host libc" >&2
    exit 1
}

echo
echo "bundle assembled and verified at: $BUNDLE_DIR"
