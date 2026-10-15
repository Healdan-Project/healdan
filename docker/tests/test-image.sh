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
PW='image test pw'

cleanup() {
  docker rm -f "$APP" "$DB" >/dev/null 2>&1 || true
  docker network rm "$NET" >/dev/null 2>&1 || true
  docker volume rm -f "$CONF" "$REPO" >/dev/null 2>&1 || true
}
trap cleanup EXIT
cleanup

docker build -t "$IMAGE" .
docker network create "$NET" >/dev/null
docker run -d --name "$DB" --network "$NET" -e MARIADB_DATABASE=logicaldoc -e MARIADB_USER=logicaldoc \
  -e MARIADB_PASSWORD="$PW" -e MARIADB_RANDOM_ROOT_PASSWORD=1 mariadb:11.4 >/dev/null

start_app() {
  docker run -d --name "$APP" --network "$NET" --restart unless-stopped -p 127.0.0.1::8080 \
    -e DB_HOST="$DB" -e DB_PASSWORD="$PW" \
    -v "$CONF:/opt/logicaldoc/conf" -v "$REPO:/opt/logicaldoc/repository" "$IMAGE" >/dev/null
}
remove_app() { docker rm -f "$APP" >/dev/null; }
# Not grep -q: it exits at the first match and, under pipefail, the SIGPIPE'd docker logs fails the check.
app_logs_have() { docker logs "$APP" 2>&1 | grep -- "$1" >/dev/null; }

echo "== refuses to start without DB_PASSWORD"
out=$(docker run --rm "$IMAGE" 2>&1) && rc=0 || rc=$?
check "exits 1" test "$rc" = 1
check "explains why" grep -q 'DB_PASSWORD is not set' <<<"$out"

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

echo "== Admin > Restart: restart.sh stops Tomcat and the restart policy brings it back"
before=$(docker inspect -f '{{.RestartCount}}' "$APP")
docker exec -u 1000 "$APP" /opt/logicaldoc/bin/restart.sh || true
deadline=$((SECONDS + 120))
while [ "$(docker inspect -f '{{.RestartCount}}' "$APP")" = "$before" ] && [ "$SECONDS" -lt "$deadline" ]; do sleep 2; done
check "container restarted" test "$(docker inspect -f '{{.RestartCount}}' "$APP")" -gt "$before"
check "healthy after restart" wait_healthy "$APP" 900

echo "== settings changed by the admin survive container recreation"
remove_app
docker run --rm -v "$CONF:/c" busybox sed -i -E \
  's|^conf\.importdir *=.*|conf.importdir=/opt/logicaldoc/repository/custom-in/|' /c/context.properties
start_app
check "healthy after recreation" wait_healthy "$APP" 900
check "conf.importdir kept" docker exec "$APP" grep -qE '^conf\.importdir *= */opt/logicaldoc/repository/custom-in/' /opt/logicaldoc/conf/context.properties
check "no re-initialization" app_logs_have 'schema present, skipping'

echo "== conf volume lost, database kept"
remove_app
docker volume rm -f "$CONF" >/dev/null && docker volume create "$CONF" >/dev/null
start_app
check "healthy with a fresh conf volume" wait_healthy "$APP" 900
check "warns about lost config" app_logs_have 'context.properties was just created, but the database already has a schema'

[ "$FAILED" = 0 ] || docker logs --tail 150 "$APP" 2>&1
finish
