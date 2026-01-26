#ifndef GDCC_LIKELY_H
#define GDCC_LIKELY_H

#define _GDCC_BOOL(x) (!!(x))

/*
 * GCC / Clang: Use __builtin_expect
 * MSVC: There is no equivalent branch prediction builtin;
 *       Use __assume only to help optimize if "determined to be true/false".
 * Keep semantic security here: do not change the running logic; MSVC degenerates to ordinary Boolean judgment.
 */
#if defined(__clang__) || defined(__GNUC__)
  #define likely(x)   (__builtin_expect(_GDCC_BOOL(x), 1))
  #define unlikely(x) (__builtin_expect(_GDCC_BOOL(x), 0))
#elif defined(_MSC_VER)
  #define likely(x)   (_GDCC_BOOL(x))
  #define unlikely(x) (_GDCC_BOOL(x))
#else
  #define likely(x)   (_GDCC_BOOL(x))
  #define unlikely(x) (_GDCC_BOOL(x))
#endif


#endif //GDCC_LIKELY_H
