# C Backend

## Reminders

### Use GDCC Class Types

- GDCC types cannot be used directly as a `godot_Object*` or `GDExtensionObjectPtr`, they need to be converted first.
  - Convert into godot Object using `_object` field.
  - Convert from godot Object using `godot_object_get_gdcc_object`.