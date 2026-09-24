# Product Grid SQL server (grid-sql-server-starter). Build from repo root:
#   powershell -File examples/scripts/build-sql-image.ps1
# Image: jamoa-grid-sql:local  (NOT the Jepsen chaos image)
FROM eclipse-temurin:25-jre
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
ARG APP_JAR=examples/docker/staging/app.jar
COPY ${APP_JAR} /app/app.jar
ENV JAVA_TOOL_OPTIONS="--enable-preview --add-modules=jdk.incubator.vector"
# Node configs are bind-mounted from examples/compose/*/config/ — not baked in.
EXPOSE 7777 15432 5615
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
