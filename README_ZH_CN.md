# RikkaHub WebDist（可移植版）

中文 | [English](README.md)

该分支用于发布 RikkaHub 的 **WebUI + Kotlin/Ktor 可移植后端**，目标是让他人 fork 后即可在 Windows/Linux 或 Docker 中直接运行和构建。

## 分支内容

- 可移植后端模块：`backend-core`、`backend-storage-sqlite`、`backend-migration`、`backend-server`
- Web 前端：`web-ui`（由后端统一静态托管）
- 容器化文件：`Dockerfile`、`docker-compose.yml`
- 一键本地脚本：`restart-fullstack.sh`、`restart-fullstack.ps1`

## 运行架构

- 后端：Kotlin + Ktor（`/api` + SSE）
- 前端：`web-ui` 构建产物
- 存储：SQLite + 文件目录 `data/`

默认数据目录：

- `data/settings.json`
- `data/rikka_hub.db`
- `data/upload/`

## 快速启动

### 方式 A：Docker（推荐）

```bash
docker compose up -d --build
```

访问：`http://127.0.0.1:8080/`

停止：

```bash
docker compose down
```

### 方式 B：本地 Linux/macOS

环境要求：

- JDK 17+
- Node.js 20+（或 Bun 用于构建 web-ui）

执行：

```bash
./restart-fullstack.sh
```

### 方式 C：本地 Windows PowerShell

环境要求：

- JDK 17+
- Node.js 20+

执行：

```powershell
./restart-fullstack.ps1
```

## 环境变量

- `HOST` 默认 `0.0.0.0`
- `PORT` 默认 `8080`
- `DATA_DIR` 默认 `data`
- `WEB_UI_DIR` 默认 `web-ui/build/client`
- `ASSETS_DIR` 默认 `app/src/main/assets`
- `JWT_ENABLED` 默认 `false`
- `ACCESS_PASSWORD` 默认空

## 手动构建运行

```bash
cd web-ui
npm install
npm run build
cd ..
./gradlew :backend-server:run
```

Windows：

```powershell
cd web-ui
npm install
npm run build
cd ..
.\gradlew.bat :backend-server:run
```

## 说明

- 本分支已剔除本地流程类文件、个人配置文件。
- `dist/`、本地工具链压缩包等文件默认被 Git 忽略。

## License

见 `LICENSE`。
