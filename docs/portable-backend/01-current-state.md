# 01 Current State

## 目标与范围
本阶段目标是把 Android 内嵌 Web 服务重写为可在 Windows/Linux 直接运行、并可容器化部署的独立后端。当前文档记录的是“阶段 A 收口”时点的真实状态，不包含后续功能扩展的假设实现。

## 已完成内容

### 模块与构建接入
- 新增模块：`backend-core`、`backend-storage-sqlite`、`backend-migration`、`backend-server`。
- 根工程已接入新模块：`settings.gradle.kts`。
- 根构建脚本新增 JVM 插件声明：`build.gradle.kts`。
- 版本目录新增后端依赖与插件：`gradle/libs.versions.toml`。

### 后端能力
- 后端主入口：`backend-server/src/main/kotlin/me/rerere/rikkahub/backend/server/Main.kt`。
- 当前已覆盖 web-ui 主链路接口：
  - `/api/auth/token`
  - `/api/settings/*` + `/api/settings/stream`
  - `/api/conversations/*` + `/api/conversations/stream` + `/api/conversations/{id}/stream`
  - `/api/files/*`
  - `/api/ai-icon`
  - `/api/assets/*`
  - `/api/system/health`、`/api/system/info`
  - `/api/migration/import`
- SSE 事件类型保持兼容：
  - settings：`update`
  - conversation list：`invalidate`
  - conversation detail：`snapshot`、`node_update`、`error`

### 存储与迁移
- 默认数据目录：`data/`
  - `settings.json`
  - `rikka_hub.db`（及 `-wal`/`-shm`）
  - `upload/*`
- 已实现 Android 备份包导入（zip）：`settings.json + rikka_hub.db + upload/*`。
- 迁移失败支持回滚到导入前快照。

### 运行与部署资产
- Docker 多阶段构建：`Dockerfile`。
- 启动脚本：`run-backend.ps1`、`run-backend.sh`。
- 后端独立 settings 文件：`settings.backend.gradle.kts`（用于精简后端构建场景）。

## 当前已验证结论
- `:backend-server:compileKotlin` 可通过。
- 后端服务可启动，并可响应 `GET /api/system/health`。
- Web UI 当前路由范围集中在聊天工作流（`home`/`conversations`/`c/:id`）。

## 关键差距（阶段 A 结束时）
- `ConversationEngine` 仍为占位生成（echo/模拟回复），尚未迁移完整 provider pipeline。
- 新后端模块自动化测试基本为空（`NO-SOURCE`）。
- 需要继续完成 Android 全业务盘点并映射到 WebUI/Backend 的逐项差距闭环。

## 本阶段收口动作
- 修复 `run-backend` 脚本中的不兼容参数，统一使用 `:backend-server:run`。
- 修复 Gradle Wrapper 的本地绝对路径依赖，恢复为标准发行地址。
- 更新 `.gitignore`，忽略本地离线压缩包与临时运行产物。
