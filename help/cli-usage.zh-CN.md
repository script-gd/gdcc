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

常用选项：

- `-o` / `--output <output>`：输出目标路径。省略时，GDCC 会根据输入文件名推导默认名称。
- `--gde <version>`：Godot GDExtension API 版本。目前仅支持 `4.5.1`。
- `--opt` / `--optimize <level>`：`debug` 或 `release`。
- `--target <platform>`：目标平台，例如 `linux-x86-64`、`windows-x86-64`、`linux-aarch64` 或 `macos-aarch64`。
- `--prefix <prefix>` 和 `--class-map Source=Canonical`：控制生成的顶层 canonical 类名。
