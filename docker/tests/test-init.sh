#!/usr/bin/env bash
# Integration test for DockerInit against a throwaway MariaDB: validation, waiting for a late database,
# schema creation, idempotent re-runs, config-loss warning and credential errors (spec §6, §9).
set -euo pipefail
cd "$(dirname "$0")/../.."
. docker/tests/lib.sh

TOOLS=logicaldoc-tools:test
NET=ldtest-init
DB=ldtest-init-db
CONF=ldtest-init-conf
PW='sm0ke,p=a\ss $x #1 é:! '

cleanup() {
  docker rm -f "$DB" >/dev/null 2>&1 || true
  docker network rm "$NET" >/dev/null 2>&1 || true
  docker volume rm -f "$CONF" >/dev/null 2>&1 || true
}
trap cleanup EXIT
cleanup

docker build --target tools -t "$TOOLS" . >/dev/null
docker network create "$NET" >/dev/null
docker volume create "$CONF" >/dev/null

# init [docker run options...]: runs DockerInit like the entrypoint does (copying defaults on first use).
init() {
  docker run --rm --network "$NET" -v "$CONF:/opt/logicaldoc/conf" \
    -e DB_HOST="$DB" -e DB_PASSWORD="$PW" "$@" "$TOOLS" bash -c '
      [ -f /opt/logicaldoc/conf/context.properties ] || cp /build/ROOT/WEB-INF/classes/context.properties /opt/logicaldoc/conf/
      exec java -cp "/build/ROOT/WEB-INF/lib/*:/build/docker-init.jar" DockerInit'
}

# db_sql <statement>: runs SQL in the test database as the app user and prints the result.
db_sql() { docker exec "$DB" sh -c 'MYSQL_PWD="$MARIADB_PASSWORD" mariadb -u"$MARIADB_USER" "$MARIADB_DATABASE" -Nse "$1"' _ "$1"; }

start_db() {
  docker run -d --name "$DB" --network "$NET" -e MARIADB_DATABASE=logicaldoc -e MARIADB_USER=logicaldoc \
    -e MARIADB_PASSWORD="$PW" -e MARIADB_RANDOM_ROOT_PASSWORD=1 mariadb:11.4 >/dev/null
}

echo "== 1. missing DB_PASSWORD"
out=$(init -e DB_PASSWORD= 2>&1) && rc=0 || rc=$?
check "exits 1" test "$rc" = 1
check "names DB_PASSWORD" grep -q 'DB_PASSWORD is not set' <<<"$out"

echo "== 2. database starts after the app; conf left unseeded by run 1 is seeded now"
log2=$(mktemp)
init -e DB_WAIT_SECONDS=180 >"$log2" 2>&1 &
pid=$!
sleep 8
start_db
wait "$pid" && rc=0 || rc=$?
out=$(cat "$log2"); rm -f "$log2"
check "exits 0" test "$rc" = 0
check "waited for the database" grep -q 'waiting for database' <<<"$out"
check "seeded the config" grep -q 'seeding /opt/logicaldoc/conf/context.properties' <<<"$out"
check "core uses the mariadb variant" grep -q 'plan: 1 logicaldoc-core -> sql/logicaldoc-core.sql.mariadb' <<<"$out"
check "webservice uses the mariadb variant" grep -q 'plan: 20 logicaldoc-webservice -> sql/logicaldoc-webservice.sql.mariadb' <<<"$out"
check "dropbox uses the generic script" grep -q 'plan: 1500 logicaldoc-dropbox -> sql/logicaldoc-dropbox.sql$' <<<"$out"
check "schema created" grep -q 'schema created' <<<"$out"
check "ld_webservicecall exists" db_sql "select 1 from ld_webservicecall where 1=0"
check "completion recorded" test "$(db_sql 'select count(*) from docker_init')" = 1
[ "$FAILED" = 0 ] || { echo "$out"; }

echo "== 3. second run is a no-op"
out=$(init 2>&1) && rc=0 || rc=$?
check "exits 0" test "$rc" = 0
check "skips initialization" grep -q 'schema present, skipping' <<<"$out"
check "no config-loss warning" lacks 'WARNING' "$out"

echo "== 4. conf volume lost, database kept"
docker volume rm -f "$CONF" >/dev/null && docker volume create "$CONF" >/dev/null
out=$(init 2>&1) && rc=0 || rc=$?
check "exits 0" test "$rc" = 0
check "skips initialization" grep -q 'schema present, skipping' <<<"$out"
check "warns about lost config" grep -q 'context.properties was just created, but the database already has a schema' <<<"$out"

echo "== 5. wrong password fails fast"
start=$SECONDS
out=$(init -e DB_PASSWORD=wrong -e DB_WAIT_SECONDS=120 2>&1) && rc=0 || rc=$?
check "exits 1" test "$rc" = 1
check "says authentication failed" grep -q 'database authentication failed' <<<"$out"
check "does not wait out the timeout" test $((SECONDS - start)) -lt 60

echo "== 6. unreachable database times out"
out=$(init -e DB_HOST=ldtest-nowhere -e DB_WAIT_SECONDS=4 2>&1) && rc=0 || rc=$?
check "exits 1" test "$rc" = 1
check "says not reachable" grep -q 'not reachable' <<<"$out"

echo "== 7. a first start that did not finish is not mistaken for a complete schema"
db_sql "drop table if exists docker_init"
out=$(init 2>&1) && rc=0 || rc=$?
check "exits 1" test "$rc" = 1
check "says the earlier initialization did not finish" grep -q 'initialization did not finish' <<<"$out"
check "does not start on the half-built schema" lacks 'schema present, skipping' "$out"

finish
