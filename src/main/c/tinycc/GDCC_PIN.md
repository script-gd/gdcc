# GDCC pin record for the vendored TinyCC sources

This directory holds a pinned vendor copy of the TinyCC compiler sources. It is consumed by
the bundle build scripts in `build-script/` which copy this tree into a build directory and
build there — this tree itself must never be configured or built in place.

- Upstream: `https://github.com/TinyCC/tinycc.git`, branch `mob`
- Pinned commit: `43c7708b85681a2fd4451c8a541af4494a8919b2` (`VERSION` = 0.9.28rc, 2026-10-03)
- Reproduce this tree:
  `git clone https://github.com/TinyCC/tinycc.git && git -C tinycc archive 43c7708b85681a2fd4451c8a541af4494a8919b2 | tar -x -C <dest>`
  Only the tracked content of that commit is vendored: `.git/` and every generated file
  (`config.mak`, `config.h`, `*.o`, `tcc`, `libtcc1.a`, `tccdefs_.h`, …) are excluded by
  construction.
- Deviation from the pristine tree: only build-essential content is vendored. Excluded on
  purpose (all are unused by the bundle build chain): the upstream `tests/` directory
  (self-contained suite for `make test`, which gdcc never runs), `examples/` and
  `win32/examples/` (usage demos), `.github/` (upstream CI), and the documentation-only files
  `tcc-doc.texi` + `texi2pod.pl` (the `tcc-doc.*` make targets are never invoked). Other changes
  are limited to the local patches listed below; the configure/build files are untouched.
- License: TinyCC is licensed under LGPL-2.1 (`COPYING`); some bundled parts carry different
  terms (`RELICENSING`). Both files ship with this source tree and are copied into every
  built bundle.
- Local patches:
  - `tccpp.c`: recognize `u8"..."` as a single preprocessing string token, retaining its
    prefix for stringification, token pasting and preprocessor output. When converted to a C
    token, skip the prefix and use the existing ordinary narrow-string parser unchanged.
    This is transparent prefix support, not C23 `char8_t` support or new encoding/merging rules.
    Regression coverage: `TinyCcU8StringTest` (requires a CLI rebuilt from these sources).
  Update this record when changing the upstream pin or local patch list.
