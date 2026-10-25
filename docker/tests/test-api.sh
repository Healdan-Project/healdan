#!/usr/bin/env bash
# The Office add-in's flow against the JSON API in the real Docker image (M2 spec §14.1): sign in, find a matter,
# file a document, check it out, save twice, read it back, then M3's additions (search within a folder, creating a
# folder, favorites, doc ID stamping, a second device, save preconditions, the #<n> v<m> lookup), hit the sign-in
# throttle, check that LogicalDOC's own services refuse the token, and sign out. In-process tests can't reach CE's real filter chain (XssFilter,
# SessionFilter and the rest), so this proves a Bearer token passes it.
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

# A .docx that carries the Healdan Doc ID custom property, which the server fills in when it stores the file (M3).
with zipfile.ZipFile(out / "api-stamped.docx", "w", zipfile.ZIP_DEFLATED) as z:
    z.writestr("[Content_Types].xml",
               '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
               '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
               '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
               '<Default Extension="xml" ContentType="application/xml"/>'
               '<Override PartName="/word/document.xml" '
               'ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>'
               '<Override PartName="/docProps/custom.xml" '
               'ContentType="application/vnd.openxmlformats-officedocument.custom-properties+xml"/>'
               '</Types>')
    z.writestr("_rels/.rels",
               '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
               '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
               '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" '
               'Target="word/document.xml"/>'
               '<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/custom-properties" '
               'Target="docProps/custom.xml"/></Relationships>')
    z.writestr("word/document.xml",
               '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
               '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>'
               '<w:p><w:r><w:t>Healdan API stamped fixture</w:t></w:r></w:p></w:body></w:document>')
    z.writestr("docProps/custom.xml",
               '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
               '<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/custom-properties" '
               'xmlns:vt="http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes">'
               '<property fmtid="{D5CDD505-2E9C-101B-9397-08002B2CF9AE}" pid="2" name="Healdan Doc ID">'
               '<vt:lpwstr>not stamped</vt:lpwstr></property></Properties>')
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

# with_token <token> <command...>: runs the command with that token in place of the first device's.
with_token() {
  local saved=$TOKEN rc=0
  TOKEN=$1
  shift
  "$@" || rc=$?
  TOKEN=$saved
  return $rc
}
# stamp_of <.docx file>: prints the Healdan Doc ID property in the file's docProps/custom.xml.
stamp_of() {
  python3 - "$1" <<'STAMP'
import re, sys, zipfile
xml = zipfile.ZipFile(sys.argv[1]).read("docProps/custom.xml").decode("utf-8")
m = re.search(r'name="Healdan Doc ID"[^>]*>\s*<vt:lpwstr>([^<]*)</vt:lpwstr>', xml)
print(m.group(1) if m else "<no property>")
STAMP
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
  req "${JSON[@]}" -d "$(jq -n --arg u "$USER_NAME" --arg p "$USER_PW" '{username: $u, password: $p, name: "smoke", device: {id: "ci-1"}}')" \
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
# A device token works only under /api (M2 spec §5.1): sent as LogicalDOC's API key, it gets no session. CE answers
# a refused sign-in there with 500; before the fix it answered 200 and a session id.
step19() {
  local token=$TOKEN
  TOKEN=
  req -H "X-API-KEY: $token" "$BASE/services/rest/auth/loginApiKey"
  TOKEN=$token
  expect 500 "GET /services/rest/auth/loginApiKey with the token as X-API-KEY" || return 1
  if grep -Eq '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-' "$OUT"; then
    echo "  it answered a session id: $(head -c 100 "$OUT")"
    return 1
  fi
}

# --- M3's additions (spec §14.1) -------------------------------------------------------------------------------
TOKEN2=
folder2=
doc2=
version2=
saved_version=
SAVE_ID=$(python3 -c 'import uuid; print(uuid.uuid4())')

step20() { # search inside the matter's workspace
  req -G --data-urlencode scope=folders --data-urlencode q=Documents --data-urlencode "within=$folder_id" \
    "$BASE/api/v1/search"
  expect 200 "GET /search?scope=folders&within=" || return 1
  json_is '[.items[] | select(.id == '"$docs_id"' and .name == "Documents")] | length' 1
}
step21() {
  req "${JSON[@]}" -d "$(jq -n --argjson p "$docs_id" '{parentId: $p, name: "M3 flow"}')" "$BASE/api/v1/folders"
  expect 201 "POST /folders" || return 1
  json_is .name "M3 flow" || return 1
  folder2=$(jq -r .id "$OUT")
  grep -Eq '^[0-9]+$' <<<"$folder2" || { echo "  no folder id"; return 1; }
}
step22() {
  req "${JSON[@]}" -d "$(jq -n --argjson f "$folder2" '{folderId: $f}')" "$BASE/api/v1/me/favorites"
  expect 201 "POST /me/favorites" || return 1
  req "$BASE/api/v1/me/favorites"; expect 200 "GET /me/favorites" || return 1
  json_is '[.items[] | select(.type == "folder" and .item.id == '"$folder2"')] | length' 1
}
step23() { # a file whose Healdan Doc ID property the server fills in
  req -F 'document={"folderId": '"$folder2"', "checkOut": false};type=application/json' \
    -F "file=@$WORK/api-stamped.docx;filename=stamped.docx" "$BASE/api/v1/documents"
  expect 201 "POST /documents (stamped)" || return 1
  doc2=$(jq -r .docNumber "$OUT")
  grep -Eq '^[0-9]+$' <<<"$doc2" || { echo "  no docNumber"; return 1; }
  json_is .version 1.0 || return 1
  req "$BASE/api/v1/documents/$doc2/content"; expect 200 "GET /content (stamped)" || return 1
  local got
  got=$(stamp_of "$OUT")
  [ "$got" = "#$doc2 v1.0" ] || { echo "  the property is '$got', expected '#$doc2 v1.0'"; return 1; }
}
step24() { # a second computer
  req "${JSON[@]}" -d "$(jq -n --arg u "$USER_NAME" --arg p "$USER_PW" \
    '{username: $u, password: $p, name: "smoke 2", device: {id: "ci-2"}}')" "$BASE/api/v1/auth/tokens"
  expect 201 "POST /auth/tokens (ci-2)" || return 1
  TOKEN2=$(jq -r .token "$OUT")
  grep -Eq '^hd_[A-Za-z0-9_-]{43}$' <<<"$TOKEN2" || { echo "  no second token"; TOKEN2=; return 1; }
  req "${JSON[@]}" -d '{"app": "LibreOffice"}' "$BASE/api/v1/documents/$doc2/checkout"
  expect 200 "POST /checkout from ci-1" || return 1
  json_is .hold.device.id ci-1 || return 1
  with_token "$TOKEN2" req "${JSON[@]}" -d '{"app": "LibreOffice"}' "$BASE/api/v1/documents/$doc2/checkout"
  expect 409 "POST /checkout from ci-2" && json_is .code held-elsewhere
}
step25() { # move the check-out; the first computer is then refused, even with ifHeld
  with_token "$TOKEN2" req "${JSON[@]}" -d '{"move": true, "app": "LibreOffice"}' "$BASE/api/v1/documents/$doc2/checkout"
  expect 200 "POST /checkout with move from ci-2" || return 1
  json_is .hold.device.id ci-2 || return 1
  req -F 'version={"kind": "snapshot", "keepCheckedOut": true, "ifHeld": true};type=application/json' \
    -F "file=@$WORK/api-second.docx;filename=stamped.docx" "$BASE/api/v1/documents/$doc2/versions"
  expect 409 "POST /versions from ci-1 after the move" && json_is .code held-elsewhere
}
step26() { # preconditions and repeat-safe saves, from the computer that holds it
  with_token "$TOKEN2" req "$BASE/api/v1/documents/$doc2"; expect 200 "GET /documents/$doc2" || return 1
  version2=$(jq -r .version "$OUT")
  with_token "$TOKEN2" req \
    -F 'version={"kind": "snapshot", "keepCheckedOut": true, "ifVersion": "0.9", "ifHeld": true};type=application/json' \
    -F "file=@$WORK/api-stamped.docx;filename=stamped.docx" "$BASE/api/v1/documents/$doc2/versions"
  expect 409 "POST /versions with ifVersion 0.9" || return 1
  json_is .code version-moved && json_is .document.version "$version2" || return 1
  local part='version={"kind": "snapshot", "keepCheckedOut": true, "ifVersion": "'$version2'", "ifHeld": true, "saveId": "'$SAVE_ID'"};type=application/json'
  with_token "$TOKEN2" req -F "$part" -F "file=@$WORK/api-stamped.docx;filename=stamped.docx" \
    "$BASE/api/v1/documents/$doc2/versions"
  expect 201 "POST /versions with the right ifVersion and a saveId" || return 1
  saved_version=$(jq -r .fileVersion "$OUT")
  [ "$saved_version" != "$version2" ] && [ "$saved_version" != null ] || { echo "  fileVersion is '$saved_version'"; return 1; }
  json_is .hold.device.id ci-2 || return 1
  with_token "$TOKEN2" req -F "$part" -F "file=@$WORK/api-stamped.docx;filename=stamped.docx" \
    "$BASE/api/v1/documents/$doc2/versions"
  expect 201 "POST /versions repeated" && json_is .fileVersion "$saved_version" || return 1
  with_token "$TOKEN2" req "$BASE/api/v1/documents/$doc2/content"; expect 200 "GET /content (saved)" || return 1
  local got
  got=$(stamp_of "$OUT")
  [ "$got" = "#$doc2 v$saved_version" ] || { echo "  the property is '$got', expected '#$doc2 v$saved_version'"; return 1; }
}
step27() {
  req -G --data-urlencode "q=#$doc2 v$saved_version" "$BASE/api/v1/lookup"
  expect 200 "GET /lookup" && json_is .docNumber "$doc2" && json_is .version "$saved_version"
}
step28() { # leave nothing held, and sign the second computer out
  with_token "$TOKEN2" req -X DELETE "$BASE/api/v1/documents/$doc2/checkout"
  expect 200 "DELETE /checkout from ci-2" || return 1
  with_token "$TOKEN2" req -X DELETE "$BASE/api/v1/auth/tokens/current"
  expect 204 "DELETE /auth/tokens/current (ci-2)"
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
# M3's additions run before the throttle step and the sign-outs, on the first token.
check "20. search finds Documents inside the matter's workspace" step20
check "21. POST /folders creates a folder in Documents" step21
check "22. a favorite is added and listed" step22
check "23. a filed .docx has its Healdan Doc ID property stamped" step23
check "24. a second computer's check-out is held-elsewhere" step24
check "25. moving the check-out refuses the first computer's save" step25
check "26. ifVersion is version-moved, a saveId makes a save repeat-safe, the stamp follows" step26
check "27. lookup resolves #<doc #> v<version>" step27
check "28. the check-out is released and the second computer signs out" step28
check "17. six wrong passwords: 401 five times, then 429" step17
# 19 runs before 18, which revokes the token.
check "19. LogicalDOC's loginApiKey refuses the token" step19
check "18. sign-out revokes the token" step18

finish
