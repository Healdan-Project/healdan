#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 The Healdan Project
# SPDX-License-Identifier: LGPL-3.0-only

# Storage backends against a stand-in S3 server, Garage (S3 spec section 16.1). The storage overlay
# (docker/tests/compose.storage.yaml) runs Garage, and its seed adds the encrypted backend "Smoke Garage" on it and the
# Store "Smoke Cloud" that uses it, with matters 1.2 and 1.3; matter 1.1 is in the smoke fixture's Store "Smoke", on
# Original. Each file holds a random token, and all but F, a PDF, are text files:
#   1. A, filed in 1.2, reaches Garage encrypted: its object doesn't hold its token.
#   2. B, C and F, filed in 1.1 and moved into 1.3, move up to Garage, and none is cached: the mover reads a moved
#      file from Original and caches nothing, while an upload's staged copy becomes its cache entry (question 9).
#   3. B reads back through Garage with its own bytes; then, with Garage stopped, A reads back from the cache.
#   4. With Garage still stopped, D is filed and waits in staging; C, never read, answers 503 through the API, the web
#      page's /download and a shared link; /convertpdf of F, never read, serves CE's notavailable.pdf (a text file has
#      no conversion, so /convertpdf wouldn't read C); A and B still read. Once Garage is back, D reaches it. Each of
#      C's and F's reads prints how long it took to fail.
#   5. Matter 1.2 moves to Smoke: A and D come home to Original, and Garage keeps their objects, held.
#   6. E, filed in 1.3, reaches Garage, as A did.
# With "after-restart" (smoke step 8, after a down and up that kept the volumes) it checks that the rows are as they
# were, A and D read from Original, C reads through Garage (its first read, as it was never cached), E reads from the
# cache rebuilt from its folder with Garage stopped, and the held objects are still in Garage.
# A check that claims a read from the cache stops Garage first, so it can fail.
# Usage: BASE=http://127.0.0.1:8080 GARAGE_CONTAINER=<id> APP_CONTAINER=<id> DB_CONTAINER=<id> STATE=<file> \
#        docker/tests/test-storage.sh [after-restart]
# STATE keeps what the first run learnt for the second. Needs curl 8.0.0 or later (only from 8.0.0 does --aws-sigv4
# send the x-amz-content-sha256 header, without which Garage refuses a request), jq, sha256sum and docker; the Docker
# containers are passed in, as the script has neither the smoke test's project nor its env file.
# docker/smoke-test.sh runs it as step 5f, and again in step 8.
set -euo pipefail
cd "$(dirname "$0")/../.."
. docker/tests/lib.sh

: "${BASE:?}" "${GARAGE_CONTAINER:?}" "${APP_CONTAINER:?}" "${DB_CONTAINER:?}" "${STATE:?}"
MODE=${1:-checks}
WAIT=60
# Garage's S3 API on the host (the overlay's port), and the overlay's fixed test key
S3=http://127.0.0.1:13900
BUCKET=healdan-smoke
S3_KEY=GK0123456789abcdef01234567
S3_SECRET=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
# SmokeStorageSeed's
PREFIX=healdan/
BACKEND_NAME='Smoke Garage'
OUTAGE="This file is in Smoke Cloud's cloud storage, which can't be reached right now."
# As DownloadErrorFilter's page escapes it
OUTAGE_HTML="This file is in Smoke Cloud&#39;s cloud storage, which can&#39;t be reached right now."
CACHE=/opt/logicaldoc/cache
ORIGINAL=/opt/logicaldoc/repository/docs
WORK=$(mktemp -d)
OUT=$WORK/body
TOKEN=

# Garage stopped by a check that then failed is started again, so the rest of the smoke test has it
cleanup() {
  [ "$(docker inspect -f '{{.State.Running}}' "$GARAGE_CONTAINER" 2>/dev/null)" = false ] \
    && docker start "$GARAGE_CONTAINER" >/dev/null 2>&1
  rm -rf "$WORK"
}
trap cleanup EXIT

curl_version=$(curl --version | sed -n '1s/^curl \([0-9]*\)\..*/\1/p')
if [ "${curl_version:-0}" -lt 8 ]; then
  echo "FAIL  curl $(curl --version | head -1 | cut -d' ' -f2) can't sign Garage's requests: it needs 8.0.0 or later"
  exit 1
fi

# The REST endpoints authenticate with HTTP basic auth; the web page's routes take the session cookie.
api() { curl -fsS -u admin:admin "$@"; }
app() { docker exec "$APP_CONTAINER" "$@"; }
db_query() {
  docker exec "$DB_CONTAINER" sh -c 'MYSQL_PWD="$MARIADB_PASSWORD" mariadb -u"$MARIADB_USER" "$MARIADB_DATABASE" -Nse "$1"' \
    sh "$1"
}
# s3 <path and query> [curl args...]: a request to Garage signed with the overlay's key
s3() { local path=$1; shift; curl -sS --aws-sigv4 "aws:amz:garage:s3" --user "$S3_KEY:$S3_SECRET" "$@" "$S3/$path"; }
# s3_keys <prefix>: the bucket's keys under the prefix, one a line
s3_keys() { s3 "$BUCKET?list-type=2&prefix=$1" -f | grep -o '<Key>[^<]*</Key>' | sed 's/<Key>//; s/<\/Key>//'; }
listed() { s3_keys "$1" | grep -qxF -- "$1"; }
sha() { sha256sum "$1" | cut -d' ' -f1; }
# poll <seconds> <command...>: runs the command every second until it succeeds, for at most that long
poll() {
  local deadline=$((SECONDS + $1))
  shift
  while ! "$@" >/dev/null 2>&1; do
    [ "$SECONDS" -lt "$deadline" ] || { "$@" || true; return 1; }
    sleep 1
  done
}
garage_running() { [ "$(docker inspect -f '{{.State.Running}}' "$GARAGE_CONTAINER")" = true ]; }
garage_stop() {
  docker stop "$GARAGE_CONTAINER" >/dev/null && ! garage_running && ! s3 "$BUCKET?list-type=2" -f -o /dev/null 2>/dev/null
}
# Started, and answering signed requests again
garage_start() { docker start "$GARAGE_CONTAINER" >/dev/null && poll "$WAIT" s3_keys "$PREFIX"; }

# req <curl args...>: calls the JSON API with the device token, the status in $status and the body in $OUT
status=
req() { status=$(curl -s -o "$OUT" -w '%{http_code}' -H "Authorization: Bearer $TOKEN" "$@") || status=000; }
expect() {
  [ "$status" = "$1" ] && return 0
  echo "  $2: HTTP $status, expected $1: $(head -c 300 "$OUT")"
  return 1
}
sign_in() {
  local body
  body=$(jq -n '{username: "admin", password: "admin", name: "storage smoke", device: {id: "storage-smoke"}}')
  TOKEN=$(curl -fsS -H 'Content-Type: application/json' -d "$body" "$BASE/api/v1/auth/tokens" | jq -r .token)
  grep -Eq '^hd_[A-Za-z0-9_-]{43}$' <<<"$TOKEN"
}
SID=
web_sign_in() {
  SID=$(curl -fsS "$BASE/services/rest/auth/login?u=admin&pw=admin" | tr -d '"[:space:]')
  grep -Eq '^[0-9a-f-]{36}$' <<<"$SID"
}

# child <parent folder id> <jq test applied to the name>: the first child folder that passes
child() {
  api -H 'Accept: application/json' "$BASE/services/rest/folder/listChildren?folderId=$1" \
    | jq -r "[.[] | select(.name | $2)][0].id // empty"
}

# The document's CE id, by its doc #, which the API names it by
doc_id() { db_query "select ld_id from ld_document where ld_customid = '$1' and ld_deleted = 0"; }
# key <doc id>: the file's place in CE's layout (123/45/doc/1.0), as on every backend
key() { printf '%s/doc/1.0' "$(printf '%s' "$1" | sed -E 's/(...)/\1\//g; s#/$##')"; }
backend_of() { db_query "select hd_backendid from hd_storage_file where hd_docid = $1 and hd_resource = '1.0'"; }
on() { [ "$(backend_of "$2")" = "$1" ]; }
# cached <doc id>: the read cache has an entry for the file (<backend>/<doc id>/<resource>@<write>)
cached() { app sh -c 'ls "$1" 2>/dev/null | grep -q "^1\.0@"' sh "$CACHE/$GARAGE/$1"; }
# held <doc id>: a held row names the file's object on Garage, by its key within the backend's prefix
held() {
  [ "$(db_query "select count(*) from hd_storage_held where hd_backendid = $GARAGE and hd_docid = $1 \
    and hd_location = '$(key "$1")'")" = 1 ]
}

# pdf <file> <text>: a one-page PDF showing the text, its cross-reference table right
pdf() {
  local out=$1 stream="BT /F1 12 Tf 20 100 Td ($2) Tj ET" i offsets="" xref o
  local objects=("<< /Type /Catalog /Pages 2 0 R >>" "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"
    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>"
    "<< /Length ${#stream} >>\nstream\n$stream\nendstream" "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
  printf '%%PDF-1.4\n' > "$out"
  for i in 1 2 3 4 5; do
    offsets="$offsets $(wc -c < "$out" | tr -d ' ')"
    printf '%d 0 obj\n%b\nendobj\n' "$i" "${objects[i - 1]}" >> "$out"
  done
  xref=$(wc -c < "$out" | tr -d ' ')
  printf 'xref\n0 6\n0000000000 65535 f \n' >> "$out"
  for o in $offsets; do printf '%010d 00000 n \n' "$o" >> "$out"; done
  printf 'trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n' "$xref" >> "$out"
}
# make <name> [pdf]: a text file, or a PDF, holding a new random token; sets <NAME>_TOKEN, <NAME>_SHA and <NAME>_PATH
make() {
  local token path=$WORK/storage-$1.${2:-txt}
  token=$(od -An -tx1 -N16 /dev/urandom | tr -d ' \n')
  if [ "${2:-txt}" = pdf ]; then
    pdf "$path" "$token"
  else
    printf 'Healdan storage smoke test, file %s.\n%s\n' "$1" "$token" > "$path"
  fi
  eval "${1}_TOKEN=$token ${1}_SHA=$(sha "$path") ${1}_PATH=$path"
}
# file <name> <folder id>: files it through the API; sets <NAME>_NUM (its doc #) and <NAME>_ID (CE's id)
file() {
  local number id path
  eval "path=\$${1}_PATH"
  req -F "document={\"folderId\": $2};type=application/json" -F "file=@$path;filename=${path##*/}" \
    "$BASE/api/v1/documents"
  expect 201 "POST /documents ($1)" || return 1
  number=$(jq -r .docNumber "$OUT")
  id=$(doc_id "$number")
  grep -Eq '^[0-9]+$' <<<"$id" || { echo "  no document for doc # $number"; return 1; }
  eval "${1}_NUM=$number ${1}_ID=$id"
}
# reads <doc #> <sha-256>: the API's content of the document is those bytes
reads() {
  req "$BASE/api/v1/documents/$1/content"
  expect 200 "GET /documents/$1/content" || return 1
  [ "$(sha "$OUT")" = "$2" ] || { echo "  doc # $1's content isn't the file uploaded"; return 1; }
}
# in_garage <doc id> <token>: the file's object is in the bucket, holds something, and not the token
in_garage() {
  local k=$PREFIX$(key "$1")
  listed "$k" || { echo "  $k isn't listed in $BUCKET"; return 1; }
  s3 "$BUCKET/$k" -f -o "$WORK/object" || { echo "  $k can't be fetched"; return 1; }
  [ -s "$WORK/object" ] || { echo "  $k is empty"; return 1; }
  if grep -qF "$2" "$WORK/object"; then echo "  $k holds the file's token: it isn't encrypted"; return 1; fi
}
# at_home <doc id> <sha-256>: the file is in Original's folder, with its bytes
at_home() {
  local got
  got=$(app sha256sum "$ORIGINAL/$(key "$1")" 2>/dev/null | cut -d' ' -f1)
  [ "$got" = "$2" ] || { echo "  $ORIGINAL/$(key "$1") is missing or isn't the file uploaded"; return 1; }
}
# timed <label> <command...>: runs the command and prints how long it took
timed() {
  local label=$1 started rc=0
  shift
  started=$(date +%s)
  "$@" || rc=$?
  echo "  $label took $(( $(date +%s) - started )) s"
  return $rc
}
# outage_api <doc #>: the API's content answers 503 storage-unreachable naming the Store
outage_api() {
  req "$BASE/api/v1/documents/$1/content"
  expect 503 "GET /documents/$1/content during the outage" || return 1
  [ "$(jq -r .code "$OUT")" = storage-unreachable ] && [ "$(jq -r .detail "$OUT")" = "$OUTAGE" ] && return 0
  echo "  not the outage's problem: $(head -c 300 "$OUT")"
  return 1
}
# outage_page <url>: answered 503 with the outage's message, escaped as HTML
outage_page() {
  local code
  code=$(curl -s -o "$OUT" -w '%{http_code}' -b "ldoc-sid=$SID" "$1") || code=000
  [ "$code" = 503 ] || { echo "  $1: HTTP $code, expected 503: $(head -c 300 "$OUT")"; return 1; }
  grep -qF "$OUTAGE_HTML" "$OUT" || { echo "  $1 doesn't give the message: $(head -c 300 "$OUT")"; return 1; }
}
# convertpdf_outages: how many times CE's /convertpdf logged a read that failed for want of the storage
convertpdf_outages() {
  app sh -c 'grep -c "ConvertPdf.*can.t be reached right now" /opt/logicaldoc/repository/logs/dms.log || true'
}
# placeholder <doc id>: /convertpdf serves CE's notavailable.pdf, after a read that met the outage
placeholder() {
  local before code
  before=$(convertpdf_outages)
  code=$(curl -s -o "$OUT" -w '%{http_code}' -b "ldoc-sid=$SID" "$BASE/convertpdf?docId=$1") || code=000
  [ "$code" = 200 ] || { echo "  /convertpdf: HTTP $code, expected 200: $(head -c 300 "$OUT")"; return 1; }
  cmp -s "$OUT" logicaldoc-core/src/main/resources/pdf/notavailable.pdf \
    || { echo "  /convertpdf didn't serve notavailable.pdf: $(head -c 100 "$OUT")"; return 1; }
  [ "$(convertpdf_outages)" -gt "$before" ] || { echo "  /convertpdf's read of $1 didn't meet the outage"; return 1; }
}
# ticket <doc id>: a shared link to the document's file, as /download-ticket's address on this stack
ticket() {
  local answer id
  answer=$(api -d "docId=$1" "$BASE/services/rest/document/createDownloadTicket") || return 1
  id=$(sed -n 's/.*ticketId=\([^"&]*\).*/\1/p' <<<"$answer")
  [ -n "$id" ] || { echo "  no ticket in: $answer" >&2; return 1; }
  echo "$BASE/download-ticket?ticketId=$id"
}
# rows: the files' rows of the documents, as the restart must leave them
rows() {
  db_query "select hd_docid, hd_resource, hd_backendid, coalesce(hd_storeid, 0), hd_size, hd_write from hd_storage_file
    where hd_docid in ($A_ID, $B_ID, $C_ID, $D_ID, $E_ID, $F_ID) order by hd_docid, hd_resource"
}

check "a device token for admin" sign_in
check "a web session for admin" web_sign_in
GARAGE=$(db_query "select ld_id from hd_storage_backend where hd_name = '$BACKEND_NAME' and ld_deleted = 0")
check "the seed's backend $BACKEND_NAME is there" grep -Eq '^[0-9]+$' <<<"$GARAGE"
[ "$FAILED" = 0 ] || exit 1

if [ "$MODE" = after-restart ]; then
  # shellcheck disable=SC1090
  . "$STATE"
  check "the rows are as before the restart" diff "$STATE.rows" <(rows)
  check "A reads from Original" reads "$A_NUM" "$A_SHA"
  check "D reads from Original" reads "$D_NUM" "$D_SHA"
  check "C was never cached" eval '! cached "$C_ID"'
  check "C reads through Garage, its first read" reads "$C_NUM" "$C_SHA"
  check "Garage stopped" garage_stop
  check "E is in the cache, rebuilt from its folder" cached "$E_ID"
  check "E reads from the cache" reads "$E_NUM" "$E_SHA"
  check "Garage started again" garage_start
  for f in A D; do
    eval "id=\$${f}_ID"
    check "Garage still holds $f's object, held" eval 'listed "$PREFIX$(key "$id")" && held "$id"'
  done
  finish
  exit 0
fi

# The folders: Smoke (1.1) and Smoke Cloud (1.2 and 1.3), each matter's Documents
smoke=$(child 5 '. == "Smoke"')
cloud=$(child 5 '. == "Smoke Cloud"')
m11=$(child "$smoke" 'startswith("1.1 ")')
m12=$(child "$cloud" 'startswith("1.2 ")')
m13=$(child "$cloud" 'startswith("1.3 ")')
docs11=$(child "$m11" '. == "Documents"')
docs12=$(child "$m12" '. == "Documents"')
docs13=$(child "$m13" '. == "Documents"')
check "the matters' Documents folders resolved" grep -Eq '^[0-9]+ [0-9]+ [0-9]+$' <<<"$docs11 $docs12 $docs13"
[ "$FAILED" = 0 ] || exit 1
for f in A B C D E; do make "$f"; done
make F pdf

echo "-- 1. encrypted in Garage"
check "A filed in matter 1.2" file A "$docs12"
check "A's row points at Garage within $WAIT s" poll "$WAIT" on "$GARAGE" "$A_ID"
check "A's object is in $BUCKET, and doesn't hold its token" in_garage "$A_ID" "$A_TOKEN"

echo "-- 2. moved up"
check "B filed in matter 1.1" file B "$docs11"
check "C filed in matter 1.1" file C "$docs11"
check "F, a PDF, filed in matter 1.1" file F "$docs11"
check "B, C and F are on Original" eval 'on 1 "$B_ID" && on 1 "$C_ID" && on 1 "$F_ID"'
for f in B C F; do
  eval "id=\$${f}_ID token=\$${f}_TOKEN"
  check "$f moved into matter 1.3" api -X PUT -o /dev/null "$BASE/services/rest/document/move?docId=$id&folderId=$docs13"
  check "$f's row points at Garage within $WAIT s" poll "$WAIT" on "$GARAGE" "$id"
  check "$f's object is in $BUCKET, encrypted" in_garage "$id" "$token"
  check "$f isn't cached" eval '! cached "$id"'
done

echo "-- 3. the same bytes back"
check "B reads back through Garage" reads "$B_NUM" "$B_SHA"
check "Garage stopped" garage_stop
check "A reads back from the cache" reads "$A_NUM" "$A_SHA"

echo "-- 4. an outage"
check "D filed in matter 1.2 with Garage stopped" file D "$docs12"
check "D's row says staging" on 0 "$D_ID"
check "C, never read, answers 503 storage-unreachable naming Smoke Cloud" timed "the API's read of C" outage_api "$C_NUM"
check "the web page's /download of C answers 503 with the message" timed "/download of C" \
  outage_page "$BASE/download?docId=$C_ID"
link=$(ticket "$C_ID" || true)
check "a shared link to C answers 503 with the message" timed "/download-ticket of C" outage_page "$link"
check "/convertpdf of F, a PDF never read, serves CE's notavailable.pdf" timed "/convertpdf of F" placeholder "$F_ID"
check "A, cached, still reads" reads "$A_NUM" "$A_SHA"
check "B, cached, still reads" reads "$B_NUM" "$B_SHA"
check "Garage started" garage_start
check "D's row points at Garage within $WAIT s" poll "$WAIT" on "$GARAGE" "$D_ID"
check "D's object is in $BUCKET, encrypted" in_garage "$D_ID" "$D_TOKEN"

echo "-- 5. moving home"
check "matter 1.2 moved to Smoke" api -X PUT -o /dev/null "$BASE/services/rest/folder/move?folderId=$m12&parentId=$smoke"
for f in A D; do
  eval "id=\$${f}_ID digest=\$${f}_SHA"
  check "$f's row points at Original within $WAIT s" poll "$WAIT" on 1 "$id"
  check "$f is in Original's folder" at_home "$id" "$digest"
  check "Garage still lists $f's object" listed "$PREFIX$(key "$id")"
  check "$f's object is held" held "$id"
done

echo "-- 6. staying in the cloud"
check "E filed in matter 1.3" file E "$docs13"
check "E's row points at Garage within $WAIT s" poll "$WAIT" on "$GARAGE" "$E_ID"
check "E's object is in $BUCKET, encrypted" in_garage "$E_ID" "$E_TOKEN"

# What the run after the restart reads back
{
  for f in A B C D E F; do
    eval "echo ${f}_ID=\$${f}_ID ${f}_NUM=\$${f}_NUM ${f}_SHA=\$${f}_SHA"
  done
} > "$STATE"
rows > "$STATE.rows"

finish
