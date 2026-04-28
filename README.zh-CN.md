![GDCC Logo Bar](asset/bar.png)

**GDCC: *GD*script to *C* *C*ompiler**

其他语言版本：[English](README.md)

GDCC 是一个将 GDScript 编译为 GDExtension 本机二进制模块的编译器，允许您通过 Godot 引擎的本机二进制接口安全且高效地运行 GDScript 代码。

从现在起，编写 GDScript，获取类似 C/C++ 原生模块的安全性与性能！

-----

# 特性

- 可拓展的架构：解析器、语义分析器、IR转换器(WIP)、中间表示、后端生成器、交叉编译模块化设计
- 反编译安全：为 GDScript 生成本机二进制模块，根源上保护源码不泄露
- 高性能：直接生成高效的静态 GDExtension 调用，避免兼容层和动态派发的性能损失
- 渐进式迁移：兼容大部分弱类型代码，允许逐步迁移到更严格的类型系统以获得更高性能

# 状态

GDCC 目前仍处于早期 alpha 阶段，请不要在生产环境中使用。

## 目前支持的 GDScript 用法

- 基本脚本结构：`class_name`、`extends`、函数声明、变量声明。
- 常见标量、内置类型、对象类型、`Variant`、`Array`、`Dictionary` 和 PackedArray 类型。
- 局部变量、赋值、返回值类型检查，以及带 `break` / `continue` 的普通 `if` / `elif` / `while` 控制流。
- 条件表达式中的 Godot 风格真值判断。
- 所有的内置类型和引擎对象的属性读取与写入。
- 常见函数调用、方法调用、构造调用和全局调用，包括语句位置的 `void` 调用。
- 已支持数组、字典、PackedArray 和类型化容器的基本索引访问。
- 有限的常见属性初始化语法。
- 混合强类型和弱类型变量代码，允许渐进式迁移到gdcc。

## 主要不支持或受限的 GDScript 用法

- `for`、`match`、`lambda`、`await`，以及复杂协程或信号流程。
- 数组和字典字面量、三元表达式、`assert`、`preload`、`get_node`、类型转换和类型测试。
- `not in`、字符串 `%` 格式化、参数默认值、局部或类常量、脚本级 `static var`。
- 基于路径的 `extends`、autoload 父类绑定、全局脚本类父类绑定，以及多模块父类绑定。
- 类似 `vector["x"]` 的内置类型 keyed 访问；已支持场景下请使用 `vector.x` 这类属性访问。
- 更宽松的 Godot 运行时转换兼容性仍不完整，包括若干 key、index 和数值类型放宽转换。

# 下载

从 [GitHub Releases](https://github.com/script-gd/gdcc/releases) 页面下载 GDCC。

普通平台包名称类似 `gdcc-<version>-<os>-<arch>.zip`。 每个普通平台包内含 `gdcc` 启动器以及其他可能随版本升级而改变的核心文件。  
`*-full.zip` 包内含相同文件，并额外打包了运行所需的全部工具链，适合开箱即用。

# 用法

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
- `--target <platform>`：目标平台，例如 `linux-x86-64`、`windows-x86-64` 或 `linux-aarch64`。
- `--prefix <prefix>` 和 `--class-map Source=Canonical`：控制生成的顶层 canonical 类名。

# 社区

- QQ群：1082512847
