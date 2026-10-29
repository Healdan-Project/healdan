# Healdan's Client Icons

The helper's and the LibreOffice extension's icons are [Tabler Icons](https://tabler.io/icons) 3.50.0, the outline
set, under the MIT licence in `LICENSE-tabler.txt` (polish spec §4.4). `tabler/` holds the SVG of each icon used,
as the release has it.

- **The helper** draws them from `clients/helper/Healdan.Helper/Windows/HelperIcons.axaml`, which
  `python3 clients/icons/write-helper-icons.py` writes from `tabler/`. `IconsTests` fails until it's run.
- **The extension's images** are rendered from the same drawings by `clients/helper/tools/IconRender`.

To add an icon, copy its SVG from the same release into `tabler/`, run the script, and add its name to
`IconsTests.SpecNames` and the spec's table.
