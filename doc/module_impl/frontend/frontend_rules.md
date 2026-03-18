# Frontend Rules

## 恢复约定

- frontend 对普通源码错误必须优先通过 `DiagnosticManager` 发诊断，不要把异常当成常规控制流。
- 当某个 AST 节点树已经无法稳定产生产物时，当前 phase 必须跳过该节点树，并继续处理同一 module 中其他仍可恢复的节点树。
- 对 deferred / skipped subtree 的 warning，优先锚定到被跳过子树的根节点；若无法识别更大的恢复根，才允许退化到节点自身这一最小 skipped root。
- 只有 programmer error、共享 side-table 破坏、协议不变量失真等不可恢复 guard rail，才允许抛异常；`FrontendSemanticException` 不作为普通源码错误的主路径。

## 诊断约定

- parser 必须保持 tolerant：`gdparser` lowering diagnostics 映射为 `parse.lowering`，parser/runtime 失败映射为 `parse.internal`，不要把运行时异常直接抛给调用方。
- skeleton / analyzer / 后续 binder-body phase 对可恢复错误必须采用“diagnostic + skip subtree”策略；不要因为单个坏节点打断整条 frontend pipeline。
- 新增 frontend 诊断或恢复路径时，必须同步更新 `diagnostic_manager.md`、相关实现注释和受影响的模块文档，避免代码与文档冲突。
- body phase 的 diagnostic owner 必须保持单一：
  - top binding 负责 bare `TYPE_META` ordinary-value misuse 的首条 `sema.binding`
  - chain binding 负责 `sema.member_resolution` / `sema.call_resolution` / chain deferred/unsupported boundary
  - expr analyzer 负责 `sema.expression_resolution` / `sema.deferred_expression_resolution` / `sema.unsupported_expression_route` / `sema.discarded_expression`
  - 若同一根源错误已经有 upstream diagnostic，下游 analyzer 只能保留 side-table status，不得再补第二条同级错误

## 测试约定

- 每条新的 frontend 恢复规则都必须同时覆盖 happy path 与 negative path。
- negative path 至少要锚定：正确 diagnostic category、坏 subtree 被跳过、同一 module 中其他合法 subtree 仍继续工作。

## MVP 支持约定 

- `lambda`, `match`, `for`不在最小可行产品范围内。
- 协程和信号上的协程不在最小可行产品范围内。
- path-based `extends`、autoload superclass、global-script-class superclass 绑定不实施。
- 多 gdcc module 的 header superclass 绑定不在最小可行产品范围内。
- 函数参数默认值不在最小可行产品范围内。
- class constant 的收集、注册、继承可见性与绑定不在 MVP 范围内，整体延后到 MVP 之后再实施。
- callable scope / block scope 中手动声明或发布的类型别名不在 MVP 范围内；frontend body phase 必须对这类 scope-local `type-meta` 采用 fail-closed 的 deferred / unsupported 处理，而不是把它们当成普通 class-like `TYPE_META` 消费。
- `FrontendTopBindingAnalyzer` 当前只发布 symbol category，不区分 read / write / call 等 usage 语义；assignment 左值链头等 use-site 也可能进入 `symbolBindings()`。
- 若后续 frontend 需要记录完整用法，必须扩展 `FrontendBinding` 模型，不要依赖当前 binding kind 反推读写调用语义。
