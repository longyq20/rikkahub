# 04 Runbook

## 杩愯鍓嶆彁
- JDK 17锛堟帹鑽?OpenJDK/Temurin锛夈€?- 鍙€夛細Bun锛堜粎鍦ㄩ渶瑕佹湰鍦版瀯寤?`web-ui` 鏃讹級銆?- 榛樿鍚庣绔彛锛歚8080`銆?
## 鐜鍙橀噺
- `HOST`锛氶粯璁?`0.0.0.0`
- `PORT`锛氶粯璁?`8080`
- `DATA_DIR`锛氶粯璁?`data`
- `WEB_UI_DIR`锛氶粯璁?`web-ui/build/client`
- `ASSETS_DIR`锛氶粯璁?`app/src/main/assets`
- `JWT_ENABLED`锛氶粯璁?`false`
- `ACCESS_PASSWORD`锛氶粯璁ょ┖
- `UPLOAD_MAX_MB`锛氶粯璁?`20`
- `APP_VERSION`锛氶粯璁?`dev`

## Windows 鍚姩
```powershell
./run-backend.ps1
```
鎴?```powershell
./gradlew.bat :backend-server:run --no-daemon
```

## Linux 鍚姩
```bash
chmod +x run-backend.sh
./run-backend.sh
```
鎴?```bash
./gradlew :backend-server:run --no-daemon
```

## 鏈湴鏋勫缓 web-ui锛堝彲閫夛級
```bash
cd web-ui
bun install --frozen-lockfile
bun run build
```
鏋勫缓浜х墿搴斾綅浜?`web-ui/build/client`銆?
## Docker 鍚姩
```bash
docker build -t rikkahub-portable:dev .
docker run --rm -p 8080:8080 -v $(pwd)/data:/data rikkahub-portable:dev
```
Windows PowerShell 鍙浛鎹负锛?```powershell
docker run --rm -p 8080:8080 -v ${PWD}\data:/data rikkahub-portable:dev
```

## 鍋ュ悍妫€鏌?```powershell
Invoke-RestMethod http://127.0.0.1:8080/api/system/health
```
鏈熸湜锛歚{"status":"ok"}`銆?
## 鏁版嵁鐩綍
```text
data/
  settings.json
  rikka_hub.db
  upload/
```

## 瀵煎叆 Android 澶囦唤
- API锛歚POST /api/migration/import`
- multipart file锛歾ip锛堣嚦灏戝寘鍚?`settings.json` 涓?`rikka_hub.db`锛?
## 甯歌鎺掗殰

### 绔彛鍗犵敤
```powershell
netstat -ano | Select-String ":8080"
Get-Process -Id <PID>
```

### web-ui 鏈瀯寤?- 璁块棶 `/` 杩斿洖 `web-ui build not found` 鏃讹紝鍏堟瀯寤?`web-ui`锛屾垨璁剧疆 `WEB_UI_DIR` 鎸囧悜宸叉瀯寤虹洰褰曘€?
### JWT 寮€鍚絾鏃犳硶璁块棶
- 纭 `JWT_ENABLED=true` 涓?`ACCESS_PASSWORD` 闈炵┖銆?- 鍏堣皟鐢?`POST /api/auth/token` 鑾峰彇 token銆?

## 离线 Gradle（网络受限时）
如果 `gradlew` 下载超时，可使用本地压缩包解压后的 Gradle 可执行文件：
```powershell
./.tools/gradle-9.1.0/bin/gradle.bat :backend-server:run --no-daemon
```