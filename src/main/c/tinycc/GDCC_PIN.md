# GDCC pin record for the vendored TinyCC sources

This directory holds a pristine vendor copy of the TinyCC compiler sources. It is consumed by
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
  `tcc-doc.texi` + `texi2pod.pl` (the `tcc-doc.*` make targets are never invoked). Nothing
  else is modified — every file needed to configure and build tcc/libtcc is untouched.
- License: TinyCC is licensed under LGPL-2.1 (`COPYING`); some bundled parts carry different
  terms (`RELICENSING`). Both files ship with this source tree and are copied into every
  built bundle.
- Local patches: none. If a future upgrade or patch is ever applied, update this file with the
  new commit and a short patch list.
