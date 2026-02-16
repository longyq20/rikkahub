$ErrorActionPreference = "Stop"

if (-not $env:HOST) { $env:HOST = "0.0.0.0" }
if (-not $env:PORT) { $env:PORT = "8080" }
if (-not $env:DATA_DIR) { $env:DATA_DIR = "data" }
if (-not $env:WEB_UI_DIR) { $env:WEB_UI_DIR = "web-ui/build/client" }
if (-not $env:ASSETS_DIR) { $env:ASSETS_DIR = "assets" }
if (-not $env:JWT_ENABLED) { $env:JWT_ENABLED = "false" }
if (-not $env:ACCESS_PASSWORD) { $env:ACCESS_PASSWORD = "" }

.\gradlew.bat :backend-server:run --no-daemon
