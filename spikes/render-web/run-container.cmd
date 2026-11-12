@echo off
rem Spike (Task 10): the packaged web app in a Render-like container. 512 MB, no swap, half a CPU.
rem Build first: mvn -pl web -am package -DskipTests
docker rm -f sn-web >nul 2>&1
docker run --rm --name sn-web --memory 512m --memory-swap 512m --cpus 0.5 -e PORT=10000 -p 10000:10000 ^
  -v "%~dp0..\..\web\target:/app:ro" eclipse-temurin:21-jre ^
  java -XX:MaxRAMPercentage=70 -XX:+UseSerialGC -Xss512k -jar /app/soap-necromancer.jar
