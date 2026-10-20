# GDCC CLI Usage

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
- `--target <platform>`: target platform, such as `linux-x86-64`, `windows-x86-64`, `linux-aarch64`, or `macos-aarch64`.
- `--prefix <prefix>` and `--class-map Source=Canonical`: control generated top-level canonical class names.
