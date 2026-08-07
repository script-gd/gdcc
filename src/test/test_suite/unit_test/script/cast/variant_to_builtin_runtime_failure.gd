class_name CastVariantToBuiltinRuntimeFailure
extends Node

# Runtime failure: Vector2 cannot construct as int. Returns default after stable error.
func cast_vector_to_int(value: Variant) -> int:
	return value as int
