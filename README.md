![GDCC Logo Bar](asset/bar.png)

**GDCC: *GD*script to *C* *C*ompiler**

Other languages: [简体中文](README.zh-CN.md)

GDCC is a compiler that turns GDScript into native GDExtension binary modules, allowing GDScript to run safely and efficiently through Godot Engine's native binary interface.

From now on, write GDScript and get the safety and performance of native C/C++-style modules.

-----

# Features

- Extensible architecture: modular design for the parser, semantic analyzer, IR lowering (WIP), intermediate representation, backend generator, and cross-compilation toolchain
- Reverse-engineering resistance: generates native binary modules for GDScript, protecting source code from disclosure fundamentally
- High performance: directly generates efficient static GDExtension calls, avoiding the overhead of compatibility layers and dynamic dispatch
- Progressive migration: remains compatible with most loosely typed code, allowing gradual migration to a stricter type system for better performance

# Status

GDCC is currently in an early alpha stage. Do not use it in production.

## Currently Supported

- Basic script structure: `class_name`, `extends`, function declarations, and variable declarations.
- Common scalar, built-in, object, `Variant`, `Array`, `Dictionary`, and packed-array types.
- Local variables, assignments, return typing, and ordinary `if` / `elif` / `while` control flow with `break` and `continue`.
- Godot-style truthiness in conditions.
- Property access and assignment on all built-in and engine object types.
- Common function, method, constructor, and global calls, including statement-position `void` calls.
- Basic container indexing for supported array, dictionary, packed-array, and typed container.
- A few common property initializer support.
- Code that mixes strongly typed and weakly typed variables, allowing a gradual migration to gdcc.

## Unsupported or Limited

- `for`, `match`, `lambda`, `await`, and coroutine flows.
- Array and dictionary literals, ternary expressions, `assert`, `preload`, `get_node`, casts, and type tests.
- `not in`, string `%` formatting, parameter defaults, local or class constants, and script-level `static var`.
- Path-based `extends`, autoload superclass binding, global script class superclass binding, and multi-module superclass binding.
- Built-in keyed access such as `vector["x"]`; use property-style access such as `vector.x` where supported.
- Broad Godot runtime conversion compatibility is still incomplete, including several widened key, index, and numeric conversion cases.

# Download

Download GDCC from the [GitHub Releases](https://github.com/script-gd/gdcc/releases) page. 

The normal platform packages are named like `gdcc-<version>-<os>-<arch>.zip`.

Each normal platform package contains the `gdcc` launcher and the core files that are likely to be modified each version.

The `*-full.zip` packages contain the same files plus all toolchains bundled. You can run `gdcc` out of the box. 

# Usage

Basic command shape:

```sh
gdcc [options] <files...>
```

Minimal example:

```sh
gdcc -o build/demo src/player.gd
```

To compile multiple scripts into one module:

```sh
gdcc -o build/demo src/player.gd src/enemy.gd
```

When compiling scripts that belong to a Godot project, pass the project file to let GDCC emit `.gdextension` metadata:

```sh
gdcc --project game/project.godot -o build/demo game/src/player.gd
```

Useful options:

- `-o` / `--output <output>`: output target path. If omitted, GDCC derives a name from the input files.
- `--gde <version>`: Godot GDExtension API version. Currently only `4.5.1` is supported.
- `--opt` / `--optimize <level>`: `debug` or `release`.
- `--target <platform>`: target platform, such as `linux-x86-64`, `windows-x86-64`, or `linux-aarch64`.
- `--prefix <prefix>` and `--class-map Source=Canonical`: control generated top-level canonical class names.

# Community

- QQ Group: 1082512847
