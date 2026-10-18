#!/usr/bin/env bash
# End-to-end acceptance test for the compose stack (spec §10). Runs as its own compose project with its own
# env file and removes everything it created on exit. Usage: docker/smoke-test.sh
# SMOKE_BUILD=0 LOGICALDOC_IMAGE=<image> docker/smoke-test.sh tests an existing image instead of building one
# (CI builds it beforehand with Buildx).
set -euo pipefail
cd "$(dirname "$0")/.."
. docker/tests/lib.sh

PROJECT=logicaldoc-smoke
PORT=${SMOKE_PORT:-18080}
BASE="http://127.0.0.1:$PORT"
WORK=$(mktemp -d)
ENV_FILE=$WORK/smoke.env
# Awkward on purpose: exercises .env quoting, context.properties escaping and the JDBC login.
PW='sm0ke,p=a\ss $x #1 é:! '

# docker/tests/compose.smoke.yaml makes Healdan seed a Store, template, client and matter to upload into.
compose() { docker compose -p "$PROJECT" --env-file "$ENV_FILE" -f compose.yaml -f docker/tests/compose.smoke.yaml "$@"; }
cleanup() {
  [ "$FAILED" = 0 ] || compose logs --tail 150 app || true
  compose down -v --remove-orphans >/dev/null 2>&1 || true
  rm -rf "$WORK"
}
trap cleanup EXIT

printf "DB_PASSWORD='%s'\nLOGICALDOC_PORT=%s\n" "$PW" "$PORT" > "$ENV_FILE"

# Fixtures: a 16x16 PNG and a one-page PDF with a correct xref table.
python3 - "$WORK" <<'PY'
import pathlib, struct, sys, zlib
out = pathlib.Path(sys.argv[1])
def chunk(kind, data):
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
raw = b"".join(b"\x00" + b"\xff\x00\x00" * 16 for _ in range(16))
out.joinpath("smoke.png").write_bytes(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 16, 16, 8, 2, 0, 0, 0))
                                      + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))
content = b"BT /F1 24 Tf 30 100 Td (smoke test) Tj ET"
objects = [
    b"<< /Type /Catalog /Pages 2 0 R >>",
    b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
    b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
    b"<< /Length %d >>\nstream\n" % len(content) + content + b"\nendstream",
    b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
]
pdf = bytearray(b"%PDF-1.4\n")
offsets = []
for number, body in enumerate(objects, 1):
    offsets.append(len(pdf))
    pdf += b"%d 0 obj\n" % number + body + b"\nendobj\n"
xref = len(pdf)
pdf += b"xref\n0 %d\n0000000000 65535 f \n" % (len(objects) + 1)
for offset in offsets:
    pdf += b"%010d 00000 n \n" % offset
pdf += b"trailer\n<< /Size %d /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n" % (len(objects) + 1, xref)
out.joinpath("smoke.pdf").write_bytes(bytes(pdf))
PY

login() { curl -fsS "$BASE/services/rest/auth/login?u=admin&pw=admin" | tr -d '"[:space:]'; }
# The REST endpoints authenticate with HTTP basic auth (they answer 401 to a sid query parameter).
api() { curl -fsS -u admin:admin "$@"; }
# Not grep -q: it exits at the first match and, under pipefail, the SIGPIPE'd compose logs fails the check.
app_logs_have() { compose logs app 2>&1 | grep -- "$1" >/dev/null; }
is_png() { [ "$(od -An -tx1 -N4 "$1" | tr -d ' \n')" = 89504e47 ]; }

echo "== 1. build and start"
if [ "${SMOKE_BUILD:-1}" = 0 ]; then compose up -d --no-build; else compose up -d --build; fi
check "app healthy" wait_healthy "$(compose ps -q app)" 900
[ "$FAILED" = 0 ] || exit 1

echo "== 2. login page"
check "GET /login.jsp is 200" test "$(http_code "$BASE/login.jsp")" = 200

echo "== 3. REST login, default workspace and the Smoke Matter"
sid=$(login)
check "REST login returns a session id" grep -Eq '^[0-9a-f-]{36}$' <<<"$sid"
ws_id=$(api -H 'Accept: application/json' "$BASE/services/rest/folder/getDefaultWorkspace" | jq -r .id)
# getPathString answers like "///Default"; squeeze the slashes.
ws_path=$(api "$BASE/services/rest/folder/getPathString?folderId=$ws_id" | tr -d '"' | tr -s /)
check "default workspace resolved" test -n "$ws_id" -a -n "$ws_path"
# Healdan files documents only inside a matter: Smoke Store > "1.1 - Smoke Matter" > Documents (see SmokeFixtureSeed).
child_id() { # <parent folder id> <jq test applied to the name>
  api -H 'Accept: application/json' "$BASE/services/rest/folder/listChildren?folderId=$1" \
    | jq -r "[.[] | select(.name | $2)][0].id // empty"
}
store_id=$(child_id 5 '. == "Smoke"')
matter_id=$(child_id "$store_id" 'startswith("1.1 ")')
doc_folder=$(child_id "$matter_id" '. == "Documents"')
check "Smoke Matter's Documents folder resolved" grep -Eq '^[0-9]+$' <<<"$doc_folder"
[ "$FAILED" = 0 ] || exit 1
# The matter's folder name has spaces and an en dash, so the path is percent-encoded segment by segment.
doc_path=$(api "$BASE/services/rest/folder/getPathString?folderId=$doc_folder" | tr -d '"' | tr -s / \
  | jq -Rr 'split("/") | map(@uri) | join("/")')

echo "== 4. upload a PDF and a PNG into the matter; thumbnails are generated"
doc_ids=""
for f in smoke.pdf smoke.png; do
  doc_id=$(api -H 'Accept: application/json' -F "folderId=$doc_folder" -F "filename=$f" \
    -F "filedata=@$WORK/$f" "$BASE/services/rest/document/upload")
  check "$f uploaded" grep -Eq '^[0-9]+$' <<<"$doc_id"
  doc_ids="$doc_ids $doc_id"
  api -o "$WORK/$f.thumb" "$BASE/services/rest/document/thumbnail/thumb$doc_path/$f" || true
done
check "PNG thumbnail generated (ImageMagick via allowed-commands.txt)" is_png "$WORK/smoke.png.thumb"
check "Poppler renders the PDF inside the container" compose exec -T app sh -c \
  'cat > /tmp/smoke.pdf && pdftoppm -png -r 50 -f 1 -l 1 /tmp/smoke.pdf /tmp/smoke-render && test -s /tmp/smoke-render-1.png' \
  < "$WORK/smoke.pdf"
check "PDF thumbnail generated (Poppler via allowed-commands.txt)" is_png "$WORK/smoke.pdf.thumb"

echo "== 5. Healdan schema, seeds, doc #s and the filing rule"
db_query() {
  compose exec -T db sh -c 'MYSQL_PWD="$MARIADB_PASSWORD" mariadb -u"$MARIADB_USER" "$MARIADB_DATABASE" -Nse "$1"' sh "$1"
}
check "hd_schema_history has version 1, successful" test \
  "$(db_query "select count(*) from hd_schema_history where version = '1' and success = 1")" = 1
check "seed.profile and seed.everyone recorded" test \
  "$(db_query "select count(*) from hd_setting where name in ('seed.profile', 'seed.everyone')")" = 2
check "smoke-fixture seed recorded" test \
  "$(db_query "select count(*) from hd_setting where name = 'seed.smoke-fixture'")" = 1
# doc_ids is "<pdf id> <png id>", uploaded in that order: doc #s 1 and 2
n=1
for id in $doc_ids; do
  custom=$(api -H 'Accept: application/json' "$BASE/services/rest/document/getDocument?docId=$id" | jq -r .customId)
  check "document $id has doc # $n" test "$custom" = "$n"
  n=$((n + 1))
done
# The filing rule: no document at the top of a matter's workspace or in the Default workspace
for target in "$matter_id" "$ws_id"; do
  code=$(http_code -u admin:admin -H 'Accept: application/json' -F "folderId=$target" -F "filename=smoke.png" \
    -F "filedata=@$WORK/smoke.png" "$BASE/services/rest/document/upload")
  check "upload into folder $target is refused (HTTP $code)" test "$code" -ge 300
done

echo "== 6. webservice schema present"
check "ld_webservicecall exists" compose exec -T db sh -c \
  'MYSQL_PWD="$MARIADB_PASSWORD" mariadb -u"$MARIADB_USER" "$MARIADB_DATABASE" -Nse "select 1 from ld_webservicecall where 1=0"'

echo "== 7. setup wizard blocked"
check "GET /setup is 403" test "$(http_code "$BASE/setup")" = 403

echo "== 8. data survives down/up"
compose down
compose up -d
check "app healthy again" wait_healthy "$(compose ps -q app)" 900
names=$(api -H 'Accept: application/json' "$BASE/services/rest/document/list?folderId=$doc_folder" | jq -r '.[].fileName')
check "smoke.pdf still listed" grep -qx smoke.pdf <<<"$names"
check "smoke.png still listed" grep -qx smoke.png <<<"$names"
check "no re-initialization" app_logs_have 'schema present, skipping'

finish
