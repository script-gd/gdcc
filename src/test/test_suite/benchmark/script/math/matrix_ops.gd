class_name BenchmarkMatrixOps
extends Node

var _a00: float = 1.0
var _a01: float = 0.5
var _a02: float = 0.25
var _a10: float = 0.75
var _a11: float = 1.0
var _a12: float = 0.5
var _a20: float = 0.25
var _a21: float = 0.75
var _a22: float = 1.0

var _b00: float = 1.0
var _b01: float = 0.25
var _b02: float = 0.5
var _b10: float = 0.0
var _b11: float = 1.0
var _b12: float = 0.25
var _b20: float = 0.0
var _b21: float = 0.0
var _b22: float = 1.0

var _c00: float = 0.125
var _c01: float = 0.125
var _c02: float = 0.125
var _c10: float = 0.125
var _c11: float = 0.125
var _c12: float = 0.125
var _c20: float = 0.125
var _c21: float = 0.125
var _c22: float = 0.125

var _m00: float = 1.0
var _m01: float = 0.5
var _m02: float = 0.25
var _m03: float = 1.0
var _m10: float = 0.0
var _m11: float = 1.0
var _m12: float = 0.5
var _m13: float = -0.5
var _m20: float = 0.0
var _m21: float = 0.0
var _m22: float = 1.0
var _m23: float = 0.25
var _m30: float = 0.0
var _m31: float = 0.0
var _m32: float = 0.0
var _m33: float = 1.0

var _v0: float = 0.5
var _v1: float = 1.0
var _v2: float = -0.5
var _v3: float = 1.0

func prepare() -> void:
    _a00 = 1.0
    _a01 = 0.5
    _a02 = 0.25
    _a10 = 0.75
    _a11 = 1.0
    _a12 = 0.5
    _a20 = 0.25
    _a21 = 0.75
    _a22 = 1.0
    _b00 = 1.0
    _b01 = 0.25
    _b02 = 0.5
    _b10 = 0.0
    _b11 = 1.0
    _b12 = 0.25
    _b20 = 0.0
    _b21 = 0.0
    _b22 = 1.0
    _c00 = 0.125
    _c01 = 0.125
    _c02 = 0.125
    _c10 = 0.125
    _c11 = 0.125
    _c12 = 0.125
    _c20 = 0.125
    _c21 = 0.125
    _c22 = 0.125
    _m00 = 1.0
    _m01 = 0.5
    _m02 = 0.25
    _m03 = 1.0
    _m10 = 0.0
    _m11 = 1.0
    _m12 = 0.5
    _m13 = -0.5
    _m20 = 0.0
    _m21 = 0.0
    _m22 = 1.0
    _m23 = 0.25
    _m30 = 0.0
    _m31 = 0.0
    _m32 = 0.0
    _m33 = 1.0
    _v0 = 0.5
    _v1 = 1.0
    _v2 = -0.5
    _v3 = 1.0

func baseline() -> float:
    return _a00 + _a11 + _a22 + _v0 + _v1 + _v2 + _v3

func benchmark() -> float:
    var a00 := _a00
    var a01 := _a01
    var a02 := _a02
    var a10 := _a10
    var a11 := _a11
    var a12 := _a12
    var a20 := _a20
    var a21 := _a21
    var a22 := _a22
    var iteration := 0
    while iteration < 6:
        var p00 := a00
        var p01 := a00 * _b01 + a01
        var p02 := a00 * _b02 + a01 * _b12 + a02
        var p10 := a10
        var p11 := a10 * _b01 + a11
        var p12 := a10 * _b02 + a11 * _b12 + a12
        var p20 := a20
        var p21 := a20 * _b01 + a21
        var p22 := a20 * _b02 + a21 * _b12 + a22
        a00 = p00 + _c00
        a01 = p01 + _c01
        a02 = p02 + _c02
        a10 = p10 + _c10
        a11 = p11 + _c11
        a12 = p12 + _c12
        a20 = p20 + _c20
        a21 = p21 + _c21
        a22 = p22 + _c22
        iteration = iteration + 1

    var v0 := _v0
    var v1 := _v1
    var v2 := _v2
    var v3 := _v3
    iteration = 0
    while iteration < 8:
        var next0 := v0 * _m00 + v1 * _m01 + v2 * _m02 + v3 * _m03
        var next1 := v1 + v2 * _m12 + v3 * _m13
        var next2 := v2 + v3 * _m23
        v0 = next0
        v1 = next1
        v2 = next2
        iteration = iteration + 1

    return a00 + a01 + a02 + a10 + a11 + a12 + a20 + a21 + a22 + v0 + v1 + v2 + v3

func check(result: float) -> bool:
    return abs(result - 40.34375) < 0.000001
