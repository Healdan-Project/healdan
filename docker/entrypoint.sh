#!/usr/bin/env bash
# Container entrypoint: repairs volume ownership, seeds config from the image defaults, runs DockerInit
# (container-managed config keys, wait for the DB, first-boot schema) and then replaces itself with tini, which
# runs Tomcat, both as the logicaldoc user.
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

# Runs a command as the app user. app_prefix is also used on its own where the command must be bash's direct child.
as_app() { "${app_prefix[@]}" "$@"; }

if [ "$(id -u)" = 0 ]; then
  app_prefix=(setpriv --reuid="$APP_UID" --regid="$APP_GID" --init-groups)
  # Bind mounts and restored backups can arrive owned by another user; the app must write both volumes.
  for dir in "$CONF" "$REPO"; do
    mkdir -p "$dir"
    if [ "$(stat -c %u "$dir")" != "$APP_UID" ] || [ -n "$(find "$dir" -maxdepth 1 ! -user "$APP_UID" -print -quit)" ]; then
      log "changing ownership of $dir to uid $APP_UID"
      if ! chown -R "$APP_UID:$APP_GID" "$dir" 2>/dev/null; then
        # NFS with root_squash and some CIFS mounts refuse chown even to root; writable is good enough.
        if as_app test -w "$dir"; then
          log "WARNING: could not change ownership of $dir; uid $APP_UID can write to it, so continuing"
        else
          die "could not change ownership of $dir and uid $APP_UID cannot write to it; on the NAS or host, give it to uid $APP_UID (gid $APP_GID) or make it writable for that user"
        fi
      fi
    fi
  done
else
  # Started with --user: assume the volumes are already writable.
  app_prefix=(env)
fi

if [ ! -f "$CONF/context.properties" ]; then
  log "no $CONF/context.properties yet, starting from the image defaults"
  as_app cp "$LDOC_HOME/defaults/context.properties" "$CONF/context.properties"
fi
for file in log.xml allowed-commands.txt; do
  [ -f "$CONF/$file" ] || as_app cp "$LDOC_HOME/defaults/$file" "$CONF/$file"
done
# The commands the image relies on are re-added on every start (a conf volume may predate them); local additions stay.
allowed=$CONF/allowed-commands.txt
[ -s "$allowed" ] && [ -n "$(tail -c1 "$allowed")" ] && echo | as_app tee -a "$allowed" >/dev/null
grep -vE '^[[:space:]]*(#|$)' "$LDOC_HOME/defaults/allowed-commands.txt" | while read -r cmd; do
  grep -qxF "$cmd" "$allowed" || echo "$cmd" | as_app tee -a "$allowed" >/dev/null
done
as_app mkdir -p "$REPO"/docs "$REPO"/index "$REPO"/users "$REPO"/plugins "$REPO"/impex/in "$REPO"/impex/out \
  "$REPO"/logs "$REPO"/db

# Until the exec below, bash is PID 1, and the kernel drops signals PID 1 has no handler for: without the trap,
# stopping the container during the DB wait or schema creation waits out the grace period and ends in SIGKILL.
# Bash defers a trap until the foreground command ends, but a signal interrupts `wait`: so DockerInit runs in the
# background while bash waits for it.
init_pid=
trap 'log "stopping"; [ -z "$init_pid" ] || kill -TERM "$init_pid" 2>/dev/null || true; wait; exit 143' TERM INT
"${app_prefix[@]}" java -cp "$CATALINA_HOME/webapps/ROOT/WEB-INF/lib/*:$LDOC_HOME/lib/docker-init.jar" DockerInit &
init_pid=$!
wait "$init_pid"

# tini becomes PID 1: it reaps orphans (soffice processes killed after a timeout) and forwards signals to Tomcat.
# It runs as the app user, after the privilege drop, so that restart.sh (run by Tomcat) may signal PID 1.
log "starting: $*"
if [ "$(id -u)" = 0 ]; then
  exec "${app_prefix[@]}" /usr/bin/tini -- "$@"
fi
exec /usr/bin/tini -- "$@"
