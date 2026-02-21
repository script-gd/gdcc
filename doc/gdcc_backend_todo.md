# Backend ToDo List

## C Backend

### CallGlobalInsnGen

- 缺参且缺失段存在 default 时直接抛 NotImplementedException，
  但没有先区分“是否仍缺必填参数”，会把一部分应报 Too few arguments 的情况也报成“未实现”。
- 参数类型元数据为 null 时会静默跳过校验；这会把“API 元数据缺失/解析异常”推迟到更后面才暴露，不应continue应该抛出错误。

### CBodyBuilder

- 非 void 返回值“丢弃”路径可能泄漏可析构类型；生成器允许 non-void 且 resultId == null 走 discard 时，
  CBodyBuilder.callAssign 的 discard 分支只发射调用语句，不做析构/释放处理，应当对返回值进行析构/释放。