![GDCC Logo Bar](asset/bar.png)

**GDCC: *GD*script to *C* *C*ompiler**

Other languages: [简体中文](README.zh-CN.md)

GDCC is a compiler that turns GDScript into native GDExtension binary modules, allowing GDScript to run safely and efficiently through Godot Engine's native binary interface.

From now on, write GDScript and get the safety and performance of native C/C++-style modules.

-----

# Features

- Extensible architecture: modular design for the parser, semantic analyzer, IR lowering (WIP), intermediate representation, backend generator, and cross-compilation toolchain
- Reverse-engineering resistance: generates native binary modules for GDScript, protecting source code from disclosure at the root
- High performance: directly generates efficient static GDExtension calls, avoiding the overhead of compatibility layers and dynamic dispatch
- Progressive migration: remains compatible with most loosely typed code, allowing gradual migration to a stricter type system for better performance

# Status

GDCC is currently in the pre-alpha stage. The core architecture is in place, while modules such as IR lowering are still under implementation.

At the moment, functionality is only available through the API. Developers are welcome to join testing and contribute code.

# Architecture

- `gdcc.frontend.parse`: a fault-tolerant incremental GDScript parser that produces the AST (Abstract Syntax Tree)
- `gdcc.frontend.sema`: a semantic analyzer that performs type checking, scope analysis, and related tasks, producing a typed AST
- `gdcc.frontend.lowering`: lowers the high-level AST into IR (Intermediate Representation)
- `gdcc.gdextension`: a GDExtension signature parser, validator, and related tooling
- `gdcc.type`: a full implementation of the Godot type system, including built-in types, user-defined types, generics, and more
- `gdcc.scope`: type-checking, scope, and symbol table infrastructure with support for nested scopes, closures, and more
- `gdcc.lir`: a linear intermediate representation that uniformly models all operations and control flow expressible in GDScript
- `gdcc.backend.c.gen`: a generator that translates LIR into C code
- `gdcc.backend.c.build`: a build toolchain that cross-compiles GDExtension modules for PC, mobile, and web targets
