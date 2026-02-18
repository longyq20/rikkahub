# syntax=docker/dockerfile:1.7

ARG BUILDPLATFORM=linux/amd64
ARG TARGETPLATFORM=linux/amd64

FROM --platform=$BUILDPLATFORM node:20-bookworm-slim AS webui-build
WORKDIR /app/web-ui
COPY web-ui/package.json ./
RUN npm install --no-audit --no-fund
COPY web-ui/ ./
RUN npm run build

FROM --platform=$BUILDPLATFORM gradle:8.14.3-jdk17 AS backend-build
WORKDIR /app
COPY gradle/ gradle/
COPY gradlew gradlew
COPY gradlew.bat gradlew.bat
COPY gradle.properties build.gradle.kts settings.gradle.kts ./
COPY backend-core/ backend-core/
COPY backend-storage-sqlite/ backend-storage-sqlite/
COPY backend-migration/ backend-migration/
COPY backend-server/ backend-server/
COPY assets/ assets/
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew
RUN ./gradlew :backend-server:installDist --no-daemon --stacktrace

FROM --platform=$TARGETPLATFORM eclipse-temurin:17-jre AS runtime
WORKDIR /app
COPY --from=backend-build /app/backend-server/build/install/backend-server /app/backend-server
COPY --from=backend-build /app/assets /app/assets
COPY --from=webui-build /app/web-ui/build/client /app/web-ui
RUN sed -i 's/\r$//' /app/backend-server/bin/backend-server && chmod +x /app/backend-server/bin/backend-server

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
