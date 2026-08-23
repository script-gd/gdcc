/*
 * GDCC vendored copy of edubart/minicoro — implementation translation unit.
 *
 * Upstream: https://github.com/edubart/minicoro
 * Pinned commit: 02dad0f8b7cbb12fe6e216ae7a76db15ca55cd7b (main branch, 2026-08)
 * License: dual Public Domain (Unlicense) / MIT No Attribution. The full upstream
 * license text is embedded in minicoro.h (header banner and trailing comment).
 *
 * Locked configuration (contract: doc/gdcc_runtime_lib.md §Coroutine Runtime):
 * - MCO_USE_ASM: force the assembly context-switch backend on every target. The
 *   fiber backend is forbidden: it converts the calling thread into a Windows
 *   fiber, which fails when Godot's main thread already is a fiber. Targets
 *   without an assembly implementation fail loudly at compile time instead of
 *   silently degrading to fibers.
 * - MCO_USE_VMEM_ALLOCATOR: virtual-memory backed coroutine stacks. Address space
 *   is reserved up front while physical pages follow the actual usage high-water
 *   mark (POSIX mmap is lazy-commit; Windows VirtualAlloc commits charge up front
 *   but still demand-zeroes physical pages).
 */

#define MCO_USE_ASM
#define MCO_USE_VMEM_ALLOCATOR
#define MINICORO_IMPL
#include "minicoro.h"
