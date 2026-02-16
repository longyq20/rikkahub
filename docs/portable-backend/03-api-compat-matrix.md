# 03 API Compatibility Matrix

## 说明
- 基线来源：Android `app/.../web/routes/*` 与新后端 `backend-server/*Routes.kt`。
- 目标：保持现有 web-ui 依赖路径与事件语义兼容。

## 路由兼容矩阵

| API | Android 原实现 | 新后端实现 | 兼容状态 | 备注 |
|---|---|---|---|---|
| `POST /api/auth/token` | 支持 | 支持 | 兼容 | JWT 关闭时返回 400。 |
| `GET /api/ai-icon` | 支持 | 支持 | 兼容 | model/provider 图标。 |
| `POST /api/settings/assistant` | 支持 | 支持 | 兼容 | assistant 切换。 |
| `POST /api/settings/assistant/model` | 支持 | 支持 | 兼容 | 模型切换。 |
| `POST /api/settings/assistant/thinking-budget` | 支持 | 支持 | 兼容 | 推理预算。 |
| `POST /api/settings/assistant/mcp` | 支持 | 支持 | 兼容 | MCP 服务器选择。 |
| `POST /api/settings/assistant/injections` | 支持 | 支持 | 兼容 | 模式注入与 lorebook。 |
| `POST /api/settings/search/enabled` | 支持 | 支持 | 兼容 | 搜索开关。 |
| `POST /api/settings/search/service` | 支持 | 支持 | 兼容 | 搜索服务选择。 |
| `POST /api/settings/model/built-in-tool` | 支持 | 支持 | 兼容 | 内建工具开关。 |
| `POST /api/settings/favorite-models` | 支持 | 支持 | 兼容 | 收藏模型。 |
| `SSE /api/settings/stream` | 支持 | 支持 | 兼容 | 事件：`update`。 |
| `GET /api/conversations/paged` | 支持 | 支持 | 兼容 | offset/limit/query。 |
| `GET /api/conversations/{id}` | 支持 | 支持 | 兼容 | 会话详情。 |
| `DELETE /api/conversations/{id}` | 支持 | 支持 | 兼容 | 删除会话。 |
| `POST /api/conversations/{id}/pin` | 支持 | 支持 | 兼容 | 置顶切换。 |
| `POST /api/conversations/{id}/regenerate-title` | 支持 | 支持 | 兼容 | 模型生成 + fallback。 |
| `POST /api/conversations/{id}/title` | 支持 | 支持 | 兼容 | 手工改标题。 |
| `POST /api/conversations/{id}/move` | 支持 | 支持 | 兼容 | 会话迁移到 assistant。 |
| `POST /api/conversations/{id}/messages` | 支持 | 支持 | 部分 | 已接入真实模型，tool 执行链待补齐。 |
| `POST /api/conversations/{id}/messages/{messageId}/edit` | 支持 | 支持 | 部分 | 编辑后可续跑，tool 执行链待补齐。 |
| `POST /api/conversations/{id}/fork` | 支持 | 支持 | 兼容 | 叉分会话。 |
| `DELETE /api/conversations/{id}/messages/{messageId}` | 支持 | 支持 | 兼容 | 删除消息。 |
| `POST /api/conversations/{id}/nodes/{nodeId}/select` | 支持 | 支持 | 兼容 | 分支切换。 |
| `POST /api/conversations/{id}/regenerate` | 支持 | 支持 | 部分 | 已接入真实模型，tool 执行链待补齐。 |
| `POST /api/conversations/{id}/stop` | 支持 | 支持 | 兼容 | 停止生成。 |
| `POST /api/conversations/{id}/tool-approval` | 支持 | 支持 | 部分 | 审批后续跑可用，真实 tool 执行链待补齐。 |
| `SSE /api/conversations/stream` | 支持 | 支持 | 兼容 | 事件：`invalidate`。 |
| `SSE /api/conversations/{id}/stream` | 支持 | 支持 | 兼容 | 事件：`snapshot`/`node_update`/`error`。 |
| `POST /api/files/upload` | 支持 | 支持 | 兼容 | 多文件上传，返回字段一致。 |
| `DELETE /api/files/{id}` | 支持 | 支持 | 兼容 | 删除文件。 |
| `GET /api/files/id/{id}` | 支持 | 支持 | 兼容 | 通过 DB id 取文件。 |
| `GET /api/files/path/{path...}` | 支持 | 支持 | 兼容 | 相对路径访问。 |
| `GET /api/assets/{path...}` | 支持 | 支持 | 兼容 | 静态资产。 |
| `POST /api/migration/import` | 支持（Android备份通道） | 支持 | 兼容 | zip 导入 + 回滚。 |
| `GET /api/migration/export` | Android App 页面能力 | 支持（新增） | 增量扩展 | 导出兼容 zip（后端已提供）。 |
| `GET /api/memory` | Android 仅 App 内 memory 管理 | 支持（新增） | 增量扩展 | assistant 作用域 memory 列表。 |
| `POST /api/memory` | Android 仅 App 内 memory 管理 | 支持（新增） | 增量扩展 | 新增 memory 记录。 |
| `PUT /api/memory/{id}` | Android 仅 App 内 memory 管理 | 支持（新增） | 增量扩展 | 更新 memory 内容。 |
| `DELETE /api/memory/{id}` | Android 仅 App 内 memory 管理 | 支持（新增） | 增量扩展 | 删除 memory 记录。 |

## 增量接口（Android Web API 原无）
- `GET /api/system/health`
- `GET /api/system/info`
- `GET /api/migration/export`
- `GET /api/memory` / `POST /api/memory` / `PUT /api/memory/{id}` / `DELETE /api/memory/{id}`

## SSE 事件兼容性
- 列表流：`invalidate`
- 详情流：`snapshot`、`node_update`、`error`
- 设置流：`update`

## 主要差异结论
- 路由层与事件命名整体兼容。
- 当前核心差异集中在 tool 真实执行链与扩展业务域，不在主干路由契约层。
