FROM eclipse-temurin:17-jre
WORKDIR /app
COPY backend-server/ /app/backend-server/
COPY web-ui-static/ /app/web-ui/
COPY assets/ /app/assets/

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
