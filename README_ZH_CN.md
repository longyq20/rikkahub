# RikkaHub WebDist（可移植版）

中文 | [English](README.md)

此分支用于发布 RikkaHub 的 **WebUI + Kotlin/Ktor 可移植后端源码**，目标是 fork 后可在 Windows/Linux/Docker 直接构建与运行。

## 分支内容

- 可移植后端模块：`backend-core`、`backend-storage-sqlite`、`backend-migration`、`backend-server`
- Web 前端源码：`web-ui`（由后端静态托管）
- Docker 部署文件：`Dockerfile`、`docker-compose.yml`
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

### 方式 A：使用已发布镜像（compose 默认）

`docker-compose.yml` 支持以下镜像标签：

- amd64: `ghcr.io/longyq20/rikkahub:webdist`
- arm64: `ghcr.io/longyq20/rikkahub:webdist-arm64`

使用默认标签（`webdist`）启动：

```bash
docker compose up -d
```

使用 arm64 标签启动：

```bash
RIKKAHUB_IMAGE_TAG=webdist-arm64 docker compose up -d
```

访问：`http://127.0.0.1:8080/`

### 方式 B：从源码构建 Docker

如果要从本地源码构建，请先取消 `docker-compose.yml` 中 `build` 配置的注释，然后执行：

```bash
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

当前 `docker-compose.yml` 中设置为：

- `HOST=0.0.0.0`
- `PORT=8080`
- `DATA_DIR=/data`
- `WEB_UI_DIR=/app/web-ui`
- `ASSETS_DIR=/app/assets`
- `JWT_ENABLED=false`

Compose 镜像选择变量：

- `RIKKAHUB_IMAGE_TAG` 默认 `webdist`
- arm64 使用 `RIKKAHUB_IMAGE_TAG=webdist-arm64`

说明：

- 当前 `docker-compose.yml` 未设置 `ACCESS_PASSWORD`。
- 数据持久化通过 `./data:/data` 挂载。

## CI 自动构建镜像

推送到 `webdist` 分支会触发 `.github/workflows/docker-image.yml` 自动构建并推送 GHCR 镜像。

镜像标签：

- `ghcr.io/<owner>/<repo>:webdist`
- `ghcr.io/<owner>/<repo>:<commit_sha>`
- `ghcr.io/<owner>/<repo>:webdist-arm64`
- `ghcr.io/<owner>/<repo>:<commit_sha>-arm64`

## 说明

- `dist/` 已从 Git 跟踪中移除并加入忽略，`restart-fullstack` 脚本不再生成该目录。
- `data/`、本地工具链压缩包、个人本地文件保持忽略。

## License

见 `LICENSE`。
