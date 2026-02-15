# 02 Architecture

## 架构总览
后端采用单进程 Kotlin/Ktor 架构，目标是跨平台可运行与容器化友好。

```text
Browser (web-ui)
   -> /api (REST + SSE)
Ktor Delivery Layer (backend-server)
   -> Application Service (ConversationEngine + orchestration)
   -> Repositories (settings / conversations / files)
SQLite + File Storage (backend-storage-sqlite)
   -> data/settings.json
   -> data/rikka_hub.db
   -> data/upload/*
Migration Importer (backend-migration)
```

## 模块职责

### `backend-core`
- 纯 Kotlin 领域模型、DTO、JSON 编解码配置。
- 会话 diff 与事件 DTO（snapshot/node_update/error/invalidate）。
- 统一异常模型（400/404/401 等）。

### `backend-storage-sqlite`
- SQLite JDBC 数据访问与 schema 初始化。
- 会话、消息节点、文件、设置仓储实现。
- 数据路径规范化与文件安全访问。

### `backend-migration`
- Android 备份 zip 导入。
- 导入前备份、导入失败回滚、导入后计数校验。

### `backend-server`
- Ktor 启动、路由注册、JWT 鉴权、SSE、静态资源托管。
- 将 `/api` 契约暴露给 web-ui。

## 请求与事件流

### 聊天主链路（当前实现）
1. web-ui `POST /api/conversations/{id}/messages`。
2. `ConversationEngine` 写入用户消息节点。
3. 引擎触发生成任务并广播会话变化事件。
4. 详情 SSE 根据 diff 发送 `node_update`，否则发送 `snapshot`。

### 设置同步
1. 前端更新设置接口（`/api/settings/*`）。
2. `SettingsJsonRepository` 持久化 `settings.json`。
3. `/api/settings/stream` SSE 推送 `update`。

### 会话列表刷新
1. 会话变化时，`ConversationEngine` 发出 list invalidate。
2. `/api/conversations/stream` 推送 `invalidate`。
3. 前端按 assistant 维度刷新分页列表。

## 数据布局

```text
data/
  settings.json
  rikka_hub.db
  rikka_hub-wal (optional)
  rikka_hub-shm (optional)
  upload/
  backup/ (migration rollback snapshots)
```

## 鉴权模型
- 默认 `JWT_ENABLED=false`，API 无鉴权。
- 启用后支持：
  - `Authorization: Bearer <token>`
  - `access_token` query 参数（用于媒体/文件 URL）。
- `POST /api/auth/token` 在 JWT 关闭时返回 400。

## 与 Android 原架构边界
- Android 侧保留 UI、通知、系统能力。
- 新后端承接 web-ui 所需服务能力，不依赖 Android Context/Room/DataStore。
- 后续完整移植将继续抽离 ChatService 的完整生成流水线。
