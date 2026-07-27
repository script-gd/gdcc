#ifndef GDCC_INTRINSIC_H
#define GDCC_INTRINSIC_H

/// Umbrella header for GDCC-owned compiler/runtime intrinsic helpers.
/// Implementation is split under `gdcc/intrinsic/` to keep each for-iterator family maintainable.
#include "intrinsic/for_range_iter.h"
#include "intrinsic/for_float_iter.h"
#include "intrinsic/for_variant_iter.h"
#include "intrinsic/for_string_iter.h"
#include "intrinsic/for_array_iter.h"
#include "intrinsic/for_dictionary_iter.h"
#include "intrinsic/for_packed_array_iter.h"
#include "intrinsic/call_arg_materialize.h"

#endif
