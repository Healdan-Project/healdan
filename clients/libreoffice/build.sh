#!/bin/sh
# Builds the LibreOffice extension, healdan.oxt: the files in healdan/, with healdan_logic.py in pythonpath/, where
# LibreOffice's Python loader finds it beside healdan.py.
#
#   clients/libreoffice/build.sh [output.oxt]     (default: clients/libreoffice/healdan.oxt)
#
# Needs only sh and python3 (its zipfile module), so it runs on macOS and Ubuntu alike.
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
(cd "$stage" && python3 -m zipfile -c "$out" META-INF description.xml description-en.txt healdan.py pythonpath *.xcu)
echo "Built $out"
