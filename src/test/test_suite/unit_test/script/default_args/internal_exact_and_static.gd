class_name DefaultArgsInternalExact
extends Node

var base: int = 100

func ping(a: int, count: int = 40) -> int:
	return a * 100 + count

static func sadd(a: int, b: int = 5, c: int = 7) -> int:
	return a + b + c

func with_self_default(extra: int = 0) -> int:
	return base + extra

func run_checks() -> int:
	var total: int = 0
	total += ping(1)
	total += ping(1, 2)
	total += self.ping(2)
	total += sadd(1)
	total += sadd(1, 1)
	total += with_self_default()
	total += with_self_default(1)
	return total
