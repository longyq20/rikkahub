# 01 Current State

## 目标与范围
目标是把 Android 内嵌 Web 服务重写为可在 Windows/Linux 直接运行、并可容器化部署的独立后端；保持现有 web-ui `/api` 契约兼容并支持 Android 历史数据导入。

## 已完成内容

### 模块与构建接入
- 新增模块：`backend-core`、`backend-storage-sqlite`、`backend-migration`、`backend-server`。
- 根工程已接入模块与依赖版本目录：`settings.gradle.kts`、`gradle/libs.versions.toml`。

### 后端能力
- 后端主入口：`backend-server/src/main/kotlin/me/rerere/rikkahub/backend/server/Main.kt`。
- 覆盖 web-ui 主链路接口：
  - `/api/auth/token`
  - `/api/settings/*` + `/api/settings/stream`
  - `/api/conversations/*` + `/api/conversations/stream` + `/api/conversations/{id}/stream`
  - `/api/files/*`
  - `/api/ai-icon`
  - `/api/assets/*`
  - `/api/system/health`、`/api/system/info`
  - `/api/migration/import`
  - `/api/migration/export`
- SSE 事件兼容：
  - settings：`update`
  - conversation list：`invalidate`
  - conversation detail：`snapshot`、`node_update`、`error`

### 生成链路（已从占位推进）
- `ConversationEngine` 已接入真实生成器：`backend-server/.../LlmGenerator.kt`。
- 已支持 provider 类型：OpenAI-compatible、Claude、Google。
- 标题再生成已切换为模型生成 + fallback。
- 再生成与 tool approval 已接入续跑逻辑。
- 已支持 approved/auto tool 执行与 output 回写（含 `get_time_info`、`memory_tool`、`search_web`、`scrape_web`）。

### 存储与迁移
- 默认数据目录：`data/settings.json`、`data/rikka_hub.db`、`data/upload/*`。
- 新增 memory 持久化表：`memoryentity`（用于 `memory_tool`）。
- Android 备份包导入（zip）已可用：`settings.json + rikka_hub.db + upload/*`。
- 备份导出 API 已可用：`GET /api/migration/export`。
- 导入失败支持回滚。

### 运行与部署资产
- Docker 多阶段构建：`Dockerfile`。
- 启动脚本：`run-backend.ps1`、`run-backend.sh`。

## 当前验证结论（2026-02-16）
- `:backend-core:test`、`:backend-storage-sqlite:test`、`:backend-server:test` 全部通过。
- 本地真实 provider 烟测通过：后端在临时配置下成功返回 `portable-smoke-ok`。

## 当前主要差距
1. Tool 执行链仍有剩余缺口（MCP 执行与模型函数调用 -> tool part 自动联动仍待补齐）。
2. API 契约自动化已覆盖主路径，仍需补极端边界与跨平台回归。
3. Android 扩展能力（memory/prompts/translator/imggen/history/log/debug/TTS/share）尚未移植。

## 本阶段结论
- 阻塞点已从“脚手架/启动”转向“业务等价与测试覆盖深度”。
- 继续推进优先级：先 B1 深度对齐，再 B2 扩展能力。
