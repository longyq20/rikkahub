# 06 Known Gaps

## P0（优先收敛）
1. Tool/MCP 能力仍有剩余缺口
- 现状：已支持 approved/auto tool 执行与 output 回写，审批后可续跑；已接入 `get_time_info`、`memory_tool`、`search_web`、`scrape_web`；已支持 OpenAI `tool_calls -> tool part` 映射，且会在后续请求中以 `role=tool` 回填已执行 tool result；已支持 `mcp__*` 工具的广告与执行（`sse`/`streamable_http`）。
- 剩余：MCP 的“服务端 tools 同步(listTools) + 配置自动补全/修剪”尚未实现；MCP 连接复用/重连策略与观测仍简化；非 OpenAI provider（Claude/Google）的工具调用协议仍未实现。
- 影响：与 Android `ChatService` 的完整工具能力仍存在差距，尤其是 MCP 运维体验与跨 provider 一致性。

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
- 当前阻塞完整移植的重点转为：MCP 运维体验（同步/重连/可观测性）与全域契约测试广度（覆盖更多设置域与失败路径）。