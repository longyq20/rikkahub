# 03 API Compatibility Matrix

## 说明
- 基线来源：Android `app/.../web/routes/*` 与新后端 `backend-server/*Routes.kt`。
- 目标：保持现有 web-ui 依赖路径与事件语义兼容。

## 路由兼容矩阵

| API | Android 原实现 | 新后端实现 | 兼容状态 | 备注 |
|---|---|---|---|---|
| `POST /api/auth/token` | 支持 | 支持 | 兼容 | JWT 关闭时返回 400，行为保持一致。 |
| `GET /api/ai-icon` | 支持 | 支持 | 兼容 | 用于 model/provider 图标。 |
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
| `POST /api/conversations/{id}/regenerate-title` | 支持 | 支持 | 部分 | 新后端当前为占位标题生成。 |
| `POST /api/conversations/{id}/title` | 支持 | 支持 | 兼容 | 手工改标题。 |
| `POST /api/conversations/{id}/move` | 支持 | 支持 | 兼容 | 会话迁移到 assistant。 |
| `POST /api/conversations/{id}/messages` | 支持 | 支持 | 部分 | 生成流程当前为占位 echo。 |
| `POST /api/conversations/{id}/messages/{messageId}/edit` | 支持 | 支持 | 部分 | 分支编辑兼容，后续需接入完整生成逻辑。 |
| `POST /api/conversations/{id}/fork` | 支持 | 支持 | 兼容 | 叉分会话。 |
| `DELETE /api/conversations/{id}/messages/{messageId}` | 支持 | 支持 | 兼容 | 删除消息。 |
| `POST /api/conversations/{id}/nodes/{nodeId}/select` | 支持 | 支持 | 兼容 | 分支切换。 |
| `POST /api/conversations/{id}/regenerate` | 支持 | 支持 | 部分 | 再生成为占位逻辑。 |
| `POST /api/conversations/{id}/stop` | 支持 | 支持 | 兼容 | 停止生成。 |
| `POST /api/conversations/{id}/tool-approval` | 支持 | 支持 | 部分 | 状态更新兼容，审批后续跑仍需接入真实 pipeline。 |
| `SSE /api/conversations/stream` | 支持 | 支持 | 兼容 | 事件：`invalidate`。 |
| `SSE /api/conversations/{id}/stream` | 支持 | 支持 | 兼容 | 事件：`snapshot`/`node_update`/`error`。 |
| `POST /api/files/upload` | 支持 | 支持 | 兼容 | 多文件上传，返回字段保持一致。 |
| `DELETE /api/files/{id}` | 支持 | 支持 | 兼容 | 删除文件。 |
| `GET /api/files/id/{id}` | 支持 | 支持 | 兼容 | 通过 DB id 取文件。 |
| `GET /api/files/path/{path...}` | 支持 | 支持 | 兼容 | 相对路径访问。 |
| `GET /api/assets/{path...}` | 支持 | 支持 | 兼容 | 静态资产。 |

## 增量接口（Android 原无）
- `GET /api/system/health`
- `GET /api/system/info`
- `POST /api/migration/import`

## SSE 事件兼容性
- 列表流：`invalidate`
- 详情流：`snapshot`、`node_update`、`error`
- 设置流：`update`

## 主要差异结论
- 路由层与事件命名整体兼容。
- 核心差异集中在生成引擎能力深度，不在路由契约层。
