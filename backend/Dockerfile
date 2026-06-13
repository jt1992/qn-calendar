FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY backend/pom.xml backend/pom.xml
COPY frontend/package*.json frontend/
RUN mvn -q -f backend/pom.xml -DskipTests dependency:go-offline
COPY frontend frontend
COPY backend/src backend/src
RUN mvn -q -f backend/pom.xml -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
ENV APP_DESKTOP_ENABLED=false \
    QN_CALENDAR_DATA_DIR=/data \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8
RUN apt-get update \
    && apt-get install -y --no-install-recommends fontconfig fonts-wqy-microhei \
    && fc-cache -f \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /data
COPY --from=build /workspace/backend/target/*.jar app.jar
VOLUME ["/data"]
EXPOSE 8080
ENTRYPOINT ["java", "-Djava.awt.headless=true", "-jar", "app.jar"]
