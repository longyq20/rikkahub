# RikkaHub WebDist

[中文文档](README_ZH_CN.md) | English

This branch packages the **portable Web UI + Kotlin/Ktor backend** build of RikkaHub.
It is intended for direct fork/build/run on Windows, Linux, and Docker.

## What This Branch Contains

- Portable backend modules: `backend-core`, `backend-storage-sqlite`, `backend-migration`, `backend-server`
- Web frontend: `web-ui` (served by backend static hosting)
- Docker runtime files: `Dockerfile`, `docker-compose.yml`
- One-command local scripts: `restart-fullstack.sh`, `restart-fullstack.ps1`

## Runtime Architecture

- Backend: Kotlin + Ktor (`/api` + SSE)
- Frontend: `web-ui` static assets
- Storage: SQLite + file storage under `data/`

Default data layout:

- `data/settings.json`
- `data/rikka_hub.db`
- `data/upload/`

## Quick Start

### Option A: Docker (recommended)

```bash
docker compose up -d --build
```

Open: `http://127.0.0.1:8080/`

Stop:

```bash
docker compose down
```

### Option B: Local (Linux/macOS)

Requirements:

- JDK 17+
- Node.js 20+ (or Bun for web-ui build)

Run:

```bash
./restart-fullstack.sh
```

### Option C: Local (Windows PowerShell)

Requirements:

- JDK 17+
- Node.js 20+

Run:

```powershell
./restart-fullstack.ps1
```

## Environment Variables

- `HOST` default `0.0.0.0`
- `PORT` default `8080`
- `DATA_DIR` default `data`
- `WEB_UI_DIR` default `web-ui/build/client`
- `ASSETS_DIR` default `assets`
- `JWT_ENABLED` default `false`
- `ACCESS_PASSWORD` default empty

## Build From Source (manual)

```bash
cd web-ui
npm install
npm run build
cd ..
./gradlew :backend-server:run
```

On Windows:

```powershell
cd web-ui
npm install
npm run build
cd ..
.\gradlew.bat :backend-server:run
```

## Notes

- This branch intentionally excludes local AI workflow/process files and personal settings files.
- `dist/` and local toolchain archives are ignored by Git.

## License

See `LICENSE`.
