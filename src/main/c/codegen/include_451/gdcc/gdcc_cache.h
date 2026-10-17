#ifndef GDCC_CACHE_H
#define GDCC_CACHE_H

#include <stdatomic.h>

// Cold-path locks only. Cache destruction requires all users to have stopped and joined.
static inline void gdcc_cache_lock(atomic_flag *lock) {
    while (atomic_flag_test_and_set_explicit(lock, memory_order_acquire)) {
    }
}

static inline void gdcc_cache_unlock(atomic_flag *lock) {
    atomic_flag_clear_explicit(lock, memory_order_release);
}

#endif
