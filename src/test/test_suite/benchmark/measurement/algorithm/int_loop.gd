# gdcc-benchmark: name=Integer loop
# gdcc-benchmark: iterations=1000
# gdcc-benchmark: warmups=3
# gdcc-benchmark: samples=10
# gdcc-benchmark: min_batch_us=1000
extends Node

const INTERPRETER_SCRIPT_PATH = "__GDCC_BENCHMARK_INTERPRETER_SCRIPT__"
const COMPILED_TARGET_NODE_NAME = "__GDCC_BENCHMARK_COMPILED_TARGET_NODE__"
const INTERPRETER_TARGET_NODE_NAME = "__GDCC_BENCHMARK_INTERPRETER_TARGET_NODE__"

func _ready() -> void:
    # Step 3 only proves that both execution targets are mounted into the same scene and that the
    # measurement node can resolve them through the shared project setup. Step 4 will replace this
    # with the real timing protocol.
    var compiled_target = get_parent().get_node(COMPILED_TARGET_NODE_NAME)
    var interpreter_target = get_parent().get_node(INTERPRETER_TARGET_NODE_NAME)

    compiled_target.prepare()
    interpreter_target.prepare()

    var compiled_result = compiled_target.benchmark()
    var interpreter_result = interpreter_target.benchmark()
    var compiled_ok = compiled_target.check(compiled_result)
    var interpreter_ok = interpreter_target.check(interpreter_result)

    if compiled_ok and interpreter_ok and compiled_result == interpreter_result:
        print("benchmark dual target ready")
        print("interpreter script path: %s" % INTERPRETER_SCRIPT_PATH)
        return

    push_error("benchmark dual target setup failed")
