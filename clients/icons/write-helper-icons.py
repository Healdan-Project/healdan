#!/usr/bin/env python3
"""
Writes clients/helper/Healdan.Helper/Windows/HelperIcons.axaml from the Tabler SVGs in tabler/ (polish spec 4.4):
each icon's 24-unit outline drawing, its paths joined, without Tabler's invisible box.

    python3 clients/icons/write-helper-icons.py
"""
import pathlib
import sys
import xml.etree.ElementTree as ET

HERE = pathlib.Path(__file__).resolve().parent
OUT = HERE.parent / "helper" / "Healdan.Helper" / "Windows" / "HelperIcons.axaml"
SVG = "{http://www.w3.org/2000/svg}"
BOX = "M0 0h24v24H0z"


def drawing(path):
    shapes = list(ET.parse(path).getroot())
    for shape in shapes:
        if shape.tag != SVG + "path":
            sys.exit("%s: %s isn't a path" % (path.name, shape.tag))
    return " ".join(s.get("d") for s in shapes if s.get("d") != BOX)


def main():
    lines = [
        '<ResourceDictionary xmlns="https://github.com/avaloniaui"',
        '                    xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml">',
        "  <!-- Written by clients/icons/write-helper-icons.py from clients/icons/tabler/ (Tabler Icons, MIT): each icon's",
        "       24-unit outline drawing, which IconView strokes. Don't edit it by hand: IconsTests checks it. -->",
    ]
    for svg in sorted((HERE / "tabler").glob("*.svg")):
        lines.append('  <StreamGeometry x:Key="Icon.%s">%s</StreamGeometry>' % (svg.stem, drawing(svg)))
    lines.append("</ResourceDictionary>")
    OUT.write_text("\n".join(lines) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
