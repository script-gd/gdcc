class_name RecursiveFactorialSmoke
extends Node

func factorial(n: int) -> int:
    if n <= 1:
        return 1
    return n * factorial(n - 1)

func compute() -> int:
    return factorial(5)
