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
- SSE 事件兼容：
  - settings：`update`
  - conversation list：`invalidate`
  - conversation detail：`snapshot`、`node_update`、`error`

### 生成链路（已从占位推进）
- `ConversationEngine` 已接入真实生成器：`backend-server/.../LlmGenerator.kt`。
- 已支持 provider 类型：OpenAI-compatible、Claude、Google。
- 标题再生成已切换为模型生成 + fallback。
- 再生成与 tool approval 已接入续跑逻辑。

### 存储与迁移
- 默认数据目录：`data/settings.json`、`data/rikka_hub.db`、`data/upload/*`。
- Android 备份包导入（zip）已可用：`settings.json + rikka_hub.db + upload/*`。
- 导入失败支持回滚。

### 运行与部署资产
- Docker 多阶段构建：`Dockerfile`。
- 启动脚本：`run-backend.ps1`、`run-backend.sh`。

## 当前验证结论（2026-02-16）
- `:backend-server:test` 通过（含新增 API/SSE 集成测试）。
- `:backend-core:test`、`:backend-storage-sqlite:test` 通过。
- 本地真实 provider 烟测通过：后端在临时配置下成功返回 `portable-smoke-ok`。

## 当前主要差距
1. Tool 执行链仍未完整迁移（目前仅审批与续跑闭环）。
2. API 契约自动化仍需扩面（files/auth/migration 边界与失败路径）。
3. Android 扩展能力（memory/prompts/translator/imggen/history/log/debug/TTS/share）尚未移植。

## 本阶段结论
- 阻塞点已从“脚手架/启动”转向“业务等价与测试覆盖深度”。
- 继续推进优先级：先 B1 深度对齐，再 B2 扩展能力。
