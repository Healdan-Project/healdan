#!/usr/bin/env bash
# Container entrypoint: repairs volume ownership, seeds config from the image defaults, runs DockerInit
# (container-managed config keys, wait for the DB, first-boot schema) and then replaces itself with Tomcat
# running as the logicaldoc user.
set -euo pipefail

readonly LDOC_HOME=/opt/logicaldoc
readonly CONF=$LDOC_HOME/conf
readonly REPO=$LDOC_HOME/repository
readonly APP_UID=1000
readonly APP_GID=1000

log() { echo "[entrypoint] $*"; }
die() { echo "[entrypoint] ERROR: $*" >&2; exit 1; }

[ -n "${DB_PASSWORD:-}" ] || die "DB_PASSWORD is not set; copy .env.example to .env and set it"

export HOME=$LDOC_HOME

if [ "$(id -u)" = 0 ]; then
  as_app() { setpriv --reuid="$APP_UID" --regid="$APP_GID" --init-groups "$@"; }
  # Bind mounts and restored backups can arrive owned by another user; the app must write both volumes.
  for dir in "$CONF" "$REPO"; do
    mkdir -p "$dir"
    if [ "$(stat -c %u "$dir")" != "$APP_UID" ] || [ -n "$(find "$dir" -maxdepth 1 ! -user "$APP_UID" -print -quit)" ]; then
      log "changing ownership of $dir to uid $APP_UID"
      chown -R "$APP_UID:$APP_GID" "$dir"
    fi
  done
else
  # Started with --user: assume the volumes are already writable.
  as_app() { "$@"; }
fi

if [ ! -f "$CONF/context.properties" ]; then
  log "no $CONF/context.properties yet, starting from the image defaults"
  as_app cp "$LDOC_HOME/defaults/context.properties" "$CONF/context.properties"
fi
for file in log.xml allowed-commands.txt; do
  [ -f "$CONF/$file" ] || as_app cp "$LDOC_HOME/defaults/$file" "$CONF/$file"
done
as_app mkdir -p "$REPO"/docs "$REPO"/index "$REPO"/users "$REPO"/plugins "$REPO"/impex/in "$REPO"/impex/out \
  "$REPO"/logs "$REPO"/db

as_app java -cp "$CATALINA_HOME/webapps/ROOT/WEB-INF/lib/*:$LDOC_HOME/lib/docker-init.jar" DockerInit

log "starting: $*"
if [ "$(id -u)" = 0 ]; then
  exec setpriv --reuid="$APP_UID" --regid="$APP_GID" --init-groups "$@"
fi
exec "$@"
