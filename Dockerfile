# Multi-stage build: the JDK and the Maven cache never reach the runtime image.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# POMs first, sources second: dependency resolution is the slow part, and copying only the POMs
# first means editing a class does not re-download the world.
COPY pom.xml .
COPY alpha-common/pom.xml alpha-common/
COPY alpha-engine/pom.xml alpha-engine/
COPY alpha-gateway/pom.xml alpha-gateway/
COPY alpha-strategy/pom.xml alpha-strategy/
COPY alpha-risk/pom.xml alpha-risk/
COPY alpha-execution/pom.xml alpha-execution/
COPY alpha-backtest/pom.xml alpha-backtest/
COPY alpha-app/pom.xml alpha-app/
RUN mvn -B -q dependency:go-offline

COPY . .
# Tests are skipped here on purpose: they are the CI job's business (and a release image should not
# be the first place a flaky test is noticed). The image is built from a commit CI already verified.
RUN mvn -B -q clean package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app

# Logs and the SQLite/business data live outside the image so a container restart keeps the record.
RUN mkdir -p /app/logs /app/data /app/reports
COPY --from=build /build/alpha-app/target/alpha-app-*.jar app.jar

# The engine's loop thread is non-daemon: the JVM stays up between bars, which is what a trading
# process should do. MaxRAMPercentage rather than a fixed -Xmx so the heap follows the container limit.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"
ENV SPRING_PROFILES_ACTIVE=paper

EXPOSE 8080
VOLUME ["/app/logs", "/app/data"]

# exec form via sh -c so JAVA_OPTS is expanded; PID 1 is the JVM, so SIGTERM reaches the
# context shutdown hook and StopWiring stops the engine, the sender and reconciliation.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
