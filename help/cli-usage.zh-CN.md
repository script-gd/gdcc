# GDCC CLI用法

基本命令格式：

```sh
gdcc [options] <files...>
```

最小示例：

```sh
gdcc -o build/demo src/player.gd
```

将多个脚本编译到同一个模块：

```sh
gdcc -o build/demo src/player.gd src/enemy.gd
```

编译属于 Godot 项目的脚本时，传入项目文件可让 GDCC 生成 `.gdextension` 元数据：

```sh
gdcc --project game/project.godot -o build/demo game/src/player.gd
```

要一次编译整个 Godot 项目，把 `project.godot` 本身作为位置参数传入。GDCC 会自动发现项目内的 `.gd3` 源（遵守 `gdcc/sync/excluded_globs`，缺省为 `addons/gdcc/*`），将其与命令行显式列出的 `.gd` 文件放入同一次编译，并把原生库与 `bin/gdcc.gdextension` 发布到项目的 `bin/` 目录：

```sh
gdcc game/project.godot
gdcc game/project.godot game/src/explicit_helper.gd
```

项目模式下，`project.godot` 中的 `gdcc/build/*` 设置提供默认的优化等级、strict mode 和 C 编译器后端；显式 `--opt`/`--backend` 选项会覆盖它们。`-o` 只选择内部构建目录（默认 `.godot/gdcc/cli/<项目身份>/`），不改变 `bin/` 发布目的地。

常用选项：

- `-o` / `--output <output>`：输出目标路径。文件模式下省略时，GDCC 会根据输入文件名推导默认名称；项目模式下仅选择内部构建目录（默认 `.godot/gdcc/cli/<项目身份>/`）。
- `--gde <version>`：Godot GDExtension API 版本。目前仅支持 `4.5.1`。
- `--opt` / `--optimize <level>`：`debug` 或 `release`。
- `--target <platform>`：目标平台，例如 `linux-x86-64`、`windows-x86-64`、`linux-aarch64` 或 `macos-aarch64`。
- `--prefix <prefix>` 和 `--class-map Source=Canonical`：控制生成的顶层 canonical 类名。
