# 05 Test Evidence

## 记录时间
- 文档整理时间：2026-02-16（UTC+08:00）。

## 构建与运行证据

### Kotlin 编译
- 在线 wrapper 命令（`./gradlew.bat :backend-server:compileKotlin --no-daemon`）在当前网络环境下载 Gradle 发行包超时。
- 离线替代命令：
  - `./.tools/gradle-9.1.0/bin/gradle.bat :backend-server:compileKotlin --no-daemon`
- 结果：通过（`BUILD SUCCESSFUL`）。

### 模块测试任务
- 命令：
  - `./.tools/gradle-9.1.0/bin/gradle.bat :backend-core:test :backend-storage-sqlite:test :backend-migration:test :backend-server:test --no-daemon`
- 结果：通过（任务可执行）。
- 现状：新增模块测试均为 `NO-SOURCE`。

### 服务健康检查
- 命令：`Invoke-RestMethod http://127.0.0.1:18080/api/system/health | ConvertTo-Json -Compress`
- 结果：`{"status":"ok"}`。

### 监听进程与端口（示例时点）
- 命令：`netstat -ano | Select-String ":18080"`
- 结果：`LISTENING`，`PID=22936`。
- 命令：`Get-Process -Id 22936 | Select Id,ProcessName,StartTime,Path`
- 结果：`java`，JDK 路径为 `C:\Users\yongqi\.jdk\openjdk-17\jdk-17.0.0.1\bin\java.exe`。

## API/SSE 烟测（前序会话）
- 已执行后端接口烟测并覆盖 web-ui 主链路。
- 结果：通过（21/21）。

## 风险结论
- 主要风险不在启动层，而在生成引擎占位实现与测试覆盖缺口。
- 阶段 B 应将“真实生成 pipeline + 自动化测试”作为最高优先级任务。
