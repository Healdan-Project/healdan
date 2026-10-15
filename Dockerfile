# syntax=docker/dockerfile:1

# LogicalDOC Community (coffeemuse fork): build from source, run on Tomcat 11.
# See docker/README.md for usage and docs/superpowers/specs/2026-10-15-docker-compose-stack-design.md for the design.

ARG MAVEN_IMAGE=maven:3.9-eclipse-temurin-21
ARG TOMCAT_IMAGE=tomcat:11.0-jre21-temurin

# ---- build: the same Maven build CI runs, producing the exploded WAR in /build/ROOT ----
FROM ${MAVEN_IMAGE} AS build
WORKDIR /src
COPY docker/maven-settings.xml /root/.m2/settings.xml
# Container files, the Dockerfile itself and top-level docs are excluded so editing them does not re-run Maven.
COPY --exclude=docker --exclude=Dockerfile --exclude=.dockerignore --exclude=.gitignore --exclude=*.md . .
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B -ntp -DskipTests install
RUN mkdir -p /build/ROOT \
 && cd /build/ROOT \
 && jar xf /src/logicaldoc-webapp/target/logicaldoc-webapp-*.war

# ---- tools: compile, test and package the container init tool against the WAR's own libraries ----
FROM build AS tools
COPY docker/tools /tools
RUN bash /tools/build.sh /build/ROOT/WEB-INF/lib /build/ROOT/WEB-INF/classes/context.properties /build/docker-init.jar
