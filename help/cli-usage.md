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

To compile a whole Godot project at once, pass `project.godot` itself as the positional input. GDCC then auto-discovers the project's `.gd3` sources (honoring `gdcc/sync/excluded_globs`, default `addons/gdcc/*`), compiles them together with any `.gd` files listed on the command line, and publishes the native library plus `bin/gdcc.gdextension` into the project's `bin/` directory:

```sh
gdcc game/project.godot
gdcc game/project.godot game/src/explicit_helper.gd
```

In project mode, `gdcc/build/*` entries in `project.godot` supply the default optimization level, strict mode, and C compiler backend; explicit `--opt`/`--backend` options override them. `-o` only selects the internal build directory (default `.godot/gdcc/cli/<project-identity>/`) — it does not change the `bin/` publish target.

Useful options:

- `-o` / `--output <output>`: output target path. In file mode, if omitted, GDCC derives a name from the input files; in project mode it only selects the internal build directory (default `.godot/gdcc/cli/<project-identity>/`).
- `--gde <version>`: Godot GDExtension API version. Currently only `4.5.1` is supported.
- `--opt` / `--optimize <level>`: `debug` or `release`.
- `--target <platform>`: target platform, such as `linux-x86-64`, `windows-x86-64`, `linux-aarch64`, or `macos-aarch64`.
- `--prefix <prefix>` and `--class-map Source=Canonical`: control generated top-level canonical class names.
