class_name EngineArrayMeshExactDefaultArgsSmoke
extends Node

func surface_count_after_exact_default_args() -> int:
    var mesh: ArrayMesh = ArrayMesh.new()
    var arrays: Array = Array()
    arrays.resize(13)

    var vertices = PackedVector3Array()
    vertices.push_back(Vector3(0.0, 0.0, 0.0))
    vertices.push_back(Vector3(1.0, 0.0, 0.0))
    vertices.push_back(Vector3(0.0, 1.0, 0.0))
    arrays[0] = vertices

    mesh.add_surface_from_arrays(3, arrays)
    return mesh.get_surface_count()
