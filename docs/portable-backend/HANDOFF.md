# HANDOFF

## 一页结论
- 阶段 A（三段提交）已完成：后端模块落地、运行/部署资产落地、归档文档落地。
- 阶段 B1 已推进到“真实生成 + 契约测试”阶段：`ConversationEngine` 不再是 echo 占位，已接入多 provider 生成与标题再生。
- `/api` 主链路与 web-ui 现有页面可联通，下一步重点转为 Android 全业务能力对照与缺口闭环。

## 最新基线（2026-02-16）
- 已完成提交：
  - `4db9847b`：后端模块与 API 主链路
  - `be792908`：运行脚本、Docker、忽略规则
  - `542fda1d`：阶段归档文档与 parity 初版矩阵
  - `acca9e02`：真实模型生成接入 + 单元测试
- 当前工作区新增：`backend-server` API/SSE 集成契约测试（4 个测试用例）。

## 关键入口文件
- 服务入口：`backend-server/src/main/kotlin/me/rerere/rikkahub/backend/server/Main.kt`
- 会话引擎：`backend-server/src/main/kotlin/me/rerere/rikkahub/backend/server/service/ConversationEngine.kt`
- 生成器：`backend-server/src/main/kotlin/me/rerere/rikkahub/backend/server/service/LlmGenerator.kt`
- 会话路由：`backend-server/src/main/kotlin/me/rerere/rikkahub/backend/server/ConversationRoutes.kt`
- SSE 路由：`backend-server/src/main/kotlin/me/rerere/rikkahub/backend/server/ConversationSseRoutes.kt`
- 设置路由：`backend-server/src/main/kotlin/me/rerere/rikkahub/backend/server/SettingsRoutes.kt`
- 文件路由：`backend-server/src/main/kotlin/me/rerere/rikkahub/backend/server/FileRoutes.kt`
- 迁移导入：`backend-migration/src/main/kotlin/me/rerere/rikkahub/backend/migration/MigrationImporter.kt`

## 快速启动
```powershell
./run-backend.ps1
```
```bash
./run-backend.sh
```

## 快速验证
```powershell
Invoke-RestMethod http://127.0.0.1:8080/api/system/health
```

## 当前最高优先未决
1. 工具执行链未完整迁移：当前已支持 `tool approval -> resume`，但缺少真实 tool 调用与结果回写闭环。
2. API 契约测试仍需扩面：已覆盖 settings/conversations/SSE 核心路径，仍需补齐 files/auth/migration 失败路径矩阵。
3. Android 全业务对照仍需持续闭环：B2 扩展能力（memory/prompts/translator/imggen/log/debug/TTS/share）尚未进入实现。

## 文档导航
- 当前状态：`docs/portable-backend/01-current-state.md`
- 架构：`docs/portable-backend/02-architecture.md`
- API 对照：`docs/portable-backend/03-api-compat-matrix.md`
- 启动手册：`docs/portable-backend/04-runbook.md`
- 测试证据：`docs/portable-backend/05-test-evidence.md`
- 已知差距：`docs/portable-backend/06-known-gaps.md`
- 任务清单：`docs/portable-backend/BACKLOG.md`
- 功能盘点：`docs/parity/feature-matrix.md` 与 `docs/parity/feature-matrix.csv`
