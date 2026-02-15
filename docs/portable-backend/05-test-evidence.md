# 05 Test Evidence

## 记录时间
- 更新时间：2026-02-16（UTC+08:00）。

## 构建与测试

### 后端测试（本轮）
- 命令：
  - `./.tools/gradle-9.1.0/bin/gradle.bat :backend-server:test --no-daemon`
- 结果：通过。
- 覆盖：
  - `ConversationEngineTest`（生成/再生/tool approval 续跑）
  - `ApiContractIntegrationTest`（`/api/conversations`、`/api/settings`、SSE 事件契约）

### 相关模块测试
- 命令：
  - `./.tools/gradle-9.1.0/bin/gradle.bat :backend-core:test :backend-storage-sqlite:test --no-daemon`
- 结果：通过。

## 运行验证

### 健康检查
- 接口：`GET /api/system/health`
- 结果：`{"status":"ok"}`。

### 真实 provider 烟测（临时实例）
- 输入：OpenAI-compatible `baseUrl/key/model`（用户提供）。
- 场景：`POST /api/conversations/{id}/messages`，轮询会话详情读取 assistant 文本。
- 结果：成功返回 `portable-smoke-ok`。

## 调试结论（本轮关键坑位）
1. Windows PowerShell 写入 `settings.json` 时若使用带 BOM 的 UTF-8，后端可能回落默认 settings。
- 规避：使用 `UTF8Encoding(false)` 写入无 BOM。

2. 手工构造 JSON 若把 `\"` 作为字面量写入，会导致请求体反序列化失败（500）。
- 规避：统一用 `ConvertTo-Json` 生成请求体。

## 当前测试缺口
- 仍需补齐：`/api/files`、`/api/auth`、`/api/migration/import` 的失败路径与边界契约测试。
- 仍需补齐：跨平台（Windows/Linux）与 Docker 场景下的自动化回归流水线。
