extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var ok := true
    if target.call("get_v") != Vector2(1.5, -2.5):
        push_error("get_v failed")
        ok = false
    if target.call("get_vi") != Vector3i(1, -2, 3):
        push_error("get_vi failed")
        ok = false
    if target.call("get_vi4") != Vector4i(1, -2, 3, -4):
        push_error("get_vi4 failed")
        ok = false

    # Non-symmetric logical columns: x/y/z basis vectors and origin must map
    # back exactly — a row/column mix-up would permute these values.
    var t: Transform3D = target.call("get_transform")
    if t.basis.x != Vector3(1, 2, 3):
        push_error("transform basis.x mismatch: %s" % t.basis.x)
        ok = false
    if t.basis.y != Vector3(4, 5, 6):
        push_error("transform basis.y mismatch: %s" % t.basis.y)
        ok = false
    if t.basis.z != Vector3(7, 8, 10):
        push_error("transform basis.z mismatch: %s" % t.basis.z)
        ok = false
    if t.origin != Vector3(9, 8, 7):
        push_error("transform origin mismatch: %s" % t.origin)
        ok = false

    var tint: Color = target.call("get_tint")
    if not tint.is_equal_approx(Color(0.25, 0.5, 0.75, 0.5)):
        push_error("get_tint mismatch: %s" % tint)
        ok = false
    if target.call("get_up") != Vector3.UP:
        push_error("get_up failed")
        ok = false
    var diag_sq := float(target.call("diag_sq"))
    if not is_finite(diag_sq) or not is_equal_approx(diag_sq, 8.5):
        push_error("diag_sq failed")
        ok = false

    # Transform2D columns and Projection columns likewise must round-trip in
    # logical order — each axis is asserted individually to catch transposes.
    var t2: Transform2D = target.call("get_t2")
    if t2.x != Vector2(1, 2):
        push_error("transform2d x mismatch: %s" % t2.x)
        ok = false
    if t2.y != Vector2(3, 4):
        push_error("transform2d y mismatch: %s" % t2.y)
        ok = false
    if t2.origin != Vector2(5, 6):
        push_error("transform2d origin mismatch: %s" % t2.origin)
        ok = false

    var p: Projection = target.call("get_projection")
    if p.x != Vector4(1, 2, 3, 4):
        push_error("projection x mismatch: %s" % p.x)
        ok = false
    if p.y != Vector4(5, 6, 7, 8):
        push_error("projection y mismatch: %s" % p.y)
        ok = false
    if p.z != Vector4(9, 10, 11, 12):
        push_error("projection z mismatch: %s" % p.z)
        ok = false
    if p.w != Vector4(13, 14, 15, 17):
        push_error("projection w mismatch: %s" % p.w)
        ok = false

    if ok:
        print("__UNIT_TEST_PASS_MARKER__")
