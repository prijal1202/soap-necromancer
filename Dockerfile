# SOAP Necromancer: generator web app + bundled mock-bank, one JVM, one port (docs/decisions.md section 3).
# Build:  docker build -t soap-necromancer .
# Run:    docker run --rm -p 8080:8080 --memory 512m soap-necromancer
#
# Tuned for Render free (512 MB, 0.1 CPU), where startup is CPU-bound. Measured at --cpus 0.1:
# plain jar ~65-86 s to healthy; + CDS archive ~41-46 s; + C1-only JIT ~28 s (docs/decisions.md 3.4).

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

# ---- run: JRE only, non-root, with a Class Data Sharing archive.
# Recipe: https://docs.spring.io/spring-boot/3.5/reference/packaging/class-data-sharing.html
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 necromancer
WORKDIR /app
COPY --from=build /src/web/target/soap-necromancer.jar /tmp/soap-necromancer.jar
# The training run and the real run share these flags, so the archive matches the runtime JVM.
# TieredStopAtLevel=1 (C1 only): on a 0.1 CPU share, C2 compiles compete with startup and requests.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -Xss512k -XX:+ExitOnOutOfMemoryError -XX:TieredStopAtLevel=1"
# Extract the jar (CDS needs the unpacked layout), then start the context once and dump the
# loaded classes. spring.context.exit=onRefresh stops right after refresh, so no port is held.
RUN java -Djarmode=tools -jar /tmp/soap-necromancer.jar extract --destination /app \
 && rm /tmp/soap-necromancer.jar \
 && java -XX:ArchiveClassesAtExit=/app/application.jsa -Dspring.context.exit=onRefresh \
        -Dserver.port=0 -jar /app/soap-necromancer.jar \
 && test -s /app/application.jsa
USER necromancer
# Render sets PORT (default 10000); locally the app falls back to 8080.
EXPOSE 8080
ENTRYPOINT ["java", "-XX:SharedArchiveFile=/app/application.jsa", "-jar", "/app/soap-necromancer.jar"]
