# SOAP Necromancer: generator web app + bundled mock-bank, one JVM, one port (docs/decisions.md section 3).
# Build:  docker build -t soap-necromancer .
# Run:    docker run --rm -p 8080:8080 --memory 512m soap-necromancer

# ---- build: the whole reactor is needed because web depends on core, generators and mock-bank.
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
# No separate dependency:go-offline layer: it cannot resolve the reactor's own modules, and hiding
# that failure would only make builds look cached when they are not. Tests run in CI, not here.
COPY pom.xml .
COPY runtime runtime
COPY core core
COPY generators generators
COPY mock-bank mock-bank
COPY web web
RUN mvn -B -q -pl web -am package -DskipTests

# ---- run: JRE only, non-root.
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 necromancer
WORKDIR /app
COPY --from=build /src/web/target/soap-necromancer.jar /app/soap-necromancer.jar
USER necromancer
# Render sets PORT (default 10000); locally the app falls back to 8080.
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -Xss512k -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "/app/soap-necromancer.jar"]
