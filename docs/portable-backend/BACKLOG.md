# BACKLOG

## P0（当前立即执行）
1. 契约测试扩面并接入 CI 门禁
- 已完成：`backend-core` / `backend-storage-sqlite` / `backend-server` 单测与首批 API/SSE 集成测试。
- 待完成：补齐 `/api/files`、`/api/auth`、`/api/migration/import` 的失败路径与边界用例。
- 验收：核心 `/api` 与 SSE 变更在 CI 中可自动拦截回归。

2. Tool 执行闭环
- 已完成：`tool approval -> resume generation` 状态机闭环。
- 待完成：真实工具执行、结果回写、异常重试与超时策略。
- 验收：审批后可看到真实 tool 输出并继续生成。

3. Android 全盘点矩阵闭环（Stage B1 维度）
- 已完成：`docs/parity/feature-matrix.csv` 首轮全量入表。
- 待完成：每个 P0/P1 条目补齐“入口文件 + 改动模块 + 测试用例”。
- 验收：不留未分类项与隐式决策。

## P1（Stage B1）
1. 聊天主链路深度对齐
- 生成 pipeline 继续对齐 Android：transformers、上下文裁剪细节、错误回退策略。

2. 设置能力与运行时行为对齐
- assistant/model/thinking/mcp/injections/search/favorite-models 已有 API；需补行为一致性回归。

3. 文件与迁移能力强化
- 上传/删除/预览回归。
- 导入报告分级（可恢复/不可恢复）与前端呈现。

4. 鉴权与部署稳定化
- JWT 开关路径与 query token 回归。
- Windows/Linux/Docker 启动脚本持续校验。

## P2（Stage B2）
1. Android 扩展能力 Web 化
- memory、prompts、translator、imggen、history、log、developer/debug、TTS、share。

2. 可观测性与运维
- 统一日志格式、基础指标、错误聚合、压测与容量基线。

## 交付节奏
- 先收敛 B1“可生产核心链路”，再推进 B2 扩展能力。
- 每个迭代必须附带：接口变更说明、测试证据、回归结论。
