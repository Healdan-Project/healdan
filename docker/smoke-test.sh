#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 The Healdan Project
# SPDX-License-Identifier: LGPL-3.0-only

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
# The stand-in sign-in provider's host port (docker/tests/compose.signin.yaml), on 127.0.0.1 only.
KEYCLOAK_PORT=${SMOKE_KEYCLOAK_PORT:-18180}
# The stand-in S3 server's host port (docker/tests/compose.storage.yaml), on 127.0.0.1 only.
GARAGE_PORT=${SMOKE_GARAGE_PORT:-13900}
WORK=$(mktemp -d)
ENV_FILE=$WORK/smoke.env
# Awkward on purpose: exercises .env quoting, context.properties escaping and the JDBC login.
PW='sm0ke,p=a\ss $x #1 é:! '

# The stack logs in UTC, as do the FAIL lines (docker/tests/lib.sh), even when the shell sets another zone.
export TZ=UTC

# docker/tests/compose.smoke.yaml makes Healdan seed a Store, template, client and matter to upload into.
# docker/tests/compose.signin.yaml adds a stand-in sign-in provider, Keycloak, and makes Healdan seed its setup.
# docker/tests/compose.storage.yaml adds a stand-in S3 server, Garage, and makes Healdan seed a backend and a Store
# on it.
compose() {
  docker compose -p "$PROJECT" --env-file "$ENV_FILE" -f compose.yaml -f docker/tests/compose.smoke.yaml \
    -f docker/tests/compose.signin.yaml -f docker/tests/compose.storage.yaml "$@"
}
# The app writes its warnings to dms.log in the repository volume, not to stdout, and step 8 replaces the container
# but keeps the volume, so the log covers the whole run. docker cp reads it from a stopped container too.
show_server_log() {
  local cid dir=$WORK/logs f
  cid=$(compose ps -aq app 2>/dev/null | head -1)
  if [ -z "$cid" ] || ! docker cp "$cid:/opt/logicaldoc/repository/logs/." "$dir" >/dev/null 2>&1 \
    || [ ! -f "$dir/dms.log" ]; then
    echo "== no dms.log to show"
    return 0
  fi
  # It rolls over at 5 MB into dms.log.1, .2, …, the higher the newer.
  for f in "$dir"/dms.log.[0-9] "$dir/dms.log"; do [ -f "$f" ] && cat "$f"; done > "$dir/run.log"
  # An entry starts "dd.MM.yyyy HH:mm:ss,SSS LEVEL". A warning or error keeps its exception's messages and "Caused
  # by" lines, without the stack frames.
  awk '/^[0-9][0-9]\.[0-9][0-9]\.[0-9][0-9][0-9][0-9] / { keep = $3 ~ /^(WARN|ERROR|FATAL)$/ }
       keep && !/^[[:space:]]+(at |\.\.\. [0-9]+ more)/' "$dir/run.log" > "$dir/problems.log"
  echo "== dms.log: the last 200 lines of the run's $(grep -cE '^[0-9.]{10} [0-9:,]{12} (WARN|ERROR|FATAL)' \
    "$dir/run.log") warnings and errors (times in UTC)"
  tail -n 200 "$dir/problems.log" | cut -c 1-400
  echo "== dms.log: its last 40 lines"
  tail -n 40 "$dir/run.log" | cut -c 1-400
}
cleanup() {
  local status=$?
  # Nothing here may stop the stack's removal.
  set +e
  if [ "$status" != 0 ]; then
    echo "== the app container's last 150 lines of output"
    compose logs --no-log-prefix --tail 150 app
    echo "== Keycloak's last 40 lines of output"
    compose logs --no-log-prefix --tail 40 keycloak
    echo "== Garage's last 40 lines of output, and its setup's"
    compose logs --no-log-prefix --tail 40 garage
    compose logs --no-log-prefix --tail 20 garage-setup
    show_server_log
  fi
  compose down -v --remove-orphans >/dev/null 2>&1
  rm -rf "$WORK"
}
trap cleanup EXIT

printf "DB_PASSWORD='%s'\nLOGICALDOC_PORT=%s\nSMOKE_KEYCLOAK_PORT=%s\nSMOKE_GARAGE_PORT=%s\n" "$PW" "$PORT" \
  "$KEYCLOAK_PORT" "$GARAGE_PORT" > "$ENV_FILE"

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
# header <url> <name>: that header's whole value on a successful GET, empty for an error. Byte by byte (LC_ALL=C), as
# macOS's tr refuses a byte that isn't UTF-8, such as a non-breaking space in Latin-1.
header() {
  curl -fsS -o /dev/null -D - "$1" | LC_ALL=C tr -d '\r' | LC_ALL=C awk -v name="$2" \
    'tolower(substr($0, 1, length(name) + 1)) == tolower(name) ":" { sub(/^[^:]*: */, ""); print }'
}
# cache_control <url>: the Cache-Control header of a successful GET, empty for an error.
cache_control() { header "$1" cache-control; }
LOADER=$BASE/login/login.nocache.js
# csp_directives <url>: the Content-Security-Policy of a successful GET, one directive a line, trimmed of plain spaces
# only, so a non-breaking space stays in its directive as browsers see it.
csp_directives() {
  header "$1" content-security-policy | LC_ALL=C awk '{ n = split($0, d, ";")
    for (i = 1; i <= n; i++) { gsub(/^ +| +$/, "", d[i]); if (d[i] != "") print d[i] } }'
}

echo "== 1. build and start"
if [ "${SMOKE_BUILD:-1}" = 0 ]; then compose up -d --no-build; else compose up -d --build; fi
check "app healthy" wait_healthy "$(compose ps -q app)" 900
# Keycloak starts beside the app and takes far less time, so this rarely waits.
check "Keycloak healthy" wait_healthy "$(compose ps -q keycloak)" 300
# Not head: under pipefail, the SIGPIPE'd compose logs would fail the line.
kc_started=$(compose logs --no-log-prefix keycloak 2>&1 | sed -n 's/.* started in \([0-9.]*\)s\..*/\1/p' || true)
echo "Keycloak started in ${kc_started%%$'\n'*} s"
# The app starts only once Garage's setup has finished (docker/tests/garage/setup.sh), which times itself.
garage_started=$(compose logs --no-log-prefix garage-setup 2>&1 \
  | sed -n 's/^Garage started and set up in \([0-9]*\) s$/\1/p' || true)
echo "Garage started and set up in ${garage_started%%$'\n'*} s"
[ "$FAILED" = 0 ] || exit 1

echo "== 2. login page and the API"
check "GET /login.jsp is 200" test "$(http_code "$BASE/login.jsp")" = 200
# A fresh stack's first start asks for no restart: DockerInit installs LogicalDOC's plugins before Tomcat starts (Docker
# spec §15). The sign-in page shows the alerts in the GUIInfo that getInfo answers, and GUIInfo holds GUIMessages only
# there, so an answer that names no GUIMessage shows none. The phrase itself is no test: the answer's bundle has it.
login_alerts_none() {
  local policy call reply
  # A serialisation policy the login module was compiled with, which lets GUIInfo and GUIMessage through. With no file
  # left to search, the outer grep would read its input, which is this script's, so it gets none.
  policy=$(compose exec -T app sh -c 'cd "$CATALINA_HOME/webapps/ROOT/login" &&
    grep -l "beans\.GUIInfo," $(grep -l "beans\.GUIMessage," *.gwt.rpc) </dev/null | head -1')
  if [ -z "$policy" ]; then echo "no .gwt.rpc policy in login/ names both GUIInfo and GUIMessage"; return 1; fi
  # InfoService.getInfo("en", "default", true), as the sign-in page calls it.
  call="7|0|8|$BASE/login/|${policy%.gwt.rpc}|com.logicaldoc.gui.common.client.services.InfoService|getInfo"
  call+="|java.lang.String/2004016611|Z|en|default|1|2|3|4|3|5|5|6|7|8|1|"
  reply=$(curl -fsS -X POST "$BASE/login/info" -H 'Content-Type: text/x-gwt-rpc; charset=utf-8' \
    -H 'X-GWT-Permutation: 0' --data-binary "$call")
  case $reply in
    //OK*) ;;
    *) echo "getInfo failed: ${reply:0:200}"; return 1 ;;
  esac
  lacks 'beans\.GUIMessage/' "$reply"
}
check "the sign-in page shows no alert, such as the restart notice" login_alerts_none
check "the first start asks for no restart" lacks 'The application has to be restarted' "$(compose logs app 2>&1)"
# The healdan-api plugin is in the WAR and /api reaches its servlet, past CE's security chain.
api_product() { curl -fsS "$BASE/api/v1/server" | jq -er .product; }
check "GET /api/v1/server answers JSON naming Healdan" test "$(api_product)" = Healdan
# The Office add-in's manifest, made from healdan-api's jar, names the address it was fetched at: no public address is
# set here, so it takes the request's, and http is fine for 127.0.0.1.
check "GET /api/addin/manifest.xml names this server" grep -q "$BASE/api/addin/commands.html" <(curl -fsS "$BASE/api/addin/manifest.xml")
# A GWT loader names its build's *.cache.js files, so the browser checks it on every load (Docker spec §13).
check "login.nocache.js is revalidated on every load" test "$(cache_control "$LOADER")" = no-cache
loader=$(curl -fsS "$LOADER" || true)
build=
[[ $loader =~ [0-9A-F]{32} ]] && build=${BASH_REMATCH[0]}
check "its $build.cache.js keeps max-age=600" test "$(cache_control "$BASE/login/$build.cache.js")" = max-age=600
# Upstream's policy had a non-breaking space after object-src, which browsers dropped as unrecognised (Docker spec §14).
check "the Content-Security-Policy has object-src 'none'" grep -qx "object-src 'none'" <(csp_directives "$BASE/login.jsp")

echo "== 2b. forwarded addresses (docker/tests/test-proxies.sh)"
# Fails three sign-ins with made-up X-Forwarded-For headers and checks which address the throttle counted; its
# output carries its own PASS/FAIL lines
check "Tomcat believes forwarded addresses only from the Docker networks" env BASE="$BASE" \
  APP_CONTAINER="$(compose ps -q app)" DB_CONTAINER="$(compose ps -q db)" bash docker/tests/test-proxies.sh

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
check "smoke-signin seed recorded" test \
  "$(db_query "select count(*) from hd_setting where name = 'seed.smoke-signin'")" = 1
check "smoke-storage seed recorded" test \
  "$(db_query "select count(*) from hd_setting where name = 'seed.smoke-storage'")" = 1
# The image sets store= to HealdanStorage on every start (ConfigPlan.HEALDAN_KEYS), and healdan-storage's seeds run
# only when it's CE's store. storage-record-original writes Original's marker, into an empty folder on a fresh stack.
storage_seeds="'seed.storage-settings', 'seed.storage-record-original', 'seed.storage-hide-stores'"
check "the storage seeds recorded, so HealdanStorage is CE's store" test \
  "$(db_query "select count(*) from hd_setting where name in ($storage_seeds)")" = 3
check "Original's marker in its folder" compose exec -T app test -s /opt/logicaldoc/repository/docs/.healdan-backend
# doc_ids is "<pdf id> <png id>", uploaded in that order: doc #s 1 and 2
n=1
for id in $doc_ids; do
  custom=$(api -H 'Accept: application/json' "$BASE/services/rest/document/getDocument?docId=$id" | jq -r .customId)
  check "document $id has doc # $n" test "$custom" = "$n"
  n=$((n + 1))
done
# The filing rule: no document at the top of a matter's workspace or in the Default workspace. REST answers a
# refusal with a bare, empty HTTP 500 (the message never reaches the body), and a bare non-2xx would also pass for
# a 401 or an unrelated 500. So the check needs the answer to be non-2xx AND the rule's own message to be logged
# in dms.log once more than before the upload.
dms_log_count() { # <message>
  compose exec -T app sh -c 'grep -cF -- "$1" /opt/logicaldoc/repository/logs/dms.log || true' sh "$1"
}
refused_with() { # <folder id> <message>
  local before after code tries=0
  before=$(dms_log_count "$2")
  code=$(curl -s -o /dev/null -w '%{http_code}' -u admin:admin -H 'Accept: application/json' -F "folderId=$1" \
    -F "filename=smoke.png" -F "filedata=@$WORK/smoke.png" "$BASE/services/rest/document/upload")
  [ "$code" -ge 300 ] || { echo "answered HTTP $code, not an error"; return 1; }
  while :; do
    after=$(dms_log_count "$2")
    [ "$after" -gt "$before" ] && return 0
    tries=$((tries + 1))
    [ "$tries" -lt 10 ] || break
    sleep 1
  done
  echo "HTTP $code, but dms.log has no new '$2'"
  return 1
}
check "upload to the matter's top level is refused by the filing rule" refused_with "$matter_id" \
  "Documents can't be saved at the top level of a matter. Choose one of its folders."
check "upload to the Default workspace is refused by the filing rule" refused_with "$ws_id" \
  "Documents must be filed in a folder inside a matter."
# An email with an emoji in its Subject and From (RFC 2047). CE's MariaDB tables are utf8, 3 bytes a character, and
# strict, so Healdan puts U+FFFD for every character beyond the Basic Multilingual Plane before the insert (R33).
# Uploaded after the doc # checks above, which expect the first two uploads to be 1 and 2.
printf '%s\r\n' 'From: =?UTF-8?B?QW5uIPCfjok=?= <ann@example.com>' 'To: bob@example.com' \
  'Subject: =?UTF-8?B?UmU6IPCfjokgU2FsZQ==?=' 'Date: Tue, 14 Oct 2025 09:29:00 +0200' 'MIME-Version: 1.0' \
  'Content-Type: text/plain; charset=UTF-8' '' 'Party.' > "$WORK/emoji.eml"
no_4byte_char() { # <text>: no UTF-8 lead byte of a 4-byte character (0xF0-0xF4)
  printf '%s' "$1" | python3 -c 'import sys; b = sys.stdin.buffer.read(); sys.exit(0 if b and not any(0xF0 <= x <= 0xF4 for x in b) else 1)'
}
email_id=$(api -H 'Accept: application/json' -F "folderId=$doc_folder" -F "filename=emoji.eml" \
  -F "filedata=@$WORK/emoji.eml" "$BASE/services/rest/document/upload" || true)
check "email with an emoji in its headers uploaded" grep -Eq '^[0-9]+$' <<<"$email_id"
email_json=$(api -H 'Accept: application/json' "$BASE/services/rest/document/getDocument?docId=$email_id" || true)
check "email named after its subject, with U+FFFD for the emoji" \
  test "$(jq -r '.fileName // empty' <<<"$email_json")" = $'Re_ \xef\xbf\xbd Sale.eml'
check "email's subject field stored with U+FFFD for the emoji" \
  test "$(jq -r '[.attributes[]? | select(.name == "emailsubject") | .stringValue][0] // empty' <<<"$email_json")" \
  = $'Re: \xef\xbf\xbd Sale'
check "email's stored file name has no 4-byte character" \
  no_4byte_char "$(db_query "select ld_filename from ld_document where ld_id = ${email_id:-0}")"

echo "== 5a. full text in Office and email files (spec §9)"
# Uploads a .docx and an .eml into the Smoke Matter and searches for a token in each; their output carries its own PASS/FAIL lines
check "full-text tokens found in the .docx and the .eml" env BASE="$BASE" FOLDER_ID="$doc_folder" bash docker/tests/test-fulltext.sh

echo "== 5b. Office previews (M1.5 spec §11.1)"
# Uploads Office files and a Draw drawing into the Smoke Matter and checks their PDFs, thumbnails and the
# conversion's hardening; its output carries its own PASS/FAIL lines
check "Office and drawing previews, markup and hardening" env BASE="$BASE" FOLDER_ID="$doc_folder" DOC_PATH="$doc_path" \
  SID="$sid" APP_CONTAINER="$(compose ps -q app)" DB_CONTAINER="$(compose ps -q db)" bash docker/tests/test-previews.sh

echo "== 5c. the add-in flow through the JSON API (M2 spec §14.1)"
# Signs in as a new non-admin user and files, checks out and saves a .docx in the Smoke Matter through /api/v1 with a
# Bearer token, through CE's real filter chain; its output carries its own PASS/FAIL lines
api_started=$SECONDS
check "the add-in flow through the API" env BASE="$BASE" bash docker/tests/test-api.sh
echo "step 5c took $((SECONDS - api_started)) s"

echo "== 5d. the helper's stack tests (clients/helper: StackCycleTests, StackProviderSignInTests)"
# StackCycleTests signs in through /api/v1 as admin with a device id, files a document in the Smoke Matter, and opens
# it, saves it twice and closes it through the helper's cycle with a fake LibreOffice. StackProviderSignInTests signs
# in through the stand-in Keycloak as sso-helper2, as the helper does, with a fake browser that sends Keycloak's name to
# its host port, and checks /me names sso-helper2. It runs after 5c, whose wrong passwords block only other usernames
# and leave this address well under the throttle's limit, and before 5e, which disables sso-helper, not sso-helper2.
# Needs the .NET SDK clients/helper/global.json names, so it runs only with SMOKE_HELPER_TEST=1, which CI sets.
# dotnet test passes when no test ran, so its summaries must add up to two passed and none skipped: a Stack test added
# here changes the two.
HELPER_STACK_TESTS=2
helper_stack_test() {
  local out passed skipped
  out=$(HEALDAN_STACK_URL="$BASE" HEALDAN_STACK_SIGNIN_PROVIDER=smoke HEALDAN_STACK_SIGNIN_USER=sso-helper2 \
    HEALDAN_STACK_SIGNIN_PASSWORD='Smoke-Sso-Pw-42!x' HEALDAN_STACK_KEYCLOAK="127.0.0.1:$KEYCLOAK_PORT" \
    dotnet test clients/helper/Healdan.Helper.sln --filter Category=Stack 2>&1) \
    || { tail -40 <<<"$out"; return 1; }
  grep -E 'Passed!|Failed!|Skipped!' <<<"$out" || true
  passed=$(sed -nE 's/.*Passed: +([0-9]+),.*/\1/p' <<<"$out" | awk '{ n += $1 } END { print n + 0 }')
  skipped=$(sed -nE 's/.*Skipped: +([0-9]+),.*/\1/p' <<<"$out" | awk '{ n += $1 } END { print n + 0 }')
  [ "$passed" = "$HELPER_STACK_TESTS" ] && [ "$skipped" = 0 ] && return 0
  echo "  $passed passed and $skipped skipped, not $HELPER_STACK_TESTS passed and none skipped"
  return 1
}
if [ "${SMOKE_HELPER_TEST:-0}" = 1 ]; then
  helper_started=$SECONDS
  check "the helper's cycle and provider sign-in against the stack" helper_stack_test
  echo "step 5d took $((SECONDS - helper_started)) s"
else
  echo "skipped: set SMOKE_HELPER_TEST=1 to run it"
fi

echo "== 5e. provider sign-in through the stand-in Keycloak (M4 spec section 16.1)"
# Signs in through Keycloak as the web page does and as the helper does, with curl and a cookie jar, and checks linking
# by email, the gate, and a person disabled at Keycloak losing their device token; its output carries its own PASS/FAIL
# lines. Its one wrong password is sso-user's, and leaves this address well under the throttle's limit.
signin_started=$SECONDS
check "provider sign-in through the stand-in" env BASE="$BASE" KEYCLOAK="127.0.0.1:$KEYCLOAK_PORT" \
  bash docker/tests/test-signin.sh
echo "step 5e took $((SECONDS - signin_started)) s"

echo "== 5f. storage backends against the stand-in Garage (S3 spec section 16.1)"
# Files text files into the seeded Smoke Cloud, whose encrypted backend is on Garage, and into Smoke, and checks that
# they reach Garage encrypted, move up and home with their matter, read back from Garage and from the read cache, and
# that an outage, with Garage stopped, keeps saves in staging and answers 503 for the files that aren't cached; its
# output carries its own PASS/FAIL lines. It stops and starts Garage itself, and step 8 runs it again after the restart,
# with what it kept in $WORK/storage.state.
storage_test() { # [after-restart]
  env BASE="$BASE" S3="http://127.0.0.1:$GARAGE_PORT" GARAGE_CONTAINER="$(compose ps -q garage)" \
    APP_CONTAINER="$(compose ps -q app)" DB_CONTAINER="$(compose ps -q db)" STATE="$WORK/storage.state" \
    bash docker/tests/test-storage.sh "$@"
}
storage_started=$SECONDS
check "storage backends against the stand-in" storage_test
echo "step 5f took $((SECONDS - storage_started)) s"

echo "== 5g. the User app in the browser (M6 spec §15.1)"
# Signs in as webuser, which the smoke overlay's SmokeWebUserSeed makes, and runs healdan-web's Playwright specs in
# Chromium and WebKit in Playwright's Docker image: a link into the app, signing in, the way to the Classic Interface and
# back, Sign Out, and an accessibility scan; then curl checks the web session's forgery refusals. Its output carries its
# own PASS/FAIL lines.
web_started=$SECONDS
check "the User app in the browser" env BASE="$BASE" bash docker/tests/test-web.sh
echo "step 5g took $((SECONDS - web_started)) s"

echo "== 6. webservice schema present"
check "ld_webservicecall exists" compose exec -T db sh -c \
  'MYSQL_PWD="$MARIADB_PASSWORD" mariadb -u"$MARIADB_USER" "$MARIADB_DATABASE" -Nse "select 1 from ld_webservicecall where 1=0"'

echo "== 7. setup wizard blocked"
check "GET /setup is 403" test "$(http_code "$BASE/setup")" = 403

echo "== 8. data survives down/up, and an earlier image's default is replaced"
# As a conf volume written before 2026-10-30 holds it: cachecontrol without the loaders' rule (Docker spec §13).
compose exec -T -u logicaldoc app sed -i 's/\*\.nocache\.js:no-cache;//' /opt/logicaldoc/conf/context.properties
check "the conf volume holds the old cachecontrol" \
  test "$(compose exec -T app grep -c 'nocache\.js:' /opt/logicaldoc/conf/context.properties)" = 0
# As one written before 2026-11-06 holds it, or an admin's policy begun from it: security.csp with a non-breaking
# space (0xA0) after object-src (§14).
compose exec -T -u logicaldoc -e LC_ALL=C app \
  sed -i "s/object-src 'none'/object-src\\xa0'none'/" /opt/logicaldoc/conf/context.properties
check "the conf volume holds the old security.csp" test "$(compose exec -T -e LC_ALL=C app \
  grep -c "^security\.csp=.*object-src[^ ]'none'" /opt/logicaldoc/conf/context.properties)" = 1
compose down
compose up -d
check "app healthy again" wait_healthy "$(compose ps -q app)" 900
names=$(api -H 'Accept: application/json' "$BASE/services/rest/document/list?folderId=$doc_folder" | jq -r '.[].fileName')
check "smoke.pdf still listed" grep -qx smoke.pdf <<<"$names"
check "smoke.png still listed" grep -qx smoke.png <<<"$names"
check "no re-initialization" app_logs_have 'schema present, skipping'
check "LogicalDOC's tables have what the scripts create" app_logs_have "LogicalDOC's tables have every table and column"
check "the old cachecontrol replaced" app_logs_have "replaced an earlier image's default with this image's: cachecontrol"
check "security.csp's non-breaking space made plain" \
  app_logs_have "replaced non-breaking spaces with plain ones in: security\.csp"
check "login.nocache.js is revalidated on every load again" test "$(cache_control "$LOADER")" = no-cache
check "the Content-Security-Policy has object-src 'none' again" \
  grep -qx "object-src 'none'" <(csp_directives "$BASE/login.jsp")
# The containers are new, so storage_test looks them up again
check "storage backends after the restart" storage_test after-restart

finish
