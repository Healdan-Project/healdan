#!/usr/bin/env bash
# Image-level checks with plain docker (spec §4, §6, §9): entrypoint guard, ownership repair, uid, /setup
# block, restart, settings persistence and config-loss handling.
set -euo pipefail
cd "$(dirname "$0")/../.."
. docker/tests/lib.sh

IMAGE=logicaldoc-community:test
NET=ldtest-image
DB=ldtest-image-db
APP=ldtest-image-app
CONF=ldtest-image-conf
REPO=ldtest-image-repo
NAS=ldtest-image-nas
PW='image test pw'

cleanup() {
  docker rm -f "$APP" "$DB" >/dev/null 2>&1 || true
  docker network rm "$NET" >/dev/null 2>&1 || true
  docker volume rm -f "$CONF" "$REPO" "$NAS" >/dev/null 2>&1 || true
}
trap cleanup EXIT
cleanup

docker build -t "$IMAGE" .
docker network create "$NET" >/dev/null
docker run -d --name "$DB" --network "$NET" -e MARIADB_DATABASE=logicaldoc -e MARIADB_USER=logicaldoc \
  -e MARIADB_PASSWORD="$PW" -e MARIADB_RANDOM_ROOT_PASSWORD=1 mariadb:11.4 >/dev/null

# start_app [repository volume] [extra docker run options...]
start_app() {
  local repo=${1:-$REPO}
  [ $# -gt 0 ] && shift
  docker run -d --name "$APP" --network "$NET" --restart unless-stopped -p 127.0.0.1::8080 \
    -e DB_HOST="$DB" -e DB_PASSWORD="$PW" \
    -v "$CONF:/opt/logicaldoc/conf" -v "$repo:/opt/logicaldoc/repository" "$@" "$IMAGE" >/dev/null
}
remove_app() { docker rm -f "$APP" >/dev/null; }
# Not grep -q: it exits at the first match and, under pipefail, the SIGPIPE'd docker logs fails the check.
app_logs_have() { docker logs "$APP" 2>&1 | grep -- "$1" >/dev/null; }

echo "== refuses to start without DB_PASSWORD"
out=$(docker run --rm "$IMAGE" 2>&1) && rc=0 || rc=$?
check "exits 1" test "$rc" = 1
check "explains why" grep -q 'DB_PASSWORD is not set' <<<"$out"

echo "== the app user cannot change what root runs at container start"
ep=$(docker inspect -f '{{index .Config.Entrypoint 0}}' "$IMAGE")
check "entrypoint and its directory not writable by uid 1000" \
  docker run --rm --entrypoint sh -u 1000 "$IMAGE" -c "test ! -w '$ep' && test ! -w \"\$(dirname '$ep')\""

echo "== volumes owned by root (like a NAS bind mount) are handed to uid 1000"
docker volume create "$CONF" >/dev/null
docker volume create "$REPO" >/dev/null
docker run --rm -v "$CONF:/c" -v "$REPO:/r" busybox sh -c 'touch /r/root-file && chown -R 0:0 /c /r'
start_app
check "becomes healthy" wait_healthy "$APP" 900
base="http://$(docker port "$APP" 8080/tcp | head -1)"
check "repository content owned by uid 1000" test "$(docker exec "$APP" stat -c %u /opt/logicaldoc/repository/root-file)" = 1000
check "context.properties owned by uid 1000" test "$(docker exec "$APP" stat -c %u /opt/logicaldoc/conf/context.properties)" = 1000
check "Tomcat (PID 1) runs as uid 1000" test "$(docker exec "$APP" stat -c %u /proc/1)" = 1000
check "schema created on first boot" app_logs_have 'schema created'

echo "== pages"
check "login page 200" test "$(http_code "$base/login.jsp")" = 200
check "/setup is 403" test "$(http_code "$base/setup")" = 403
check "/setup.jsp is 403" test "$(http_code "$base/setup.jsp")" = 403
check "app generated its encryption key in the conf volume" \
  docker exec "$APP" grep -qE '^encryption\.key *= *[^ ]' /opt/logicaldoc/conf/context.properties
check "gs is not an allowed command" lacks '^gs$' "$(docker exec "$APP" cat /opt/logicaldoc/conf/allowed-commands.txt)"

echo "== Admin > Restart: restart.sh stops Tomcat and the restart policy brings it back"
before=$(docker inspect -f '{{.RestartCount}}' "$APP")
docker exec -u 1000 "$APP" /opt/logicaldoc/bin/restart.sh || true
deadline=$((SECONDS + 120))
while [ "$(docker inspect -f '{{.RestartCount}}' "$APP")" = "$before" ] && [ "$SECONDS" -lt "$deadline" ]; do sleep 2; done
check "container restarted" test "$(docker inspect -f '{{.RestartCount}}' "$APP")" -gt "$before"
check "healthy after restart" wait_healthy "$APP" 900

echo "== settings changed by the admin survive container recreation"
remove_app
# Like a conf volume from an older image: one default key and one allowed command missing, plus a local addition.
docker run --rm -v "$CONF:/c" busybox sh -c "
  sed -i -E 's|^conf\.importdir *=.*|conf.importdir=/opt/logicaldoc/repository/custom-in/|; /^cookies\.samesite *=/d' /c/context.properties
  sed -i '/^pdftotext\$/d' /c/allowed-commands.txt
  echo mytool >> /c/allowed-commands.txt"
start_app
check "healthy after recreation" wait_healthy "$APP" 900
conf_file() { docker exec "$APP" cat "/opt/logicaldoc/conf/$1"; }
check "conf.importdir kept" grep -qE '^conf\.importdir *= */opt/logicaldoc/repository/custom-in/' <<<"$(conf_file context.properties)"
check "missing default key restored" grep -qE '^cookies\.samesite *= *lax' <<<"$(conf_file context.properties)"
check "missing default command restored" grep -qx pdftotext <<<"$(conf_file allowed-commands.txt)"
check "locally added command kept" grep -qx mytool <<<"$(conf_file allowed-commands.txt)"
check "no re-initialization" app_logs_have 'schema present, skipping'

echo "== conf volume lost, database kept"
remove_app
docker volume rm -f "$CONF" >/dev/null && docker volume create "$CONF" >/dev/null
start_app
check "healthy with a fresh conf volume" wait_healthy "$APP" 900
check "warns about lost config" app_logs_have 'context.properties was just created, but the database already has a schema'

echo "== a volume the container may not chown (e.g. NFS with root_squash)"
remove_app
docker volume create "$NAS" >/dev/null
# Not empty, or Docker would copy the image's uid-1000 directory into it on first mount.
docker run --rm -v "$NAS:/r" busybox sh -c 'touch /r/.keep && chown -R 0:0 /r && chmod 755 /r'
out=$(docker run --rm --cap-drop CHOWN --network "$NET" -e DB_HOST="$DB" -e DB_PASSWORD="$PW" \
  -v "$CONF:/opt/logicaldoc/conf" -v "$NAS:/opt/logicaldoc/repository" "$IMAGE" 2>&1) && rc=0 || rc=$?
check "not writable: exits 1" test "$rc" = 1
check "not writable: says how to fix the share" grep -q 'give it to uid 1000' <<<"$out"
docker run --rm -v "$NAS:/r" busybox chmod 777 /r
start_app "$NAS" --cap-drop CHOWN
check "writable: starts anyway" wait_healthy "$APP" 900
check "writable: warns about the ownership" app_logs_have 'could not change ownership'

[ "$FAILED" = 0 ] || docker logs --tail 150 "$APP" 2>&1
finish
