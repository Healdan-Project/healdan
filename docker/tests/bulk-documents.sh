#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 The Healdan Project
# SPDX-License-Identifier: LGPL-3.0-only

# Files COUNT small generated documents of mixed types (.docx, .pdf, .xlsx, .eml, .odt) into one folder through
# POST /api/v1/documents, five at a time, to measure big folders and searches (M6 plan, Tasks 9 and 10, and the
# owner's walkthrough). Each file has words of its own and each .eml its own Message-ID, new on every run, as a
# matter holds a message once. Only for a throwaway or test stack: it fills the folder for good.
# Usage: docker/tests/bulk-documents.sh BASE TOKEN FOLDER_ID COUNT [WORD]
#    or: BASE=http://127.0.0.1:8080 TOKEN=hd_... FOLDER_ID=123 COUNT=2000 docker/tests/bulk-documents.sh
# TOKEN is a device token (POST /api/v1/auth/tokens) of a user who may file into the folder. WORD, if given, is in
# every file's text, for a search that finds them all. Needs curl and python3.
set -euo pipefail

BASE=${1:-${BASE:-}}
TOKEN=${2:-${TOKEN:-}}
FOLDER_ID=${3:-${FOLDER_ID:-}}
COUNT=${4:-${COUNT:-}}
WORD=${5:-${WORD:-}}
if [ -z "$BASE" ] || [ -z "$TOKEN" ] || ! [[ $FOLDER_ID =~ ^[0-9]+$ ]] || ! [[ $COUNT =~ ^[0-9]+$ ]]; then
  echo "Usage: $0 BASE TOKEN FOLDER_ID COUNT [WORD]   (or set BASE, TOKEN, FOLDER_ID, COUNT and WORD)" >&2
  exit 2
fi

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
mkdir "$WORK/files" "$WORK/out"

python3 - "$WORK/files" "$COUNT" "$WORD" <<'PY'
import email.message, email.policy, email.utils, pathlib, random, sys, uuid, zipfile

out, count, word = pathlib.Path(sys.argv[1]), int(sys.argv[2]), sys.argv[3]
random.seed(20261110)
TOPICS = ["Lease", "Letter", "Board minutes", "Witness statement", "Invoice", "Engagement", "Settlement",
          "Disclosure", "Memo", "Draft agreement", "Schedule", "Notice", "Opinion", "Chronology", "Costs"]
WORDS = ["tenant", "landlord", "claim", "hearing", "indemnity", "warranty", "breach", "notice", "deadline",
         "counsel", "estate", "transfer", "covenant", "liability", "consent", "evidence", "invoice", "minutes"]

def text(i):
    words = random.sample(WORDS, 6) + ([word] if word else [])
    return "Healdan bulk document %d: %s." % (i, " ".join(words))

def ooxml(path, main, parts, body):
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("[Content_Types].xml",
                   '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
                   '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
                   '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
                   '<Default Extension="xml" ContentType="application/xml"/>' + parts + '</Types>')
        z.writestr("_rels/.rels",
                   '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
                   '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
                   '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" '
                   'Target="%s"/></Relationships>' % main)
        for name, content in body:
            z.writestr(name, content)

def docx(path, words):
    ooxml(path, "word/document.xml",
          '<Override PartName="/word/document.xml" '
          'ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>',
          [("word/document.xml",
            '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>'
            '<w:p><w:r><w:t>%s</w:t></w:r></w:p></w:body></w:document>' % words)])

def xlsx(path, words):
    ooxml(path, "xl/workbook.xml",
          '<Override PartName="/xl/workbook.xml" '
          'ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>'
          '<Override PartName="/xl/worksheets/sheet1.xml" '
          'ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>',
          [("xl/workbook.xml",
            '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" '
            'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">'
            '<sheets><sheet name="Sheet1" sheetId="1" r:id="rId1"/></sheets></workbook>'),
           ("xl/_rels/workbook.xml.rels",
            '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
            '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" '
            'Target="worksheets/sheet1.xml"/></Relationships>'),
           ("xl/worksheets/sheet1.xml",
            '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>'
            '<row r="1"><c r="A1" t="inlineStr"><is><t>%s</t></is></c></row></sheetData></worksheet>' % words)])

def odt(path, words):
    with zipfile.ZipFile(path, "w") as z:
        # The mimetype comes first, stored, as OpenDocument asks
        z.writestr(zipfile.ZipInfo("mimetype"), "application/vnd.oasis.opendocument.text")
        z.writestr("META-INF/manifest.xml",
                   '<?xml version="1.0" encoding="UTF-8"?>'
                   '<manifest:manifest xmlns:manifest="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0" '
                   'manifest:version="1.2">'
                   '<manifest:file-entry manifest:full-path="/" manifest:media-type="application/vnd.oasis.opendocument.text"/>'
                   '<manifest:file-entry manifest:full-path="content.xml" manifest:media-type="text/xml"/>'
                   '</manifest:manifest>', zipfile.ZIP_DEFLATED)
        z.writestr("content.xml",
                   '<?xml version="1.0" encoding="UTF-8"?>'
                   '<office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" '
                   'xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" office:version="1.2">'
                   '<office:body><office:text><text:p>%s</text:p></office:text></office:body>'
                   '</office:document-content>' % words, zipfile.ZIP_DEFLATED)

def pdf(path, words):
    stream = b"BT /F1 12 Tf 72 720 Td (" + words.encode("ascii", "replace") + b") Tj ET"
    objects = [b"<< /Type /Catalog /Pages 2 0 R >>",
               b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
               b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R "
               b"/Resources << /Font << /F1 5 0 R >> >> >>",
               b"<< /Length %d >>\nstream\n" % len(stream) + stream + b"\nendstream",
               b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"]
    data = bytearray(b"%PDF-1.4\n")
    offsets = []
    for number, body in enumerate(objects, 1):
        offsets.append(len(data))
        data += b"%d 0 obj\n" % number + body + b"\nendobj\n"
    xref = len(data)
    data += b"xref\n0 %d\n0000000000 65535 f \n" % (len(objects) + 1)
    for offset in offsets:
        data += b"%010d 00000 n \n" % offset
    data += b"trailer\n<< /Size %d /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n" % (len(objects) + 1, xref)
    path.write_bytes(bytes(data))

def eml(path, words, i, subject):
    mail = email.message.EmailMessage()
    mail["From"] = "Pat Client <pat@example.com>"
    mail["To"] = "Healdan <healdan@example.com>"
    mail["Subject"] = subject
    mail["Date"] = email.utils.format_datetime(email.utils.localtime())
    # Not from the seeded random: two runs into one matter would file the same messages, which it refuses
    mail["Message-ID"] = "<bulk-%s-%d@healdan.test>" % (uuid.uuid4().hex, i)
    mail.set_content(words)
    path.write_bytes(mail.as_bytes(policy=email.policy.SMTP))

KINDS = [("docx", docx), ("pdf", pdf), ("xlsx", xlsx), ("eml", None), ("odt", odt)]
for i in range(1, count + 1):
    extension, make = KINDS[i % len(KINDS)]
    subject = "%s %04d" % (random.choice(TOPICS), i)
    path = out / ("%s.%s" % (subject, extension))
    if make is None:
        eml(path, text(i), i, subject)
    else:
        make(path, text(i))
PY

# upload <file>: files one document; says which failed, and every hundredth that worked
upload() {
  local file=$1 name status
  name=$(basename "$file")
  status=$(curl -s -o "$OUTDIR/$name.out" -w '%{http_code}' -H "Authorization: Bearer $TOKEN" \
    -F "document={\"folderId\": $FOLDER_ID, \"checkOut\": false};type=application/json" \
    -F "file=@$file;filename=$name" "$BASE/api/v1/documents") || status=000
  if [ "$status" != 201 ]; then
    echo "$name: HTTP $status: $(head -c 300 "$OUTDIR/$name.out")" >&2
    return 1
  fi
  [[ $name =~ 00\. ]] && echo "filed $name"
  return 0
}
export -f upload
export BASE TOKEN FOLDER_ID
export OUTDIR=$WORK/out

started=$SECONDS
# shellcheck disable=SC2016 # the inner bash expands $1, the file xargs hands it
if find "$WORK/files" -type f | sort | xargs -P 5 -I{} bash -c 'upload "$1"' _ {}; then
  echo "Filed $COUNT documents into folder $FOLDER_ID in $((SECONDS - started))s"
else
  echo "Some documents weren't filed: see above" >&2
  exit 1
fi
