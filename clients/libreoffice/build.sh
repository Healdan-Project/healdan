#!/bin/sh
# Builds the LibreOffice extension, healdan.oxt: the files in healdan/, with healdan_logic.py in pythonpath/, where
# LibreOffice's Python loader finds it beside healdan.py.
#
#   clients/libreoffice/build.sh [output.oxt]     (default: clients/libreoffice/healdan.oxt)
#
# The same sources give the same bytes: entries in sorted order, each with the same time (SOURCE_DATE_EPOCH when it's
# set, else 1980-01-01, zip's earliest) and the same permissions. Needs only sh and python3, so it runs on macOS and
# Ubuntu alike.
set -eu

here=$(cd "$(dirname "$0")" && pwd)
out=${1:-"$here/healdan.oxt"}
case "$out" in
  /*) ;;
  *) out="$(pwd)/$out" ;;
esac

stage=$(mktemp -d)
trap 'rm -rf "$stage"' EXIT

cp -R "$here/healdan/." "$stage/"
mkdir -p "$stage/pythonpath"
cp "$here/healdan_logic.py" "$stage/pythonpath/"
find "$stage" \( -name '__pycache__' -o -name '.DS_Store' \) -prune -exec rm -rf {} +

rm -f "$out"
python3 -I - "$stage" "$out" <<'PY'
import os
import sys
import time
import zipfile

stage, out = sys.argv[1], sys.argv[2]
earliest = 315532800  # 1980-01-01T00:00:00Z
epoch = max(int(os.environ.get("SOURCE_DATE_EPOCH") or earliest), earliest)
when = time.gmtime(epoch)[:6]
names = sorted(os.path.relpath(os.path.join(folder, name), stage).replace(os.sep, "/")
               for folder, _, files in os.walk(stage) for name in files)
with zipfile.ZipFile(out, "w") as oxt:
    for name in names:
        entry = zipfile.ZipInfo(name, when)
        entry.compress_type = zipfile.ZIP_DEFLATED
        entry.create_system = 3  # Unix, whichever system builds it
        entry.external_attr = 0o100644 << 16  # a plain file, rw-r--r--
        with open(os.path.join(stage, name), "rb") as source:
            oxt.writestr(entry, source.read(), compresslevel=9)
PY
echo "Built $out"
