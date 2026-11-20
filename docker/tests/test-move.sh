#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 The Healdan Project
# SPDX-License-Identifier: LGPL-3.0-only

# The move from MariaDB (PostgreSQL spec section 10.2): moves the frozen fixture in docker/tests/move-fixture with the
# move-from-mariadb command, as the Install manual gives it, in each way that matters, then starts the app on the
# result. Runs as its own Compose project and removes everything it made when it ends. Usage: docker/tests/test-move.sh
# LOGICALDOC_IMAGE names the image (logicaldoc-community:local, which docker compose build tags), MOVE_PROJECT the
# Compose project (healdan-move) and MOVE_PORT the app's port on 127.0.0.1 (18090).
#
# The fixture is loaded once, and the cases run in the order a move would meet them: the app's start refused before
# any move (its conf volume names MariaDB), refused (an older source), stopped part-way (a zero date) with the app's
# start refused on what it left, stopped again (a NUL), stopped by its own comparison (a value changed on the target),
# and run again to the end with --replace-nul. Then the report, the data, every table's rows counted on both sides,
# and the app on it.
set -euo pipefail
cd "$(dirname "$0")/../.."
. docker/tests/lib.sh

PROJECT=${MOVE_PROJECT:-healdan-move}
IMAGE=${LOGICALDOC_IMAGE:-logicaldoc-community:local}
PORT=${MOVE_PORT:-18090}
BASE="http://127.0.0.1:$PORT"
FIXTURE=docker/tests/move-fixture
started=$SECONDS

# The refusals come before this run claims anything, and it shows them all before it stops.
refused=0
if ! [[ $PROJECT =~ ^[a-z0-9]+([-_][a-z0-9]+)*$ ]]; then
  echo "MOVE_PROJECT '$PROJECT' isn't a project name this script takes: lowercase letters and digits, joined by - or _"
  refused=1
fi
# As smoke-test.sh's: a claimed project is another run's, or one stopped before it could remove its stack, and a
# project with containers or volumes and no claim is another stack, which this script never offers to remove.
CLAIM=$PROJECT-claim
if [ -n "$(docker ps -aq --filter "label=healdan.move-claim=$PROJECT")" ]; then
  echo "Another move test is using Compose project $PROJECT, or one stopped before it could remove its stack. Set"
  echo "MOVE_PROJECT to another name, or, once no run is using it, remove it:"
  echo "  docker rm -f $CLAIM && (cd / && docker compose -p $PROJECT down -v --remove-orphans)"
  refused=1
elif [ -n "$(docker ps -aq --filter "label=com.docker.compose.project=$PROJECT"
  docker volume ls -q --filter "label=com.docker.compose.project=$PROJECT")" ]; then
  echo "Compose project $PROJECT has containers or volumes that no move test claimed: it's another stack, which this"
  echo "script leaves alone. Set MOVE_PROJECT to another name."
  refused=1
fi
if ! [[ $PORT =~ ^[0-9]{1,5}$ ]] || [ "$PORT" -lt 1 ] || [ "$PORT" -gt 65535 ]; then
  echo "MOVE_PORT '$PORT' isn't a port number: set it to one from 1 to 65535"
  refused=1
elif (exec 3<>"/dev/tcp/127.0.0.1/$PORT") 2>/dev/null; then
  echo "Port $PORT on 127.0.0.1 is in use, by another move test or something else: set MOVE_PORT to a free one"
  refused=1
fi
if ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
  echo "There's no image $IMAGE: build one (docker compose build tags logicaldoc-community:local), or name one with"
  echo "LOGICALDOC_IMAGE"
  refused=1
fi
for f in mariadb.sql.gz volumes.tgz expected.env; do
  [ -s "$FIXTURE/$f" ] || { echo "The fixture has no $FIXTURE/$f"; refused=1; }
done
[ "$refused" = 0 ] || exit 1

# What make.sh recorded of the fixture: doc #s, ids, the files' SHA-256s, row counts, dates and the like
. "$FIXTURE/expected.env"

WORK=$(mktemp -d)
ENV_FILE=$WORK/move.env
# Not the fixture's password: the start-up tool puts the environment's into the conf volume at every start.
printf 'DB_PASSWORD=move-test-pw\nLOGICALDOC_IMAGE=%s\nLOGICALDOC_PORT=%s\n' "$IMAGE" "$PORT" > "$ENV_FILE"
# The stack logs in UTC, as do the FAIL lines (docker/tests/lib.sh), even when the shell sets another zone.
export TZ=UTC
# Each service's health checked every second or two while it starts, not every 10 or 30 seconds, so a wait ends when
# the service is ready. Nothing else about the stack changes. Compose needs the start period beside the interval: the
# app's is the Dockerfile's.
cat > "$WORK/fast.yaml" <<'YAML'
services:
  db:
    healthcheck:
      start_interval: 1s
  app:
    healthcheck:
      start_period: 300s
      start_interval: 2s
YAML
# An admin's MariaDB volume holds a database already. The test's starts empty, and the image makes one there only with
# a root password option: a random one, as the old compose.yaml gave it.
cat > "$WORK/move-mariadb.yaml" <<'YAML'
services:
  mariadb:
    environment:
      MARIADB_RANDOM_ROOT_PASSWORD: "1"
    healthcheck:
      start_interval: 1s
YAML
# The stack as the Install manual's Moving from MariaDB runs it: with compose.move.yaml for the move, and without it
# for the app. The app's compose leaves the overlay's MariaDB be, as it does when an admin starts the app.
compose_move() {
  docker compose -p "$PROJECT" --env-file "$ENV_FILE" -f compose.yaml -f compose.move.yaml -f "$WORK/fast.yaml" \
    -f "$WORK/move-mariadb.yaml" "$@"
}
compose_app() {
  COMPOSE_IGNORE_ORPHANS=true docker compose -p "$PROJECT" --env-file "$ENV_FILE" -f compose.yaml -f "$WORK/fast.yaml" \
    "$@"
}
cleanup() {
  local status=$?
  # Nothing here may stop the stack's removal.
  set +e
  if [ "$status" != 0 ] || [ "$FAILED" != 0 ]; then
    echo "== the app container's last 100 lines of output"
    compose_app logs --no-log-prefix --tail 100 app
    echo "== PostgreSQL's last 40 lines"
    compose_move logs --no-log-prefix --tail 40 db
    echo "== MariaDB's last 20 lines"
    compose_move logs --no-log-prefix --tail 20 mariadb
  fi
  compose_move down -v --remove-orphans >/dev/null 2>&1
  docker rm -f "$CLAIM" >/dev/null 2>&1
  rm -rf "$WORK"
}
# The claim, as smoke-test.sh's: a container named for the project, which Docker makes only once. Running, it holds
# the app's port until the app's first start; stopped, it claims the project until cleanup removes it.
if ! docker create --name "$CLAIM" --label "healdan.move-claim=$PROJECT" -p "127.0.0.1:$PORT:80" busybox:1.37 \
  sleep 86400 >/dev/null; then
  echo "Couldn't claim Compose project $PROJECT with a container named $CLAIM (Docker's message above): another run"
  echo "may have claimed it just now. Set MOVE_PROJECT to another name."
  rm -rf "$WORK"
  exit 1
fi
if ! docker start "$CLAIM" >/dev/null; then
  docker rm -f "$CLAIM" >/dev/null
  echo "Docker couldn't publish port $PORT (its message above): set MOVE_PORT to a free one"
  rm -rf "$WORK"
  exit 1
fi
trap cleanup EXIT

# healthy <container> <timeout seconds>: as lib.sh's wait_healthy, looking every second, and giving up on a container
# that stopped, or that its restart policy is starting again
healthy() {
  local deadline=$((SECONDS + $2)) state
  while :; do
    state=$(docker inspect -f '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' \
      "$1" 2>/dev/null || echo missing)
    case $state in
      *' healthy') return 0 ;;
      exited* | dead* | restarting* | *' none' | missing) echo "container '$1' is $state"; return 1 ;;
    esac
    [ "$SECONDS" -lt "$deadline" ] || { echo "container $1 is '$state' after $2 s"; return 1; }
    sleep 1
  done
}
# maria <SQL>: the fixture's MariaDB, rows tab-separated without headers
maria() {
  compose_move exec -T mariadb sh -c 'MYSQL_PWD="$MARIADB_PASSWORD" mariadb -u"$MARIADB_USER" "$MARIADB_DATABASE" \
    --default-character-set=utf8mb4 -N -B' <<<"$1"
}
# pg <SQL>: the target, as PostgreSQL's superuser over the container's socket, columns separated by |
pg() { compose_move exec -T db psql -U postgres -d logicaldoc -X -A -t -v ON_ERROR_STOP=1 -c "$1"; }
# pg_script: the SQL on stdin, as pg runs its SQL
pg_script() { compose_move exec -T db psql -U postgres -d logicaldoc -X -q -v ON_ERROR_STOP=1 -f -; }
sha256() { if command -v sha256sum >/dev/null; then sha256sum "$1"; else shasum -a 256 "$1"; fi | cut -d' ' -f1; }
# sums <file>: every MariaDB table's checksum (CHECKSUM TABLE, over every row), then every sequence's next value
sums() {
  maria "checksum table $(cat "$WORK/tables")" > "$1"
  maria "$(cat "$WORK/sequences")" >> "$1"
}
# move <name> [option]: runs the command as the Install manual gives it, keeps its own lines ([move] ...) in
# $WORK/<name>.out and its exit status in $WORK/<name>.status, and prints both. MariaDB's checksums before and after
# it go in $WORK/<name>.maria-before and -after. Compose's own progress (its containers made and started, an image
# pulled), which it prints in another form off a terminal and from one version to another, is left out of what's
# captured, so the capture holds the command's output alone.
move() {
  local name=$1 status=0
  shift
  sums "$WORK/$name.maria-before"
  compose_move --progress quiet run --rm -T app move-from-mariadb "$@" > "$WORK/$name.raw" 2>&1 || status=$?
  sums "$WORK/$name.maria-after"
  grep '^\[move\]' "$WORK/$name.raw" > "$WORK/$name.out" || true
  echo "$status" > "$WORK/$name.status"
  cat "$WORK/$name.out"
  # A command that never ran, such as one Compose couldn't start, says why in Compose's lines
  [ -s "$WORK/$name.out" ] || tail -n 20 "$WORK/$name.raw"
  echo "(exit status $status)"
}
# is <expected> <actual>: they're the same; else both, for the FAIL line under them
is() {
  [ "$1" = "$2" ] && return 0
  echo "  expected: $1"
  echo "  got:      $2"
  return 1
}
exited() { is "$2" "$(cat "$WORK/$1.status")"; }
# alone <name>: the run printed its own lines and nothing else, no stack trace or logger's line beside them, but for
# the empty line Compose ends a container's error output with
alone() {
  local rest traces
  rest=$(grep -vE '^\[move\]|^$' "$WORK/$1.raw" || true)
  traces=$(grep -E 'Exception|^[[:space:]]+at ' "$WORK/$1.out" || true)
  [ -z "$rest$traces" ] && return 0
  [ -z "$rest" ] || head -20 <<<"$rest"
  [ -z "$traces" ] || head -20 <<<"$traces"
  return 1
}
# says <name> <line>: the command printed exactly that line
says() { grep -qxF -- "$2" "$WORK/$1.out"; }
# unfinished: the target holds one move, unfinished (the count unfinished, then all)
unfinished() { is "1|1" "$(pg "select count(*) filter (where finished is null), count(*) from docker_move")"; }
# snapshot <name>: each file and folder of the conf and repository volumes, with its type, mode, owner, size and time,
# then each file's SHA-256, read through read-only mounts. It stops the test unless it lists the fixture's keys.
snapshot() {
  docker run --rm -v "${PROJECT}_logicaldoc-conf:/v/conf:ro" -v "${PROJECT}_logicaldoc-repository:/v/repository:ro" \
    --entrypoint sh "$IMAGE" -c 'cd /v && find conf repository -printf "%y %m %U:%G %s %T@ %p\n" | LC_ALL=C sort &&
      find conf repository -type f -print0 | LC_ALL=C sort -z | xargs -0 -r sha256sum' > "$WORK/$1"
  if ! grep -q "  conf/healdan-storage-keys/backend-$LOCAL_BACKEND_ID\.key$" "$WORK/$1" \
    || ! grep -q '  conf/healdan-secret\.key$' "$WORK/$1"; then
    fail "the conf and repository volumes couldn't be read whole ($1)"
    exit 1
  fi
}
# same <name> <name>: two snapshots, or two lists, are the same; else their first differences
same() {
  cmp -s "$WORK/$1" "$WORK/$2" && return 0
  diff "$WORK/$1" "$WORK/$2" | head -20
  return 1
}
# conf_sha: the SHA-256 of the conf volume's context.properties, read through a read-only mount
conf_sha() {
  docker run --rm -v "${PROJECT}_logicaldoc-conf:/c:ro" --entrypoint sha256sum "$IMAGE" /c/context.properties \
    | cut -d' ' -f1
}
# refused_start: starts the app as an admin's docker compose up -d does, and waits, looking every second, for the
# start-up tool to stop it; its container is $app. Refused, the restart policy would start it again, refused each time:
# without it, it stays stopped, and says how.
refused_start() {
  compose_app up -d app
  app=$(compose_app ps -aq app)
  docker update --restart=no "$app" >/dev/null
  local deadline=$((SECONDS + 120))
  until [ "$(docker inspect -f '{{.State.Status}}' "$app")" = exited ] || [ "$SECONDS" -ge "$deadline" ]; do sleep 1; done
}
app_said() { docker logs "$app" 2>&1 | grep -F -- "$1" >/dev/null; }

echo "== 1. the fixture: its MariaDB from the dump, and its conf and repository volumes"
compose_move up -d mariadb
check "MariaDB healthy" healthy "$(compose_move ps -q mariadb)" 300
[ "$FAILED" = 0 ] || exit 1
loaded=$SECONDS
load_dump() {
  gzip -dc "$FIXTURE/mariadb.sql.gz" | compose_move exec -T mariadb sh -c \
    'MYSQL_PWD="$MARIADB_PASSWORD" mariadb -u"$MARIADB_USER" "$MARIADB_DATABASE"'
}
check "the dump loads" load_dump
# Into the volumes the app's service mounts, which Compose makes as its own, with owners, modes and times as they were
unpack() {
  compose_move run --rm -T --no-deps --entrypoint tar app -C /opt/logicaldoc --numeric-owner -xzpf - \
    < "$FIXTURE/volumes.tgz"
}
check "the volumes unpack" unpack
echo "loaded in $((SECONDS - loaded)) s"
maria "select concat('\`', table_name, '\`') from information_schema.tables where table_schema = database()
  and table_type = 'BASE TABLE' order by table_name" | paste -sd, - > "$WORK/tables"
maria "select concat('select ''', table_name, ''', next_not_cached_value from \`', table_name, '\`')
  from information_schema.tables where table_schema = database() and table_type = 'SEQUENCE' order by table_name" \
  | sed '1!s/^/union all /' > "$WORK/sequences"
check "MariaDB holds the fixture's $COUNT_ld_document documents, and the documents' sequence as it was" \
  is "$COUNT_ld_document"$'\t'"$DOCUMENT_SEQ_NEXT" \
  "$(maria "select count(*), (select next_not_cached_value from ld_document_SEQ) from ld_document")"
[ "$FAILED" = 0 ] || exit 1

echo "== 1a. the app started before the move: refused, as its conf volume names MariaDB and PostgreSQL holds no move"
# The upgrade without the move that the Install manual warns of, on the fixture's volumes and an empty PostgreSQL
conf_before=$(conf_sha)
# The claim has held the port, which the app takes now
docker kill "$CLAIM" >/dev/null 2>&1 || true
refused_at=$SECONDS
refused_start
check "the app's start stopped with status 1" \
  is "exited 1" "$(docker inspect -f '{{.State.Status}} {{.State.ExitCode}}' "$app")"
check "the start-up tool says to move the data first" app_said "[docker-init] ERROR: this install's conf volume names MariaDB as its database, so its data is in MariaDB, and the PostgreSQL database that DB_HOST, DB_PORT and DB_NAME name didn't answer within 15 seconds or holds no finished move. Healdan now runs on PostgreSQL only, so this start stopped and changed nothing. Move the data across first with the move-from-mariadb command"
check "Tomcat never started" lacks 'starting: catalina.sh run' "$(docker logs "$app" 2>&1)"
check "the conf volume's context.properties is as it was" is "$conf_before" "$(conf_sha)"
check "PostgreSQL's database holds no table" \
  is 0 "$(pg "select count(*) from pg_class where relnamespace = 'public'::regnamespace")"
compose_app rm -sf app >/dev/null 2>&1
echo "the refused start took $((SECONDS - refused_at)) s, PostgreSQL's first start included"
# After the refused start, whose entrypoint makes the repository's folders and re-adds the image's allowed commands
# before the start-up tool's guard: the snapshots compare what the moves do
snapshot volumes-0

echo "== 2. refused: a source at an older Healdan schema version"
rank=$(maria "select max(installed_rank) from hd_schema_history")
older_version=$(maria "select version from hd_schema_history where installed_rank < $rank order by installed_rank desc
  limit 1")
# Its last row, kept to put back: the next cases need the source whole
compose_move exec -T mariadb sh -c 'MYSQL_PWD="$MARIADB_PASSWORD" mariadb-dump -u"$MARIADB_USER" --no-create-info \
  --skip-triggers --compact --where="installed_rank = $1" "$MARIADB_DATABASE" hd_schema_history' sh "$rank" \
  > "$WORK/history-row.sql"
maria "delete from hd_schema_history where installed_rank = $rank"
move older
check "the move exits 1" exited older 1
check "it says the source's schema is at version $older_version, names the release tagged mariadb-final, and changed nothing" \
  grep -qE "^\[move\] ERROR: the source's Healdan schema is at version $older_version, and the move takes only version [0-9]+, the last on MariaDB\. .*the release tagged mariadb-final.*Nothing was changed\.$" \
  "$WORK/older.out"
check "it printed nothing else" alone older
check "the target has no table" is 0 "$(pg "select count(*) from pg_class where relnamespace = 'public'::regnamespace")"
compose_move exec -T mariadb sh -c 'MYSQL_PWD="$MARIADB_PASSWORD" mariadb -u"$MARIADB_USER" "$MARIADB_DATABASE"' \
  < "$WORK/history-row.sql"
check "the source's history is whole again" is "$rank" "$(maria "select max(installed_rank) from hd_schema_history")"

echo "== 3. stopped part-way by a value PostgreSQL can't hold, and the app refusing to start on what it left"
# A zero date, which MariaDB takes only with its strict modes off
maria "set session sql_mode = ''; update ld_version set ld_versiondate = '0000-00-00 00:00:00' where ld_id = $VERSION_ID"
move zero
check "the move exits 1" exited zero 1
check "it names the table, the row's key and the column" says zero \
  "[move]     table ld_version, row ld_id=$VERSION_ID, column ld_versiondate: the value is MariaDB's zero date, which PostgreSQL can't hold"
check "it says why it stopped" \
  grep -qF "[move] ERROR: 1 value in MariaDB can't go into PostgreSQL as it is, so the move stopped" "$WORK/zero.out"
check "the target holds an unfinished move" unfinished
check "nothing of the table it stopped at is in the target" is 0 "$(pg "select count(*) from ld_version")"
check "a table copied before it holds its rows: the target holds what a rerun must clear" \
  is "$COUNT_ld_document" "$(pg "select count(*) from ld_document")"
check "it printed nothing else" alone zero
snapshot volumes-1
check "the moves so far changed nothing in the conf and repository volumes" same volumes-0 volumes-1

refused_start
check "the app's start stopped with status 1" \
  is "exited 1" "$(docker inspect -f '{{.State.Status}} {{.State.ExitCode}}' "$app")"
check "the start-up tool says to run the move again" app_said "[docker-init] ERROR: the database holds a move from MariaDB that didn't finish: it failed, or was stopped part-way. Healdan won't start on it, so this start stopped and changed nothing. Run the move-from-mariadb command again, which starts the move over"
check "Tomcat never started" lacks 'starting: catalina.sh run' "$(docker logs "$app" 2>&1)"
conf_url() {
  docker run --rm -v "${PROJECT}_logicaldoc-conf:/c:ro" --entrypoint grep "$IMAGE" -a '^jdbc\.url=' /c/context.properties
}
check "the conf volume still names MariaDB" grep -q '^jdbc\.url=jdbc:mariadb:' <<<"$(conf_url)"
compose_app rm -sf app >/dev/null 2>&1

echo "== 3a. a NUL, which stops the move unless --replace-nul is given"
maria "update ld_version set ld_versiondate = '$VERSION_DATE' where ld_id = $VERSION_ID;
  update ld_document set ld_comment = concat('Quarterly', char(0), 'Report') where ld_id = $NUL_DOC_ID"
snapshot volumes-2
move nul
check "the move exits 1" exited nul 1
check "it names the place" says nul \
  "[move]     table ld_document, row ld_id=$NUL_DOC_ID, column ld_comment: the text holds a NUL character, which PostgreSQL's text types can't hold"
check "it says --replace-nul would replace it" grep -qF \
  "[move] ERROR: 1 value in MariaDB holds a NUL character, which PostgreSQL's text can't hold, so the move stopped" \
  "$WORK/nul.out"
check "the target holds an unfinished move" unfinished
check "it printed nothing else" alone nul

echo "== 3b. stopped by its own comparison: a value changed on the target, which only the comparison can see"
# As PostgreSQL's superuser, in a schema of the test's own, so that public stays empty: when the move creates hd_matter,
# an event trigger gives it a row trigger that reverses each name inserted. The value is still storable, so the copy and
# its inserts go through, and only the comparison of what was carried with what the target holds can object.
pg_script <<'SQL'
create schema movetest;
grant usage on schema movetest to public;
create function movetest.reversed() returns trigger language plpgsql as $$
begin
  new.hd_name := reverse(new.hd_name);
  return new;
end $$;
create function movetest.attach() returns event_trigger language plpgsql as $$
begin
  if exists (select 1 from pg_event_trigger_ddl_commands() where object_identity = 'public.hd_matter') then
    execute 'create trigger movetest_reversed before insert on public.hd_matter for each row'
      ' execute function movetest.reversed()';
  end if;
end $$;
create event trigger movetest_attach on ddl_command_end when tag in ('CREATE TABLE') execute function movetest.attach();
SQL
move compare --replace-nul
pg_script <<'SQL'
set client_min_messages = warning;
drop event trigger movetest_attach;
drop schema movetest cascade;
SQL
check "the move exits 1" exited compare 1
rebuilt=$(sed -n 's/^\[move\] Foreign keys rebuilt: \([0-9]*\) of \([0-9]*\)$/\1 \2/p' "$WORK/compare.out")
check "it got as far as the comparison: every table copied, every foreign key rebuilt ($rebuilt)" \
  sh -c 'test $# = 2 && test "$1" = "$2" && test "$1" -gt 0' sh $rebuilt
check "the comparison refuses" says compare "[move] ERROR: the target doesn't hold what the source holds, so the move isn't finished. Run it again, which starts over; if it fails the same way, keep both databases as they are and report it:"
check "naming hd_matter alone, and none of its values" \
  is "[move]   table hd_matter: both have $COUNT_hd_matter rows, but their values differ" \
  "$(grep -E '^\[move\]   table .*(rows, but their values differ|rows, and the target [0-9]+)$' "$WORK/compare.out")"
named_none() {
  local name
  for name in "Smoke Matter" "Second Matter" "$MATTER_NAME" "rettaM ekomS" "rettaM dnoceS" "rellüM tôpéD"; do
    if grep -F -- "$name" "$WORK/compare.out" >/dev/null; then echo "  it printed '$name'"; return 1; fi
  done
}
check "no matter's name, as it was or reversed, is in what it printed" named_none
check "the target holds an unfinished move" unfinished
check "it printed nothing else" alone compare

echo "== 3c. run again, the trigger gone, with --replace-nul: it starts over, and finishes"
move done --replace-nul
check "the move exits 0" exited done 0
check "it started over, removing what the unfinished move made" grep -qE \
  "^\[move\] Target: PostgreSQL [0-9.]+, an unfinished move's tables removed \.\.\. ok$" "$WORK/done.out"
check "it printed nothing else" alone done
check "the target holds one move, finished" \
  is "1|1" "$(pg "select count(*) filter (where finished is not null), count(*) from docker_move")"
# The app's own start is the smoke test's to watch: up to here, PostgreSQL has seen the moves, the test's trigger, and
# the start-up tool's look at what the zero date's move left.
compose_move logs --no-log-prefix db > "$WORK/db.log" 2>&1
pg_log() {
  grep -F 'database system is ready to accept connections' "$WORK/db.log" >/dev/null \
    || { echo "  it has no line saying the server is ready to accept connections: it wasn't read"; return 1; }
  is "" "$(grep 'ERROR:' "$WORK/db.log" || true)"
}
check "PostgreSQL's log has no ERROR line" pg_log
snapshot volumes-3
check "the moves changed nothing in the conf and repository volumes" same volumes-2 volumes-3

echo "== 4. the report"
cat > "$WORK/report-end" <<EOF
[move] Only the source has these, which weren't copied:
[move]   column ld_uniquetag.ld_int1
[move] A space replaced each NUL in 1 value (--replace-nul):
[move]   table ld_document, row ld_id=$NUL_DOC_ID, column ld_comment
[move] Move complete.
EOF
tail -n 5 "$WORK/done.out" > "$WORK/done-end"
check "it ends with what only the source has (ld_uniquetag.ld_int1 alone), the NUL it replaced, and Move complete." \
  same report-end done-end
compared=$(sed -n 's/^\[move\] Comparing: rows and checksums match in \([0-9]*\) of \([0-9]*\) tables$/\1 \2/p' \
  "$WORK/done.out")
check "rows and checksums match in every table it copied ($compared)" \
  sh -c 'test $# = 2 && test "$1" = "$2" && test "$1" -gt 70' sh $compared
check "no counter can hand out an id its table holds" \
  says done "[move] Counters: none can hand out an id its table already holds ... ok"

echo "== 5. the data, by psql"
# Every table counted on both sides, apart from the move's own report: one query a side, each made from its catalogue,
# a line per table, its name in lower case and its rows. Flyway's history and the markers are each side's own, as the
# move has them (MovePlan.OWN): MariaDB has the start-up tool's marker too.
maria "select concat('select ''', lower(table_name), ''', count(*) from \`', table_name, '\`')
  from information_schema.tables where table_schema = database() and table_type = 'BASE TABLE'
  and lower(table_name) not in ('hd_schema_history', 'docker_init', 'docker_move') order by table_name" \
  | sed '1!s/^/union all /' > "$WORK/maria-counts.sql"
maria "$(cat "$WORK/maria-counts.sql")" | LC_ALL=C sort > "$WORK/counts-maria"
pg "select string_agg(format('select %L, count(*) from %I', lower(tablename), tablename), ' union all '
  order by tablename) from pg_tables where schemaname = 'public'
  and tablename not in ('hd_schema_history', 'docker_init', 'docker_move')" > "$WORK/pg-counts.sql"
pg "$(cat "$WORK/pg-counts.sql")" | tr '|' '\t' | LC_ALL=C sort > "$WORK/counts-pg"
counted=$(wc -l < "$WORK/counts-maria" | tr -d ' ')
check "MariaDB's tables were all counted ($counted)" test "$counted" -gt 70
check "every table has as many rows on PostgreSQL as on MariaDB, and neither side has a table the other lacks" \
  same counts-maria counts-pg
for t in ld_document ld_version ld_history ld_user hd_matter hd_trash hd_storage_file hd_email hd_admin_event \
  hd_error hd_error_hour; do
  eval "want=\$COUNT_$t"
  check "$t has its $want rows" is "$want" "$(pg "select count(*) from $t")"
done
check "apiuser's avatar is its 6,000 characters" is "6000|$AVATAR_SHA256" "$(pg "select length(ld_avatar),
  encode(sha256(convert_to(ld_avatar, 'UTF8')), 'hex') from ld_user where ld_username = 'apiuser'")"
when() { pg "select to_char($1, 'YYYY-MM-DD HH24:MI:SS.US') from $2 where ld_id = $3"; }
check "a history date at .069 is .069" is '2026-11-19 10:15:30.069000' "$(when ld_date ld_history "$DATE_069_ID")"
check "one at .760 is .760" is '2026-11-19 10:15:31.760000' "$(when ld_date ld_history "$DATE_760_ID")"
check "one at .000 is .000" is '2026-11-19 10:15:32.000000' "$(when ld_date ld_history "$DATE_000_ID")"
check "a plain datetime, mended after the zero date, is as it was" \
  is "$VERSION_DATE.000000" "$(when ld_versiondate ld_version "$VERSION_ID")"
# The Admin app's tables, V12's: an error's datetime(3)s, its accented message and its fingerprint, a char(64) that
# PostgreSQL would pad were it shorter; its hours' counts, whose key is the error and a plain datetime; and the two
# identity columns, whose sequences must be past every id moved
check "the error's dates are to the millisecond, .069 as .069" \
  is '2026-11-19 10:15:34.069000|2026-11-19 11:20:35.760000|2026-11-19 12:00:00.000000' \
  "$(pg "select to_char(hd_firstseen, 'YYYY-MM-DD HH24:MI:SS.US'), to_char(hd_lastseen, 'YYYY-MM-DD HH24:MI:SS.US'),
    to_char(hd_resolvedat, 'YYYY-MM-DD HH24:MI:SS.US') from hd_error where hd_id = $ERROR_ID")"
check "its accented message, its fingerprint and who resolved it are as they were" \
  is "$ERROR_MESSAGE|64|$ERROR_FINGERPRINT|1|admin" \
  "$(pg "select hd_message, length(hd_fingerprint), hd_fingerprint, hd_resolved, hd_resolvedby from hd_error
    where hd_id = $ERROR_ID")"
check "its count for each hour is as it was" is '2026-11-19 10:00:00=2,2026-11-19 11:00:00=1' \
  "$(pg "select string_agg(to_char(hd_hour, 'YYYY-MM-DD HH24:MI:SS') || '=' || hd_count, ',' order by hd_hour)
    from hd_error_hour where hd_errorid = $ERROR_ID")"
check "the administration record's and the errors' identity columns go on after ids $MAX_ADMIN_EVENT_ID and $MAX_ERROR_ID" \
  is "t|t" "$(pg "select pg_sequence_last_value(pg_get_serial_sequence('hd_admin_event', 'hd_id')::regclass)
    >= $MAX_ADMIN_EVENT_ID, pg_sequence_last_value(pg_get_serial_sequence('hd_error', 'hd_id')::regclass) >= $MAX_ERROR_ID")"
check "the NUL became a space" is 'Quarterly Report' "$(pg "select ld_comment from ld_document where ld_id = $NUL_DOC_ID")"
check "matter 1.$MATTER_NUMBER's accented name is as it was" \
  is "$MATTER_NAME" "$(pg "select hd_name from hd_matter where hd_number = $MATTER_NUMBER")"
check "the documents' sequence goes on from MariaDB's next value" \
  is "$DOCUMENT_SEQ_NEXT|f" "$(pg "select last_value, is_called from ld_document_seq")"

echo "== 7a. MariaDB, untouched by the moves"
for name in older zero nul compare done; do
  check "every table's checksum and every sequence's next value are as the $name move found them" \
    same "$name.maria-before" "$name.maria-after"
done
check "ld_document's row count is as it was" is "$COUNT_ld_document" "$(maria "select count(*) from ld_document")"

echo "== 6. the app on the moved database"
compose_move stop mariadb
app_started=$SECONDS
compose_app up -d
# The checks below need it, and earlier failures don't stop them
if healthy "$(compose_app ps -q app)" 900; then pass "app healthy"; else fail "app healthy"; exit 1; fi
echo "the app started in $((SECONDS - app_started)) s"
app=$(compose_app ps -q app)
check "the start-up tool found the moved schema" app_said 'schema present, skipping database initialization'
TOKEN=$(curl -fsS -H 'Content-Type: application/json' \
  -d '{"username": "apiuser", "password": "'"$APIUSER_PASSWORD"'", "name": "Move test", "device": {"id": "move-test"}}' \
  "$BASE/api/v1/auth/tokens" | jq -r '.token // empty' || true)
check "apiuser signs in with the fixture's password" test -n "$TOKEN"
AUTH=(-H "Authorization: Bearer $TOKEN")
content_sha() { curl -fsS "${AUTH[@]}" -o "$WORK/content" "$BASE/api/v1/documents/$1/content" && sha256 "$WORK/content"; }
check "doc # $DOC_NUMBER's file, on Original, is the one filed" is "$DOC_SHA256" "$(content_sha "$DOC_NUMBER" || true)"
check "doc # $LOCAL_DOC_NUMBER's file reads back from the encrypted local backend: its key and marker are accepted" \
  is "$LOCAL_DOC_SHA256" "$(content_sha "$LOCAL_DOC_NUMBER" || true)"
check "provider smoke is listed, so its sealed client secret opens with the conf volume's key" \
  is '["smoke"]' "$(curl -fsS "$BASE/api/v1/server" | jq -c '[.providers[].id]' || true)"
sid=$(curl -fsS "$BASE/services/rest/auth/login?u=admin&pw=admin" | tr -d '"[:space:]' || true)
curl -fsS -H "sid: $sid" "$BASE/data/documenthistory.xml?docId=$DOC_ID" > "$WORK/history.xml" || true
check "the app reads doc # $DOC_NUMBER's history dates to the millisecond" sh -c 'for d in "$@"; do
    grep -F "<date>$d</date>" "'"$WORK/history.xml"'" >/dev/null || exit 1; done' sh \
  2026-11-19T10:15:30.069Z 2026-11-19T10:15:31.760Z 2026-11-19T10:15:32.000Z
check "and its version 1.0's date" is 2026-11-19T10:15:33Z "$(curl -fsS "${AUTH[@]}" \
  "$BASE/api/v1/documents/$DOC_NUMBER/versions" | jq -r '.items[] | select(.version == "1.0") | .date' || true)"
printf 'Filed after the move.\n' > "$WORK/after.txt"
filed=$(curl -fsS "${AUTH[@]}" -F "document={\"folderId\": $FOLDER_ID, \"checkOut\": false};type=application/json" \
  -F "file=@$WORK/after.txt;filename=After the move.txt" "$BASE/api/v1/documents" | jq -r '.docNumber // empty' || true)
check "a new document gets doc # $((HIGHEST_DOC_NUMBER + 1))" is "$((HIGHEST_DOC_NUMBER + 1))" "$filed"
ids=$(pg "select d.ld_id, (select max(ld_id) from ld_version where ld_documentid = d.ld_id),
  (select min(ld_id) from ld_history where ld_docid = d.ld_id) from ld_document d where d.ld_customid = '${filed:-0}'")
follow() { # <document id>|<version id>|<history id>
  local IFS='|'
  set -- $1
  test $# = 3 && test "$1" -gt "$MAX_DOCUMENT_ID" && test "$2" -gt "$MAX_VERSION_ID" && test "$3" -gt "$MAX_HISTORY_ID"
}
check "its document, version and history ids follow the fixture's ($ids)" follow "$ids"
trash=$(curl -fsS "${AUTH[@]}" -X DELETE "$BASE/api/v1/documents/${filed:-0}" | jq -r '.id // empty' || true)
check "deleting it answers with Trash item $trash, after the fixture's" test "${trash:-0}" -gt "$MAX_TRASH_ID"
curl -fsS -o /dev/null "${AUTH[@]}" -X DELETE "$BASE/api/v1/auth/tokens/current" || true

echo "== 7b. the MariaDB volume, after the app's start"
check "it's still there" docker volume inspect -f '{{.Name}}' "${PROJECT}_db-data"

echo "The move test took $((SECONDS - started)) s"
finish
