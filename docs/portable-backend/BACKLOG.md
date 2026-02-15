# BACKLOG

## P0（立即执行）
1. 迁移真实生成引擎（替换 echo 占位）
- 目标：对齐 Android `ChatService` 的生成流水线能力。
- 涉及模块：`backend-server`、`backend-core`、必要时抽取 `ai` 可复用逻辑。
- 验收：消息发送/再生/工具审批后的续跑行为与 Android 语义一致。

2. 建立测试基线
- 单元测试：conversation diff、tool approval 状态迁移、repository CRUD。
- 集成测试：`/api/settings`、`/api/conversations`、`/api/files`、SSE 三条流。
- 验收：CI 可稳定执行，核心契约失败可被自动拦截。

3. 完成功能盘点矩阵首轮闭环
- 输出：`docs/parity/feature-matrix.csv` 每项都填充状态、差距与验收标准。
- 验收：Android 页面和核心业务能力 100% 入表。

## P1（Stage B1）
1. 聊天主链路全量对齐
- 发送、编辑分支、再生、停止、tool approval、SSE 增量推送。

2. 设置能力全量对齐
- assistant/model/thinking/mcp/injections/search/favorite-models。

3. 文件与迁移能力强化
- 上传/删除/预览一致性。
- 导入报告细化与异常分级。

4. 鉴权与部署稳定化
- JWT 启用路径回归。
- Windows/Linux/Docker 启动脚本回归。

## P2（Stage B2）
1. Android 扩展能力 Web 化
- memory、prompts、translator、imggen、history、log、developer/debug、TTS、share。

2. 可观测性与运维能力
- 统一日志格式。
- 基础指标与错误聚合。
- 压测与容量基线。

## 交付节奏
- 先保证 B1 “可生产使用的核心对齐”，再推进 B2 扩展能力。
- 每个迭代必须附带：接口变更说明、测试证据、回归结论。
