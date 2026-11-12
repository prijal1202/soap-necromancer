# Throwaway spike (Task 2, item 3): multi-stage build, JRE runtime, binds $PORT, JVM flags from docs/decisions.md.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY Hello.java .
RUN javac -d /out Hello.java

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /out /app
ENV PORT=10000
EXPOSE 10000
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-XX:+UseSerialGC", "-Xss512k", "-cp", "/app", "Hello"]
