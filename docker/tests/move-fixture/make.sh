#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 The Healdan Project
# SPDX-License-Identifier: LGPL-3.0-only

# Makes the fixture docker/tests/test-move.sh moves from MariaDB to PostgreSQL: an install on MariaDB, with rows that
# try the move, as its database's dump (mariadb.sql.gz), its conf and repository volumes (volumes.tgz) and what the
# test expects of them (expected.env), written beside this script. The fixture is frozen: README.md says when it's
# made again.
#
# This checkout runs on PostgreSQL, so the install comes from the last release that runs on MariaDB:
#   MOVE_FIXTURE_CHECKOUT=<a checkout of that release> MOVE_FIXTURE_IMAGE=<its image> docker/tests/move-fixture/make.sh
# The image is the checkout's own, built with docker build -t <name> <checkout>. MOVE_FIXTURE_PROJECT names the Compose
# project it runs under (move-fixture by default) and MOVE_FIXTURE_PORT the app's port on 127.0.0.1 (18091). It runs
# the checkout's compose.yaml with its smoke overlay, and removes that project's containers and volumes when it ends.
#
# Everything in the fixture is invented, and it goes into a public repository: the smoke stack's published test
# passwords, the database password below, and keys made for this stack alone. Client addresses are put to 127.0.0.1
# before the dump, and the end checks that no other address, nor this machine's name, is in what it wrote.
set -euo pipefail
OUT=$(cd "$(dirname "$0")" && pwd)
. "$OUT/../lib.sh"

CHECKOUT=${MOVE_FIXTURE_CHECKOUT:-}
IMAGE=${MOVE_FIXTURE_IMAGE:-}
PROJECT=${MOVE_FIXTURE_PROJECT:-move-fixture}
PORT=${MOVE_FIXTURE_PORT:-18091}
BASE="http://127.0.0.1:$PORT"
if [ -z "$CHECKOUT" ] || [ -z "$IMAGE" ]; then
  echo "Usage: MOVE_FIXTURE_CHECKOUT=<checkout> MOVE_FIXTURE_IMAGE=<image> $0"
  echo "  MOVE_FIXTURE_CHECKOUT  a checkout of the last release that runs on MariaDB"
  echo "  MOVE_FIXTURE_IMAGE     that checkout's image, built with docker build -t <name> <checkout>"
  echo "  MOVE_FIXTURE_PROJECT   the Compose project to run under (move-fixture)"
  echo "  MOVE_FIXTURE_PORT      the app's port on 127.0.0.1 (18091)"
  exit 2
fi
CHECKOUT=$(cd "$CHECKOUT" && pwd)
refused=0
if ! grep -q 'image: mariadb:' "$CHECKOUT/compose.yaml" 2>/dev/null; then
  echo "$CHECKOUT/compose.yaml runs no MariaDB: MOVE_FIXTURE_CHECKOUT must be a release that runs on MariaDB"
  refused=1
fi
if ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
  echo "There's no image $IMAGE: build it with docker build -t $IMAGE $CHECKOUT"
  refused=1
fi
if ! [[ $PROJECT =~ ^[a-z0-9]+([-_][a-z0-9]+)*$ ]]; then
  echo "MOVE_FIXTURE_PROJECT '$PROJECT' isn't a Compose project name: lowercase letters and digits, joined by - or _"
  refused=1
elif [ -n "$(docker ps -aq --filter "label=com.docker.compose.project=$PROJECT"
  docker volume ls -q --filter "label=com.docker.compose.project=$PROJECT")" ]; then
  echo "Compose project $PROJECT has containers or volumes already, which this script leaves alone: set"
  echo "MOVE_FIXTURE_PROJECT to another name"
  refused=1
fi
if ! [[ $PORT =~ ^[0-9]{1,5}$ ]] || [ "$PORT" -lt 1 ] || [ "$PORT" -gt 65535 ]; then
  echo "MOVE_FIXTURE_PORT '$PORT' isn't a port number"
  refused=1
elif (exec 3<>"/dev/tcp/127.0.0.1/$PORT") 2>/dev/null; then
  echo "Port $PORT on 127.0.0.1 is in use: set MOVE_FIXTURE_PORT to a free one"
  refused=1
fi
[ "$refused" = 0 ] || exit 1

WORK=$(mktemp -d)
# The database password, which the conf volume's context.properties keeps. Published here; test-move.sh uses another.
DB_PW=move-fixture-db-pw
APIUSER_PW='Api-Smoke-Pw-42!x'
export TZ=UTC
printf 'DB_PASSWORD=%s\nLOGICALDOC_IMAGE=%s\nLOGICALDOC_PORT=%s\n' "$DB_PW" "$IMAGE" "$PORT" > "$WORK/env"
# The sign-in overlay's provider, without its Keycloak: the smoke-signin seed saves provider smoke with its client
# secret sealed under the conf volume's key, and nothing contacts the provider. GET /api/v1/server lists it only while
# that secret opens, which the test checks after the move.
cat > "$WORK/signin.yaml" <<'YAML'
services:
  app:
    environment:
      HEALDAN_SIGNIN_ALLOW_HTTP: "true"
      HEALDAN_SMOKE_SIGNIN_ISSUER: http://keycloak:8080/realms/healdan
      HEALDAN_SMOKE_SIGNIN_CLIENT_ID: healdan
      HEALDAN_SMOKE_SIGNIN_CLIENT_SECRET: smoke-client-secret
YAML
compose() {
  docker compose -p "$PROJECT" --env-file "$WORK/env" --project-directory "$CHECKOUT" -f "$CHECKOUT/compose.yaml" \
    -f "$CHECKOUT/docker/tests/compose.smoke.yaml" -f "$WORK/signin.yaml" "$@"
}
cleanup() {
  local status=$?
  set +e
  if [ "$status" != 0 ]; then
    echo "== the app's last 80 lines of output"
    compose logs --no-log-prefix --tail 80 app
  fi
  compose down -v --remove-orphans >/dev/null 2>&1
  rm -rf "$WORK"
}
trap cleanup EXIT
die() { echo "make.sh: $*" >&2; exit 1; }
# sql <statements>: runs them in the install's MariaDB, printing rows tab-separated, without headers.
sql() {
  compose exec -T db sh -c 'MYSQL_PWD="$MARIADB_PASSWORD" mariadb -u"$MARIADB_USER" "$MARIADB_DATABASE" \
    --default-character-set=utf8mb4 -N -B' <<<"$1"
}
sha256() { if command -v sha256sum >/dev/null; then sha256sum "$1"; else shasum -a 256 "$1"; fi | cut -d' ' -f1; }

# The files it files, the same bytes each time: five Word documents and an email.
python3 - "$WORK" <<'PY'
import pathlib, sys, zipfile
out = pathlib.Path(sys.argv[1])
parts = {
    "[Content_Types].xml": '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>',
    "_rels/.rels": '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>',
}
def docx(name, text):
    with zipfile.ZipFile(out / name, "w", zipfile.ZIP_DEFLATED) as z:
        for part, body in list(parts.items()) + [("word/document.xml", '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>%s</w:t></w:r></w:p></w:body></w:document>' % text)]:
            z.writestr(zipfile.ZipInfo(part, (2026, 11, 19, 10, 0, 0)), body)
docx("one.docx", "The move fixture's first document")
docx("one-v2.docx", "The move fixture's first document, sent to the client")
docx("two.docx", "Un résumé naïf")
docx("three.docx", "The move fixture's third document, deleted")
docx("local.docx", "Kept on the local storage backend")
(out / "meeting.eml").write_bytes("\r\n".join([
    "From: Ann Example <ann@example.com>", "To: Bob Example <bob@example.com>",
    "Subject: =?UTF-8?Q?R=C3=A9union_de_chantier?=", "Date: Thu, 19 Nov 2026 09:30:00 +0000",
    "Message-ID: <move-fixture-1@example.com>", "MIME-Version: 1.0", "Content-Type: text/plain; charset=UTF-8", "",
    "The site meeting moves to Thursday.", ""]).encode())
PY

# gwt.py <base> <sid> <policy> <beans folder> <path> <interface> <method> <arguments as JSON>: a GWT-RPC call, as the
# Classic Interface makes it, for what has no other route (the Storage tab's service, New Matter). It writes each bean
# field by field, as GWT does: in name order, read from the bean's source in the checkout. Prints the answer and fails
# unless it's //OK.
cat > "$WORK/gwt.py" <<'PY'
import json, re, string, sys, urllib.request
base, sid, policy_file, beans, path, interface, method, args = sys.argv[1:9]
policy = open(policy_file, encoding="utf-8").read()
B64 = string.ascii_uppercase + string.ascii_lowercase + string.digits + "$_"
strings, index, tokens = [], {}, []
def ref(text):
    if text is None:
        return "0"
    if text not in index:
        strings.append(text)
        index[text] = len(strings)
    return str(index[text])
def signature(name):
    found = re.search(r"^%s, [^\n]*?, (%s/\d+), \d+$" % (re.escape(name), re.escape(name)), policy, re.M)
    if not found:
        sys.exit("the policy %s names no %s" % (policy_file, name))
    return found.group(1)
def long64(v):
    out = ""
    while v:
        out, v = B64[v & 63] + out, v >> 6
    return out or "A"
def put(kind, v):
    if kind == "long":
        tokens.append(long64(int(v or 0)))
    elif kind == "boolean":
        tokens.append("1" if v else "0")
    elif kind == "String":
        tokens.append(ref(v))
    elif kind in ("Boolean", "Long") and v is not None:
        tokens.append(ref(signature("java.lang." + kind)))
        tokens.append(("1" if v else "0") if kind == "Boolean" else long64(int(v)))
    elif kind in ("Boolean", "Long", "Date") and v is None:
        tokens.append("0")
    else:
        sys.exit("gwt.py can't write a %s" % kind)
def bean(name):
    return "com.logicaldoc.gui.frontend.client.healdan.beans." + name
for text in (base + "/frontend/", policy_file.rsplit("/", 1)[-1][:-len(".gwt.rpc")], interface, method):
    tokens.append(ref(text))
args = json.loads(args)
tokens.append(str(len(args)))
for kind, *rest in args:
    tokens.append(ref(signature(bean(rest[0])) if kind == "bean" else {"long": "J", "String": "java.lang.String/2004016611"}[kind]))
for kind, *rest in args:
    if kind != "bean":
        put(kind, rest[0])
        continue
    tokens.append(ref(signature(bean(rest[0]))))
    source = open("%s/%s.java" % (beans, rest[0]), encoding="utf-8").read()
    given = dict(rest[1])
    for type_, field in sorted(re.findall(r"^\s*private (?!static )(?!final )(\w+) (\w+)(?: = [^;]+)?;", source, re.M),
                               key=lambda f: f[1]):
        put(type_, given.pop(field, None))
    if given:
        sys.exit("%s has no %s" % (rest[0], ", ".join(given)))
escape = lambda t: t.replace("\\", "\\\\").replace("|", "\\!")
body = "7|0|%d|%s|%s|" % (len(strings), "|".join(escape(t) for t in strings), "|".join(tokens))
request = urllib.request.Request(base + path, data=body.encode("utf-8"), method="POST", headers={
    "Content-Type": "text/x-gwt-rpc; charset=utf-8", "X-GWT-Permutation": "0", "sid": sid})
with urllib.request.urlopen(request, timeout=120) as response:
    reply = response.read().decode("utf-8")
print(reply)
sys.exit(0 if reply.startswith("//OK") else 1)
PY
BEANS=$CHECKOUT/logicaldoc-gui/src/main/java/com/logicaldoc/gui/frontend/client/healdan/beans
SERVICES=com.logicaldoc.gui.frontend.client.healdan.services
# rpc <service> <path> <method> <arguments as JSON>, as admin: prints the answer
rpc() {
  local reply
  reply=$(python3 "$WORK/gwt.py" "$BASE" "$SID" "$WORK/$1/"*.gwt.rpc "$BEANS" "$2" "$SERVICES.$1" "$3" "$4" 2>&1) \
    || die "the $1 call $3 failed: ${reply:0:600}"
  echo "$reply"
}

echo "== 1. the stack, with the smoke overlay"
compose up -d
wait_healthy "$(compose ps -q app)" 900 || die "the app didn't become healthy"
for service in HealdanStorageService HealdanMatterService; do
  policy=$(compose exec -T app sh -c "cd \"\$CATALINA_HOME/webapps/ROOT/frontend\" && grep -l 'services\\.$service,' *.gwt.rpc" \
    | head -1)
  [ -n "$policy" ] || die "no .gwt.rpc policy in frontend/ names $service"
  # Under its own name, which is what the call gives as the policy's
  mkdir "$WORK/$service"
  compose cp "app:/usr/local/tomcat/webapps/ROOT/frontend/$policy" "$WORK/$service/$policy" >/dev/null 2>&1
done
SID=$(curl -fsS "$BASE/services/rest/auth/login?u=admin&pw=admin" | tr -d '"[:space:]')
[ -n "$SID" ] || die "admin couldn't sign in"

echo "== 2. documents, a version, a delete and an email through the API, as apiuser"
TOKEN=$(curl -fsS -H 'Content-Type: application/json' \
  -d '{"username":"apiuser","password":"'"$APIUSER_PW"'","name":"Move fixture","device":{"id":"move-fixture"}}' \
  "$BASE/api/v1/auth/tokens" | jq -r .token)
[ -n "$TOKEN" ] && [ "$TOKEN" != null ] || die "apiuser couldn't sign in"
AUTH=(-H "Authorization: Bearer $TOKEN")
matter_folder=$(sql "select hd_folderid from hd_matter where hd_number = 1")
FOLDER_ID=$(sql "select ld_id from ld_folder where ld_parentid = $matter_folder and ld_name = 'Documents'")
[ -n "$FOLDER_ID" ] || die "matter 1.1 has no Documents folder"
# file <folder id> <file> <name>: prints its doc #
file() {
  curl -fsS "${AUTH[@]}" -F "document={\"folderId\": $1, \"checkOut\": false};type=application/json" \
    -F "file=@$WORK/$2;filename=$3" "$BASE/api/v1/documents" | jq -er .docNumber
}
one=$(file "$FOLDER_ID" one.docx 'Engagement letter.docx')
two=$(file "$FOLDER_ID" two.docx 'Résumé naïf.docx')
three=$(file "$FOLDER_ID" three.docx 'Draft to delete.docx')
curl -fsS -o /dev/null "${AUTH[@]}" -X POST "$BASE/api/v1/documents/$one/checkout"
curl -fsS -o /dev/null "${AUTH[@]}" \
  -F 'version={"kind": "version", "comment": "Sent to the client", "keepCheckedOut": false};type=application/json' \
  -F "file=@$WORK/one-v2.docx;filename=Engagement letter.docx" "$BASE/api/v1/documents/$one/versions"
TRASH_ID=$(curl -fsS "${AUTH[@]}" -X DELETE "$BASE/api/v1/documents/$three" | jq -er .id)
email=$(file "$FOLDER_ID" meeting.eml meeting.eml)
echo "doc #s $one, $two and $three (deleted, Trash item $TRASH_ID); the email is doc # $email"

echo "== 3. a Store on an encrypted local backend, through the Storage tab's service, and a document in it"
everyone=$(sql "select ld_id from ld_group where ld_name = 'Everyone' and ld_type = 0")
STORE_ID=$(curl -fsS -u admin:admin -H 'Content-Type: application/json' -H 'Accept: application/json' \
  -d '{"name": "Dépôt Local", "parentId": 5, "type": 1}' "$BASE/services/rest/folder/create" | jq -er .id)
# As the Stores page says: the groups the template lets read a matter must read its Store
curl -fsS -u admin:admin -X PUT -H 'Content-Type: application/json' -d "[{\"groupId\": $everyone, \"read\": true}]" \
  "$BASE/services/rest/folder/setAccessControlList?folderId=$STORE_ID"
client=$(sql "select ld_id from hd_client where hd_number = 1")
template=$(sql "select hd_templateid from hd_matter where hd_number = 1")
rpc HealdanMatterService /frontend/healdanmatter create "[[\"bean\", \"GUIMatter\", {\"clientId\": $client,
  \"number\": 5, \"name\": \"Dépôt Müller\", \"storeId\": $STORE_ID, \"ownerId\": 1, \"templateId\": $template}]]" >/dev/null
# In the repository volume, so that the fixture holds it; apart from Original's, staging's and the cache's folders
backend_path=/opt/logicaldoc/repository/move-local
compose exec -T -u 1000 app mkdir "$backend_path"
added=$(rpc HealdanStorageService /frontend/healdanstorage addBackend \
  "[[\"bean\", \"GUIStorageBackend\", {\"kind\": \"local\", \"name\": \"Move Local\", \"path\": \"$backend_path\",
  \"encrypted\": true}], [\"String\", null]]")
recovery=$(grep -oE '([0-9A-Z]{4} ){12}[0-9A-Z]{4}' <<<"$added") || die "the added backend's answer has no recovery key"
BACKEND_ID=$(sql "select ld_id from hd_storage_backend where hd_name = 'Move Local'")
rpc HealdanStorageService /frontend/healdanstorage confirmRecoveryKey \
  "[[\"long\", $BACKEND_ID], [\"String\", \"${recovery: -4}\"]]" >/dev/null
rpc HealdanStorageService /frontend/healdanstorage setStoreBackend "[[\"long\", $STORE_ID], [\"long\", $BACKEND_ID]]" \
  >/dev/null
local_matter=$(sql "select hd_folderid from hd_matter where hd_number = 5")
local_folder=$(sql "select ld_id from ld_folder where ld_parentid = $local_matter and ld_name = 'Documents'")
LOCAL_DOC_NUMBER=$(file "$local_folder" local.docx 'Plan du dépôt.docx')
LOCAL_DOC_ID=$(sql "select ld_id from ld_document where ld_customid = '$LOCAL_DOC_NUMBER'")
# A save into the Store is staged, and the mover, which the save wakes, takes it to the backend
moved() {
  test "$(sql "select count(*) from hd_storage_file where hd_docid = $LOCAL_DOC_ID and hd_backendid = $BACKEND_ID
    and hd_waiting is null")" = 1
}
deadline=$((SECONDS + 120))
until moved; do [ "$SECONDS" -lt "$deadline" ] || die "doc # $LOCAL_DOC_NUMBER didn't reach the backend"; sleep 1; done
echo "Store $STORE_ID on backend $BACKEND_ID; doc # $LOCAL_DOC_NUMBER is on it"

# Read back through the app before the app stops: what the test reads after the move
check_content() { # <doc #> <file>
  curl -fsS "${AUTH[@]}" -o "$WORK/read" "$BASE/api/v1/documents/$1/content" \
    && test "$(sha256 "$WORK/read")" = "$(sha256 "$WORK/$2")"
}
check_content "$one" one-v2.docx || die "doc # $one doesn't read back as filed"
check_content "$LOCAL_DOC_NUMBER" local.docx || die "doc # $LOCAL_DOC_NUMBER doesn't read back as filed"
curl -fsS -o /dev/null "${AUTH[@]}" -X DELETE "$BASE/api/v1/auth/tokens/current"
curl -fsS "$BASE/api/v1/server" | jq -e '[.providers[].id] == ["smoke"]' >/dev/null \
  || die "the server doesn't list provider smoke, whose sealed secret should open"
curl -fsS -o /dev/null -X DELETE "$BASE/services/rest/auth/logout?sid=$SID" || true

echo "== 4. the app stopped, then SQL"
compose stop app
ONE_ID=$(sql "select ld_id from ld_document where ld_customid = '$one'")
TWO_ID=$(sql "select ld_id from ld_document where ld_customid = '$two'")
# Its first three history rows get dates whose fraction MariaDB's driver misread (.069 came back as .69), one at or
# above a tenth, and none. The version 1.0 of doc # 1 gets a plain datetime, which test-move.sh also zeroes and mends.
history=$(sql "select ld_id from ld_history where ld_docid = $ONE_ID order by ld_id limit 3")
[ "$(wc -l <<<"$history" | tr -d ' ')" = 3 ] || die "doc # $one has fewer than three history rows"
DATE_069_ID=$(sed -n 1p <<<"$history"); DATE_760_ID=$(sed -n 2p <<<"$history"); DATE_000_ID=$(sed -n 3p <<<"$history")
VERSION_ID=$(sql "select ld_id from ld_version where ld_documentid = $ONE_ID and ld_version = '1.0'")
[ -n "$VERSION_ID" ] || die "doc # $one has no version 1.0"
# A data URL that a browser decodes, 6,000 characters in all, where CE's generic script gives ld_avatar 4,000 (the
# PostgreSQL spec's section 6.4): 22 characters of prefix and a PNG of 4,483 bytes, whose base64 is 5,980 characters
# with its padding, which browsers decode without it.
avatar=$(python3 -c '
import base64, struct, zlib
def chunk(kind, data):
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
head = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 1, 1, 8, 2, 0, 0, 0))
tail = chunk(b"IDAT", zlib.compress(b"\x00\x2a\x6b\x9b")) + chunk(b"IEND", b"")
filler = 4483 - len(head) - len(tail) - 12 - len(b"Comment\x00")
png = head + chunk(b"tEXt", b"Comment\x00" + b"x" * filler) + tail
assert len(png) == 4483
print("data:image/png;base64," + base64.b64encode(png).decode().rstrip("="))')
[ "${#avatar}" = 6000 ] || die "the avatar is ${#avatar} characters, not 6000"
sql "update ld_user set ld_avatar = '$avatar' where ld_username = 'apiuser';
update ld_history set ld_date = '2026-11-19 10:15:30.069' where ld_id = $DATE_069_ID;
update ld_history set ld_date = '2026-11-19 10:15:31.760' where ld_id = $DATE_760_ID;
update ld_history set ld_date = '2026-11-19 10:15:32.000' where ld_id = $DATE_000_ID;
update ld_version set ld_versiondate = '2026-11-19 10:15:33' where ld_id = $VERSION_ID;"
# The client addresses the app recorded, the Docker network's gateway here, become 127.0.0.1 wherever they are: in
# ld_ip columns, ld_session's, and inside text such as ld_user_history's comments and ld_session.ld_clientid.
address='[0-9]{1,3}([.][0-9]{1,3}){3}'
rewrites=$(sql "select concat('update \`', table_name, '\` set \`', column_name, '\` = regexp_replace(\`', column_name,
  '\`, ''$address'', ''127.0.0.1'') where \`', column_name, '\` regexp ''$address'';') from information_schema.columns
  where table_schema = database() and data_type in ('char', 'varchar', 'tinytext', 'text', 'mediumtext', 'longtext')")
sql "$rewrites"

echo "== 5. the dump, the volumes and expected.env"
compose exec -T db sh -c 'MYSQL_PWD="$MARIADB_PASSWORD" mariadb-dump -u"$MARIADB_USER" --single-transaction \
  --skip-dump-date --default-character-set=utf8mb4 "$MARIADB_DATABASE"' | gzip -n -9 > "$OUT/mariadb.sql.gz"
# The app rewrites its logs at every start, and the test needs none: the folder stays, empty.
docker run --rm -v "${PROJECT}_logicaldoc-conf:/v/conf:ro" -v "${PROJECT}_logicaldoc-repository:/v/repository:ro" \
  --entrypoint tar "$IMAGE" -C /v --numeric-owner --sort=name --exclude='repository/logs/*' -cf - conf repository \
  | gzip -n -9 > "$OUT/volumes.tgz"
count() { sql "select count(*) from $1"; }
{
  echo "# Written by make.sh from $(git -C "$CHECKOUT" rev-parse HEAD): what test-move.sh expects of the fixture."
  echo "APIUSER_PASSWORD='$APIUSER_PW'"
  echo "# Doc # $one: its second version's file, on Original"
  echo "DOC_NUMBER=$one"
  echo "DOC_ID=$ONE_ID"
  echo "DOC_SHA256=$(sha256 "$WORK/one-v2.docx")"
  echo "# Doc # $LOCAL_DOC_NUMBER: on the encrypted local backend Move Local, in the Store Dépôt Local"
  echo "LOCAL_DOC_NUMBER=$LOCAL_DOC_NUMBER"
  echo "LOCAL_DOC_SHA256=$(sha256 "$WORK/local.docx")"
  echo "LOCAL_BACKEND_ID=$BACKEND_ID"
  echo "# Matter 1.1's Documents folder, where the test files a new document"
  echo "FOLDER_ID=$FOLDER_ID"
  echo "# The next doc # and ids must follow these"
  echo "HIGHEST_DOC_NUMBER=$(sql "select max(cast(ld_customid as unsigned)) from ld_document")"
  echo "MAX_DOCUMENT_ID=$(sql "select max(ld_id) from ld_document")"
  echo "MAX_VERSION_ID=$(sql "select max(ld_id) from ld_version")"
  echo "MAX_HISTORY_ID=$(sql "select max(ld_id) from ld_history")"
  echo "MAX_TRASH_ID=$(sql "select max(hd_id) from hd_trash")"
  echo "DOCUMENT_SEQ_NEXT=$(sql "select next_not_cached_value from ld_document_SEQ")"
  echo "TRASH_ID=$TRASH_ID"
  echo "# Row counts"
  for t in ld_document ld_version ld_history ld_user hd_matter hd_trash hd_storage_file hd_email; do
    echo "COUNT_$t=$(count "$t")"
  done
  echo "# Dates: three in ld_history (datetime(3)) and a plain datetime in ld_version"
  echo "DATE_069_ID=$DATE_069_ID"
  echo "DATE_760_ID=$DATE_760_ID"
  echo "DATE_000_ID=$DATE_000_ID"
  echo "VERSION_ID=$VERSION_ID"
  echo "VERSION_DATE='2026-11-19 10:15:33'"
  echo "# Accented text, made through the app: matter 1.5's name"
  echo "MATTER_NUMBER=5"
  echo "MATTER_NAME='Dépôt Müller'"
  echo "# The document test-move.sh gives a NUL, and the avatar"
  echo "NUL_DOC_ID=$TWO_ID"
  echo "AVATAR_SHA256=$(printf '%s' "$avatar" > "$WORK/avatar" && sha256 "$WORK/avatar")"
} > "$OUT/expected.env"

echo "== 6. nothing in it that isn't the stack's"
found=$( (gzip -dc "$OUT/mariadb.sql.gz"; gzip -dc "$OUT/volumes.tgz") | LC_ALL=C grep -aoE '([0-9]{1,3}\.){3}[0-9]{1,3}' \
  | LC_ALL=C sort -u | grep -vxE '127\.0\.0\.1|228\.8\.8\.8' || true)
[ -z "$found" ] || die "the fixture holds addresses besides 127.0.0.1 and CE's multicast default: $found"
for name in "$(hostname)" "$(hostname -s 2>/dev/null || true)"; do
  [ -n "$name" ] || continue
  if (gzip -dc "$OUT/mariadb.sql.gz"; gzip -dc "$OUT/volumes.tgz") | LC_ALL=C grep -aqiF -- "$name"; then
    die "the fixture holds this machine's name, $name"
  fi
done
ls -l "$OUT/mariadb.sql.gz" "$OUT/volumes.tgz" "$OUT/expected.env"
echo "Done"
