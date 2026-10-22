#!/usr/bin/env bash
# Office preview checks (M1.5 spec §11.1). Uploads Word, Excel, PowerPoint and OpenDocument files into a matter
# folder, each holding a nonsense token, and checks that:
# - the background worker converts them ahead of time, before anyone asks for a preview;
# - the PDFs hold the tokens, show tracked changes the file hides, and carry the comment as a PDF note;
# - the thumbnails are PNGs;
# - a conversion fetches no linked content and runs no macro (a probe listener in the app container must stay silent);
# - a broken file fails cleanly, leaving no LibreOffice process;
# - tini, PID 1, reaps orphaned processes.
# Usage: BASE=http://127.0.0.1:8080 FOLDER_ID=<id> DOC_PATH=<the folder's percent-encoded path> SID=<session id>
#        APP_CONTAINER=<id> DB_CONTAINER=<id> docker/tests/test-previews.sh   (needs curl, jq, python3 and docker)
# docker/smoke-test.sh runs it as step 5b.
set -euo pipefail
cd "$(dirname "$0")/../.."
. docker/tests/lib.sh

: "${BASE:?}" "${FOLDER_ID:?}" "${DOC_PATH:?}" "${SID:?}" "${APP_CONTAINER:?}" "${DB_CONTAINER:?}"
TIMEOUT=${PREVIEW_TIMEOUT:-120}
STARTED=$SECONDS
PROBE_PORT=18999
MARKER=/tmp/healdan-macro-ran
WORK=$(mktemp -d)

cleanup() {
  docker exec "$APP_CONTAINER" sh -c \
    'kill "$(cat /tmp/probe.pid 2>/dev/null)" 2>/dev/null; rm -rf /tmp/probe /tmp/probe.log /tmp/probe.pid' \
    >/dev/null 2>&1 || true
  rm -rf "$WORK"
}
trap cleanup EXIT

# The REST endpoints authenticate with HTTP basic auth; /convertpdf takes the session cookie.
api() { curl -fsS -u admin:admin "$@"; }
app() { docker exec "$APP_CONTAINER" "$@"; }
db_query() {
  docker exec "$DB_CONTAINER" sh -c 'MYSQL_PWD="$MARIADB_PASSWORD" mariadb -u"$MARIADB_USER" "$MARIADB_DATABASE" -Nse "$1"' \
    sh "$1"
}
is_png() { [ "$(od -An -tx1 -N4 "$1" | tr -d ' \n')" = 89504e47 ]; }

# Fixtures, adapted from the design spike of 2026-10-22. The two that test hardening point at the probe on 127.0.0.1.
python3 - "$WORK" "$PROBE_PORT" "$MARKER" <<'PY'
import pathlib, random, sys, zipfile
out, port, marker = pathlib.Path(sys.argv[1]), sys.argv[2], sys.argv[3]
X = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
PKG = "http://schemas.openxmlformats.org/package/2006/"
OD = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
OCT = "application/vnd.openxmlformats-officedocument."
W = 'xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="%s"' % OD
S = 'xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="%s"' % OD
P = ('xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="%s" '
     'xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"' % OD)

def rels(*items):  # (id, type, target[, external])
    return X + '<Relationships xmlns="%srelationships">%s</Relationships>' % (PKG, "".join(
        '<Relationship Id="%s" Type="%s/%s" Target="%s"%s/>'
        % (i[0], OD, i[1], i[2], ' TargetMode="External"' if len(i) > 3 else "") for i in items))

def ooxml(name, main, parts, types):  # types: {part: content type after OCT}
    with zipfile.ZipFile(out / name, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("[Content_Types].xml", X + '<Types xmlns="%scontent-types">' % PKG
                   + '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
                   + '<Default Extension="xml" ContentType="application/xml"/>'
                   + "".join('<Override PartName="/%s" ContentType="%s%s"/>' % (p, OCT, t) for p, t in types.items())
                   + "</Types>")
        z.writestr("_rels/.rels", rels(("rId1", "officeDocument", main)))
        for path, xml in parts.items():
            z.writestr(path, xml)

def docx(name, body, settings="", extra_rels=(), comment=None):
    parts = {"word/document.xml": X + "<w:document %s><w:body>%s</w:body></w:document>" % (W, body),
             "word/settings.xml": X + "<w:settings %s>%s</w:settings>" % (W, settings)}
    types = {"word/document.xml": "wordprocessingml.document.main+xml",
             "word/settings.xml": "wordprocessingml.settings+xml"}
    items = [("rId1", "settings", "settings.xml")]
    if comment:
        parts["word/comments.xml"] = (X + '<w:comments %s><w:comment w:id="0" w:author="Ann" w:date="2026-10-22T00:00:00Z" '
                                      'w:initials="A"><w:p><w:r><w:t>%s</w:t></w:r></w:p></w:comment></w:comments>'
                                      % (W, comment))
        types["word/comments.xml"] = "wordprocessingml.comments+xml"
        items.append(("rId2", "comments", "comments.xml"))
    parts["word/_rels/document.xml.rels"] = rels(*(items + list(extra_rels)))
    ooxml(name, "word/document.xml", parts, types)

def para(text):
    return '<w:p><w:r><w:t xml:space="preserve">%s</w:t></w:r></w:p>' % text

def xlsx(name, cell):
    ooxml(name, "xl/workbook.xml", {
        "xl/workbook.xml": X + '<workbook %s><sheets><sheet name="Sheet1" sheetId="1" r:id="rId1"/></sheets></workbook>' % S,
        "xl/_rels/workbook.xml.rels": rels(("rId1", "worksheet", "worksheets/sheet1.xml")),
        "xl/worksheets/sheet1.xml": X + '<worksheet %s><sheetData><row r="1">%s</row></sheetData></worksheet>' % (S, cell),
    }, {"xl/workbook.xml": "spreadsheetml.sheet.main+xml", "xl/worksheets/sheet1.xml": "spreadsheetml.worksheet+xml"})

def pptx(name, text):
    tree = '<p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>'
    colors = "".join('<a:%s><a:srgbClr val="%s"/></a:%s>' % (c, v, c) for c, v in (
        ("dk1", "000000"), ("lt1", "FFFFFF"), ("dk2", "1F497D"), ("lt2", "EEECE1"), ("accent1", "4F81BD"),
        ("accent2", "C0504D"), ("accent3", "9BBB59"), ("accent4", "8064A2"), ("accent5", "4BACC6"),
        ("accent6", "F79646"), ("hlink", "0000FF"), ("folHlink", "800080")))
    fill = '<a:solidFill><a:schemeClr val="phClr"/></a:solidFill>'
    font = '<a:latin typeface="Carlito"/><a:ea typeface=""/><a:cs typeface=""/>'
    theme = (X + '<a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" name="T"><a:themeElements>'
             '<a:clrScheme name="T">%s</a:clrScheme><a:fontScheme name="T"><a:majorFont>%s</a:majorFont>'
             '<a:minorFont>%s</a:minorFont></a:fontScheme><a:fmtScheme name="T"><a:fillStyleLst>%s</a:fillStyleLst>'
             '<a:lnStyleLst>%s</a:lnStyleLst><a:effectStyleLst>%s</a:effectStyleLst><a:bgFillStyleLst>%s'
             '</a:bgFillStyleLst></a:fmtScheme></a:themeElements></a:theme>'
             % (colors, font, font, fill * 3, ('<a:ln w="9525">%s</a:ln>' % fill) * 3,
                "<a:effectStyle><a:effectLst/></a:effectStyle>" * 3, fill * 3))
    clr = ('<p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2" accent3="accent3" '
           'accent4="accent4" accent5="accent5" accent6="accent6" hlink="hlink" folHlink="folHlink"/>')
    box = ('<p:sp><p:nvSpPr><p:cNvPr id="2" name="Text"/><p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr><p:spPr>'
           '<a:xfrm><a:off x="914400" y="914400"/><a:ext cx="6400800" cy="1828800"/></a:xfrm><a:prstGeom prst="rect">'
           '<a:avLst/></a:prstGeom></p:spPr><p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:rPr lang="en-US" sz="4000"/>'
           '<a:t>%s</a:t></a:r></a:p></p:txBody></p:sp>' % text)
    no_override = '<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>'
    ooxml(name, "ppt/presentation.xml", {
        "ppt/presentation.xml": X + '<p:presentation %s><p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rId1"/>'
        '</p:sldMasterIdLst><p:sldIdLst><p:sldId id="256" r:id="rId2"/></p:sldIdLst><p:sldSz cx="9144000" cy="6858000"/>'
        '<p:notesSz cx="6858000" cy="9144000"/></p:presentation>' % P,
        "ppt/_rels/presentation.xml.rels": rels(("rId1", "slideMaster", "slideMasters/slideMaster1.xml"),
                                                ("rId2", "slide", "slides/slide1.xml"), ("rId3", "theme", "theme/theme1.xml")),
        "ppt/slideMasters/slideMaster1.xml": X + '<p:sldMaster %s><p:cSld><p:spTree>%s</p:spTree></p:cSld>%s'
        '<p:sldLayoutIdLst><p:sldLayoutId id="2147483649" r:id="rId1"/></p:sldLayoutIdLst></p:sldMaster>' % (P, tree, clr),
        "ppt/slideMasters/_rels/slideMaster1.xml.rels": rels(("rId1", "slideLayout", "../slideLayouts/slideLayout1.xml"),
                                                             ("rId2", "theme", "../theme/theme1.xml")),
        "ppt/slideLayouts/slideLayout1.xml": X + '<p:sldLayout %s type="blank"><p:cSld><p:spTree>%s</p:spTree></p:cSld>%s'
        '</p:sldLayout>' % (P, tree, no_override),
        "ppt/slideLayouts/_rels/slideLayout1.xml.rels": rels(("rId1", "slideMaster", "../slideMasters/slideMaster1.xml")),
        "ppt/slides/slide1.xml": X + '<p:sld %s><p:cSld><p:spTree>%s%s</p:spTree></p:cSld>%s</p:sld>'
        % (P, tree, box, no_override),
        "ppt/slides/_rels/slide1.xml.rels": rels(("rId1", "slideLayout", "../slideLayouts/slideLayout1.xml")),
        "ppt/theme/theme1.xml": theme,
    }, {"ppt/presentation.xml": "presentationml.presentation.main+xml",
        "ppt/slideMasters/slideMaster1.xml": "presentationml.slideMaster+xml",
        "ppt/slideLayouts/slideLayout1.xml": "presentationml.slideLayout+xml",
        "ppt/slides/slide1.xml": "presentationml.slide+xml", "ppt/theme/theme1.xml": "theme+xml"})

docx("preview.docx", para("Healdan preview fixture zqxword"))
xlsx("preview.xlsx", '<c r="A1" t="inlineStr"><is><t>zqxcell</t></is></c>')
pptx("preview.pptx", "zqxslide")
docx("name.docx", para("Healdan preview fixture zqxname"))
# A tracked insertion and deletion that the file's own view setting hides, and a comment
docx("markup.docx",
     '<w:p><w:r><w:t xml:space="preserve">zqxkept </w:t></w:r><w:ins w:id="1" w:author="Ann" w:date="2026-10-22T00:00:00Z">'
     '<w:r><w:t xml:space="preserve">zqxinserted </w:t></w:r></w:ins><w:del w:id="2" w:author="Ann" '
     'w:date="2026-10-22T00:00:00Z"><w:r><w:delText>zqxdeleted</w:delText></w:r></w:del></w:p>'
     '<w:p><w:commentRangeStart w:id="0"/><w:r><w:t>zqxcommented</w:t></w:r><w:commentRangeEnd w:id="0"/>'
     '<w:r><w:commentReference w:id="0"/></w:r></w:p>',
     settings='<w:revisionView w:markup="false" w:insDel="false"/>', comment="zqxcomment")
# A picture linked, not embedded, from the probe. Its text shows it was converted even though the picture can't load.
docx("link.docx", para("Healdan link fixture zqxlink") +
     '<w:p><w:r><w:drawing><wp:inline xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing">'
     '<wp:extent cx="914400" cy="914400"/><wp:docPr id="1" name="p"/>'
     '<a:graphic xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">'
     '<a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture">'
     '<pic:pic xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:nvPicPr><pic:cNvPr id="1" name="p"/>'
     '<pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:link="rId9"/></pic:blipFill><pic:spPr><a:xfrm>'
     '<a:off x="0" y="0"/><a:ext cx="914400" cy="914400"/></a:xfrm><a:prstGeom prst="rect"/></pic:spPr></pic:pic>'
     '</a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>',
     extra_rels=[("rId9", "image", "http://127.0.0.1:%s/zqxprobe-image.png" % port, True)])
# WEBSERVICE with no cached value, so that loading must compute it. Excel stores it as _xlfn.WEBSERVICE; plain
# WEBSERVICE comes out of LibreOffice's import as #NAME? and would never fetch anything. B1 shows it was converted.
xlsx("webservice.xlsx", '<c r="A1"><f>_xlfn.WEBSERVICE("http://127.0.0.1:%s/zqxprobe-calc")</f></c>'
     '<c r="B1" t="inlineStr"><is><t>zqxsheet</t></is></c>' % port)
# An OpenDocument text whose Basic macro, bound to the open event, leaves a marker file. The ooo: and dom:
# prefixes must be declared, or LibreOffice drops the binding.
ODF = "urn:oasis:names:tc:opendocument:xmlns:"
with zipfile.ZipFile(out / "macro.odt", "w", zipfile.ZIP_DEFLATED) as z:
    z.writestr(zipfile.ZipInfo("mimetype"), "application/vnd.oasis.opendocument.text")  # first, stored
    z.writestr("content.xml", X + '<office:document-content xmlns:office="%soffice:1.0" xmlns:text="%stext:1.0" '
               'xmlns:script="%sscript:1.0" xmlns:xlink="http://www.w3.org/1999/xlink" '
               'xmlns:ooo="http://openoffice.org/2004/office" xmlns:dom="http://www.w3.org/2001/xml-events" '
               'office:version="1.3"><office:scripts><office:event-listeners><script:event-listener '
               'script:language="ooo:script" script:event-name="dom:load" xlink:type="simple" '
               'xlink:href="vnd.sun.star.script:Standard.Module1.Main?language=Basic&amp;location=document"/>'
               '</office:event-listeners></office:scripts><office:body><office:text>'
               '<text:p>Healdan macro fixture zqxmacro</text:p></office:text></office:body></office:document-content>'
               % (ODF, ODF, ODF))
    z.writestr("Basic/script-lc.xml", X + '<library:libraries xmlns:library="http://openoffice.org/2000/library" '
               'xmlns:xlink="http://www.w3.org/1999/xlink"><library:library library:name="Standard" library:link="false"/>'
               '</library:libraries>')
    z.writestr("Basic/Standard/script-lb.xml", X + '<library:library xmlns:library="http://openoffice.org/2000/library" '
               'library:name="Standard" library:readonly="false" library:passwordprotected="false">'
               '<library:element library:name="Module1"/></library:library>')
    z.writestr("Basic/Standard/Module1.xml", X + '<script:module xmlns:script="http://openoffice.org/2000/script" '
               'script:name="Module1" script:language="StarBasic">Sub Main\n  Dim n As Integer\n  n = FreeFile()\n'
               '  Open "%s" For Output As #n\n  Print #n, "ran"\n  Close #n\nEnd Sub\n</script:module>' % marker)
    z.writestr("META-INF/manifest.xml", X + '<manifest:manifest xmlns:manifest="%smanifest:1.0" manifest:version="1.3">'
               % ODF + "".join('<manifest:file-entry manifest:full-path="%s" manifest:media-type="%s"/>' % e for e in (
                   ("/", "application/vnd.oasis.opendocument.text"), ("content.xml", "text/xml"),
                   ("Basic/script-lc.xml", "text/xml"), ("Basic/Standard/script-lb.xml", "text/xml"),
                   ("Basic/Standard/Module1.xml", "text/xml"))) + "</manifest:manifest>")
# 2 KB of random bytes behind a zip signature. Without the signature LibreOffice imports the bytes as plain text and
# makes a PDF of them; with it, it can't load the file, which is the failure this checks. Seeded, so every run is alike.
(out / "bad.docx").write_bytes(b"PK\x03\x04" + random.Random(20261022).randbytes(2044))
PY

echo "target: $BASE, folder $FOLDER_ID, timeout ${TIMEOUT}s"

# The probe: a web server as uid 1000, LibreOffice's user, on the loopback interface LibreOffice would fetch from.
# Its log must show a request it was sent on purpose, or "no request logged" would prove nothing.
probe_ready() { # [path]: the request to send, healdan-probe-ready by default
  local tries=0 path=${1:-healdan-probe-ready}
  while [ "$tries" -lt 20 ]; do
    if app curl -s -o /dev/null "http://127.0.0.1:$PROBE_PORT/$path" 2>/dev/null \
      && app grep -q "$path" /tmp/probe.log 2>/dev/null; then
      return 0
    fi
    tries=$((tries + 1))
    sleep 1
  done
  echo "  the probe isn't answering on port $PROBE_PORT or isn't logging"
  return 1
}
docker exec -d -u 1000 "$APP_CONTAINER" sh -c "mkdir -p /tmp/probe && echo \$\$ > /tmp/probe.pid && \
  exec jwebserver -b 127.0.0.1 -p $PROBE_PORT -d /tmp/probe -o info > /tmp/probe.log 2>&1"
app rm -f "$MARKER"
check "probe listening and logging requests" probe_ready
[ "$FAILED" = 0 ] || exit 1

# upload <fixture> <name>: prints the new document id.
upload() {
  api -H 'Accept: application/json' -F "folderId=$FOLDER_ID" -F "filename=$2" -F "filedata=@$WORK/$1" \
    "$BASE/services/rest/document/upload"
}
# The worker takes the newest jobs first, so the readable files go last and are converted first.
NAMED='Board minutes – é.docx'
bad_id=$(upload bad.docx bad.docx || true)
macro_id=$(upload macro.odt macro.odt || true)
webservice_id=$(upload webservice.xlsx webservice.xlsx || true)
link_id=$(upload link.docx link.docx || true)
markup_id=$(upload markup.docx markup.docx || true)
named_id=$(upload name.docx "$NAMED" || true)
pptx_id=$(upload preview.pptx preview.pptx || true)
xlsx_id=$(upload preview.xlsx preview.xlsx || true)
docx_id=$(upload preview.docx preview.docx || true)
uploaded=$SECONDS
all_ok=0
for id in "$bad_id" "$macro_id" "$webservice_id" "$link_id" "$markup_id" "$named_id" "$pptx_id" "$xlsx_id" "$docx_id"; do
  grep -Eq '^[0-9]+$' <<<"$id" || all_ok=1
done
check "nine Office files uploaded" test "$all_ok" = 0
[ "$FAILED" = 0 ] || exit 1
READABLE="$docx_id $xlsx_id $pptx_id $named_id $markup_id"
echo "  documents: docx $docx_id, xlsx $xlsx_id, pptx $pptx_id, named $named_id, markup $markup_id, link $link_id," \
  "webservice $webservice_id, macro $macro_id, bad $bad_id"

# queued <ids...>: prints how many of the documents still have a job.
queued() { db_query "select count(*) from hd_preview_job where hd_docid in ($(echo "$*" | tr ' ' ','))"; }

# drained <ids...>: waits, within the timeout counted from the uploads, until none of the documents has a job: the
# worker deletes a job once its version is converted. Nothing has asked for a preview yet.
drained() {
  local left
  while :; do
    left=$(queued "$@" || echo '?')
    if [ "$left" = 0 ]; then echo "  drained $((SECONDS - uploaded))s after the uploads"; return 0; fi
    if [ "$SECONDS" -ge $((uploaded + TIMEOUT)) ]; then
      echo "  $left still queued after ${TIMEOUT}s:"
      db_query "select hd_docid, hd_attempts, hd_lasterror from hd_preview_job where hd_docid in ($(echo "$*" | tr ' ' ','))" \
        | sed 's/^/    /' || true
      return 1
    fi
    sleep 2
  done
}

# stored <id>: the version's PDF conversion is in the store (store.1.dir; CE splits the id into 3-digit directories).
stored() {
  local fv rel
  fv=$(db_query "select ld_fileversion from ld_document where ld_id = $1")
  rel=$(sed -E 's/(...)/\1\//g; s/\/$//' <<<"$1")
  app test -s "/opt/logicaldoc/repository/docs/$rel/doc/$fv-conversion.pdf"
}

check "the five readable files converted ahead of time, within ${TIMEOUT}s" drained $READABLE
for id in $READABLE; do
  check "document $id's PDF stored before any preview was asked for" stored "$id"
done
# The hardening checks below mean something only if LibreOffice really opened these three: a dropped job also leaves
# no row.
check "link.docx, webservice.xlsx and macro.odt converted too" drained "$link_id" "$webservice_id" "$macro_id"
for id in "$link_id" "$webservice_id" "$macro_id"; do
  check "document $id's PDF stored before any preview was asked for" stored "$id"
done

# failed_once: bad.docx's job has failed at least once. The worker records the try, its error and the retry 1 minute
# later in one update, after the run has ended and its processes were swept, so the check below starts after the run
# and well before the retry (whose delay is printed). A retry coinciding with a process check would be over in under
# a second, well inside that check's 10 seconds.
failed_once() {
  local row attempts retry error
  while :; do
    row=$(db_query "select hd_attempts, timestampdiff(second, now(), hd_nexttry), coalesce(hd_lasterror, '')
      from hd_preview_job where hd_docid = $bad_id") || { echo "  can't read bad.docx's job"; return 1; }
    if [ -z "$row" ]; then
      echo "  bad.docx has no job: it was converted, or never queued"
      return 1
    fi
    attempts=$(cut -f1 <<<"$row")
    retry=$(cut -f2 <<<"$row")
    error=$(cut -f3- <<<"$row")
    if [ "$attempts" -ge 1 ] && [ -n "$error" ]; then
      echo "  bad.docx's job: attempts $attempts, retry in ${retry}s, error: $error"
      return 0
    fi
    if [ "$SECONDS" -ge $((uploaded + TIMEOUT)) ]; then
      echo "  bad.docx's job not tried after ${TIMEOUT}s (attempts $attempts)"
      return 1
    fi
    sleep 2
  done
}
# processes: prints "<pid> <command line>" for each process in the app container. Fails if it can't read the process
# table, which always holds PID 1, tini; a process that ends during the scan is skipped.
processes() {
  local list
  list=$(app sh -c 'for d in /proc/[0-9]*; do
      printf "%s " "${d#/proc/}"; tr "\0" " " 2>/dev/null < "$d/cmdline"; echo
    done; true') || return 1
  grep -q '^1 .*tini' <<<"$list" || return 1
  printf '%s\n' "$list"
}
# soffice_gone: within 10 seconds, no process in the app container has soffice on its command line. The runner
# sweeps a slot's processes after every run, but a killed process takes a moment to go.
soffice_gone() {
  local deadline=$((SECONDS + 10)) list left
  while :; do
    list=$(processes) || { echo "  can't read the app container's process table"; return 1; }
    left=$(grep soffice <<<"$list" || true)
    [ -z "$left" ] && return 0
    [ "$SECONDS" -lt "$deadline" ] || { echo "  still running after 10s: $left"; return 1; }
    sleep 1
  done
}
check "bad.docx's conversion failed (at least 1 attempt)" failed_once
check "no soffice process left once it failed" soffice_gone

# pdf_text <id> <file>: fetches the document's preview PDF the way the viewer does, with the session cookie (the
# server ignores a sid parameter unless security.acceptsid is on), once, and prints its text, extracted inside the app
# container (Poppler is there).
pdf_text() {
  [ -s "$WORK/$2" ] || curl -fsS -b "ldoc-sid=$SID" -o "$WORK/$2" "$BASE/convertpdf?docId=$1" || return 1
  docker exec -i "$APP_CONTAINER" sh -c \
    'f=$(mktemp) && cat > "$f" && pdftotext "$f" - ; rc=$?; rm -f "$f"; exit $rc' < "$WORK/$2"
}
has_token() { # <id> <token>
  local text
  text=$(pdf_text "$1" "$1.pdf") || { echo "  no PDF for document $1"; return 1; }
  grep -q "$2" <<<"$text" || { echo "  document $1's PDF text: $(tr -s '\n ' ' ' <<<"$text" | cut -c1-200)"; return 1; }
}
check "preview.docx's PDF holds zqxword" has_token "$docx_id" zqxword
check "preview.xlsx's PDF holds zqxcell" has_token "$xlsx_id" zqxcell
check "preview.pptx's PDF holds zqxslide" has_token "$pptx_id" zqxslide
check "$NAMED's PDF holds zqxname" has_token "$named_id" zqxname
check "markup.docx's PDF shows the tracked insertion" has_token "$markup_id" zqxinserted
check "markup.docx's PDF shows the tracked deletion the file hides" has_token "$markup_id" zqxdeleted
# The comment is a PDF text note: /Contents holds it as UTF-16BE hex with a byte order mark, or as a literal string.
comment_note() {
  python3 - "$WORK/$markup_id.pdf" <<'PY'
import sys
pdf = open(sys.argv[1], "rb").read()
hex_note = ("FEFF" + "zqxcomment".encode("utf-16-be").hex()).upper().encode()
sys.exit(0 if hex_note in pdf.upper() or b"(zqxcomment)" in pdf else 1)
PY
}
check "markup.docx's PDF carries the comment as a note" comment_note

# Thumbnails: by path, as the smoke test asks for the PNG's and the PDF's.
docx_name=$(api -H 'Accept: application/json' "$BASE/services/rest/document/getDocument?docId=$docx_id" \
  | jq -r '.fileName | @uri' || true)
for type in thumb tile; do
  api -o "$WORK/$type.png" "$BASE/services/rest/document/thumbnail/$type$DOC_PATH/$docx_name" || true
  check "preview.docx's $type is a PNG" is_png "$WORK/$type.png"
done

# Hardening (spec §5.3). LibreOffice opened the three files: their PDFs hold their text.
check "link.docx's PDF holds zqxlink" has_token "$link_id" zqxlink
check "webservice.xlsx's PDF holds zqxsheet" has_token "$webservice_id" zqxsheet
check "macro.odt's PDF holds zqxmacro" has_token "$macro_id" zqxmacro
# no_fetch: the probe, still listening and logging, was never asked for anything the fixtures link to.
no_fetch() {
  local log hits
  probe_ready healdan-probe-still-there || return 1
  log=$(app cat /tmp/probe.log) || { echo "  can't read the probe's log"; return 1; }
  hits=$(grep -a zqxprobe <<<"$log" || true)
  [ -z "$hits" ] || { echo "  the probe was asked for: $hits"; return 1; }
}
check "no linked image or WEBSERVICE fetched while converting" no_fetch
check "no macro ran while converting" app test ! -e "$MARKER"

# placeholder <id>: the preview is CE's "Not Available" PDF (its text breaks the line between the words), and holds
# no token.
placeholder() {
  local text
  text=$(pdf_text "$1" "$1.pdf") || { echo "  no PDF for document $1"; return 1; }
  text=$(tr -s '\n\f ' '   ' <<<"$text")
  echo "  bad.docx's preview reads: $(cut -c1-120 <<<"$text")"
  grep -q 'Not Available' <<<"$text" && ! grep -q zqx <<<"$text"
}
check "bad.docx's preview is the placeholder, with no token" placeholder "$bad_id"
check "no soffice process left after its preview was asked for" soffice_gone

# tini reaps: an orphan's parent becomes PID 1, which must collect it once it exits.
no_zombie() {
  local stats zombies
  app sh -c '(sleep 1 &)' || { echo "  can't start a process in the app container"; return 1; }
  sleep 3
  stats=$(app sh -c 'for f in /proc/[0-9]*/stat; do cat "$f" 2>/dev/null; done; true') \
    || { echo "  can't read the app container's process table"; return 1; }
  grep -q '^1 (tini) ' <<<"$stats" || { echo "  no PID 1 (tini) in the process table"; return 1; }
  zombies=$(sed -n 's/^\([0-9]*\) (\(.*\)) Z .*/\1 \2/p' <<<"$stats")
  [ -z "$zombies" ] || { echo "  zombies: $zombies"; return 1; }
}
check "no zombie process: PID 1 reaps orphans" no_zombie

echo "previews: $((SECONDS - STARTED))s"
finish
