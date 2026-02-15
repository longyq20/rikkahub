# HANDOFF

## 一页结论
- 可移植后端骨架已落地，`/api` 主链路与 web-ui 已打通。
- 当前阶段完成了文档归档、运行基线固化、提交切分准备。
- 下一步重点不是继续搭脚手架，而是做 Android 全业务能力对照与真实业务迁移。

## 关键入口文件
- 服务入口：`backend-server/src/main/kotlin/me/rerere/rikkahub/backend/server/Main.kt`
- 会话引擎：`backend-server/src/main/kotlin/me/rerere/rikkahub/backend/server/service/ConversationEngine.kt`
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

## 当前最大未决事项
1. 用 Android `ChatService` 真实能力替换 `ConversationEngine` 占位逻辑。
2. 建立后端单元/集成测试体系，覆盖核心 `/api` 契约与 SSE。
3. 完成 Android 功能全盘点并在 WebUI/Backend 中逐项闭环。

## 文档导航
- 当前状态：`docs/portable-backend/01-current-state.md`
- 架构：`docs/portable-backend/02-architecture.md`
- API 对照：`docs/portable-backend/03-api-compat-matrix.md`
- 启动手册：`docs/portable-backend/04-runbook.md`
- 测试证据：`docs/portable-backend/05-test-evidence.md`
- 已知差距：`docs/portable-backend/06-known-gaps.md`
- 任务清单：`docs/portable-backend/BACKLOG.md`
- 功能盘点：`docs/parity/feature-matrix.md` 与 `docs/parity/feature-matrix.csv`
