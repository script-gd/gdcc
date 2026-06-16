extends Node

const INTERPRETER_SCRIPT_PATH = "__GDCC_BENCHMARK_INTERPRETER_SCRIPT__"
const CASE_PATH = "__GDCC_BENCHMARK_CASE_PATH__"
const CASE_NAME = "__GDCC_BENCHMARK_CASE_NAME__"
const COMPILED_TARGET_NODE_NAME = "__GDCC_BENCHMARK_COMPILED_TARGET_NODE__"
const INTERPRETER_TARGET_NODE_NAME = "__GDCC_BENCHMARK_INTERPRETER_TARGET_NODE__"
const PASS_MARKER = "__GDCC_BENCHMARK_PASS_MARKER__"
const ITERATIONS = __GDCC_BENCHMARK_ITERATIONS__
const WARMUPS = __GDCC_BENCHMARK_WARMUPS__
const SAMPLES = __GDCC_BENCHMARK_SAMPLES__
const MIN_BATCH_US = __GDCC_BENCHMARK_MIN_BATCH_US__

func _ready() -> void:
    var compiled_target = get_parent().get_node(COMPILED_TARGET_NODE_NAME)
    var interpreter_target = get_parent().get_node(INTERPRETER_TARGET_NODE_NAME)

    print(
        "GDCC_BENCHMARK_HEADER case=%s name=%s iterations=%d warmups=%d samples=%d min_batch_us=%d" %
        [CASE_PATH, CASE_NAME, ITERATIONS, WARMUPS, SAMPLES, MIN_BATCH_US]
    )
    print("interpreter script path: %s" % INTERPRETER_SCRIPT_PATH)

    _run_warmups(compiled_target)
    _run_warmups(interpreter_target)
    _run_samples("compiled", compiled_target)
    _run_samples("interpreter", interpreter_target)

    print(PASS_MARKER)
    get_tree().quit()

func _prepare_target(target: Node) -> void:
    if target.has_method("prepare"):
        target.prepare()

func _run_warmups(target: Node) -> void:
    var remaining := 0
    while remaining < WARMUPS:
        _run_baseline_batch(target)
        _run_benchmark_batch(target)
        remaining += 1

func _run_samples(path_name: String, target: Node) -> void:
    var sample_index := 0
    while sample_index < SAMPLES:
        var baseline_us = _run_baseline_batch(target)
        var benchmark_result = _run_benchmark_batch(target)
        var benchmark_us: int = benchmark_result["elapsed_us"]
        var body_ns = _body_ns(baseline_us, benchmark_us)
        var check_result = _run_check(target, benchmark_result["value"])
        print(
            "GDCC_BENCHMARK_RESULT case=%s path=%s sample=%d iterations=%d baseline_us=%d benchmark_us=%d body_ns=%d check_ran=%s check_passed=%s" %
            [
                CASE_PATH,
                path_name,
                sample_index,
                ITERATIONS,
                baseline_us,
                benchmark_us,
                body_ns,
                "true" if check_result["ran"] else "false",
                "true" if check_result["passed"] else "false"
            ]
        )
        if not check_result["passed"]:
            push_error("benchmark behavior check failed on %s sample %d" % [path_name, sample_index])
            return
        sample_index += 1

func _run_baseline_batch(target: Node) -> int:
    var start_us = Time.get_ticks_usec()
    var iteration := 0
    while iteration < ITERATIONS:
        _prepare_target(target)
        target.baseline()
        iteration += 1
    return Time.get_ticks_usec() - start_us

func _run_benchmark_batch(target: Node) -> Dictionary:
    var start_us = Time.get_ticks_usec()
    var value = null
    var iteration := 0
    while iteration < ITERATIONS:
        _prepare_target(target)
        value = target.benchmark()
        iteration += 1
    return {
        "elapsed_us": Time.get_ticks_usec() - start_us,
        "value": value
    }

func _run_check(target: Node, result) -> Dictionary:
    if not target.has_method("check"):
        return {
            "ran": false,
            "passed": true
        }
    return {
        "ran": true,
        "passed": bool(target.check(result))
    }

func _body_ns(baseline_us: int, benchmark_us: int) -> int:
    return int((benchmark_us - baseline_us) * 1000.0 / ITERATIONS)
