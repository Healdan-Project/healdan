#!/usr/bin/env python3
#
# Checks that every Font Awesome icon the GWT client asks for exists in the
# Font Awesome Free bundle under logicaldoc-gui/war/fontawesome. Upstream
# LogicalDOC is written against Font Awesome Pro, so a sync can bring back Pro
# styles (fal, fa-light, fa-thin, ...) or Pro-only icon names. Those still
# compile and only show up at runtime as the blinking missing-icon placeholder.
# Run by .github/workflows/build-logicaldoc.yml.
#
# Checks the string literals in logicaldoc-gui/src/main/java:
#   - no Pro-only style class appears;
#   - the icon passed to an AwesomeFactory method (as a literal, or a
#     `static final String` constant) is a Free solid icon, or a brand icon for
#     newBrandToolStripButton. Inside AwesomeFactory the calls are unqualified;
#   - an icon picked into an `icn` variable, as the dashlets do, is a Free solid
#     icon;
#   - every fa-<name> class is a Free icon in the style its literal names (fab,
#     far, otherwise solid) or a Font Awesome utility class.
# Icon names built at runtime aren't covered.
#
# Usage: check-fontawesome-icons.py [repo root]
# Exit status: 0 when every icon resolves, 1 otherwise.

import pathlib
import re
import sys

ROOT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else pathlib.Path(__file__).resolve().parents[2])
GUI_SRC = ROOT / "logicaldoc-gui/src/main/java"
FA_JS = ROOT / "logicaldoc-gui/war/fontawesome/js"

# Short prefixes and style classes of the Pro styles, the sharp family and kits
PRO_STYLES = {
    "fal", "fat", "fad", "fass", "fasr", "fasl", "fast", "fasds", "fak", "fakd",
    "fa-light", "fa-thin", "fa-duotone", "fa-sharp", "fa-sharp-duotone", "fa-kit", "fa-kit-duotone",
}

# Classes that modify an icon rather than name one
UTILITY = {
    "fw", "li", "ul", "border", "inverse", "pull-left", "pull-right", "pull-start", "pull-end",
    "spin", "spin-pulse", "spin-reverse", "pulse", "beat", "beat-fade", "bounce", "fade", "flip",
    "shake", "flip-horizontal", "flip-vertical", "flip-both", "rotate-90", "rotate-180",
    "rotate-270", "rotate-by", "stack", "stack-1x", "stack-2x", "layers", "layers-counter",
    "layers-text", "layers-top-left", "layers-top-right", "layers-bottom-left",
    "layers-bottom-right", "2xs", "xs", "sm", "lg", "xl", "2xl", "1x", "2x", "3x", "4x", "5x",
    "6x", "7x", "8x", "9x", "10x", "solid", "regular", "brands", "sr-only", "sr-only-focusable",
    "swap-opacity",
}

# AwesomeFactory methods whose first argument is an icon name, and the set it must be in
# (getSpinnerIconHtml always draws fa-spinner; its first argument is the animation)
ICON_METHODS = {
    "newToolStripButton": "solid",
    "newToolStripToolStripMenuButton": "solid",
    "newIconButton": "solid",
    "newColoredIconButton": "solid",
    "getIconHtml": "solid",
    "getSpinnerIconHtml": "utility",
    "getIconButtonHTML": "solid",
    "getColoredIconHtml": "solid",
    "getColoredIconHtmlWithoutI18N": "solid",
    "newBrandToolStripButton": "brands",
}

STRING_LITERAL = re.compile(r'"((?:[^"\\\n]|\\.)*)"')
CONSTANT = re.compile(r'static\s+final\s+String\s+(\w+)\s*=\s*"((?:[^"\\\n]|\\.)*)"\s*;')
ICON_CALL = re.compile(r'AwesomeFactory\.(\w+)\(\s*(?:"([^"]*)"|([A-Z][A-Z0-9_]*)\s*[,)])')
# The same call made from inside AwesomeFactory, without the class name
LOCAL_ICON_CALL = re.compile(r'(?<![\w.])(\w+)\(\s*(?:"([^"]*)"|([A-Z][A-Z0-9_]*)\s*[,)])')
ICON_VARIABLE = re.compile(r'\bicn\s*=\s*"([^"]*)"')


def icon_names(js_file):
    """Icon names and their aliases defined in a Font Awesome JS bundle."""
    text = js_file.read_text(encoding="utf-8")
    names = set()
    for m in re.finditer(r'(?:"([a-z0-9-]+)"|\b([a-z][a-z0-9]*)):\[\d+,\d+,\[([^\]]*)\]', text):
        names.add(m.group(1) or m.group(2))
        names.update(re.findall(r'"([a-z0-9-]+)"', m.group(3)))
    if not names:
        sys.exit(f"no icons found in {js_file}; has the bundle format changed?")
    return names


def main():
    sets = {style: icon_names(FA_JS / f"{style}.min.js") for style in ("solid", "regular", "brands")}
    sets["utility"] = UTILITY
    sources = sorted(GUI_SRC.rglob("*.java"))
    texts = {path: path.read_text(encoding="utf-8") for path in sources}

    constants = {}
    for text in texts.values():
        for m in CONSTANT.finditer(text):
            constants.setdefault(m.group(1), set()).add(m.group(2))

    problems = []
    for path, text in texts.items():
        rel = path.relative_to(ROOT)

        def report(offset, message):
            problems.append(f"{rel}:{text.count(chr(10), 0, offset) + 1}: {message}")

        for m in STRING_LITERAL.finditer(text):
            tokens = re.split(r"[\s'\"=<>]+", m.group(1))
            for token in tokens:
                if token in PRO_STYLES:
                    report(m.start(), f"Pro-only style '{token}'")
            style = "brands" if "fab" in tokens else "regular" if "far" in tokens else "solid"
            for token in tokens:
                name = token[3:] if token.startswith("fa-") else None
                if name and name not in UTILITY and token not in PRO_STYLES and name not in sets[style]:
                    report(m.start(), f"'{token}' is not a Font Awesome Free {style} icon")

        for m in ICON_VARIABLE.finditer(text):
            if m.group(1) not in sets["solid"]:
                report(m.start(), f"icn = '{m.group(1)}' is not a Font Awesome Free solid name")

        calls = LOCAL_ICON_CALL if path.name == "AwesomeFactory.java" else ICON_CALL
        for m in calls.finditer(text):
            style = ICON_METHODS.get(m.group(1))
            if style is None:
                continue
            names = {m.group(2)} if m.group(2) is not None else constants.get(m.group(3), set())
            for name in names:
                if name not in sets[style]:
                    report(m.start(), f"AwesomeFactory.{m.group(1)}: '{name}' is not a Font Awesome Free {style} name")

    for problem in problems:
        print(problem)
    if problems:
        print(f"{len(problems)} Font Awesome icon problem(s); the bundle is Font Awesome Free", file=sys.stderr)
        return 1
    print(f"Checked {len(sources)} GUI sources: every Font Awesome icon is in the Free bundle")
    return 0


if __name__ == "__main__":
    sys.exit(main())
