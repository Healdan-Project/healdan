# syntax=docker/dockerfile:1

# LogicalDOC Community (coffeemuse fork): build from source, run on Tomcat 11.
# See docker/README.md for usage and docs/superpowers/specs/2026-10-15-docker-compose-stack-design.md for the design.

ARG MAVEN_IMAGE=maven:3.9-eclipse-temurin-21
ARG TOMCAT_IMAGE=tomcat:11.0-jre21-temurin

# ---- build: the same Maven build CI runs, producing the exploded WAR in /build/ROOT ----
FROM ${MAVEN_IMAGE} AS build
WORKDIR /src
COPY docker/maven-settings.xml /root/.m2/settings.xml
# docker/ is excluded so editing container files does not re-run the Maven build.
COPY --exclude=docker . .
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B -ntp -DskipTests install
RUN mkdir -p /build/ROOT \
 && cd /build/ROOT \
 && jar xf /src/logicaldoc-webapp/target/logicaldoc-webapp-*.war
