# RikkaHub WebDist（可移植版）

中文 | [English](README.md)

此分支用于发布 RikkaHub 的 **WebUI + Kotlin/Ktor 可移植后端**，目标是 fork 后可在 Windows/Linux/Docker 直接构建与运行。

## 分支内容

- 可移植后端模块：`backend-core`、`backend-storage-sqlite`、`backend-migration`、`backend-server`
- Web 前端：`web-ui`（由后端静态托管）
- 发布运行包：`dist/`（预构建后端运行时 + Web 静态资源 + assets）
- Docker 运行文件：`Dockerfile`、`docker-compose.yml`，以及生成的 `dist/Dockerfile`
- 一键本地脚本：`restart-fullstack.sh`、`restart-fullstack.ps1`

## 运行架构

- 后端：Kotlin + Ktor（`/api` + SSE）
- 前端：`web-ui` 静态资源
- 存储：SQLite + `data/` 文件目录

默认数据目录：

- `data/settings.json`
- `data/rikka_hub.db`
- `data/upload/`

## 快速启动

### 方式 A：源码目录 Docker（推荐）

```bash
docker compose up -d --build
```

访问：`http://127.0.0.1:8080/`

### 方式 B：使用 `dist/` 运行包 Docker

```bash
cd dist
docker compose up -d --build
```

### 方式 C：本地运行（Windows/Linux/macOS）

环境要求：

- JDK 17+
- Node.js 20+

Linux/macOS：

```bash
./restart-fullstack.sh
```

Windows PowerShell：

```powershell
./restart-fullstack.ps1
```

## 环境变量

- `HOST` 默认 `0.0.0.0`
- `PORT` 默认 `8080`
- `DATA_DIR` 默认 `data`
- `WEB_UI_DIR` 默认 `web-ui/build/client`
- `ASSETS_DIR` 默认 `assets`
- `JWT_ENABLED` 默认 `false`
- `ACCESS_PASSWORD` 默认空

## CI 自动构建镜像

推送到 `webdist` 分支会触发 `.github/workflows/docker-image.yml` 自动构建并推送 GHCR 镜像。

镜像标签：

- `ghcr.io/<owner>/<repo>:webdist`
- `ghcr.io/<owner>/<repo>:<commit_sha>`

## 说明

- 本分支会将 `dist/` 纳入 Git，用于发布后直接 Docker 部署。
- `data/`、本地工具链压缩包、个人本地文件仍保持忽略。

## License

见 `LICENSE`。
