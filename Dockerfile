ARG JAVA_VERSION="21"

FROM maven:3.9-eclipse-temurin-${JAVA_VERSION} AS build
WORKDIR /app
COPY . .
RUN mvn --batch-mode --no-transfer-progress -Pproduction clean package


FROM eclipse-temurin:${JAVA_VERSION}-jre-alpine

ARG APP_NAME
ARG APP_VERSION

RUN test -n "$APP_NAME" || (echo "APP_NAME  not set" && false) \
    && test -n "$APP_VERSION" || (echo "APP_VERSION  not set" && false)

# /app/data holds the SQLite database (filebrowser.database-path); it is created
# here so a volume mounted over it starts out owned by the app user.
RUN apk add --no-cache curl \
    && addgroup -S -g 10001 filebrowser \
    && adduser -S -D -H -u 10001 -G filebrowser filebrowser \
    && mkdir -p /app/data \
    && chown filebrowser:filebrowser /app/data

WORKDIR /app
COPY --from=build --chown=filebrowser:filebrowser /app/target/${APP_NAME}-${APP_VERSION}.jar /app/app.jar

USER filebrowser:filebrowser
EXPOSE 8080

ENV JAVA_TOOL_OPTIONS="-XX:+ExitOnOutOfMemoryError -XX:MaxRAMPercentage=75"
ENTRYPOINT ["java","-jar","/app/app.jar"]

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=5 \
    CMD curl -fsS -o /dev/null "http://127.0.0.1:${PORT:-8080}/" || exit 1
