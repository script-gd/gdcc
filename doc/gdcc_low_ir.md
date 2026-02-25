# Low IR

Low IR is the intermediate representation (IR) emitted by the middle-end
of the compiler mapping directly to GDExtension APIs.

## Overview

Low IR is a function level IR. Each function consists of 3 parts:
- Signature: names, parameters, return type
- Variables: local variables used in the function
  - Variables are indexed starting from 0, var 0-N are function parameters
  - Variables have id, type, name(optional) and storage class (parameter, stack, constant)
- Basic Blocks: sequences of instructions with a single entry and exit point
  - Basic blocks have id and a list of instructions
  - Control flow is represented by branches between basic blocks
  - Each function has an entry block and may have multiple exit blocks
  - Each basic block ends with a terminator instruction (branch, return, etc.)

Low IR does NOT use an SSA form since it is designed to be transpiled to
GDExtension C code. Instead, Low IR uses explicit load/store instructions to
manipulate variables.

## Types

See [Types](gdcc_type_system.md) for details on the type system used in Low IR.

## Instructions

Each instruction has an optional return value, a string id, and a list of operands:
```
($<result_id>)? = <instruction_id> ($<operand_id>|<literial>) ...
```

### New Data Instructions

#### literal_bool
Creates a new boolean constant.
```
$<result_id> = literal_bool <true|false>
```

#### literal_int
Creates a new i64 constant.
```
$<result_id> = literal_int <i64_value>
```

#### literal_float
Creates a new f64 constant.
```
$<result_id> = literal_float <f64_value>
```

#### literal_string
Creates a new String constant.
```
$<result_id> = literal_string "<string_value_utf8>"
```

#### literal_string_name
Creates a new StringName constant.
```
$<result_id> = literal_string_name "<string_value_utf8>"
```

#### literal_nil
Creates a new Variant Nil constant. 
```
$<result_id> = literal_nil
```

#### literal_null
Create a new Object null constant.
```
$<result_id> = literal_null
```

### Construction & Destruction Instructions

#### construct_builtin
Constructs a builtin of a specific type with arguments. The type is the same as the type
of the result variable.
```
$<result_id> = construct_builtin $<arg1_id> $<arg2_id> ...
```

#### construct_array
Constructs a new TypedArray of a specific class / type.
If the class_name is omitted, constructs a generic Array.
```
$<result_id> = construct_array "<class_name>"?
```

#### construct_dictionary
Constructs a new TypedDictionary of a specific class / type.
If both class names are omitted, constructs a generic Dictionary.
If only one class name is provided, constructs a TypedDictionary with
the specified key type with a generic value type.
```
$<result_id> = construct_dictionary "<key_class_name>"? "<value_class_name>"?
```

#### construct_object
Constructs a new Object of a specific class.
If the new class object extends RefCounted, the returned object is owned (reference count increased by 1).
```
$<result_id> = construct_object <class_name>
```

#### construct_callable
Constructs a new Callable from a function in this compiling unit.
```
$<result_id> = construct_callable "<function_name>"
```

#### construct_lambda
Constructs a new Callable from a lambda function in this compiling unit.
For implementation, `godot_callable_custom_create2` is used.
All captures are copied into a tmp struct and passed to the lambda via `callable_userdata`.
If there are no captures, NULL is passed as `callable_userdata`.
`free_func` in `GDExtensionCallableCustomInfo2` must be set to destruct the captures.
```
$<result_id> = construct_lambda "<lambda_function_name>" $<capture1_id> $<capture2_id> ...
```

#### destruct
Destructs a variable, releasing any resources it holds.
All Variants must be destructed after use to avoid memory leaks.  

Warning: 
- Destruct object that is not ref-counted is not always needed since users may want to do it manually.
- Destruct object that is not ref-counted does actually mem-delete the object.
- However, destructing ref-counted objects is required to decrease the reference count.

Types can be destruct:
- String
- StringName
- NodePath
- Callable
- Signal
- Dictionary
- Array
- Packed*Array
- Object
- Variant
All types not in the above list are stack allocated and do not need to be destructed.
However, destructing them is a no-op and allowed.

An optional lifecycle provenance enum string can be provided: `AUTO_GENERATED`, `INTERNAL`, `USER_EXPLICIT`, `UNKNOWN`.
If it is not provided, it defaults to `UNKNOWN` and a warning should be emitted since provenance is important for validating the correct usage of this instruction.

This instruction should not be used arbitrarily on any variable. It should only be used in specific scenarios that meets the provenance requirement.

Restrictions:
- Allowed:
  - `AUTO_GENERATED`: only compiler-injected destruct in `__finally__`.
  - `INTERNAL`: only compiler internal/temp variables (for example numeric IDs or `__tmp_*` IDs).
  - `USER_EXPLICIT`: only frontend-lowered explicit lifecycle intent from user GDScript source.
  - `UNKNOWN`: compatibility mode warning only; strict mode rejects.
- Forbidden:
  - Hand-written or externally injected lifecycle instructions without valid provenance.
  - `AUTO_GENERATED` outside compiler auto-generated paths.
  - `INTERNAL` on ordinary user-named variables.
- Violation result:
  - Backend validation fails fast with `InvalidInsnException` before C code generation.

```
destruct $<variant_id> "[lifecycle provenance]"
```

#### try_own_object
Attempts to take ownership of an Object. If successful, the reference count is increased.
If the Object is not ref-counted, this is a no-op.

The lifecycle provenance is the same as `destruct` instruction.
The same restrictions and validation behavior apply.

```
try_own_object $<object_id> "[lifecycle provenance]"
```

#### try_release_object
Attempts to release ownership of an Object. If successful, the reference count is decreased.
If the Object is not ref-counted, this is a no-op.

The lifecycle provenance is the same as `destruct` instruction.
The same restrictions and validation behavior apply.

```
try_release_object $<object_id> "[lifecycle provenance]"
```

#### unary_op
Performs a built-in operation on one operand. 
For all available operations, see enum `GodotOperator`.
```
$<result_id> = unary_op "<op_name>" $<operand_id>
```

#### binary_op
Performs a built-in operation on two operands.
For all available operations, see enum `GodotOperator`.
```
$<result_id> = binary_op "<op_name>" $<left_operand_id> $<right_operand_id>
```

### Indexing Instructions

#### variant_get
Gets a value from a Variant by another Variant.
```
$<result_id> = variant_get $<variant_id> $<key>
```

#### variant_get_keyed
Gets a value from a keyed Variant (usually Dictionary) by another Variant
```
$<result_id> = variant_get_keyed $<keyed_variant_id> $<Variant>
```

#### variant_get_named
Gets a value from a named Variant (usually Object) by StringName.
```
$<result_id> = variant_get_named $<named_variant_id> $<StringName>
```

#### variant_get_indexed
Sets a value in a Variant by int.
```
$<result_id> = variant_get_indexed $<variant_id> $<int>
```

#### variant_set
Sets a value in a Variant by another Variant.
```
variant_set $<variant_id> $<key> $<value>
```

#### variant_set_keyed
Sets a value in a keyed Variant (usually Dictionary) by another Variant
```
variant_set_keyed $<keyed_variant_id> $<key> $<value>
```

#### variant_set_named
Sets a value in a named Variant (usually Object) by StringName.
```
variant_set_named $<named_variant_id> $<key:StringName> $<value>
```

#### variant_set_indexed
Sets a value in a Variant by int.
```
variant_set_indexed $<variant_id:Variant> $<index:int> $<value>
```

### Type Instructions

#### get_variant_type
Gets the type int id of Variant. The id is the same in order of `GdExtensionTypeEnum`.
```
$<result_id:int> = get_variant_type $<variant_id>
```

#### get_class_name
Gets the type name of a variable as String:
- If this variable has a static type (not Variant), returns the static type name.
- If this variable is of Variant type, returns the type name.
- If this variable is an Object, returns the class name.
```
$<result_id:String> = get_class_name $<id>
```

#### object_cast
Casts an Object to a specific class. If the Object is not an instance of the class, returns null.
```
$<result_id> = object_cast "<class_name>" $<object_id>
```

#### is_instance_of
Checks if an Object is an instance of a specific class.
```
$<result_id:bool> = is_instance_of "<class_name>" $<object_id:Object>
```

#### pack_variant
Packs a value into a Variant.
```
$<result_id> = pack_variant $<value_id>
```

#### unpack_variant
Unpacks a value from a Variant.
```
$<result_id> = unpack_variant $<variant_id>
```

#### variant_is_nil
Checks if a Variant is nil.
```
$<result_id:bool> = variant_is_nil $<variant_id:Variant>
```

#### object_is_null
Checks if an Object is null.
```
$<result_id:bool> = object_is_null $<object_id:Object>
```

### Control Flow Instructions

#### goto
Unconditional branch to a basic block.
```
goto <target_block_id>
```

#### go_if
Conditional branch to one of two basic blocks based on a boolean condition.
```
go_if $<condition_id:bool> <true_block_id> <false_block_id>
```

#### return
Returns from the current function, optionally with a return value.
```
return ($<return_value_id>)?
```

### Call Instructions

#### call_global
Calls a global function by name.
```
$<result_id>? = call_global "<function_name>" $<arg1_id> $<arg2_id> ...
```

#### call_method
Calls a method on an Object by method name.
```
$<result_id>? = call_method "<method_name>" $<object_id> $<arg1_id> $<arg2_id> ...
```

#### call_super_method
Calls a super method on an Object by method name.
If the method does not exist in the super class, it will result in a runtime error.
```
$<result_id>? = call_super_method "<method_name>" $<object_id> $<arg1_id> $<arg2_id> ...
```

#### call_static_method
Calls a static method on a class by class name and method name.
```
$<result_id>? = call_static_method "<class_name>" "<method_name>" $<arg1_id> $<arg2_id> ...
```

#### call_intrinsic
Calls an intrinsic function by name.
```
$<result_id>? = call_intrinsic "<intrinsic_name>" $<arg1_id> $<arg2_id> ...
```

### Load/Store Instructions

#### load_property
Loads a property from an Object by property name.
```
$<result_id> = load_property "<property_name>" $<object_id>
```

#### store_property
Stores a value to a property in an Object by property name.
```
store_property "<property_name>" $<object_id> $<value_id>
```

#### load_static
Loads a static variable/property by name.
```
$<result_id> = load_static "<class_name>" "<static_name>"
```

#### store_static
Stores a value to a static variable/property by name.
```
store_static "<class_name>" "<static_name>" $<value_id>
```

### Misc Instructions

#### nop
No operation. Does nothing.
```
nop
```

#### line_number
Sets the current source code line number for debugging purposes.
```
line_number <line_number:int>
```


### Instruction Usage Restrictions

The lifecycle instructions `destruct`, `try_own_object`, and `try_release_object` are controlled instructions.
They are not general-purpose instructions for arbitrary external LIR.

Allowed/forbidden quick reference:

| Provenance | Allowed | Forbidden |
| --- | --- | --- |
| `AUTO_GENERATED` | Compiler-generated `destruct` in `__finally__` | Any non-`__finally__` usage; any own/release instruction |
| `INTERNAL` | Compiler internal lifecycle maintenance on temp/internal variables | User-named variables (for example `obj`, `value`) |
| `USER_EXPLICIT` | Frontend-lowered explicit lifecycle intent from user source | Emitting in auto-generated blocks (`__prepare__`, `__finally__`) |
| `UNKNOWN` | Compat mode warning pass-through | Strict mode |

Legal IR snippets:

```text
__finally__:
destruct $17 "AUTO_GENERATED";
```

```text
entry:
try_release_object $tmp_ref "USER_EXPLICIT";
```

Illegal IR snippets:

```text
entry:
destruct $value "AUTO_GENERATED"; // invalid: AUTO_GENERATED outside __finally__
```

```text
entry:
try_own_object $obj "INTERNAL"; // invalid: INTERNAL on ordinary user-named variable
```

## Syntax

A Low IR file (which is a .xml format file) consists of 4 parts:
- Class Definitions
- Signals
- Properties
- Functions

### Class Definitions
```xml
<!-- name is optional for anonymous classes -->
<!-- is_abstract and is_tool are optional, default to false -->
<class_def name="<class_name>" 
           super="super_class_name" 
           is_abstract="false" 
           is_tool="false">
    <annotation key="<annotation_key>" value="<annotation_value>"/>
    <annotation key="<annotation_key>" value="<annotation_value>"/>
    <signals>...</signals>
    <properties>...</properties>
    <functions>...</functions>
</class_def>
```

### Signals

```xml
<signals>
    <signal name="<signal_name>">
        <!-- type is optional, defaults to Variant -->
        <parameter name="<arg_name>" type="<arg_type>"/>
        <parameter name="<arg_name>" type="<arg_type>"/>
    </signal>
</signals>
```

### Properties

```xml
<properties>
    <!-- init_func is optional -->
    <!-- The return value of the function will be used to initialize the property.
         If this is not a static property, the first parameter must be of type Object representing 'self'. -->
    <!-- getter_func & setter_func are optional  -->
    <!-- The getter func should receive a self parameter and return the same type as the prop.  -->
    <!-- The setter func should receive a self and a value parameter in the same type as the prop. -->
    <!-- If getter & setter are not present, a default one is generated -->
    <property name="<prop_name>" 
              type="<prop_type>" 
              is_static="false" 
              init_func="<init_function_name>"
              getter_func="<getter_function_name>"
              setter_func="<setter_function_name>">
        <annotation key="<annotation_key>" value="<annotation_value>"/>
        <annotation key="<annotation_key>" value="<annotation_value>"/>
    </property>
</properties>
```

### Functions

```xml
<functions>
    <function name="<function_name>" 
              is_static="false" 
              is_abstract="false" 
              is_lambda="false"
              is_vararg="false"
              is_hidden="false">
        <annotation key="<annotation_key>" value="<annotation_value>"/>
        <annotation key="<annotation_key>" value="<annotation_value>"/>
        <parameters>
            <!-- default_value_func is optional -->
            <!-- if present, the function will be called if no argument is provided and its return value will be used -->
            <!-- if no default_value_func and the argument is not provided, it is an error -->
            <parameter name="<param_name>" type="<param_type>" default_value_func="<default_value_function_name"/>
            <parameter name="<param_name>" type="<param_type>" default_value_func="<default_value_function_name"/>
        </parameters>
        <!-- Only lambda function contains this field  -->
        <captures>
            <capture name="<capture_name>" type="<capture_type>"/>
            <capture name="<capture_name>" type="<capture_type>"/>
        </captures>
        <return_type type="<return_type>"/>
        <variables>
            <variable id="<var_id>" type="<var_type>"/>
        </variables>
        <basic_blocks entry="<entry_block_id>">
            <basic_block id="<block_id>">
                instruction1;
                instruction2;
                ...
            </basic_block>
            <!-- more basic blocks -->
        </basic_blocks>
    </function>
</functions>
```

### Demo

GdScript:
```gdscript
class_name RotatingCamera extends Camera3D

@export var pitch_degree: float = 45;
@export var rotating_speed_degree: float = 60;
@export var length: float = 5;
var time: float = 0;

func _init() -> void:
    print("Camera init");

func _ready() -> void:
    print("Camera ready");

func _process(delta: float) -> void:
    time += delta;
    if length < 0:
        printerr("Length should not be less than 0");
        return;
    var vec = Vector3(length, 0, 0);
    vec = vec.rotated(Vector3.BACK, deg_to_rad(pitch_degree));
    vec = vec.rotated(Vector3.UP, deg_to_rad(rotating_speed_degree * time));
    self.position = vec;
    self.look_at_from_position(vec, Vector3.ZERO);

func get_pitch(to_radius := false) -> float:
    if to_radius:
        return deg_to_rad(self.pitch_degree);
    return self.pitch_degree;
```

Low IR:
> Note: lifecycle instructions in this demo are shown with explicit provenance to avoid ambiguity.
> They are illustrative controlled examples, not a signal that external hand-written IR can use lifecycle instructions freely.
```xml

<ir>
    <class_def name="RotatingCamera" super="Camera3D" is_abstract="false" is_tool="false">
        <properties>
            <property name="pitch_degree" type="float" is_static="false" init_func="_field_init_pitch_degree">
                <annotation key="export" value=""/>
            </property>
            <property name="rotating_speed_degree" type="float" is_static="false"
                      init_func="_field_init_rotating_speed_degree">
                <annotation key="export" value=""/>
            </property>
            <property name="length" type="float" is_static="false" init_func="_field_init_length">
                <annotation key="export" value=""/>
            </property>
            <property name="time" type="float" is_static="false" init_func="_field_init_time"/>
        </properties>
        <signals></signals>
        <functions>
            <function name="_init"
                      is_static="false"
                      is_abstract="false"
                      is_lambda="false"
                      is_vararg="false"
                      is_hidden="false">
                <parameters>
                    <parameter name="self" type="RotatingCamera"/>
                </parameters>
                <return_type type="void"/>
                <variables>
                    <variable id="self" type="RotatingCamera"/>
                    <variable id="0" type="String"/>
                    <variable id="1" type="Variant"/>
                </variables>
                <basic_blocks entry="entry">
                    <basic_block id="entry">
                        line_number 9;
                        $0 = literal_string "Camera init";
                        $1 = pack_variant $0;
                        call_global "print" $1;
                        destruct $1 "INTERNAL";
                        destruct $0 "INTERNAL";
                        return;
                    </basic_block>
                </basic_blocks>
            </function>
            <function name="_ready"
                      is_static="false"
                      is_abstract="false"
                      is_vararg="false"
                      is_hidden="false">
                <parameters>
                    <parameter name="self" type="RotatingCamera"/>
                </parameters>
                <return_type type="void"/>
                <variables>
                    <variable id="self" type="RotatingCamera"/>
                    <variable id="0" type="String"/>
                    <variable id="1" type="Variant"/>
                </variables>
                <basic_blocks entry="entry">
                    <basic_block id="entry">
                        line_number 12;
                        $0 = literal_string "Camera ready";
                        $1 = pack_variant $0;
                        call_global "print" $1;
                        destruct $1 "INTERNAL";
                        destruct $0 "INTERNAL";
                        return;
                    </basic_block>
                </basic_blocks>
            </function>
            <function name="_process"
                      is_static="false"
                      is_abstract="false"
                      is_vararg="false"
                      is_hidden="false">
                <parameters>
                    <parameter name="self" type="RotatingCamera"/>
                    <parameter name="delta" type="float"/>
                </parameters>
                <return_type type="void"/>
                <variables>
                    <variable id="self" type="RotatingCamera"/>
                    <variable id="delta" type="float"/>
                    <variable id="0" type="float"/>
                    <variable id="1" type="float"/>
                    <variable id="2" type="float"/>
                    <variable id="3" type="float"/>
                    <variable id="4" type="Vector3"/>
                    <variable id="5" type="Vector3"/>
                    <variable id="6" type="float"/>
                    <variable id="7" type="float"/>
                    <variable id="8" type="Vector3"/>
                    <variable id="9" type="Vector3"/>
                    <variable id="10" type="float"/>
                    <variable id="11" type="float"/>
                    <variable id="12" type="float"/>
                    <variable id="13" type="float"/>
                    <variable id="14" type="Vector3"/>
                    <variable id="15" type="Vector3"/>
                    <variable id="16" type="String"/>
                    <variable id="17" type="Variant"/>
                    <variable id="18" type="bool"/>
                </variables>
                <basic_blocks entry="bb1">
                    <basic_block id="bb1">
                        line_number 15;
                        $0 = load_property "time" $self;
                        $1 = binary_op "ADD" $0 $delta;
                        store_property "time" $self $1;
                        line_number 16;
                        $2 = literal_float 0;
                        $3 = load_property "length" $self;
                        $18 = binary_op "LESS" $3 $2;
                        go_if $18 bb2 bb3;
                    </basic_block>
                    <basic_block id="bb2">
                        line_number 17;
                        $16 = literal_string "Length should not be less than 0";
                        $17 = pack_variant $16;
                        call_global "printerr" $17;
                        destruct $17 "INTERNAL";
                        destruct $16 "INTERNAL";
                        return;
                    </basic_block>
                    <basic_block id="bb3">
                        line_number 19;
                        $4 = construct_builtin $3 $2 $2;
                        $5 = load_static "Vector3" "BACK";
                        $6 = load_property "pitch_degree" $self;
                        $7 = call_global "deg_to_rad" $6;
                        $8 = call_method "rotated" $4 $5 $7;
                        line_number 20;
                        $9 = load_static "Vector3" "UP";
                        $10 = load_property "rotating_speed_degree" $self;
                        $11 = load_property "time" $self;
                        $12 = binary_op "MULTIPLY" $10 $11;
                        $13 = call_global "deg_to_rad" $12;
                        $14 = call_method "rotated" $8 $9 $13;
                        line_number 22;
                        store_property "position" $self $14;
                        line_number 23;
                        $15 = load_static "Vector3" "ZERO";
                        call_method "look_at_from_position" $self $14 $15;
                        return;
                    </basic_block>
                </basic_blocks>
            </function>
            <function name="get_pitch"
                      is_static="false"
                      is_abstract="false"
                      is_vararg="false"
                      is_hidden="false">
                <parameters>
                    <parameter name="self" type="RotatingCamera"/>
                    <parameter name="to_radius" type="bool" default_value_func="_default_get_pitch$to_radius"/>
                </parameters>
                <return_type type="float"/>
                <variables>
                    <variable id="self" type="RotatingCamera"/>
                    <variable id="to_radians" type="bool"/>
                    <variable id="0" type="float"/>
                    <variable id="1" type="float"/>
                </variables>
                <basic_blocks entry="entry">
                    <basic_block id="bb1">
                        line_number 26;
                        go_if $to_radians bb2 bb3;
                    </basic_block>
                    <basic_block id="bb2">
                        line_number 27;
                        $0 = load_property "pitch_degree" $self;
                        $1 = call_global "deg_to_rad" $0;
                        return $1;
                    </basic_block>
                    <basic_block id="bb3">
                        line_number 29;
                        $0 = load_property "pitch_degree" $self;
                        return $0;
                    </basic_block>
                </basic_blocks>
            </function>
            <function name="_default_get_pitch$to_radius"
                      is_static="true"
                      is_abstract="false"
                      is_vararg="false"
                      is_hidden="true">
                <parameters/>
                <return_type type="bool"/>
                <variables>
                    <variable id="0" type="bool"/>
                </variables>
                <basic_blocks entry="entry">
                    <basic_block id="entry">
                        line_number 26;
                        $0 = literal_bool false;
                        return $0;
                    </basic_block>
                </basic_blocks>
            </function>
            <function name="_field_init_pitch_degree"
                      is_static="true"
                      is_abstract="false"
                      is_vararg="false"
                      is_hidden="true">
                <parameters>
                    <parameter name="self" type="RotatingCamera"/>
                </parameters>
                <return_type type="float"/>
                <variables>
                    <variable id="self" type="RotatingCamera"/>
                    <variable id="0" type="float"/>
                </variables>
                <basic_blocks entry="entry">
                    <basic_block id="entry">
                        line_number 6;
                        $0 = literal_float 45;
                        return $0;
                    </basic_block>
                </basic_blocks>
            </function>
            <function name="_field_init_rotating_speed_degree"
                      is_static="false"
                      is_abstract="false"
                      is_vararg="false"
                      is_hidden="true">
                <parameters>
                    <parameter name="self" type="RotatingCamera"/>
                </parameters>
                <return_type type="float"/>
                <variables>
                    <variable id="self" type="RotatingCamera"/>
                    <variable id="0" type="float"/>
                </variables>
                <basic_blocks entry="entry">
                    <basic_block id="entry">
                        line_number 7;
                        $0 = literal_float 60;
                        return $0;
                    </basic_block>
                </basic_blocks>
            </function>
            <function name="_field_init_length"
                      is_static="false"
                      is_abstract="false"
                      is_vararg="false"
                      is_hidden="true">
                <parameters>
                    <parameter name="self" type="RotatingCamera"/>
                </parameters>
                <return_type type="float"/>
                <variables>
                    <variable id="self" type="RotatingCamera"/>
                    <variable id="0" type="float"/>
                </variables>
                <basic_blocks entry="entry">
                    <basic_block id="entry">
                        line_number 8;
                        $0 = literal_float 5;
                        return $0;
                    </basic_block>
                </basic_blocks>
            </function>
        </functions>
    </class_def>
</ir>
```
