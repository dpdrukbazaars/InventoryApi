# DP DrukBazaars API in a container (Render, or any Docker host).
# Build:  docker build -t drukbazaars-api .   Run:  docker run -p 8080:8080 --env-file drukbazaars.env drukbazaars-api
# Settings come from environment variables (see DEPLOY-RENDER.md at the top of the repository).

# ---- 1. Build the jar with Maven and Java 25 ----
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package && cp target/Rest-api-*.jar /build/app.jar

# ---- 2. Run it on the Java 25 runtime only ----
FROM eclipse-temurin:25-jre
WORKDIR /app
RUN useradd --system --uid 10001 --home /app drukbazaars && chown drukbazaars /app
COPY --from=build /build/app.jar /app/app.jar
USER drukbazaars

# The live-site settings (application-prod.properties). Memory is kept within a small host (Render free: 512 MB):
# heap at most 60% of the container, one garbage-collector thread, the quick compiler only (faster start on a slow
# CPU). Times are Bhutan time, like the shop. Every value can be overridden by the host's environment variables.
ENV SPRING_PROFILES_ACTIVE=prod \
    SERVER_TOMCAT_THREADS_MAX=40 \
    SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=5 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -XX:MaxMetaspaceSize=160m -XX:ReservedCodeCacheSize=48m -Xss512k -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Duser.timezone=Asia/Thimphu"

# Render tells the app its port in PORT (application.properties: server.port=${PORT:8080})
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
