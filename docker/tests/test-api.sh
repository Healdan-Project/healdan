#!/usr/bin/env bash
# The Office add-in's flow against the JSON API in the real Docker image (M2 spec §14.1): sign in, find a matter,
# file a document, check it out, save twice, read it back, hit the sign-in throttle and sign out. In-process tests
# can't reach CE's real filter chain (XssFilter, SessionFilter and the rest), so this proves a Bearer token passes it.
# Needs the smoke fixture (Store "Smoke" > "1.1 - Smoke Matter" > Documents) and a running stack.
# Usage: BASE=http://127.0.0.1:8080 docker/tests/test-api.sh   (needs curl, jq, python3, cmp)
set -euo pipefail
cd "$(dirname "$0")/../.."
. docker/tests/lib.sh

BASE=${BASE:-http://127.0.0.1:8080}
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
# A throwaway password for a throwaway user on a throwaway stack.
USER_NAME=apiuser
USER_PW='Api-Smoke-Pw-42!x'
OUT=$WORK/body
TOKEN=

# Three .docx files that differ in their text, so that content checks can tell the uploads apart.
python3 - "$WORK" <<'PY'
import pathlib, sys, zipfile
out = pathlib.Path(sys.argv[1])
def docx(path, text):
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("[Content_Types].xml",
                   '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
                   '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
                   '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
                   '<Default Extension="xml" ContentType="application/xml"/>'
                   '<Override PartName="/word/document.xml" '
                   'ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>'
                   '</Types>')
        z.writestr("_rels/.rels",
                   '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
                   '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
                   '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" '
                   'Target="word/document.xml"/></Relationships>')
        z.writestr("word/document.xml",
                   '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
                   '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>'
                   '<w:p><w:r><w:t>%s</w:t></w:r></w:p></w:body></w:document>' % text)
for name in ("first", "second", "third"):
    docx(out / ("api-%s.docx" % name), "Healdan API fixture " + name)
PY

# req <expected status> <curl args...>: calls the API and leaves the body in $OUT. Sends the bearer token when set.
status=
req() {
  local auth=()
  [ -z "$TOKEN" ] || auth=(-H "Authorization: Bearer $TOKEN")
  status=$(curl -s -o "$OUT" -w '%{http_code}' ${auth[@]+"${auth[@]}"} "$@") || status=000
}
# expect <status> <what>: succeeds when the last req answered that status; otherwise says what came back.
expect() {
  [ "$status" = "$1" ] && return 0
  echo "  $2: HTTP $status, expected $1: $(head -c 300 "$OUT")"
  return 1
}
# json_is <jq filter> <expected>: the last body's value at the filter equals the expected text.
json_is() {
  local got
  got=$(jq -r "$1" "$OUT" 2>/dev/null || echo '<not JSON>')
  [ "$got" = "$2" ] && return 0
  echo "  $1 is '$got', expected '$2': $(head -c 300 "$OUT")"
  return 1
}

# --- Setup: a non-admin user, over SOAP, as the stack notes describe. -----------------------------------------
admin_sid=$(curl -fsS -d username=admin -d password=admin "$BASE/services/rest/auth/loginForm" | tr -d '"[:space:]')
soap_user=$(cat <<XML
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:ws="http://ws.logicaldoc.com">
<soapenv:Body><ws:storeUser><sid>$admin_sid</sid><user>
<username>$USER_NAME</username><password>$USER_PW</password><name>Api</name><firstName>User</firstName>
<email>apiuser@example.com</email><language>en</language><enabled>true</enabled>
</user></ws:storeUser></soapenv:Body></soapenv:Envelope>
XML
)
soap_code=$(curl -s -o "$OUT" -w '%{http_code}' -H 'Content-Type: text/xml; charset=utf-8' -H 'SOAPAction: ""' \
  -d "$soap_user" "$BASE/services/Security")
check "setup: the non-admin user $USER_NAME stored over SOAP" test "$soap_code" = 200
[ "$FAILED" = 0 ] || { head -c 500 "$OUT"; exit 1; }

# Each step is a function, so that `check` reports it as one line. They run in the current shell and share state.
JSON=(-H 'Content-Type: application/json')
folder_id=
docs_id=
doc=
me_id=

step1() { req "$BASE/api/v1/server"; expect 200 "GET /server" && json_is .product Healdan; }
step2() {
  req "$BASE/api/docs/"; expect 200 "GET /api/docs/" || return 1
  req "$BASE/api/v1/openapi.yaml"; expect 200 "GET /openapi.yaml"
}
# The admin's session id as a cookie is not an API credential: only a device token is.
step3() { req -H "Cookie: ldoc-sid=$admin_sid" "$BASE/api/v1/me"; expect 401 "GET /me with a cookie and no token"; }
# CXF's own /rest servers must not show up under /api.
step4() { req "$BASE/api/rest/system/getInfo"; expect 404 "GET /api/rest/system/getInfo"; }
step5() {
  req "${JSON[@]}" -d "$(jq -n --arg u "$USER_NAME" --arg p "$USER_PW" '{username: $u, password: $p, name: "smoke"}')" \
    "$BASE/api/v1/auth/tokens"
  expect 201 "POST /auth/tokens" || return 1
  TOKEN=$(jq -r .token "$OUT")
  grep -Eq '^hd_[A-Za-z0-9_-]{43}$' <<<"$TOKEN" || { echo "  no token in the answer"; TOKEN=; return 1; }
  # The token goes through CE's real filter chain (XssFilter canonicalizes every header) and signs us in.
  req "$BASE/api/v1/me"; expect 200 "GET /me with the token" && json_is .username "$USER_NAME" || return 1
  me_id=$(jq -r .id "$OUT")
}
step6() {
  req -G --data-urlencode scope=matters --data-urlencode q=1.1 "$BASE/api/v1/search"
  expect 200 "GET /search" || return 1
  json_is '.items | length' 1 && json_is '.items[0].type' matter && json_is '.items[0].name' 'Smoke Matter' || return 1
  json_is '.items[0].display' $'1.1 \xe2\x80\x93 Smoke Matter' || return 1
  folder_id=$(jq -r '.items[0].folderId' "$OUT")
  grep -Eq '^[0-9]+$' <<<"$folder_id" || { echo "  no folderId"; return 1; }
}
step7() {
  req "$BASE/api/v1/folders/$folder_id/children?type=folder"; expect 200 "GET /folders/$folder_id/children" || return 1
  docs_id=$(jq -r '[.items[] | select(.name == "Documents" and .type == "folder")][0].id // empty' "$OUT")
  grep -Eq '^[0-9]+$' <<<"$docs_id" || { echo "  no Documents folder among: $(head -c 300 "$OUT")"; return 1; }
  req "$BASE/api/v1/folders/$folder_id"; expect 200 "GET /folders/$folder_id" || return 1
  json_is .canFile false || return 1
  req "$BASE/api/v1/folders/$docs_id"; expect 200 "GET /folders/$docs_id" && json_is .canFile true
}
step8() {
  req "$BASE/api/v1/folders/$docs_id/form"; expect 200 "GET /folders/$docs_id/form" || return 1
  json_is '[.items[] | select(.name == "docclass")][0].required' true
}
step9() {
  req -F 'document={"folderId": '"$docs_id"', "checkOut": false};type=application/json' \
    -F "file=@$WORK/api-first.docx;filename=api.docx" "$BASE/api/v1/documents"
  expect 201 "POST /documents" || return 1
  doc=$(jq -r .docNumber "$OUT")
  grep -Eq '^[0-9]+$' <<<"$doc" || { echo "  no docNumber"; return 1; }
  json_is .hold null && json_is .version 1.0
}
step10() {
  req "$BASE/api/v1/matters/recent"; expect 200 "GET /matters/recent" || return 1
  json_is '[.items[] | select(.folderId == '"$folder_id"')] | length' 1
}
step11() {
  req -G --data-urlencode "q=#$doc" "$BASE/api/v1/lookup"; expect 200 "GET /lookup" && json_is .docNumber "$doc"
}
step12() {
  req -X POST "$BASE/api/v1/documents/$doc/checkout"; expect 200 "POST /checkout" || return 1
  json_is .hold.by.id "$me_id" || return 1
  req "$BASE/api/v1/me/checkouts"; expect 200 "GET /me/checkouts" || return 1
  json_is '[.items[] | select(.docNumber == '"$doc"')] | length' 1
}
step13() {
  req -F 'version={"kind": "snapshot", "keepCheckedOut": true};type=application/json' \
    -F "file=@$WORK/api-second.docx;filename=api.docx" "$BASE/api/v1/documents/$doc/versions"
  expect 201 "POST /versions (snapshot)" || return 1
  json_is .fileVersion 1.1 && json_is .hold.by.id "$me_id"
}
step14() {
  req -F 'version={"kind": "version", "comment": "Sent to the client", "keepCheckedOut": false};type=application/json' \
    -F "file=@$WORK/api-third.docx;filename=api.docx" "$BASE/api/v1/documents/$doc/versions"
  expect 201 "POST /versions (version)" || return 1
  json_is .fileVersion 2.0 && json_is .hold null
}
step15() {
  req "$BASE/api/v1/documents/recent"; expect 200 "GET /documents/recent" || return 1
  json_is '[.items[] | select(.docNumber == '"$doc"')] | length' 1
}
step16() {
  req "$BASE/api/v1/documents/$doc/content"; expect 200 "GET /content" || return 1
  cmp "$OUT" "$WORK/api-third.docx" || { echo "  the current content is not the last upload"; return 1; }
  req "$BASE/api/v1/documents/$doc/content?version=1.0"; expect 200 "GET /content?version=1.0" || return 1
  cmp "$OUT" "$WORK/api-first.docx" || { echo "  version 1.0 is not the first upload"; return 1; }
}
# Five wrong passwords block the username (the sixth is 429); an unknown name is counted like a real one.
step17() {
  local i
  for i in 1 2 3 4 5; do
    req "${JSON[@]}" -d '{"username": "nosuchuser", "password": "wrong-password"}' "$BASE/api/v1/auth/tokens"
    expect 401 "wrong password $i" || return 1
  done
  req "${JSON[@]}" -d '{"username": "nosuchuser", "password": "wrong-password"}' "$BASE/api/v1/auth/tokens"
  expect 429 "wrong password 6"
}
step18() {
  req -X DELETE "$BASE/api/v1/auth/tokens/current"; expect 204 "DELETE /auth/tokens/current" || return 1
  req "$BASE/api/v1/me"; expect 401 "GET /me with the revoked token"
}

check "1. GET /api/v1/server names Healdan" step1
check "2. the docs page and openapi.yaml are served" step2
check "3. a session cookie without a token is 401" step3
check "4. CXF's /rest services are not under /api" step4
check "5. sign-in gives a token that passes CE's filters" step5
[ -n "$TOKEN" ] || { echo "no token: the rest of the flow can't run"; finish; }
check "6. search finds the Smoke Matter" step6
check "7. the matter's workspace can't be filed into, its Documents folder can" step7
check "8. the Documents form requires a Class" step8
check "9. a .docx is filed through the API" step9
check "10. Recent Matters lists the matter" step10
check "11. lookup resolves #<doc #>" step11
check "12. check-out holds the document and lists it" step12
check "13. a snapshot save keeps it checked out as 1.1" step13
check "14. Save as new version checks it in as 2.0" step14
check "15. Recent Documents lists the document" step15
check "16. content and content?version=1.0 return the right files" step16
check "17. six wrong passwords: 401 five times, then 429" step17
check "18. sign-out revokes the token" step18

finish
