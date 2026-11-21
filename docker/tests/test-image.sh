#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 The Healdan Project
# SPDX-License-Identifier: LGPL-3.0-only

# Image-level checks with plain docker (spec §4, §6, §9): entrypoint guard, the move-from-mariadb command before
# anything else, prompt stop during init, ownership repair, uid, /setup block, Admin > Restart, settings persistence
# and config-loss handling. TEST_IMAGE names the image it builds (logicaldoc-community:test), and TEST_IMAGE_PREFIX its
# network, containers and volumes (ldtest-image, so ldtest-image-db, ldtest-image-app and so on), so that two runs can
# share a machine. Usage: docker/tests/test-image.sh
set -euo pipefail
cd "$(dirname "$0")/../.."
. docker/tests/lib.sh

IMAGE=${TEST_IMAGE:-logicaldoc-community:test}
NET=${TEST_IMAGE_PREFIX:-ldtest-image}
DB=$NET-db
APP=$NET-app
CONF=$NET-conf
REPO=$NET-repo
CACHE=$NET-cache
NAS=$NET-nas
FRESH=$NET-fresh
PW='image test pw'

cleanup() {
  docker rm -fv "$APP" "$DB" >/dev/null 2>&1 || true
  docker network rm "$NET" >/dev/null 2>&1 || true
  docker volume rm -f "$CONF" "$REPO" "$CACHE" "$NAS" "$FRESH-conf" "$FRESH-repo" "$FRESH-cache" >/dev/null 2>&1 || true
}
trap cleanup EXIT
cleanup

docker_build -t "$IMAGE" .
docker network create "$NET" >/dev/null
# As compose.yaml's db service: the built-in C.UTF-8 locale, passwords over TCP, and the role script, which makes the
# app's role and its database
docker run -d --name "$DB" --network "$NET" -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD="$PW" -e POSTGRES_DB=postgres \
  -e POSTGRES_INITDB_ARGS="--encoding=UTF8 --locale-provider=builtin --builtin-locale=C.UTF-8 --locale=C --auth-host=scram-sha-256 --auth-local=trust" \
  -e HEALDAN_DB_NAME=logicaldoc -e HEALDAN_DB_USER=logicaldoc -e HEALDAN_DB_PASSWORD="$PW" -e TZ=UTC \
  -v "$PWD/docker/postgres/10-healdan-role.sh:/docker-entrypoint-initdb.d/10-healdan-role.sh:ro" postgres:18 >/dev/null

# start_app [repository volume] [extra docker run options...]
start_app() {
  local repo=${1:-$REPO}
  [ $# -gt 0 ] && shift
  docker run -d --name "$APP" --network "$NET" --restart unless-stopped -p 127.0.0.1::8080 \
    -e DB_HOST="$DB" -e DB_PASSWORD="$PW" \
    -v "$CONF:/opt/logicaldoc/conf" -v "$repo:/opt/logicaldoc/repository" -v "$CACHE:/opt/logicaldoc/cache" "$@" \
    "$IMAGE" >/dev/null
}
remove_app() { docker rm -f "$APP" >/dev/null; }
# Not grep -q: it exits at the first match and, under pipefail, the SIGPIPE'd docker logs fails the check.
app_logs_have() { docker logs "$APP" 2>&1 | grep -- "$1" >/dev/null; }

echo "== refuses to start without DB_PASSWORD"
out=$(docker run --rm "$IMAGE" 2>&1) && rc=0 || rc=$?
check "exits 1" test "$rc" = 1
check "explains why" grep -q 'DB_PASSWORD is not set' <<<"$out"

echo "== move-from-mariadb runs before the entrypoint touches a volume (PostgreSQL spec §9.1)"
# With no MariaDB to read, the command stops with its own error. The image's folders are empty, so fresh volumes stay
# empty unless something writes to them: the entrypoint's ownership repair, conf seeding or repository folders.
for v in conf repo cache; do docker volume create "$FRESH-$v" >/dev/null; done
out=$(docker run --rm --network "$NET" -e DB_HOST="$DB" -e DB_PASSWORD="$PW" -e MOVE_SOURCE_HOST="$NET-nowhere" \
  -v "$FRESH-conf:/opt/logicaldoc/conf" -v "$FRESH-repo:/opt/logicaldoc/repository" \
  -v "$FRESH-cache:/opt/logicaldoc/cache" "$IMAGE" move-from-mariadb 2>&1) && rc=0 || rc=$?
check "exits 1" test "$rc" = 1
check "with the command's own error" grep -q "^\[move\] ERROR: can't connect to the source" <<<"$out"
check "nothing of the entrypoint's start or the start-up tool's ran" lacks '^\[entrypoint\]\|^\[docker-init\]' "$out"
check "the volumes are still empty" docker run --rm -v "$FRESH-conf:/c" -v "$FRESH-repo:/r" -v "$FRESH-cache:/k" busybox \
  sh -c 'test -z "$(find /c /r /k -mindepth 1)"'
[ "$FAILED" = 0 ] || echo "$out"
docker volume rm -f "$FRESH-conf" "$FRESH-repo" "$FRESH-cache" >/dev/null

echo "== the app user cannot change what root runs at container start"
ep=$(docker inspect -f '{{index .Config.Entrypoint 0}}' "$IMAGE")
check "entrypoint and its directory not writable by uid 1000" \
  docker run --rm --entrypoint sh -u 1000 "$IMAGE" -c "test ! -w '$ep' && test ! -w \"\$(dirname '$ep')\""

echo "== stopping the container while it waits for the database is prompt"
docker run -d --name "$APP" --network "$NET" -e DB_HOST="$NET-nowhere" -e DB_PASSWORD="$PW" \
  -e DB_WAIT_SECONDS=600 "$IMAGE" >/dev/null
deadline=$((SECONDS + 60))
until app_logs_have 'waiting for database' || [ "$SECONDS" -ge "$deadline" ]; do sleep 1; done
started=$SECONDS
docker stop -t 30 "$APP" >/dev/null
check "stops in under 10s (grace period 30s)" test $((SECONDS - started)) -lt 10
check "ended by SIGTERM, not SIGKILL" test "$(docker inspect -f '{{.State.ExitCode}}' "$APP")" != 137
docker rm -fv "$APP" >/dev/null

echo "== volumes owned by root (like a NAS bind mount) are handed to uid 1000"
docker volume create "$CONF" >/dev/null
docker volume create "$REPO" >/dev/null
docker volume create "$CACHE" >/dev/null
docker run --rm -v "$CONF:/c" -v "$REPO:/r" -v "$CACHE:/k" busybox \
  sh -c 'touch /r/root-file /k/root-file && chown -R 0:0 /c /r /k'
start_app
check "becomes healthy" wait_healthy "$APP" 900
base="http://$(docker port "$APP" 8080/tcp | head -1)"
check "repository content owned by uid 1000" test "$(docker exec "$APP" stat -c %u /opt/logicaldoc/repository/root-file)" = 1000
check "cache content owned by uid 1000" test "$(docker exec "$APP" stat -c %u /opt/logicaldoc/cache/root-file)" = 1000
check "context.properties owned by uid 1000" test "$(docker exec "$APP" stat -c %u /opt/logicaldoc/conf/context.properties)" = 1000
# uid 1000 must own PID 1, or restart.sh may not signal it. Tomcat is the JVM running Catalina's Bootstrap; the
# brackets keep the scan from matching its own command line.
check "PID 1 is tini, running as uid 1000" \
  docker exec "$APP" sh -c 'grep -q "^1 (tini) " /proc/1/stat && test "$(stat -c %u /proc/1)" = 1000'
tomcat_uid=$(docker exec "$APP" sh -c 'for d in /proc/[0-9]*; do
    tr "\0" " " 2>/dev/null <"$d/cmdline" | grep -q "catalina[.]startup[.]Bootstrap" && stat -c %u "$d"
  done; true' || true)
check "Tomcat runs as uid 1000" test "$tomcat_uid" = 1000
check "schema created on first boot" app_logs_have 'schema created'

echo "== pages"
check "login page 200" test "$(http_code "$base/login.jsp")" = 200
check "/setup is 403" test "$(http_code "$base/setup")" = 403
check "/setup.jsp is 403" test "$(http_code "$base/setup.jsp")" = 403
check "app generated its encryption key in the conf volume" \
  docker exec "$APP" grep -qE '^encryption\.key *= *[^ ]' /opt/logicaldoc/conf/context.properties
check "gs is not an allowed command" lacks '^gs$' "$(docker exec "$APP" cat /opt/logicaldoc/conf/allowed-commands.txt)"

echo "== Admin > Restart: the app runs restart.sh (allowed-commands.txt) and the restart policy brings it back"
before=$(docker inspect -f '{{.RestartCount}}' "$APP")
sid=$(curl -fsS "$base/services/rest/auth/login?u=admin&pw=admin" | tr -d '"[:space:]')
# The GWT-RPC request the Restart button sends (SystemService.restart, no arguments), with the REST session id
# in the sid header. Tomcat may be shutting down as it answers, hence || true.
reply=$(curl -sS -X POST "$base/frontend/system" -H 'Content-Type: text/x-gwt-rpc; charset=utf-8' \
  -H 'X-GWT-Permutation: 0' -H "sid: $sid" \
  --data-binary "7|0|4|$base/frontend/|0|com.logicaldoc.gui.frontend.client.services.SystemService|restart|1|2|3|4|0|" \
  2>&1 || true)
check "restart call succeeds" grep -q '^//OK' <<<"$reply"
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
