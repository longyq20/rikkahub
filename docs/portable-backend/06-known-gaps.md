# 06 Known Gaps

## P0（优先收敛）
1. Tool 执行能力缺口
- 现状：已支持审批状态更新与续跑，但未接入真实 tool 调用/结果回写链路。
- 影响：与 Android `ChatService` 的工具能力仍不等价。

2. 契约测试覆盖仍不完整
- 现状：已覆盖 conversations/settings/SSE 核心路径；files/auth/migration 边界与失败路径仍不足。
- 影响：高频改动区域仍存在回归风险。

## P1（B1 阶段）
1. 生成 pipeline 细节仍需对齐
- transformers、上下文窗口细节、异常分级与回退策略尚未完全对齐 Android。

2. 迁移导入体验与报告细化
- API 已有，前端展示和错误分级仍待补齐。

3. 部署回归持续化
- Windows/Linux/Docker 启动流程需纳入持续回归脚本。

## P2（B2 阶段）
1. Android 扩展业务能力未落地 Web 对照
- memory、prompts、translator、imggen、history、log、developer/debug、TTS、share。

2. 可观测性能力仍简化
- 缺少统一请求日志格式、指标与错误聚合。

## 结论
- “占位生成”与“测试空白”两个阶段性核心阻塞已显著收敛。
- 当前真正阻塞完整移植的是业务等价深度（tool/runtime）与测试覆盖广度（全域契约）。
