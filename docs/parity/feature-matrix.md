# Feature Matrix

## 说明
- 本矩阵用于 Android -> WebUI -> Backend 的逐项功能移植跟踪。
- 字段与 CSV 对齐：
  - `功能ID`
  - `Android入口`
  - `数据模型/存储`
  - `现有API`
  - `WebUI状态`
  - `Backend状态`
  - `差距类型`
  - `优先级`
  - `验收标准`

## 差距类型枚举
- `前端缺失`
- `后端缺失`
- `契约不一致`
- `平台差异需替代实现`

## 状态总览（当前）
- 聊天主链路：路由与交互已接通，但生成引擎仍是占位实现。
- 设置主链路：WebUI 与 Backend 已基本对齐。
- 文件与基础迁移：上传/访问/删除与导入 API 已具备。
- 扩展能力：memory/prompts/translator/imggen/log/debug/TTS/share 仍主要缺失。

## 分阶段实施建议

### Stage B1（核心可用全量）
- 完成聊天生成引擎真实迁移（替换 echo）。
- 补齐 tool approval -> continue generation 的完整状态机。
- 做完 `/api` + SSE 自动化契约测试。
- 完成迁移导入前端入口和导入报告展示。

### Stage B2（扩展能力全量）
- 按矩阵逐项补齐 memory、prompts、translator、imggen、history、log/debug、TTS、share。
- 对 Android 平台专属能力给出 Web 等价替代或明确降级策略。

## 使用方式
1. 每次迭代先更新 `feature-matrix.csv` 的状态与差距字段。
2. 再提交对应代码与测试证据。
3. 发布前确保所有 `P0/P1` 项状态达到“已实现 + 已验收”。
