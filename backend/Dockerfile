# syntax=docker/dockerfile:1.7

FROM node:22.12.0-bookworm-slim AS frontend-dependencies
WORKDIR /workspace/frontend
COPY frontend/package*.json ./
RUN --mount=type=cache,target=/root/.npm npm ci

FROM frontend-dependencies AS frontend-build
COPY frontend/index.html frontend/vite.config.js ./
COPY frontend/public public
COPY frontend/src src
RUN npm run build

FROM maven:3.9-eclipse-temurin-21 AS backend-build
WORKDIR /workspace
COPY backend/pom.xml backend/pom.xml
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -f backend/pom.xml -DskipTests dependency:go-offline
COPY backend/src backend/src
COPY --from=frontend-build /workspace/frontend/dist backend/src/main/resources/static
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -f backend/pom.xml \
    -DskipTests \
    -Dskip.installnodenpm \
    -Dskip.npm \
    package

FROM eclipse-temurin:21-jre
WORKDIR /app
# SQLite persists LocalDateTime values as epoch timestamps. Keep the JVM in UTC so
# databases created by earlier releases are decoded without an eight-hour shift.
ENV APP_DESKTOP_ENABLED=false \
    APP_TIME_ZONE=Asia/Shanghai \
    QN_CALENDAR_DATA_DIR=/data \
    TZ=UTC \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8
RUN apt-get update \
    && apt-get install -y --no-install-recommends fontconfig fonts-wqy-microhei \
    && fc-cache -f \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /data
COPY --from=backend-build /workspace/backend/target/*.jar app.jar
VOLUME ["/data"]
EXPOSE 8080
ENTRYPOINT ["java", "-Djava.awt.headless=true", "-Duser.timezone=UTC", "-jar", "app.jar"]
