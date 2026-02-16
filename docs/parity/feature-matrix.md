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

## 状态总览（2026-02-16）
- 聊天主链路：已从占位推进到真实模型生成，SSE 主事件契约已自动化验证。
- 设置主链路：WebUI 与 Backend 已对齐，具备流式同步。
- 文件与迁移：基础 API 已具备，边界测试与前端入口仍需补齐。
- 扩展能力：memory、prompts 已完成 WebUI + Backend 贯通；translator/imggen/log/debug/TTS/share 仍缺失。

## 分阶段实施建议

### Stage B1（核心可用全量）
- 已完成：
  - 真实生成链路接入（替换 echo）。
  - tool approval 后续跑闭环。
  - 首批 `/api` + SSE 自动化契约测试。
- 待完成：
  - tool 真实执行链与结果回写。
  - files/auth/migration 失败路径自动化。
  - 迁移导入前端入口与导入报告展示。

### Stage B2（扩展能力全量）
- 按矩阵逐项补齐 memory、prompts、translator、imggen、history、log/debug、TTS、share。
- 对 Android 平台专属能力给出 Web 等价替代或明确降级策略。

## 使用方式
1. 每次迭代先更新 `feature-matrix.csv` 的状态与差距字段。
2. 再提交对应代码与测试证据。
3. 发布前确保所有 `P0/P1` 项达到“已实现 + 已验收”或明确风险豁免。
