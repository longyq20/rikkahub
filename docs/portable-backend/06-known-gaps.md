# 06 Known Gaps

## P0（必须优先解决）
1. 生成引擎仍为占位实现
- 位置：`backend-server/.../ConversationEngine.kt`
- 现状：`buildAssistantReply()` 返回 echo 文本，未接入 provider、transformers、tools 执行链。
- 影响：无法满足 Android 端真实业务能力移植目标。

2. 自动化测试缺失
- 现状：新增后端模块基本没有有效测试用例。
- 影响：后续重构风险高，难以保证契约稳定。

## P1（应在 B1 阶段收敛）
1. 工具审批后续跑逻辑不完整
- 现状：已可修改 `approvalState`，但未与真实 generation loop 全闭环打通。

2. 标题再生成能力为占位
- 现状：`regenerate-title` 使用固定模板时间字符串，不是模型生成。

3. 运行脚本历史兼容问题
- 现状：已在本阶段修复 `--settings-file` 参数问题。
- 后续：需在 CI 或文档中持续校验脚本可用性。

## P2（B2 阶段推进）
1. Android 扩展业务能力未完成 Web 对照落地
- memory / prompts / translator / imggen / history / log / developer / TTS / share。

2. 观测性能力仍简化
- 缺少统一请求日志、指标、错误聚合与压测报告。

## 结论
- 路由契约层已具备继续推进条件。
- 真正阻塞“完整移植”的是业务引擎深度与测试体系，不是 Ktor 脚手架。
