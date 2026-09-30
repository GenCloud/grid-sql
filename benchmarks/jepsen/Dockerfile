# Jepsen node image: fat jar is built on the host (grid-sql-jepsen-starter Spring Boot
# repackage already embeds the reactor). Prefer:
#   bash benchmarks/jepsen/scripts/build-jepsen-image.sh
#   powershell -File benchmarks/jepsen/scripts/build-jepsen-image.ps1
# Manual:
#   docker build -f benchmarks/jepsen/Dockerfile --target runtime-hostjar \
#     --build-arg APP_JAR=benchmarks/jepsen/docker-staging/app.jar -t jamoa-grid-jepsen:local .
FROM eclipse-temurin:25-jre AS runtime-hostjar
WORKDIR /app
ARG APP_JAR=benchmarks/jepsen/docker-staging/app.jar
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
COPY ${APP_JAR} /app/app.jar
COPY benchmarks/jepsen/configs/ /app/config/
COPY benchmarks/jepsen/multidc/configs/ /app/config/multidc/
COPY benchmarks/jepsen/witness/configs/ /app/config/witness/
ENV JAVA_TOOL_OPTIONS="--enable-preview --add-modules=jdk.incubator.vector"
EXPOSE 7777 7778 7779 15432 15433 15434 15437 5615 5616 5617 5620
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
