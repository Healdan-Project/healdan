#!/usr/bin/env bash
# Full-text check for Office and email files (M1 spec §9). Uploads a .docx and an .eml, each holding a nonsense
# token, asks the server to index them and searches for the tokens. CE doesn't extract text from these formats, so
# on a stack without Healdan's parsers both checks FAIL; they pass once the Healdan parsers are in.
# Usage: BASE=http://127.0.0.1:8080 [FOLDER_ID=<id>] docker/tests/test-fulltext.sh   (needs curl, jq, python3)
set -euo pipefail
cd "$(dirname "$0")/../.."
. docker/tests/lib.sh

BASE=${BASE:-http://127.0.0.1:8080}
TIMEOUT=${FULLTEXT_TIMEOUT:-600}
INTERVAL=15
DOCX_TOKEN=zqxhealdandocx
EML_TOKEN=zqxhealdaneml
RUN=$$
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

# The REST endpoints authenticate with HTTP basic auth.
api() { curl -fsS -u admin:admin "$@"; }

python3 - "$WORK" "$DOCX_TOKEN" "$EML_TOKEN" <<'PY'
import pathlib, sys, zipfile
out, docx_token, eml_token = pathlib.Path(sys.argv[1]), sys.argv[2], sys.argv[3]
with zipfile.ZipFile(out / "fulltext.docx", "w", zipfile.ZIP_DEFLATED) as z:
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
               '<w:p><w:r><w:t>Healdan full-text fixture %s</w:t></w:r></w:p></w:body></w:document>' % docx_token)
(out / "fulltext.eml").write_text(
    "From: sender@example.com\r\nTo: recipient@example.com\r\nSubject: Healdan full-text fixture\r\n"
    "Date: Sat, 17 Oct 2026 10:00:00 +0000\r\nMessage-ID: <fulltext@example.com>\r\nMIME-Version: 1.0\r\n"
    "Content-Type: text/plain; charset=UTF-8\r\n\r\nHealdan full-text fixture %s\r\n" % eml_token)
PY

if [ -z "${FOLDER_ID:-}" ]; then
  FOLDER_ID=$(api -H 'Accept: application/json' "$BASE/services/rest/folder/getDefaultWorkspace" | jq -r .id)
fi
echo "target: $BASE, folder $FOLDER_ID"

# upload <file> <name>: prints the new document id.
upload() {
  api -H 'Accept: application/json' -F "folderId=$FOLDER_ID" -F "filename=$2" -F "filedata=@$WORK/$1" \
    "$BASE/services/rest/document/upload"
}

# indexed_state <id>: prints the document's indexed flag (0 to index, 1 indexed, 2 skipped).
indexed_state() {
  api -H 'Accept: application/json' "$BASE/services/rest/document/getDocument?docId=$1" | jq -r .indexed
}

# hit_ids <token>: prints the ids of the documents a full-text search for the token finds. The REST JSON renders a
# single hit as an object and several as an array, and no hit as no "hits" at all.
hit_ids() {
  api -H 'Content-Type: application/json' -H 'Accept: application/json' \
    -d "{\"expression\":\"$1\",\"type\":0,\"maxHits\":50,\"expressionLanguage\":\"en\"}" \
    "$BASE/services/rest/search/find" | jq -r '.hits // empty | if type == "array" then .[] else . end | .id'
}

# found <token> <doc id>: polls the search until the token finds the document or the timeout ends.
# Reports "indexed but token not found" separately from "not indexed", so a miss is unambiguous. The reindex call is
# synchronous, so a document that is flagged indexed and still unfound after one more poll is a real miss: stop early.
found() {
  local token=$1 id=$2 deadline=$((SECONDS + TIMEOUT)) state hits seen_indexed=0
  while :; do
    hits=$(hit_ids "$token")
    if grep -qx "$id" <<<"$hits"; then return 0; fi
    state=$(indexed_state "$id")
    [ "$state" = 1 ] && seen_indexed=$((seen_indexed + 1)) || seen_indexed=0
    if [ "$SECONDS" -ge "$deadline" ] || [ "$seen_indexed" -ge 2 ]; then
      case $state in
        1) echo "  doc $id: indexed (flag 1) but a search for '$token' finds nothing: no text was extracted" ;;
        *) echo "  doc $id: still not indexed (flag $state) after ${TIMEOUT}s; the result says nothing about extraction" ;;
      esac
      return 1
    fi
    sleep "$INTERVAL"
  done
}

docx_id=$(upload fulltext.docx "fulltext-$RUN.docx")
eml_id=$(upload fulltext.eml "fulltext-$RUN.eml")
check ".docx uploaded" grep -Eq '^[0-9]+$' <<<"$docx_id"
check ".eml uploaded" grep -Eq '^[0-9]+$' <<<"$eml_id"
[ "$FAILED" = 0 ] || exit 1

# Ask for indexing now rather than waiting for the scheduled task (the form field is really named doc1).
for id in "$docx_id" "$eml_id"; do
  api -d "doc1=$id" "$BASE/services/rest/document/reindex" >/dev/null
done

check "$DOCX_TOKEN found" found "$DOCX_TOKEN" "$docx_id"
check "$EML_TOKEN found" found "$EML_TOKEN" "$eml_id"

finish
