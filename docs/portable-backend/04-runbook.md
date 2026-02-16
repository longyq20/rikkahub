# 04 Runbook

## 运行前提
- JDK 17（建议 OpenJDK/Temurin）。
- Node.js + npm（用于构建 `web-ui`）。
- 默认后端端口：`8080`。

## 环境变量
- `HOST`：默认 `0.0.0.0`
- `PORT`：默认 `8080`
- `DATA_DIR`：默认 `data`
- `WEB_UI_DIR`：默认 `web-ui/build/client`
- `ASSETS_DIR`：默认 `app/src/main/assets`
- `JWT_ENABLED`：默认 `false`
- `ACCESS_PASSWORD`：默认空
- `UPLOAD_MAX_MB`：默认 `20`
- `APP_VERSION`：默认 `dev`

## Windows 一键构建并重启（推荐）
```powershell
./restart-fullstack.ps1
```

### Windows 脚本参数
- `-SkipFrontendBuild`：跳过 `web-ui` 构建。
- `-SkipBackendBuild`：跳过后端 classes 构建。
- `-Foreground`：前台运行后端（默认后台运行并写日志到 `.tmp-backend/`）。

## Windows 启动（兼容旧方式）
```powershell
./run-backend.ps1
```
或
```powershell
./gradlew.bat :backend-server:run --no-daemon
```

## Linux 一键构建并重启（推荐）
```bash
chmod +x restart-fullstack.sh
./restart-fullstack.sh
```

## Linux 启动（兼容旧方式）
```bash
chmod +x run-backend.sh
./run-backend.sh
```
或
```bash
./gradlew :backend-server:run --no-daemon
```

## 本地构建 web-ui（可选）
```bash
cd web-ui
npm install
npm run build
```
构建产物目录：`web-ui/build/client`。

## Docker 启动
```bash
docker build -t rikkahub-portable:dev .
docker run --rm -p 8080:8080 -v $(pwd)/data:/data rikkahub-portable:dev
```
Windows PowerShell：
```powershell
docker run --rm -p 8080:8080 -v ${PWD}\data:/data rikkahub-portable:dev
```

## 健康检查
```powershell
Invoke-RestMethod http://127.0.0.1:8080/api/system/health
```
期望：`{"status":"ok"}`。

## 数据目录
```text
data/
  settings.json
  rikka_hub.db
  upload/
```

## 导入 Android 备份
- 接口：`POST /api/migration/import`
- `multipart file`：zip（至少包含 `settings.json` 和 `rikka_hub.db`）。

## 常见排障

### 端口占用
```powershell
netstat -ano | Select-String ":8080"
Get-Process -Id <PID>
```

### web-ui 未构建
- 访问 `/` 返回 `web-ui build not found` 时，先构建 `web-ui` 或设置 `WEB_UI_DIR` 指向已构建目录。

### JWT 开启但无法访问
- 确认 `JWT_ENABLED=true` 且 `ACCESS_PASSWORD` 非空。
- 先调用 `POST /api/auth/token` 获取 token。

### 网络受限时使用离线 Gradle
```powershell
./.tools/gradle-9.1.0/bin/gradle.bat :backend-server:run --no-daemon
```
