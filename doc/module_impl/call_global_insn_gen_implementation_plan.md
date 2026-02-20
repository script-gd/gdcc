# CallGlobalInsnGen 实施计划（归档）

## 状态

该文档对应上一版 `CALL_GLOBAL` utility-only 实现方案。由于相关实现已被移除，本文不再作为当前代码基线。

## 归档原因

- 旧方案在 `CBodyBuilder` 内引入了较重耦合。
- 调用路径存在较多手工字符串拼接，复用现有 API 不足。
- 文档与代码演进出现偏移，继续按本文执行会误导后续开发。

## 当前约束

- 若需要恢复 `CALL_GLOBAL` 的 utility 调用能力，请先完成新方案设计评审。
- 设计与实现必须遵循“先复用现有 API、后补缺口”的原则。
- 经验与反模式请参考：`doc/module_impl/call_global_utility_lessons.md`。

## 备注

历史细节和测试矩阵已从本文件移除，避免将过时设计误认为现行规范。
