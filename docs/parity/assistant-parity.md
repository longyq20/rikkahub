# Assistant 功能对照（Android -> Web）

更新时间：2026-02-16

## 已对齐（本阶段）
- 助手基础：切换/新增/克隆/删除。
- 基础参数：`name`、`chatModelId`、`temperature`、`topP`、`contextMessageSize`、`thinkingBudget`、`maxTokens`。
- 行为开关：`streamOutput`、`useAssistantAvatar`、`enableMemory`、`useGlobalMemory`、`enableRecentChatsReference`。
- Prompt 配置：`systemPrompt`、`messageTemplate`、`quickMessages`。
- 请求覆盖：`customHeaders`、`customBodies`（JSON 数组编辑并应用）。
- 绑定项：`tags`、`localTools`、`mcpServers`、`modeInjectionIds`、`lorebookIds`。
- 高级兜底：单助手 JSON 编辑并应用。

## 部分对齐 / 待补齐
- `presetMessages`：仅支持通过高级 JSON 编辑，缺少结构化 UI。
- `regexes`：仅支持通过高级 JSON 编辑，缺少结构化 UI。
- `avatar/background`：仅支持通过高级 JSON 编辑，缺少图形化编辑器。
- Android 助手列表页的拖拽排序/过滤体验：Web 端尚未完全对齐。

## Android 页面映射
- `AssistantBasicPage.kt`：已覆盖主要字段。
- `AssistantPromptPage.kt`：已覆盖系统提示词、消息模板、快捷语；预设消息/正则待结构化。
- `AssistantRequestPage.kt`：已覆盖（JSON 方式）。
- `AssistantMemoryPage.kt`：开关在助手页，内存条目管理在 `Settings > Memory`。
- `AssistantMcpPage.kt`：已覆盖（绑定选择）。
- `AssistantLocalToolPage.kt`：已覆盖（`javascript_engine/time_info/clipboard`）。
- `AssistantInjectionsPage.kt`：已覆盖（mode/lorebook 绑定选择）。

## 下一阶段建议顺序
1. 结构化补齐 `presetMessages`。
2. 结构化补齐 `regexes`。
3. 补齐 `avatar/background` 图形化编辑。
4. 评估并补齐助手列表排序/过滤与标签体验。
