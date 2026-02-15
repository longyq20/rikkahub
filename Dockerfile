# syntax=docker/dockerfile:1.7

FROM oven/bun:1 AS webui-build
WORKDIR /app/web-ui
COPY web-ui/package.json web-ui/bun.lock ./
RUN bun install --frozen-lockfile
COPY web-ui/ ./
RUN bun run build

FROM gradle:8.14.3-jdk17 AS backend-build
WORKDIR /app
COPY gradle/ gradle/
COPY gradlew gradlew
COPY gradlew.bat gradlew.bat
COPY gradle.properties build.gradle.kts settings.backend.gradle.kts ./
COPY backend-core/ backend-core/
COPY backend-storage-sqlite/ backend-storage-sqlite/
COPY backend-migration/ backend-migration/
COPY backend-server/ backend-server/
COPY app/src/main/assets/ app/src/main/assets/
RUN chmod +x gradlew
RUN ./gradlew --settings-file settings.backend.gradle.kts :backend-server:installDist --no-daemon

FROM eclipse-temurin:17-jre AS runtime
WORKDIR /app
COPY --from=backend-build /app/backend-server/build/install/backend-server /app/backend-server
COPY --from=backend-build /app/app/src/main/assets /app/assets
COPY --from=webui-build /app/web-ui/build/client /app/web-ui

ENV HOST=0.0.0.0
ENV PORT=8080
ENV DATA_DIR=/data
ENV WEB_UI_DIR=/app/web-ui
ENV ASSETS_DIR=/app/assets
ENV JWT_ENABLED=false
ENV ACCESS_PASSWORD=

VOLUME ["/data"]
EXPOSE 8080
ENTRYPOINT ["/app/backend-server/bin/backend-server"]