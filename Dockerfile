# syntax=docker/dockerfile:1

# Healdan (LogicalDOC Community fork): build from source, run on Tomcat 11.
# See docker/README.md for usage and docs/superpowers/specs/2026-10-15-docker-compose-stack-design.md for the design.

ARG MAVEN_IMAGE=maven:3.9-eclipse-temurin-25
ARG TOMCAT_IMAGE=tomcat:11.0-jre25-temurin

# ---- build: the same Maven build CI runs, producing the exploded WAR in /build/ROOT ----
FROM ${MAVEN_IMAGE} AS build
WORKDIR /src
COPY docker/maven-settings.xml /root/.m2/settings.xml
# Container files, the Dockerfile itself and top-level docs are excluded so editing them does not re-run Maven.
COPY --exclude=docker --exclude=Dockerfile --exclude=.dockerignore --exclude=.gitignore --exclude=*.md . .
# The cache mount keeps only third-party dependencies: the reactor's own artifacts (com/logicaldoc, about half its
# size) are rebuilt every time. Docker CI saves the mount between runs (.github/workflows/docker-image.yml).
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B -ntp -DskipTests install \
 && rm -rf /root/.m2/repository/com/logicaldoc
RUN mkdir -p /build/ROOT \
 && cd /build/ROOT \
 && jar xf /src/logicaldoc-webapp/target/logicaldoc-webapp-*.war

# ---- tools: compile, test and package the container init tool against the WAR's own libraries ----
FROM build AS tools
COPY docker/tools /tools
RUN bash /tools/build.sh /build/ROOT/WEB-INF/lib /build/ROOT/WEB-INF/classes/context.properties /build/docker-init.jar

# ---- runtime: Tomcat 11 serving the exploded WAR at / ----
FROM ${TOMCAT_IMAGE} AS runtime
# Thumbnails and previews: ImageMagick (images; uses Ghostscript for PS/PDF input), Poppler (PDF).
# LibreOffice (headless writer, calc, impress) converts Office files for previews; Carlito, Caladea and Liberation
# are metric-compatible with Calibri, Cambria and Arial/Times, so the layout survives. tini is PID 1 and reaps the
# soffice processes the converter kills after a timeout. curl serves the healthcheck.
RUN apt-get update \
 && apt-get install -y --no-install-recommends imagemagick poppler-utils ghostscript curl tini \
    libreoffice-writer-nogui libreoffice-calc-nogui libreoffice-impress-nogui \
    fonts-crosextra-carlito fonts-crosextra-caladea fonts-liberation \
 && rm -rf /var/lib/apt/lists/*

# Ubuntu base images ship an "ubuntu" user with uid 1000; replace it with ours.
RUN (userdel -r ubuntu 2>/dev/null || true) \
 && (groupdel ubuntu 2>/dev/null || true) \
 && groupadd -g 1000 logicaldoc \
 && useradd -u 1000 -g 1000 -d /opt/logicaldoc -M -s /usr/sbin/nologin logicaldoc

RUN rm -rf "$CATALINA_HOME"/webapps/* "$CATALINA_HOME"/webapps.dist
COPY --from=build --chown=1000:1000 /build/ROOT "$CATALINA_HOME/webapps/ROOT"
COPY --from=tools --chown=1000:1000 /build/docker-init.jar /opt/logicaldoc/lib/docker-init.jar
COPY docker/server.xml "$CATALINA_HOME/conf/server.xml"
COPY docker/rewrite.config "$CATALINA_HOME/conf/Catalina/localhost/rewrite.config"
COPY --chmod=0755 docker/setenv.sh "$CATALINA_HOME/bin/setenv.sh"
# Root runs the entrypoint, so it lives root-owned outside anything uid 1000 can write.
COPY --chmod=0755 docker/entrypoint.sh /usr/local/bin/logicaldoc-entrypoint.sh
COPY --chmod=0755 --chown=1000:1000 docker/restart.sh /opt/logicaldoc/bin/restart.sh
# LogicalDOC only runs external commands listed in a classpath allowed-commands.txt; seeded into conf/.
COPY docker/allowed-commands.txt /opt/logicaldoc/defaults/allowed-commands.txt

# context.properties and log.xml must leave WEB-INF/classes: Tomcat's webapp class loader looks there before
# common.loader, where the live, writable copies in /opt/logicaldoc/conf are found.
RUN mkdir -p /opt/logicaldoc/defaults /opt/logicaldoc/conf /opt/logicaldoc/repository \
 && mv "$CATALINA_HOME/webapps/ROOT/WEB-INF/classes/context.properties" \
       "$CATALINA_HOME/webapps/ROOT/WEB-INF/classes/log.xml" /opt/logicaldoc/defaults/ \
 && sed -i 's|^common.loader=.*|&,"/opt/logicaldoc/conf"|' "$CATALINA_HOME/conf/catalina.properties" \
 && grep -q '"/opt/logicaldoc/conf"' "$CATALINA_HOME/conf/catalina.properties" \
 && chown -R 1000:1000 /opt/logicaldoc "$CATALINA_HOME/logs" "$CATALINA_HOME/temp" "$CATALINA_HOME/work"

# Declared last: CI passes a new REVISION for every commit, and an ARG invalidates the cache of every RUN after it.
ARG VERSION=local
ARG REVISION=unknown
# description replaces the Ubuntu base image's own.
LABEL org.opencontainers.image.title="Healdan (LogicalDOC Community fork)" \
      org.opencontainers.image.description="LogicalDOC Community document management on Tomcat 11" \
      org.opencontainers.image.source="https://github.com/Healdan-Project/healdan" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}" \
      org.opencontainers.image.licenses="LGPL-3.0-only"

VOLUME ["/opt/logicaldoc/conf", "/opt/logicaldoc/repository"]
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=10s --start-period=300s --retries=3 \
  CMD curl -fsS -o /dev/null http://localhost:8080/login.jsp || exit 1
ENTRYPOINT ["/usr/local/bin/logicaldoc-entrypoint.sh"]
CMD ["catalina.sh", "run"]
