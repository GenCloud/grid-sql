# Build from repository root:
#   docker build -f benchmarks/jepsen/Dockerfile -t jamoa-grid-jepsen:local .
# Host-jar (no Maven in image):
#   docker build -f benchmarks/jepsen/Dockerfile --target runtime-hostjar \
#     --build-arg APP_JAR=example-app/target/example-app-….jar -t jamoa-grid-jepsen:local .
# Requires BuildKit for cache mounts (DOCKER_BUILDKIT=1).
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
RUN apt-get update && apt-get install -y --no-install-recommends maven curl \
    && rm -rf /var/lib/apt/lists/*
COPY pom.xml .
COPY grid-sql-client/pom.xml grid-sql-client/
COPY grid-server-core/pom.xml grid-server-core/
COPY example-app/pom.xml example-app/
COPY grid-jdbc/pom.xml grid-jdbc/
COPY grid-sql-client grid-sql-client
COPY grid-server-core grid-server-core
COPY grid-jdbc grid-jdbc
COPY example-app example-app
# Parent reactor may include more modules; build what Jepsen needs.
# Cache Maven local repo across builds (BuildKit).
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -pl example-app -am package -Dmaven.test.skip=true \
    && cp example-app/target/grid-sql-server-starter-*.jar /src/app.jar \
    || cp example-app/target/example-app-*.jar /src/app.jar

# Slim runtime when jar was built on the host (see build-jepsen-image.ps1).
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

FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /src/app.jar /app/app.jar
COPY benchmarks/jepsen/configs/ /app/config/
COPY benchmarks/jepsen/multidc/configs/ /app/config/multidc/
COPY benchmarks/jepsen/witness/configs/ /app/config/witness/
ENV JAVA_TOOL_OPTIONS="--enable-preview --add-modules=jdk.incubator.vector"
EXPOSE 7777 7778 7779 15432 15433 15434 15437 5615 5616 5617 5620
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
