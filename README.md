# RikkaHub WebDist

[中文文档](README_ZH_CN.md) | English

This branch packages the **portable Web UI + Kotlin/Ktor backend** build of RikkaHub.
It is intended for direct fork/build/run on Windows, Linux, and Docker.

## What This Branch Contains

- Portable backend modules: `backend-core`, `backend-storage-sqlite`, `backend-migration`, `backend-server`
- Web frontend: `web-ui` (served by backend static hosting)
- Release runtime package: `dist/` (prebuilt backend runtime + web static + assets)
- Docker runtime files: `Dockerfile`, `docker-compose.yml`, and generated `dist/Dockerfile`
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

### Option A: Docker from source (recommended)

```bash
docker compose up -d --build
```

Open: `http://127.0.0.1:8080/`

### Option B: Docker from release runtime package (`dist/`)

```bash
cd dist
docker compose up -d --build
```

### Option C: Local (Windows/Linux/macOS)

Requirements:

- JDK 17+
- Node.js 20+

Run:

```bash
./restart-fullstack.sh
```

Windows PowerShell:

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

## CI

Push to `webdist` triggers GitHub Actions workflow `.github/workflows/docker-image.yml`.

Published image tags on GHCR:

- `ghcr.io/<owner>/<repo>:webdist`
- `ghcr.io/<owner>/<repo>:<commit_sha>`

## Notes

- `dist/` is tracked in this branch as a release runtime package for direct Docker deployment.
- `data/`, local toolchains, and personal local files are still ignored.

## License

See `LICENSE`.
