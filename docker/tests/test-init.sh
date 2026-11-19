#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 The Healdan Project
# SPDX-License-Identifier: LGPL-3.0-only

# Integration test for DockerInit against a throwaway PostgreSQL, started as compose.yaml's db service is: validation,
# waiting for a late database, schema creation, the role's lock timeout, LogicalDOC's plugins installed once,
# idempotent re-runs, LogicalDOC's tables lacking a change, the checks of the server, database and role, the guard
# against starting an install empty, config-loss warning and credential errors (Docker spec §6, §9, §15, §16;
# PostgreSQL spec §6.1 to §6.3, §7.4, §8.2, §8.3).
# TEST_INIT_TOOLS names the tools image it builds (logicaldoc-tools:test), and TEST_INIT_PREFIX its network, database
# container and volumes (ldtest-init, so ldtest-init-db, ldtest-init-conf and so on), so that two runs can share a
# machine. Usage: docker/tests/test-init.sh
set -euo pipefail
cd "$(dirname "$0")/../.."
. docker/tests/lib.sh

TOOLS=${TEST_INIT_TOOLS:-logicaldoc-tools:test}
NET=${TEST_INIT_PREFIX:-ldtest-init}
DB=$NET-db
CONF=$NET-conf
REPO=$NET-repo
PW='sm0ke,p=a\ss $x #1 é:! '

cleanup() {
  docker rm -f "$DB" >/dev/null 2>&1 || true
  docker network rm "$NET" >/dev/null 2>&1 || true
  docker volume rm -f "$CONF" "$REPO" >/dev/null 2>&1 || true
}
trap cleanup EXIT
cleanup

docker build --target tools -t "$TOOLS" . >/dev/null
docker network create "$NET" >/dev/null
docker volume create "$CONF" >/dev/null
docker volume create "$REPO" >/dev/null

# init [docker run options...]: runs DockerInit like the entrypoint does (copying defaults on first use, and with the
# conf folder on the classpath for the plugins it installs).
init() {
  docker run --rm --network "$NET" -v "$CONF:/opt/logicaldoc/conf" -v "$REPO:/opt/logicaldoc/repository" \
    -e DB_HOST="$DB" -e DB_PASSWORD="$PW" "$@" "$TOOLS" bash -c '
      for f in context.properties log.xml; do
        [ -f /opt/logicaldoc/conf/$f ] || cp /build/ROOT/WEB-INF/classes/$f /opt/logicaldoc/conf/
      done
      exec java -cp "/build/ROOT/WEB-INF/lib/*:/build/docker-init.jar:/opt/logicaldoc/conf" DockerInit'
}

# db_sql <statement>: runs SQL in the test database as the app's role over TCP, as the app connects, and prints the
# result
db_sql() {
  docker exec "$DB" sh -c 'PGPASSWORD="$HEALDAN_DB_PASSWORD" psql -X -q -At -F "$(printf "\t")" -v ON_ERROR_STOP=1 \
    -h 127.0.0.1 -U "$HEALDAN_DB_USER" -d "$HEALDAN_DB_NAME" -c "$1"' _ "$1"
}
# db_admin <statement> [database]: runs SQL as the server's superuser, over the container's socket
db_admin() { docker exec "$DB" psql -X -q -At -v ON_ERROR_STOP=1 -U postgres -d "${2:-logicaldoc}" -c "$1"; }
# db_script: runs the SQL on its input as the app's role, as Upgrading's commands give it to psql
db_script() {
  docker exec -i "$DB" sh -c 'PGPASSWORD="$HEALDAN_DB_PASSWORD" psql -X -q -v ON_ERROR_STOP=1 -h 127.0.0.1 \
    -U "$HEALDAN_DB_USER" -d "$HEALDAN_DB_NAME" -f -'
}
# tables <database>: how many tables the database's public schema holds
tables() { db_admin "select count(*) from pg_tables where schemaname = 'public'" "$1"; }
# table_names <database>: the names of the tables in its public schema, a line each
table_names() { db_admin "select tablename from pg_tables where schemaname = 'public' order by tablename" "$1"; }
# role_settings <database> [role]: how many settings, such as lock_timeout, the role (the app's) has in the database
role_settings() {
  db_admin "select count(*) from pg_db_role_setting s join pg_roles r on r.oid = s.setrole
    join pg_database d on d.oid = s.setdatabase where r.rolname = '${2:-logicaldoc}' and d.datname = '$1'" postgres
}
# untouched <the table names before>: the app's database holds the same tables as before, and no setting for the app's
# role, which section 8 takes away first: a start that wrote nothing in it
untouched() {
  local now settings
  now=$(table_names logicaldoc) && settings=$(role_settings logicaldoc) || { echo "  can't read the database"; return 1; }
  [ -n "$1" ] && [ "$now" = "$1" ] && [ "$settings" = 0 ] && return 0
  echo "  tables the same: $([ -n "$1" ] && [ "$now" = "$1" ] && echo yes || echo no); the role's settings: $settings"
  return 1
}

# As compose.yaml's db service: the built-in C.UTF-8 locale, passwords over TCP, the trusted socket, and the role
# script, which makes the app's role and its database
start_db() {
  docker run -d --name "$DB" --network "$NET" -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD="$PW" \
    -e POSTGRES_DB=postgres \
    -e POSTGRES_INITDB_ARGS="--encoding=UTF8 --locale-provider=builtin --builtin-locale=C.UTF-8 --locale=C --auth-host=scram-sha-256 --auth-local=trust" \
    -e HEALDAN_DB_NAME=logicaldoc -e HEALDAN_DB_USER=logicaldoc -e HEALDAN_DB_PASSWORD="$PW" -e TZ=UTC \
    -v "$PWD/docker/postgres/10-healdan-role.sh:/docker-entrypoint-initdb.d/10-healdan-role.sh:ro" postgres:18 >/dev/null
}
# conf_sha: the SHA-256 of the conf volume's context.properties, to tell whether a start changed it
conf_sha() { docker run --rm -v "$CONF:/c:ro" "$TOOLS" sha256sum /c/context.properties | cut -d' ' -f1; }
# conf_unchanged <SHA-256 before>: the conf volume's context.properties is as it was
conf_unchanged() { [ "${#1}" = 64 ] && [ "$(conf_sha)" = "$1" ]; }
# conf_key <key>: the key's value as the conf volume's context.properties holds it, as written
conf_key() { docker run --rm -v "$CONF:/c:ro" "$TOOLS" sh -c 'grep -a "^$1 *=" /c/context.properties | cut -d= -f2-' _ "$1"; }
# name_mariadb: makes the conf volume name MariaDB's driver, URL, dbms and dialect, as a MariaDB install's does before
# its move, as LogicalDOC writes them
name_mariadb() {
  docker run --rm -v "$CONF:/c" "$TOOLS" sed -i -E \
    -e 's|^jdbc\.url *=.*|jdbc.url=jdbc:mariadb://db:3306/logicaldoc|' -e 's|^jdbc\.dbms *=.*|jdbc.dbms=mariadb|' \
    -e 's|^jdbc\.driver *=.*|jdbc.driver=org.mariadb.jdbc.Driver|' \
    -e 's|^hibernate\.dialect *=.*|hibernate.dialect=org.hibernate.dialect.MariaDBDialect|' /c/context.properties
}

echo "== 1. missing DB_PASSWORD"
out=$(init -e DB_PASSWORD= 2>&1) && rc=0 || rc=$?
check "exits 1" test "$rc" = 1
check "names DB_PASSWORD" grep -q 'DB_PASSWORD is not set' <<<"$out"

echo "== 1b. LOGICALDOC_TRUSTED_PROXIES with a host name, refused before the database wait"
out=$(init -e LOGICALDOC_TRUSTED_PROXIES=192.168.1.10,npm 2>&1) && rc=0 || rc=$?
check "exits 1" test "$rc" = 1
check "names the setting and the entry" grep -q "LOGICALDOC_TRUSTED_PROXIES: 'npm'" <<<"$out"

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
# PostgreSQL has no variant of its own: every plugin gets its generic script
check "core uses the generic script" grep -q 'plan: 1 logicaldoc-core -> sql/logicaldoc-core.sql$' <<<"$out"
check "webservice uses the generic script" grep -q 'plan: 20 logicaldoc-webservice -> sql/logicaldoc-webservice.sql$' <<<"$out"
check "dropbox uses the generic script" grep -q 'plan: 1500 logicaldoc-dropbox -> sql/logicaldoc-dropbox.sql$' <<<"$out"
check "schema created" grep -q 'schema created' <<<"$out"
check "ld_webservicecall exists" test "$(db_sql "select to_regclass('ld_webservicecall') is not null")" = t
check "completion recorded" test "$(db_sql 'select count(*) from docker_init')" = 1
check "installed LogicalDOC's plugins" grep -q "installed LogicalDOC's plugins" <<<"$out"
check "the core plugin's install marker is in the repository" docker run --rm -v "$REPO:/r" "$TOOLS" \
  sh -c 'ls /r/plugins/logicaldoc-core/install-* >/dev/null'
# The role's lock_timeout in its database (PostgreSQL spec §7.4), which every new connection of the role takes
check "says the server, the database and the lock timeout" \
  grep -q "PostgreSQL 18 (18[0-9]*), a UTF8 database, lock_timeout 50s for the role 'logicaldoc' in it" <<<"$out"
check "a new connection of the role has lock_timeout 50s" test "$(db_sql 'show lock_timeout')" = 50s
check "the database sorts by code point: no warning of its collation" lacks "WARNING: the database's collation" "$out"
check "the conf names PostgreSQL's driver" test "$(conf_key jdbc.driver)" = org.postgresql.Driver
check "the conf names PostgreSQL as its dbms" test "$(conf_key jdbc.dbms)" = postgresql
check "the conf names PostgreSQL's dialect" test "$(conf_key hibernate.dialect)" = org.hibernate.dialect.PostgreSQLDialect
[ "$FAILED" = 0 ] || { echo "$out"; }

echo "== 3. second run is a no-op"
out=$(init 2>&1) && rc=0 || rc=$?
check "exits 0" test "$rc" = 0
check "skips initialization" grep -q 'schema present, skipping' <<<"$out"
check "no config-loss warning" lacks 'WARNING' "$out"
check "the plugins are installed already" lacks "installed LogicalDOC's plugins" "$out"
check "checks LogicalDOC's tables" grep -q "LogicalDOC's tables have every table and column" <<<"$out"

# The 14 quota thresholds upstream sync #215 put on ld_tenant, which a database made before it lacks.
QUOTA_THRESHOLDS="forms users sessions documents workflows storage rousers reports apicalls tickets stamps
  importfolders emailaccounts pages"
quota_thresholds() {
  db_sql "select count(*) from information_schema.columns where table_schema = current_schema() and table_name = 'ld_tenant' and column_name like '%quotathr'"
}

echo "== 3b. ld_tenant as before upstream sync #215: the start stops, and changes nothing"
drops=
for q in $QUOTA_THRESHOLDS; do drops="${drops:+$drops, }drop column ld_${q}quotathr"; done
db_sql "alter table ld_tenant $drops"
check "ld_tenant has no quota thresholds" test "$(quota_thresholds)" = 0
out=$(init 2>&1) && rc=0 || rc=$?
check "exits 1" test "$rc" = 1
check "names ld_tenant and the columns it lacks, in the script's order" \
  grep -q 'table ld_tenant lacks columns ld_formsquotathr, ld_usersquotathr, .*, ld_emailaccountsquotathr, ld_pagesquotathr\.' <<<"$out"
check "points to the changes to LogicalDOC's tables" \
  grep -q 'then start again: https://docs.healdan.com/install/upgrading/#changes-to-logicaldocs-tables$' <<<"$out"
check "adds no column itself" test "$(quota_thresholds)" = 0
[ "$FAILED" = 0 ] || { echo "$out"; }

echo "== 3c. Upgrading's SQL, and a column an older version left doesn't count"
# Every PostgreSQL database was made by a version that had every change before it, so the quota thresholds go back
# by this test's own SQL. Then each change the Install manual's Upgrading page lists under Changes to LogicalDOC's
# Tables runs as the page gives it, the SQL it feeds psql, so the page's own change is what's tested. Until an
# upstream sync adds one, the page lists none, and that part only says so.
adds=
for q in $QUOTA_THRESHOLDS; do adds="${adds:+$adds, }add column if not exists ld_${q}quotathr int"; done
db_sql "alter table ld_tenant $adds"
check "ld_tenant has its 14 quota thresholds again" test "$(quota_thresholds)" = 14
upgrade_sql=$(awk '/^## / { on = ($0 == "## Changes to LogicalDOC'"'"'s Tables"); next }
  on && /psql.*<< *'"'"'?SQL'"'"'?$/ { sql = 1; next }
  on && sql && /^SQL$/ { sql = 0; print ""; next }
  on && sql' site/content/install/upgrading.md)
if [ -z "$upgrade_sql" ]; then
  echo "skipped: Upgrading lists no change to LogicalDOC's tables for PostgreSQL, so there's no SQL of its to run"
else
  check "Upgrading's SQL runs, and leaves what the database has" db_script <<<"$upgrade_sql"
fi
db_sql "alter table ld_tenant add column ld_leftover int"
out=$(init 2>&1) && rc=0 || rc=$?
check "exits 0" test "$rc" = 0
check "checks LogicalDOC's tables" grep -q "LogicalDOC's tables have every table and column" <<<"$out"
check "the column left stays" test "$(db_sql "select count(*) from information_schema.columns where table_schema = current_schema() and table_name = 'ld_tenant' and column_name = 'ld_leftover'")" = 1
db_sql "alter table ld_tenant drop column ld_leftover"

echo "== 3d. a table the scripts create is missing"
db_sql "alter table ld_webservicecall rename to ld_webservicecall_gone"
out=$(init 2>&1) && rc=0 || rc=$?
check "exits 1" test "$rc" = 1
check "names the table" grep -q 'table ld_webservicecall is missing\.' <<<"$out"
check "creates no table itself" test "$(db_sql "select to_regclass('ld_webservicecall') is null")" = t
db_sql "alter table ld_webservicecall_gone rename to ld_webservicecall"
out=$(init 2>&1) && rc=0 || rc=$?
check "starts again once it's back" test "$rc" = 0

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

# The checks of the server, the database and the role, before anything is created in them (PostgreSQL spec §6.1 to
# §6.3): each in a database of its own beside the app's, made as the superuser
echo "== 7. a database that isn't UTF-8 is refused by name, and nothing is made in it"
db_admin "create database latin1 owner logicaldoc template template0 encoding 'LATIN1' locale 'C'" postgres
out=$(init -e DB_NAME=latin1 2>&1) && rc=0 || rc=$?
check "exits 1" test "$rc" = 1
check "says its encoding and the one Healdan needs" grep -qF "the database's encoding is LATIN1, and Healdan needs UTF8" <<<"$out"
check "says how to make one" grep -qF "make a new one with create database <name> owner <role> template template0 encoding 'UTF8' locale_provider builtin builtin_locale 'C.UTF-8' locale 'C'" <<<"$out"
check "creates no table" test "$(tables latin1)" = 0

echo "== 7b. a database that doesn't sort by code point starts, with a warning"
# libc's en_US.utf8, as a server set up with its operating system's locale would make it
db_admin "create database enus owner logicaldoc template template0 encoding 'UTF8' locale_provider libc locale 'en_US.utf8'" postgres
out=$(init -e DB_NAME=enus 2>&1) && rc=0 || rc=$?
check "exits 0" test "$rc" = 0
check "warns of its collation and the folder lookups" grep -qF "WARNING: the database's collation is en_US.utf8 (libc), which PostgreSQL doesn't take as sorting by code point: Healdan's lists can sort in another order, and LogicalDOC's folder lookups can't use their index." <<<"$out"
check "the schema is created" grep -q 'schema created' <<<"$out"

echo "== 7c. a role that can't create tables in its schema is refused before any table exists"
# Since PostgreSQL 15, only the database's owner may create in public: here the superuser owns it
db_admin "create role nocreate login password 'nocreate-pw'" postgres
db_admin "create database nocreate owner postgres template template0 encoding 'UTF8' locale_provider builtin builtin_locale 'C.UTF-8' locale 'C'" postgres
out=$(init -e DB_NAME=nocreate -e DB_USER=nocreate -e DB_PASSWORD=nocreate-pw 2>&1) && rc=0 || rc=$?
check "exits 1" test "$rc" = 1
check "names the role and the schema" grep -qF "the role 'nocreate' can't create tables in the schema 'public', as Healdan has to" <<<"$out"
check "creates no table" test "$(tables nocreate)" = 0
check "sets no lock_timeout for it" test "$(role_settings nocreate nocreate)" = 0

# The guard against starting an install empty (PostgreSQL spec §8.3), against the app's database and conf volume:
# each refusal comes before the start writes anything, to the conf volume or to the database. The app's role loses its
# lock_timeout in the app's database first, which every start that gets past the guard sets again, so a refused start
# that wrote to the database would show.
GUARD_MOVE_PAGE=https://docs.healdan.com/install/moving-from-mariadb/
echo "== 8. a conf volume that names MariaDB, with PostgreSQL not answering: move first"
name_mariadb
db_admin "alter role logicaldoc in database logicaldoc reset lock_timeout" postgres
check "the app's role has no setting in its database" test "$(role_settings logicaldoc)" = 0
before=$(conf_sha)
start=$SECONDS
out=$(init -e DB_HOST=ldtest-nowhere -e DB_WAIT_SECONDS=120 2>&1) && rc=0 || rc=$?
check "exits 1" test "$rc" = 1
check "says the data is in MariaDB, to move it first, and where that's described" grep -qF "this install's conf volume names MariaDB as its database, so its data is in MariaDB, and the PostgreSQL database that DB_HOST, DB_PORT and DB_NAME name didn't answer within 15 seconds or holds no finished move. Healdan now runs on PostgreSQL only, so this start stopped and changed nothing. Move the data across first with the move-from-mariadb command, as Moving from MariaDB to PostgreSQL describes: $GUARD_MOVE_PAGE" <<<"$out"
check "waits 15 seconds for an answer, not DB_WAIT_SECONDS" test $((SECONDS - start)) -lt 60
check "leaves the conf volume as it was" conf_unchanged "$before"

echo "== 8b. a conf volume that names MariaDB, with PostgreSQL answering and holding no move: move first"
tables_before=$(table_names logicaldoc)
out=$(init 2>&1) && rc=0 || rc=$?
check "exits 1" test "$rc" = 1
check "says to move first" grep -qF "names MariaDB as its database, so its data is in MariaDB" <<<"$out"
check "leaves the conf volume as it was" conf_unchanged "$before"
check "and the database: the same tables, and no setting for the role" untouched "$tables_before"

echo "== 8c. a move that didn't finish: run it again"
db_sql "create table docker_move (started timestamp not null, finished timestamp)"
db_sql "insert into docker_move (started) values (current_timestamp)"
tables_before=$(table_names logicaldoc)
out=$(init 2>&1) && rc=0 || rc=$?
check "exits 1, with the conf naming MariaDB" test "$rc" = 1
check "says to run the move again" grep -qF "the database holds a move from MariaDB that didn't finish: it failed, or was stopped part-way. Healdan won't start on it, so this start stopped and changed nothing. Run the move-from-mariadb command again, which starts the move over, as Moving from MariaDB to PostgreSQL describes: $GUARD_MOVE_PAGE" <<<"$out"
check "leaves the conf volume as it was" conf_unchanged "$before"
check "and the database: the same tables, and no setting for the role" untouched "$tables_before"

echo "== 8d. a finished move: the start goes on, and puts the conf volume's database keys right"
db_sql "update docker_move set finished = current_timestamp"
out=$(init 2>&1) && rc=0 || rc=$?
check "exits 0" test "$rc" = 0
check "skips initialization" grep -q 'schema present, skipping' <<<"$out"
check "sets PostgreSQL's driver, dbms and dialect" grep -qF "set Healdan's own: hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect, jdbc.dbms=postgresql, jdbc.driver=org.postgresql.Driver" <<<"$out"
check "the conf names PostgreSQL's driver again" test "$(conf_key jdbc.driver)" = org.postgresql.Driver
check "and PostgreSQL as its dbms" test "$(conf_key jdbc.dbms)" = postgresql
check "and the URL of the PostgreSQL it started on" \
  test "$(conf_key jdbc.url)" = "jdbc:postgresql://$DB:5432/logicaldoc?logServerErrorDetail=false"
check "gives the app's role its lock_timeout in its database again" test "$(db_sql 'show lock_timeout')" = 50s

echo "== 8e. a conf volume that names PostgreSQL, with an unfinished move: run it again"
db_sql "update docker_move set finished = null"
db_admin "alter role logicaldoc in database logicaldoc reset lock_timeout" postgres
before=$(conf_sha)
tables_before=$(table_names logicaldoc)
out=$(init 2>&1) && rc=0 || rc=$?
check "exits 1" test "$rc" = 1
check "says to run the move again" grep -qF "the database holds a move from MariaDB that didn't finish" <<<"$out"
check "leaves the conf volume as it was" conf_unchanged "$before"
check "and the database: the same tables, and no setting for the role" untouched "$tables_before"
db_sql "drop table docker_move"

echo "== 8f. an empty database beside a repository that holds an install's files: restore first"
db_admin "create database emptydb owner logicaldoc template template0 encoding 'UTF8' locale_provider builtin builtin_locale 'C.UTF-8' locale 'C'" postgres
docker run --rm -v "$REPO:/r" "$TOOLS" sh -c 'mkdir -p /r/docs && touch /r/docs/.healdan-backend && chown -R 1000:1000 /r/docs'
before=$(conf_sha)
out=$(init -e DB_NAME=emptydb 2>&1) && rc=0 || rc=$?
check "exits 1" test "$rc" = 1
check "says to restore the database first, and where that's described" grep -qF "the database is empty, but the repository volume already holds an install's files." <<<"$out"
check "names the restore's page" grep -qF "Restore the database first, as Restoring onto a Fresh Stack describes: https://docs.healdan.com/install/backup-and-restore/#restoring-onto-a-fresh-stack" <<<"$out"
check "creates no table" test "$(tables emptydb)" = 0
check "sets no lock_timeout for the role in it" test "$(role_settings emptydb)" = 0
check "leaves the conf volume as it was" conf_unchanged "$before"
docker run --rm -v "$REPO:/r" "$TOOLS" rm -rf /r/docs

echo "== 9. a first start that did not finish is not mistaken for a complete schema"
db_sql "drop table if exists docker_init"
out=$(init 2>&1) && rc=0 || rc=$?
check "exits 1" test "$rc" = 1
check "says the earlier initialization did not finish" grep -q 'initialization did not finish' <<<"$out"
check "does not start on the half-built schema" lacks 'schema present, skipping' "$out"

finish
