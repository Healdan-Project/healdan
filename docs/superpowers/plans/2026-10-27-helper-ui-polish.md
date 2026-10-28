# Healdan Helper and LibreOffice Extension UI Polish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Healdan Helper look at home on a Mac in Healdan's colours, and make the LibreOffice extension's commands recognisable at a glance, as the approved polish spec describes, without changing what either does.

**Architecture:**
- **The helper's look** lives in two style files over Avalonia's Fluent theme:
  - `HelperStyles.axaml` holds Healdan's tokens, Fluent's resources set to them, and the Mac-style control styles.
  - `HelperIcons.axaml` holds the Tabler icons, written by a script from the committed SVGs, which `IconsTests` keeps in step.
- **The windows** are rebuilt as three shells: forms, alerts, and the browser windows (Open and Save), whose toolbar sits in the title bar on macOS.
- **The menu bar icon** is drawn at run time from the mark's own shapes.
- **New rules are pure code in `Healdan.Helper.Core`,** with tests first: the attention dot's reason, file icons, the crash headline, the search hint, and Settings' extension row.
- **The extension's images** are rendered from the same icon geometry by a small tool and committed. Its notices are split into headline and detail at display time.

**Tech Stack:** .NET SDK 10.0 (`clients/helper/global.json`), Avalonia 12.1.4 with `Avalonia.Themes.Fluent`, xUnit 2.9; the render tool adds `Avalonia.Headless` and `Avalonia.Skia` 12.1.4, outside the solution; Tabler Icons (MIT, outline set, the latest 3.x release when Task 1 runs); Python 3 standard library and UNO for the extension; `unittest`.

**Spec:** [`docs/superpowers/specs/2026-10-27-helper-ui-polish-design.md`](../specs/2026-10-27-helper-ui-polish-design.md), approved by the owner on 2026-10-27, with its mockups in [`2026-10-27-helper-polish-mockups/`](../specs/2026-10-27-helper-polish-mockups/). Every § below points into it. Where the two disagree, the spec wins, except for "Spec corrections and calls this plan makes".

**Status:** Tasks 1–13 built on 2026-10-27, in PR 1 ([Healdan-Project/healdan#93](https://github.com/Healdan-Project/healdan/pull/93)) and PR 2; Task 14, the owner's checks on a Mac, is next.

## Global Constraints

Every task's requirements include these.

- **Only `clients/`, `site/`, `docs/`, `CLAUDE.md` and `docs/ROADMAP.md` change.** No server code, no upstream file, no CI workflow (spec §3, §12).
- **Behaviour doesn't change.** The copy cycle, the channel and its routes, the API calls, and what each button does stay as they are. Only §6–§8's listed UI changes are new (Install… in Settings, the attention dot, the details tabs, the File Format pop-up).
- **Wording doesn't change**, except:
  - After a Crash's split into headline and body (§6.8);
  - the search hint ", or type #1234" (§6.2);
  - the ellipses on **Open from Local Computer…** and **Save to Local Computer…**;
  - **Older version: opens read-only** (§6.2);
  - "Browse" in Title Case instead of capitals;
  - **File Format**;
  - Settings' new row labels (§6.5);
  - the tray tooltip reasons (§7.2).
- **Colours are spec §4.1's values, exactly,** and live only in `HelperStyles.axaml`. `HealdanMark.axaml` keeps the mark's own colours. No hex colour appears in any other `.axaml` or `.cs` file. The menu bar icon (`Colors.Black`, `Colors.White`) and the render tool are the only exceptions.
- **Type** is `.AppleSystemUIFont` on macOS and the desktop's default elsewhere (§4.2).
- **Icons are Tabler's outline set,** from `clients/icons/tabler/`, one release. They're resources named `Icon.<tabler-name>`, drawn by `IconView` at 1.5 pt (§4.4).
- **No new runtime NuGet package for the helper.** `NoticesTests` must keep passing unchanged.
- **The extension** stays Python standard library plus UNO, its `.py` files stay ASCII, and `build.sh` stays `sh` plus `python3`, giving the same bytes from the same sources.
- **Docs** follow the Writing Docs page: Title Case labels, British spelling, Healdan's glossary.
- **C#** matches the surrounding code: XML doc comments that cite the spec (`polish spec §n`), `(call)` where the plan decided something, one type per file, and file-scoped namespaces.
- **Commits:** one per task, in the repo's style: an imperative subject, a body that says why, and `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Commit and push only as the task says, and open a PR only with the owner's go-ahead.

## Review Focus

These are the risks to a person using the helper that the unit tests don't reach. Each task names which of them it must check.

1. **Everything is still reachable by keyboard and screen reader.**
   - Tab reaches every control in Open, Save, Properties, Settings and the alerts.
   - The segmented control works with the arrow keys.
   - Every icon-only button has a tooltip and `AutomationProperties.Name`.
2. **Dark mode is legible.** Every window's dark render has no near-black fields, no invisible text, and no icon that disappears against its background.
3. **The unified title bar behaves like a title bar.** It drags, double-click zooms, the system buttons don't overlap the toolbar, and full screen works. Every toolbar control still takes clicks.
4. **The menu bar icon is crisp at 22 pt** on light and dark menu bars, inverts when highlighted, and its dot is visible.
5. **The extension's images are sharp on Retina,** and readable on LibreOffice's light, dark and high-contrast toolbars.

## Spec corrections and calls this plan makes

The owner hears about these when the plan is handed over. Task 13 folds 1–4 into the spec, and the changes the build made (its Step 4).

1. **Infobars split at display time.** The notices in `healdan_logic.py` stay single strings, so the flows and their tests don't change. A new pure function, `notice_parts(text)`, splits one into headline and detail, and `tell` uses it (§8.3).
2. **The search hint is in the Documents scope only.** A typed doc # jumps from any scope, but only the Documents scope's placeholder says so. Folders, Matters and Clients keep theirs, so they stay short (§6.2).
3. **The render tool ships with the extension (PR 2), not the helper (PR 1).** Its only output is the extension's images, which PR 1's reviewer couldn't judge (§8.5, §12).
4. **After Install… in Settings,** the row shows `ExtensionCheck.Summary()` again, rather than a text mapped from the install's outcome. The summary already says "Waiting for you to accept version … in LibreOffice" (§6.5).
5. **Icons draw through a small `IconView` control.** It scales Tabler's 24-unit drawing to its size and keeps the stroke at 1.5 pt whatever the size. Avalonia's `Path` would stretch each icon to its own bounds and scale the stroke.
6. **The menu bar icon's geometry is built by walking `HealdanMark`'s own shapes,** so `MarkTests` still covers it, and the favicon stays the one source.
7. **`Warnings.Crashed` returns a record** with `Headline` and `Body`. Its `ToString()` is today's sentence, so the crash notification keeps its wording.
8. **The `muted` text class takes the Secondary colour** (`#6E6E73` / `#AEAEB2`), since the windows use it for text people read. Muted (`#8E8E93`) is left for Fluent's placeholders. This is how §4.1's "every text colour passes AA" holds without touching every window.
9. **The renderer's fake data matches the docs' pictures** (998.1 – Healdan Demo, Robert Example, Jane Smith, MacBook, `https://healdan.example`), so re-rendered pictures fit the pages' prose.

## Conventions

- **The helper's tests:** `dotnet test clients/helper/Healdan.Helper.sln`. For one class, add `--filter "FullyQualifiedName~<Class>"`.
- **Building the app:** `dotnet build clients/helper/Healdan.Helper.sln`. It must build with no new warnings.
- **The extension's tests:** `python3 -m unittest discover -s clients/libreoffice/tests -v`.
- **The renderer** is a scratch tool from the design session, outside the repo. It draws all ten windows, light and dark, into PNGs:

  ```bash
  R=<the renderer's folder>    # the design session's scratch renderer, outside the repo
  $R/render.sh                 # every window, light and dark, into $R/out/<window>-<light|dark>.png
  $R/render.sh open dark       # one window and theme
  $R/compare.sh                # changed pixels against $R/baseline/, with diffs in $R/diff/
  ```

  - `baseline/` holds the before images. Don't rebase it until Task 9.
  - Its `RenderApp.axaml` copies `App.axaml`'s styles, and it warns when they drift. Task 2 brings it in step, and later tasks keep it so.
  - If the renderer has gone, rebuild it before Task 2:
    - a console project referencing `Healdan.Helper.csproj` by path, with `Avalonia.Headless` and `Avalonia.Skia` 12.1.4;
    - Skia drawing, not the headless stub;
    - an `Application` with `App.axaml`'s styles;
    - each window built through its internal constructor by reflection;
    - a fake `HttpMessageHandler` for the API, modelled on `Healdan.Helper.Core.Tests/Browse/FakeRoutes.cs`.
- **Running the real helper** (Tasks 4, 8 and 14): `HEALDAN_HELPER_DEV_ROOT=/tmp/hh dotnet run --project clients/helper/Healdan.Helper`. It's a Debug helper with its files apart from the installed one. Quit the installed helper first, since only one runs per user.
- **Asking first.** Downloading Tabler (Task 1), and anything that pushes or opens a PR, waits for the owner's go-ahead in chat.

## PRs

Both target `master`. PR 2 stacks on PR 1.

- **PR 1, Tasks 1–9:** the helper: icons, foundations, windows, menu bar icon, its docs and records.
- **PR 2, Tasks 10–13:** the extension: rendered images, package wiring, infobars, and its docs and records.
- **Task 14:** the owner's by-hand checks on the Mac (§10.3), after PR 2.

## Files

**Helper (PR 1)**

| File | Responsibility |
|---|---|
| `clients/icons/tabler/*.svg`, `clients/icons/README.md`, `clients/icons/LICENSE-tabler.txt` | the Tabler sources, their release and licence |
| `clients/icons/write-helper-icons.py` | writes `HelperIcons.axaml` from the sources |
| `clients/helper/Healdan.Helper/Windows/HelperIcons.axaml` | `Icon.<name>` geometries (generated) |
| `clients/helper/Healdan.Helper/Windows/IconView.cs` | draws one icon at any size, 1.5 pt stroke |
| `clients/helper/Healdan.Helper/Windows/Icons.cs` | icons by name, for code and bindings |
| `clients/helper/Healdan.Helper/Windows/HelperStyles.axaml` | tokens, Fluent overrides, control styles |
| `clients/helper/Healdan.Helper/App.axaml(.cs)` | accent palettes; the macOS font |
| `clients/helper/Healdan.Helper/Windows/AlertMark.axaml(.cs)` | the mark with the caution badge, for alerts |
| `clients/helper/Healdan.Helper/Windows/BrowserChrome.cs` | Open's and Save's toolbar in the title bar on macOS |
| `clients/helper/Healdan.Helper/Windows/*Window.axaml(.cs)`, `PlacesPane.cs`, `BrowseControls.cs`, `PropertiesForm.cs`, `AppWindows.cs` | the windows, rebuilt |
| `clients/helper/Healdan.Helper/Windows/HealdanMark.axaml(.cs)` | `Silhouette()` and `Shield()` from its shapes |
| `clients/helper/Healdan.Helper/Tray/TrayIcons.cs`, `Tray/TrayMenu.cs` | the menu bar icon and dot |
| `clients/helper/Healdan.Helper/Platform/LoginItem.cs` | `Summary()` for Settings |
| `clients/helper/Healdan.Helper.Core/Ui/Attention.cs`, `Ui/CrashedWarning.cs`, `Ui/Warnings.cs` | the dot's reason; After a Crash's parts |
| `clients/helper/Healdan.Helper.Core/Browse/FileIcons.cs`, `Browse/Entry.cs`, `Browse/Browser.cs` | file icons; the search hint |
| `clients/helper/Healdan.Helper.Core/Platform/ExtensionSummary.cs`, `Platform/ExtensionCheck.cs` | Settings' extension row |
| `clients/helper/Healdan.Helper.Core.Tests/App/IconsTests.cs`, `Ui/AttentionTests.cs`, `Browse/FileIconsTests.cs`, and edits to `BrowserTests`, `HoldsAndTimesTests`, `ExtensionCheckTests` | the tests |
| `clients/helper/Healdan.Helper/THIRD-PARTY-NOTICES.txt` | Tabler's notice |

**Extension (PR 2)**

| File | Responsibility |
|---|---|
| `clients/helper/tools/IconRender/IconRender.csproj`, `Program.cs` | renders the extension's PNGs |
| `clients/libreoffice/healdan/icons/*.png` | the toolbar and menu images, and the extension's icon (rendered) |
| `clients/libreoffice/healdan/Addons.xcu`, `description.xml`, `THIRD-PARTY-NOTICES.txt` | the images and separators, the icon, the notice |
| `clients/libreoffice/healdan_logic.py`, `healdan/healdan.py` | `notice_parts`; `tell` uses it |
| `clients/libreoffice/tests/test_package.py`, `tests/test_logic.py` | the tests |

---

### Task 1: The icon set

**Files:**
- Create: `clients/icons/tabler/<name>.svg` (30 files), `clients/icons/README.md`, `clients/icons/LICENSE-tabler.txt`, `clients/icons/write-helper-icons.py`
- Create: `clients/helper/Healdan.Helper/Windows/HelperIcons.axaml` (generated), `Windows/IconView.cs`, `Windows/Icons.cs`
- Modify: `clients/helper/Healdan.Helper/Windows/HelperStyles.axaml` (merge the icons in), `clients/helper/Healdan.Helper/THIRD-PARTY-NOTICES.txt`
- Test: `clients/helper/Healdan.Helper.Core.Tests/App/IconsTests.cs`

**Interfaces:**
- Produces:
  - resources `Icon.<tabler-name>` (`StreamGeometry`, 24-unit);
  - `public sealed class IconView : Control`, with `Data` (`Geometry?`), `Foreground` (`IBrush?`, inherited), `Fill` (`IBrush?`) and `StrokeThickness` (`double`, default 1.5);
  - `public static class Icons`, with `Geometry Get(string name)`, `IValueConverter ByName` and `IconView View(string name, double size = 16)`.

The 30 icons (spec §4.4): `alert-triangle`, `briefcase`, `building`, `chevron-left`, `chevron-right`, `chevron-up`, `clock`, `device-floppy`, `eye`, `file`, `file-plus`, `file-spreadsheet`, `file-text`, `file-type-pdf`, `folder`, `folder-open`, `folder-plus`, `hash`, `history`, `list-details`, `lock`, `photo`, `power`, `presentation`, `puzzle`, `search`, `selector`, `server`, `star`, `versions`.

- [ ] **Step 1: Write the failing test** (`IconsTests.cs`)

```csharp
using System.Xml.Linq;

namespace Healdan.Helper.Core.Tests.App;

/// <summary>
/// The helper's icons (polish spec §4.4) are Tabler's: <c>HelperIcons.axaml</c> holds each SVG in
/// <c>clients/icons/tabler/</c> as one geometry, which <c>clients/icons/write-helper-icons.py</c> writes. An icon added
/// or changed fails here until the script has run.
/// </summary>
public sealed class IconsTests
{
    private static readonly XNamespace Svg = "http://www.w3.org/2000/svg";
    private static readonly XNamespace Xaml = "https://github.com/avaloniaui";
    private static readonly XNamespace X = "http://schemas.microsoft.com/winfx/2006/xaml";

    /// <summary>Tabler's invisible 24-unit box, which every icon starts with.</summary>
    private const string Box = "M0 0h24v24H0z";

    private static readonly string[] SpecNames =
    [
        "alert-triangle", "briefcase", "building", "chevron-left", "chevron-right", "chevron-up", "clock", "device-floppy",
        "eye", "file", "file-plus", "file-spreadsheet", "file-text", "file-type-pdf", "folder", "folder-open", "folder-plus",
        "hash", "history", "list-details", "lock", "photo", "power", "presentation", "puzzle", "search", "selector", "server",
        "star", "versions"
    ];

    [Fact]
    public void EachIconIsItsTablerDrawing()
    {
        var sources = Directory.GetFiles(AppFiles.Repository("clients", "icons", "tabler"), "*.svg")
            .ToDictionary(Path.GetFileNameWithoutExtension, Drawing);
        var icons = XDocument.Load(AppFiles.Helper("Windows", "HelperIcons.axaml")).Root!
            .Elements(Xaml + "StreamGeometry")
            .ToDictionary(e => ((string)e.Attribute(X + "Key")!)["Icon.".Length..], e => e.Value.Trim());

        Assert.NotEmpty(sources);
        Assert.Equal(sources.OrderBy(p => p.Key, StringComparer.Ordinal), icons.OrderBy(p => p.Key, StringComparer.Ordinal));
    }

    [Fact]
    public void TheSpecsIconsAreAllThere()
    {
        var names = Directory.GetFiles(AppFiles.Repository("clients", "icons", "tabler"), "*.svg").Select(Path.GetFileNameWithoutExtension);
        Assert.Equal(SpecNames, names.Order(StringComparer.Ordinal));
    }

    /// <summary>An SVG's drawing: its paths' data in order, without the box. Tabler's outline set draws only paths.</summary>
    private static string Drawing(string file)
    {
        var shapes = XDocument.Load(file).Root!.Elements().ToList();
        Assert.All(shapes, s => Assert.Equal(Svg + "path", s.Name));
        return string.Join(" ", shapes.Select(s => (string)s.Attribute("d")!).Where(d => d != Box));
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `dotnet test clients/helper/Healdan.Helper.sln --filter "FullyQualifiedName~IconsTests"`
Expected: FAIL with `DirectoryNotFoundException` for `clients/icons/tabler`.

- [ ] **Step 3: Get the icons** (ask the owner first: it downloads Tabler from npm)

```bash
S=$(mktemp -d) && cd "$S" && npm view @tabler/icons version        # note the version, say 3.x.y
npm pack @tabler/icons@<that version> && tar -xzf tabler-icons-*.tgz
mkdir -p "$OLDPWD/clients/icons/tabler"
for n in alert-triangle briefcase building chevron-left chevron-right chevron-up clock device-floppy eye file file-plus \
         file-spreadsheet file-text file-type-pdf folder folder-open folder-plus hash history list-details lock photo power \
         presentation puzzle search selector server star versions; do
  cp "package/icons/outline/$n.svg" "$OLDPWD/clients/icons/tabler/"
done
cp package/LICENSE "$OLDPWD/clients/icons/LICENSE-tabler.txt"; cd "$OLDPWD"
```

If an icon is missing from the release, stop and ask the owner for a substitute. Don't draw one.

- [ ] **Step 4: Write `clients/icons/README.md`**

```markdown
# Healdan's Client Icons

The helper's and the LibreOffice extension's icons are [Tabler Icons](https://tabler.io/icons) <version>, the outline
set, under the MIT licence in `LICENSE-tabler.txt` (polish spec §4.4). `tabler/` holds the SVG of each icon used,
as the release has it.

- **The helper** draws them from `clients/helper/Healdan.Helper/Windows/HelperIcons.axaml`, which
  `python3 clients/icons/write-helper-icons.py` writes from `tabler/`. `IconsTests` fails until it's run.
- **The extension's images** are rendered from the same drawings by `clients/helper/tools/IconRender`.

To add an icon, copy its SVG from the same release into `tabler/`, run the script, and add its name to
`IconsTests.SpecNames` and the spec's table.
```

- [ ] **Step 5: Write the generator** (`clients/icons/write-helper-icons.py`)

```python
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
```

Run: `python3 clients/icons/write-helper-icons.py`

- [ ] **Step 6: Write `IconView`** (`clients/helper/Healdan.Helper/Windows/IconView.cs`)

```csharp
using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.Documents;
using Avalonia.Media;

namespace Healdan.Helper.Windows;

/// <summary>
/// One of Healdan's icons (polish spec §4.4): Tabler's 24-unit drawing scaled to the control's size, stroked with round
/// caps and joins at <see cref="StrokeThickness"/> whatever the size. Avalonia's <c>Path</c> would stretch each icon to
/// its own bounds and scale its stroke. (call)
/// </summary>
public sealed class IconView : Control
{
    public static readonly StyledProperty<Geometry?> DataProperty = AvaloniaProperty.Register<IconView, Geometry?>(nameof(Data));

    public static readonly StyledProperty<IBrush?> ForegroundProperty = TextElement.ForegroundProperty.AddOwner<IconView>();

    /// <summary>A fill, for an icon shown "on", such as a favourite's star.</summary>
    public static readonly StyledProperty<IBrush?> FillProperty = AvaloniaProperty.Register<IconView, IBrush?>(nameof(Fill));

    public static readonly StyledProperty<double> StrokeThicknessProperty =
        AvaloniaProperty.Register<IconView, double>(nameof(StrokeThickness), 1.5);

    static IconView()
    {
        AffectsRender<IconView>(DataProperty, ForegroundProperty, FillProperty, StrokeThicknessProperty);
        WidthProperty.OverrideDefaultValue<IconView>(16);
        HeightProperty.OverrideDefaultValue<IconView>(16);
    }

    public Geometry? Data
    {
        get => GetValue(DataProperty);
        set => SetValue(DataProperty, value);
    }

    public IBrush? Foreground
    {
        get => GetValue(ForegroundProperty);
        set => SetValue(ForegroundProperty, value);
    }

    public IBrush? Fill
    {
        get => GetValue(FillProperty);
        set => SetValue(FillProperty, value);
    }

    public double StrokeThickness
    {
        get => GetValue(StrokeThicknessProperty);
        set => SetValue(StrokeThicknessProperty, value);
    }

    public override void Render(DrawingContext context)
    {
        var scale = Math.Min(Bounds.Width, Bounds.Height) / 24;
        if (Data is not { } data || scale <= 0)
            return;
        var pen = new Pen(Foreground, StrokeThickness / scale, lineCap: PenLineCap.Round, lineJoin: PenLineJoin.Round);
        using (context.PushTransform(Matrix.CreateScale(scale, scale)))
            context.DrawGeometry(Fill, pen, data);
    }
}
```

- [ ] **Step 7: Write `Icons`** (`clients/helper/Healdan.Helper/Windows/Icons.cs`)

```csharp
using Avalonia;
using Avalonia.Data.Converters;
using Avalonia.Media;

namespace Healdan.Helper.Windows;

/// <summary>The icons in <c>HelperIcons.axaml</c> by their Tabler names (polish spec §4.4), for code and bindings.</summary>
public static class Icons
{
    /// <summary>A binding's converter: a Tabler name, such as an entry's <c>Icon</c>, to its drawing.</summary>
    public static readonly IValueConverter ByName = new FuncValueConverter<string?, Geometry?>(name => name is null ? null : Get(name));

    public static Geometry Get(string name) =>
        Application.Current!.TryFindResource("Icon." + name, out var found) && found is Geometry geometry
            ? geometry
            : throw new ArgumentException($"HelperIcons.axaml has no icon named {name}", nameof(name));

    /// <summary>An icon for a row built in code, <paramref name="size"/> points square.</summary>
    public static IconView View(string name, double size = 16) => new() { Data = Get(name), Width = size, Height = size };
}
```

- [ ] **Step 8: Merge the icons into the styles.** In `HelperStyles.axaml`, make `Styles.Resources`' dictionary start with:

```xml
<ResourceDictionary.MergedDictionaries>
  <ResourceInclude Source="avares://HealdanHelper/Windows/HelperIcons.axaml" />
</ResourceDictionary.MergedDictionaries>
```

- [ ] **Step 9: Add Tabler's notice.** In `THIRD-PARTY-NOTICES.txt`, add a section after Avalonia's in the file's own format, with no `Packages:` line, since Tabler isn't a NuGet package:

```text
################################################################################
Tabler Icons <version>
https://tabler.io/icons
The icons in the helper's windows, and the LibreOffice extension's toolbar images
################################################################################

<LICENSE-tabler.txt, verbatim>
```

- [ ] **Step 10: Run the tests and build**

Run: `dotnet test clients/helper/Healdan.Helper.sln`
Expected: PASS, `IconsTests` and `NoticesTests` included, and the app builds with no new warnings.

- [ ] **Step 11: Commit** — "Add Tabler's icons to the helper"

---

### Task 2: Foundations: colour, type and controls

**Files:**
- Modify: `clients/helper/Healdan.Helper/App.axaml`, `App.axaml.cs`, `Windows/HelperStyles.axaml` (rewritten)
- Modify (scratch, not committed): `$R/RenderApp.axaml(.cs)`

**Interfaces:**
- Consumes: `IconView`, `Icon.*` (Task 1).
- Produces:
  - **tokens** (`DynamicResource`): `HdWindowBrush`, `HdBarBrush`, `HdSidebarBrush`, `HdGroupedBrush`, `HdGroupBrush`, `HdLineBrush`, `HdAltRowBrush`, `HdPillBrush`, `HdSegmentBrush`, `HdInactiveSelectionBrush`, `HdTextBrush`, `HdSecondaryBrush`, `HdMutedBrush`, `HdAccentBrush`, `HdAccentTextBrush`, `HdOnAccentBrush`, `HdFieldBrush`, `HdFieldLineBrush`, `HdReadOnlyBrush`, `HdYoursBrush`, `HdTheirsBrush`, `HdCautionBrush`, `HdWarnBrush`, `HdWarnLineBrush`, `HdWarnTextBrush`, `HdRequiredBrush`, `HdProblemBrush`, `HdStarBrush`, `HdChipBrush`, `HdChipTextBrush`, and `HdFocusRing` (`BoxShadows`);
  - **classes:**
    - `TextBlock`: `muted`, `label`, `heading`, `headline`, `title`, `column`, `required`, `problem`, `group-header`, `footnote`;
    - `SelectableTextBlock.value`, `Border.chip`, `TextBox.readonly`, `TextBox.notices`, `Border.warn`;
    - `ListBox`: `rows`, `places`, `segmented`, `versions`, and `ListBoxItem.group`;
    - `StackPanel.holder` and `.mine`, `Border.group`, `Grid.row` and `.divided`, `Border.toolbar`, `Border.footer` and `.line`, `Border.thumbnail`, `Border.pane`, `Border.nav`, `Border.crumb`;
    - `Button`: `tool`, `link`, `footer`, `accent`; `IconView`: `row-icon`, `folder`, `star`, `on`.

- [ ] **Step 1: Render the before set.** It's in `$R/baseline/` already, so check that it's there:

Run: `ls $R/baseline | wc -l`
Expected: `20`.

- [ ] **Step 2: Check Fluent's resource keys.** Every Fluent key this task overrides must exist in Fluent 12.1.4, or the override does nothing:

```bash
python3 -I - <<'EOF'
import os
b = open(os.path.expanduser('~/.nuget/packages/avalonia.themes.fluent/12.1.4/lib/net10.0/Avalonia.Themes.Fluent.dll'), 'rb').read()
keys = """ControlCornerRadius ButtonBackground ButtonBackgroundPointerOver ButtonBackgroundPressed ButtonBorderBrush
ButtonBorderBrushPointerOver ButtonBorderBrushPressed ButtonForeground ButtonForegroundPointerOver ButtonForegroundPressed
AccentButtonBackground AccentButtonBackgroundPointerOver AccentButtonBackgroundPressed AccentButtonBackgroundDisabled
AccentButtonForeground AccentButtonForegroundPointerOver AccentButtonForegroundPressed AccentButtonForegroundDisabled
TextControlBackground TextControlBackgroundPointerOver TextControlBackgroundFocused TextControlBorderBrush
TextControlBorderBrushPointerOver TextControlBorderBrushFocused TextControlForeground TextControlPlaceholderForeground
TextControlBorderThemeThickness TextControlBorderThemeThicknessFocused ComboBoxBackground ComboBoxBackgroundPointerOver
ComboBoxBorderBrush ComboBoxBorderBrushPointerOver ComboBoxDropDownGlyphForeground CheckBoxCheckBackgroundFillChecked
CheckBoxCheckBackgroundStrokeChecked""".split()
print([k for k in keys if k.encode('utf-16-le') not in b] or "all found")
EOF
```

Expected: `all found`. Drop any key it lists from Step 4, and record it in the decision log.

- [ ] **Step 3: Accent and font** (`App.axaml`, `App.axaml.cs`)

`App.axaml`'s palettes become:

```xml
<ColorPaletteResources x:Key="Light" Accent="#FF0C69C0" />
<ColorPaletteResources x:Key="Dark" Accent="#FF1A74C4" />
```

`App.axaml.cs`'s `Initialize` becomes:

```csharp
public override void Initialize()
{
    AvaloniaXamlLoader.Load(this);
    // San Francisco on macOS (polish spec §4.2): Skia's default there is Helvetica. Every top level, so pop-ups and
    // tooltips too; elsewhere the desktop's own font.
    if (OperatingSystem.IsMacOS())
    {
        var font = new Style(x => x.Is<TopLevel>());
        font.Setters.Add(new Setter(TemplatedControl.FontFamilyProperty, new FontFamily(".AppleSystemUIFont")));
        Styles.Add(font);
    }
}
```

Add `using Avalonia.Controls.Primitives; using Avalonia.Media; using Avalonia.Styling;` as needed.

- [ ] **Step 4: Rewrite `HelperStyles.axaml`** with the content below.
  - Tokens are spec §4.1's.
  - Fluent's keys take the token values (§4.3).
  - `TextBlock.muted` takes Secondary (call 8).
  - Keep today's `HdFolderIcon`, `HdDocumentIcon` and `HdStarIcon` geometries and the `Path.folder`, `Path.document`, `Path.star` and `Path.star.on` styles, unchanged, at the end of the file, under the comment `<!-- Until Task 6: the old icons, while windows still use them. -->`. They now take Secondary and the star token, so every commit builds. Task 6 removes them.

```xml
<Styles xmlns="https://github.com/avaloniaui"
        xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml"
        xmlns:local="using:Healdan.Helper.Windows">
  <!-- The helper's look (polish spec §4): a Mac's controls in Healdan's colours, over Fluent. -->
  <Styles.Resources>
    <ResourceDictionary>
      <ResourceDictionary.MergedDictionaries>
        <ResourceInclude Source="avares://HealdanHelper/Windows/HelperIcons.axaml" />
      </ResourceDictionary.MergedDictionaries>
      <ResourceDictionary.ThemeDictionaries>
        <ResourceDictionary x:Key="Light">
          <!-- Healdan's tokens (polish spec §4.1). -->
          <SolidColorBrush x:Key="HdWindowBrush" Color="#FFFFFF" />
          <SolidColorBrush x:Key="HdBarBrush" Color="#F6F6F6" />
          <SolidColorBrush x:Key="HdSidebarBrush" Color="#F5F5F7" />
          <SolidColorBrush x:Key="HdGroupedBrush" Color="#F2F2F4" />
          <SolidColorBrush x:Key="HdGroupBrush" Color="#FFFFFF" />
          <SolidColorBrush x:Key="HdLineBrush" Color="#E5E5EA" />
          <SolidColorBrush x:Key="HdAltRowBrush" Color="#F4F5F5" />
          <SolidColorBrush x:Key="HdPillBrush" Color="#E1E1E6" />
          <SolidColorBrush x:Key="HdSegmentBrush" Color="#FFFFFF" />
          <SolidColorBrush x:Key="HdInactiveSelectionBrush" Color="#DCDCDC" />
          <SolidColorBrush x:Key="HdTextBrush" Color="#1D1D1F" />
          <SolidColorBrush x:Key="HdSecondaryBrush" Color="#6E6E73" />
          <SolidColorBrush x:Key="HdMutedBrush" Color="#8E8E93" />
          <SolidColorBrush x:Key="HdAccentBrush" Color="#0C69C0" />
          <SolidColorBrush x:Key="HdAccentTextBrush" Color="#0C69C0" />
          <SolidColorBrush x:Key="HdOnAccentBrush" Color="#FFFFFF" />
          <SolidColorBrush x:Key="HdFieldBrush" Color="#FFFFFF" />
          <SolidColorBrush x:Key="HdFieldLineBrush" Color="#C7C7CC" />
          <SolidColorBrush x:Key="HdReadOnlyBrush" Color="#F5F5F7" />
          <SolidColorBrush x:Key="HdYoursBrush" Color="#1E7B34" />
          <SolidColorBrush x:Key="HdTheirsBrush" Color="#B3261E" />
          <SolidColorBrush x:Key="HdCautionBrush" Color="#C77700" />
          <SolidColorBrush x:Key="HdWarnBrush" Color="#FFF6E5" />
          <SolidColorBrush x:Key="HdWarnLineBrush" Color="#F2C26B" />
          <SolidColorBrush x:Key="HdWarnTextBrush" Color="#6B3D00" />
          <SolidColorBrush x:Key="HdRequiredBrush" Color="#D70015" />
          <SolidColorBrush x:Key="HdProblemBrush" Color="#C42B1C" />
          <SolidColorBrush x:Key="HdStarBrush" Color="#F59F00" />
          <SolidColorBrush x:Key="HdChipBrush" Color="#EEF3FC" />
          <SolidColorBrush x:Key="HdChipTextBrush" Color="#3B5BDB" />
          <BoxShadows x:Key="HdFocusRing">0 0 0 3 #6622A2E0</BoxShadows>
          <!-- Fluent's own, set to them (polish spec §4.3). -->
          <SolidColorBrush x:Key="ButtonBackground" Color="#FFFFFF" />
          <SolidColorBrush x:Key="ButtonBackgroundPointerOver" Color="#F2F2F4" />
          <SolidColorBrush x:Key="ButtonBackgroundPressed" Color="#E5E5EA" />
          <SolidColorBrush x:Key="ButtonBorderBrush" Color="#C7C7CC" />
          <SolidColorBrush x:Key="ButtonBorderBrushPointerOver" Color="#AEAEB2" />
          <SolidColorBrush x:Key="ButtonBorderBrushPressed" Color="#AEAEB2" />
          <SolidColorBrush x:Key="ButtonForeground" Color="#1D1D1F" />
          <SolidColorBrush x:Key="ButtonForegroundPointerOver" Color="#1D1D1F" />
          <SolidColorBrush x:Key="ButtonForegroundPressed" Color="#1D1D1F" />
          <SolidColorBrush x:Key="AccentButtonBackground" Color="#0C69C0" />
          <SolidColorBrush x:Key="AccentButtonBackgroundPointerOver" Color="#0B5FAE" />
          <SolidColorBrush x:Key="AccentButtonBackgroundPressed" Color="#0A5499" />
          <SolidColorBrush x:Key="AccentButtonBackgroundDisabled" Color="#800C69C0" />
          <SolidColorBrush x:Key="AccentButtonForeground" Color="#FFFFFF" />
          <SolidColorBrush x:Key="AccentButtonForegroundPointerOver" Color="#FFFFFF" />
          <SolidColorBrush x:Key="AccentButtonForegroundPressed" Color="#FFFFFF" />
          <SolidColorBrush x:Key="AccentButtonForegroundDisabled" Color="#CCFFFFFF" />
          <SolidColorBrush x:Key="TextControlBackground" Color="#FFFFFF" />
          <SolidColorBrush x:Key="TextControlBackgroundPointerOver" Color="#FFFFFF" />
          <SolidColorBrush x:Key="TextControlBackgroundFocused" Color="#FFFFFF" />
          <SolidColorBrush x:Key="TextControlBorderBrush" Color="#C7C7CC" />
          <SolidColorBrush x:Key="TextControlBorderBrushPointerOver" Color="#AEAEB2" />
          <SolidColorBrush x:Key="TextControlBorderBrushFocused" Color="#0C69C0" />
          <SolidColorBrush x:Key="TextControlForeground" Color="#1D1D1F" />
          <SolidColorBrush x:Key="TextControlPlaceholderForeground" Color="#8E8E93" />
          <SolidColorBrush x:Key="ComboBoxBackground" Color="#FFFFFF" />
          <SolidColorBrush x:Key="ComboBoxBackgroundPointerOver" Color="#F2F2F4" />
          <SolidColorBrush x:Key="ComboBoxBorderBrush" Color="#C7C7CC" />
          <SolidColorBrush x:Key="ComboBoxBorderBrushPointerOver" Color="#AEAEB2" />
          <SolidColorBrush x:Key="ComboBoxDropDownGlyphForeground" Color="#6E6E73" />
          <SolidColorBrush x:Key="CheckBoxCheckBackgroundFillChecked" Color="#0C69C0" />
          <SolidColorBrush x:Key="CheckBoxCheckBackgroundStrokeChecked" Color="#0C69C0" />
        </ResourceDictionary>
        <ResourceDictionary x:Key="Dark">
          <SolidColorBrush x:Key="HdWindowBrush" Color="#1E1E1E" />
          <SolidColorBrush x:Key="HdBarBrush" Color="#2A2A2C" />
          <SolidColorBrush x:Key="HdSidebarBrush" Color="#262628" />
          <SolidColorBrush x:Key="HdGroupedBrush" Color="#1E1E1E" />
          <SolidColorBrush x:Key="HdGroupBrush" Color="#2A2A2C" />
          <SolidColorBrush x:Key="HdLineBrush" Color="#38383A" />
          <SolidColorBrush x:Key="HdAltRowBrush" Color="#232325" />
          <SolidColorBrush x:Key="HdPillBrush" Color="#3A3A3C" />
          <SolidColorBrush x:Key="HdSegmentBrush" Color="#636366" />
          <SolidColorBrush x:Key="HdInactiveSelectionBrush" Color="#464646" />
          <SolidColorBrush x:Key="HdTextBrush" Color="#F2F2F7" />
          <SolidColorBrush x:Key="HdSecondaryBrush" Color="#AEAEB2" />
          <SolidColorBrush x:Key="HdMutedBrush" Color="#8E8E93" />
          <SolidColorBrush x:Key="HdAccentBrush" Color="#1A74C4" />
          <SolidColorBrush x:Key="HdAccentTextBrush" Color="#5CB3F0" />
          <SolidColorBrush x:Key="HdOnAccentBrush" Color="#FFFFFF" />
          <SolidColorBrush x:Key="HdFieldBrush" Color="#2C2C2E" />
          <SolidColorBrush x:Key="HdFieldLineBrush" Color="#48484A" />
          <SolidColorBrush x:Key="HdReadOnlyBrush" Color="#2A2A2C" />
          <SolidColorBrush x:Key="HdYoursBrush" Color="#69DB7C" />
          <SolidColorBrush x:Key="HdTheirsBrush" Color="#FF8A80" />
          <SolidColorBrush x:Key="HdCautionBrush" Color="#F5A623" />
          <SolidColorBrush x:Key="HdWarnBrush" Color="#3B2A14" />
          <SolidColorBrush x:Key="HdWarnLineBrush" Color="#A0611B" />
          <SolidColorBrush x:Key="HdWarnTextBrush" Color="#FFD8A8" />
          <SolidColorBrush x:Key="HdRequiredBrush" Color="#FF6961" />
          <SolidColorBrush x:Key="HdProblemBrush" Color="#FF6961" />
          <SolidColorBrush x:Key="HdStarBrush" Color="#FCC419" />
          <SolidColorBrush x:Key="HdChipBrush" Color="#23314F" />
          <SolidColorBrush x:Key="HdChipTextBrush" Color="#91A7FF" />
          <BoxShadows x:Key="HdFocusRing">0 0 0 3 #6622A2E0</BoxShadows>
          <SolidColorBrush x:Key="ButtonBackground" Color="#4A4A4D" />
          <SolidColorBrush x:Key="ButtonBackgroundPointerOver" Color="#545457" />
          <SolidColorBrush x:Key="ButtonBackgroundPressed" Color="#5E5E62" />
          <SolidColorBrush x:Key="ButtonBorderBrush" Color="#4A4A4D" />
          <SolidColorBrush x:Key="ButtonBorderBrushPointerOver" Color="#545457" />
          <SolidColorBrush x:Key="ButtonBorderBrushPressed" Color="#5E5E62" />
          <SolidColorBrush x:Key="ButtonForeground" Color="#F2F2F7" />
          <SolidColorBrush x:Key="ButtonForegroundPointerOver" Color="#F2F2F7" />
          <SolidColorBrush x:Key="ButtonForegroundPressed" Color="#F2F2F7" />
          <SolidColorBrush x:Key="AccentButtonBackground" Color="#1A74C4" />
          <SolidColorBrush x:Key="AccentButtonBackgroundPointerOver" Color="#1F7BCC" />
          <SolidColorBrush x:Key="AccentButtonBackgroundPressed" Color="#2383D6" />
          <SolidColorBrush x:Key="AccentButtonBackgroundDisabled" Color="#801A74C4" />
          <SolidColorBrush x:Key="AccentButtonForeground" Color="#FFFFFF" />
          <SolidColorBrush x:Key="AccentButtonForegroundPointerOver" Color="#FFFFFF" />
          <SolidColorBrush x:Key="AccentButtonForegroundPressed" Color="#FFFFFF" />
          <SolidColorBrush x:Key="AccentButtonForegroundDisabled" Color="#CCFFFFFF" />
          <SolidColorBrush x:Key="TextControlBackground" Color="#2C2C2E" />
          <SolidColorBrush x:Key="TextControlBackgroundPointerOver" Color="#2C2C2E" />
          <SolidColorBrush x:Key="TextControlBackgroundFocused" Color="#2C2C2E" />
          <SolidColorBrush x:Key="TextControlBorderBrush" Color="#48484A" />
          <SolidColorBrush x:Key="TextControlBorderBrushPointerOver" Color="#5A5A5E" />
          <SolidColorBrush x:Key="TextControlBorderBrushFocused" Color="#1A74C4" />
          <SolidColorBrush x:Key="TextControlForeground" Color="#F2F2F7" />
          <SolidColorBrush x:Key="TextControlPlaceholderForeground" Color="#8E8E93" />
          <SolidColorBrush x:Key="ComboBoxBackground" Color="#4A4A4D" />
          <SolidColorBrush x:Key="ComboBoxBackgroundPointerOver" Color="#545457" />
          <SolidColorBrush x:Key="ComboBoxBorderBrush" Color="#4A4A4D" />
          <SolidColorBrush x:Key="ComboBoxBorderBrushPointerOver" Color="#545457" />
          <SolidColorBrush x:Key="ComboBoxDropDownGlyphForeground" Color="#AEAEB2" />
          <SolidColorBrush x:Key="CheckBoxCheckBackgroundFillChecked" Color="#1A74C4" />
          <SolidColorBrush x:Key="CheckBoxCheckBackgroundStrokeChecked" Color="#1A74C4" />
        </ResourceDictionary>
      </ResourceDictionary.ThemeDictionaries>
      <CornerRadius x:Key="ControlCornerRadius">6</CornerRadius>
      <Thickness x:Key="TextControlBorderThemeThickness">1</Thickness>
      <Thickness x:Key="TextControlBorderThemeThicknessFocused">1</Thickness>
    </ResourceDictionary>
  </Styles.Resources>

  <Style Selector="Window">
    <Setter Property="Background" Value="{DynamicResource HdWindowBrush}" />
    <Setter Property="Foreground" Value="{DynamicResource HdTextBrush}" />
    <Setter Property="FontSize" Value="13" />
  </Style>

  <!-- Controls at the Mac's sizes (polish spec §4.3). -->
  <Style Selector="Button">
    <Setter Property="MinHeight" Value="22" />
    <Setter Property="Padding" Value="12,2" />
    <Setter Property="FontSize" Value="13" />
  </Style>
  <Style Selector="TextBox">
    <Setter Property="MinHeight" Value="22" />
    <Setter Property="Padding" Value="6,2" />
    <Setter Property="CornerRadius" Value="5" />
    <Setter Property="VerticalContentAlignment" Value="Center" />
  </Style>
  <Style Selector="TextBox:focus /template/ Border#PART_BorderElement">
    <Setter Property="BoxShadow" Value="{DynamicResource HdFocusRing}" />
  </Style>
  <Style Selector="ComboBox">
    <Setter Property="MinHeight" Value="22" />
    <Setter Property="Padding" Value="10,2,8,2" />
  </Style>
  <!-- A pop-up button's ⌃⌄: Tabler's selector, filled, in place of Fluent's chevron. -->
  <Style Selector="ComboBox /template/ PathIcon#DropDownGlyph">
    <Setter Property="Data" Value="{StaticResource Icon.selector}" />
    <Setter Property="Width" Value="9" />
    <Setter Property="Height" Value="12" />
  </Style>
  <Style Selector="CheckBox">
    <Setter Property="MinHeight" Value="22" />
  </Style>
  <Style Selector="CheckBox /template/ Border#NormalRectangle">
    <Setter Property="Width" Value="14" />
    <Setter Property="Height" Value="14" />
    <Setter Property="CornerRadius" Value="3" />
  </Style>

  <!-- Bars and panes. Forms have none: their content sits on the window (polish spec §5.1). -->
  <Style Selector="Border.toolbar">
    <Setter Property="Background" Value="{DynamicResource HdBarBrush}" />
    <Setter Property="BorderBrush" Value="{DynamicResource HdLineBrush}" />
    <Setter Property="BorderThickness" Value="0,0,0,1" />
    <Setter Property="Padding" Value="12,8" />
  </Style>
  <Style Selector="Border.footer">
    <Setter Property="Padding" Value="20,12,20,16" />
  </Style>
  <Style Selector="Border.footer.line">
    <Setter Property="BorderBrush" Value="{DynamicResource HdLineBrush}" />
    <Setter Property="BorderThickness" Value="0,1,0,0" />
    <Setter Property="Padding" Value="14,10" />
  </Style>
  <Style Selector="Border.pane">
    <Setter Property="BorderBrush" Value="{DynamicResource HdLineBrush}" />
    <Setter Property="BorderThickness" Value="0,0,1,0" />
  </Style>
  <Style Selector="Border.nav">
    <Setter Property="Background" Value="{DynamicResource HdSidebarBrush}" />
    <Setter Property="BorderBrush" Value="{DynamicResource HdLineBrush}" />
    <Setter Property="BorderThickness" Value="0,0,1,0" />
  </Style>
  <Style Selector="Border.crumb">
    <Setter Property="BorderBrush" Value="{DynamicResource HdLineBrush}" />
    <Setter Property="BorderThickness" Value="0,0,0,1" />
    <Setter Property="Padding" Value="12,6" />
  </Style>
  <Style Selector="Border.thumbnail">
    <Setter Property="CornerRadius" Value="6" />
    <Setter Property="BorderThickness" Value="1" />
    <Setter Property="BorderBrush" Value="{DynamicResource HdLineBrush}" />
    <Setter Property="ClipToBounds" Value="True" />
  </Style>

  <!-- Text (polish spec §4.2). "muted" is Secondary, as the windows use it for text people read (call 8). -->
  <Style Selector="TextBlock.muted">
    <Setter Property="Foreground" Value="{DynamicResource HdSecondaryBrush}" />
  </Style>
  <Style Selector="TextBlock.label">
    <Setter Property="Foreground" Value="{DynamicResource HdSecondaryBrush}" />
    <Setter Property="FontSize" Value="11" />
  </Style>
  <Style Selector="TextBlock.heading, TextBlock.headline">
    <Setter Property="FontSize" Value="13" />
    <Setter Property="FontWeight" Value="SemiBold" />
  </Style>
  <Style Selector="TextBlock.title">
    <Setter Property="FontSize" Value="15" />
    <Setter Property="FontWeight" Value="SemiBold" />
  </Style>
  <Style Selector="TextBlock.column">
    <Setter Property="Foreground" Value="{DynamicResource HdSecondaryBrush}" />
    <Setter Property="FontSize" Value="11" />
  </Style>
  <Style Selector="TextBlock.group-header">
    <Setter Property="Foreground" Value="{DynamicResource HdSecondaryBrush}" />
    <Setter Property="FontSize" Value="11" />
    <Setter Property="FontWeight" Value="SemiBold" />
    <Setter Property="Margin" Value="4,14,4,5" />
  </Style>
  <Style Selector="TextBlock.footnote">
    <Setter Property="Foreground" Value="{DynamicResource HdSecondaryBrush}" />
    <Setter Property="FontSize" Value="11" />
    <Setter Property="TextWrapping" Value="Wrap" />
    <Setter Property="Margin" Value="4,5,4,0" />
  </Style>
  <Style Selector="TextBlock.required">
    <Setter Property="Foreground" Value="{DynamicResource HdRequiredBrush}" />
  </Style>
  <Style Selector="TextBlock.problem">
    <Setter Property="Foreground" Value="{DynamicResource HdProblemBrush}" />
    <Setter Property="FontSize" Value="11" />
    <Setter Property="TextWrapping" Value="Wrap" />
  </Style>
  <Style Selector="SelectableTextBlock.value">
    <Setter Property="TextWrapping" Value="Wrap" />
    <Setter Property="Margin" Value="0,1,0,2" />
  </Style>

  <!-- "from the matter", "from Drafts". -->
  <Style Selector="Border.chip">
    <Setter Property="Background" Value="{DynamicResource HdChipBrush}" />
    <Setter Property="CornerRadius" Value="8" />
    <Setter Property="Padding" Value="6,0" />
    <Setter Property="Margin" Value="4,0,0,0" />
    <Setter Property="VerticalAlignment" Value="Center" />
  </Style>
  <Style Selector="Border.chip > TextBlock">
    <Setter Property="Foreground" Value="{DynamicResource HdChipTextBrush}" />
    <Setter Property="FontSize" Value="10" />
  </Style>
  <Style Selector="TextBox.readonly">
    <Setter Property="Background" Value="{DynamicResource HdReadOnlyBrush}" />
    <Setter Property="Foreground" Value="{DynamicResource HdSecondaryBrush}" />
  </Style>
  <!-- Open-Source Notices: the file on the window's colours, unwrapped (polish spec §6.7). -->
  <Style Selector="TextBox.notices, TextBox.notices:focus, TextBox.notices:pointerover">
    <Setter Property="Background" Value="{DynamicResource HdWindowBrush}" />
  </Style>
  <Style Selector="TextBox.notices /template/ Border#PART_BorderElement, TextBox.notices:focus /template/ Border#PART_BorderElement, TextBox.notices:pointerover /template/ Border#PART_BorderElement">
    <Setter Property="Background" Value="{DynamicResource HdWindowBrush}" />
    <Setter Property="BorderThickness" Value="0" />
    <Setter Property="BoxShadow" Value="" />
  </Style>
  <Style Selector="Border.warn">
    <Setter Property="Background" Value="{DynamicResource HdWarnBrush}" />
    <Setter Property="BorderBrush" Value="{DynamicResource HdWarnLineBrush}" />
    <Setter Property="BorderThickness" Value="1" />
    <Setter Property="CornerRadius" Value="7" />
    <Setter Property="Padding" Value="10,8" />
  </Style>
  <Style Selector="Border.warn TextBlock">
    <Setter Property="Foreground" Value="{DynamicResource HdWarnTextBrush}" />
  </Style>

  <!-- Settings' groups (polish spec §6.5). -->
  <Style Selector="Border.group">
    <Setter Property="Background" Value="{DynamicResource HdGroupBrush}" />
    <Setter Property="BorderBrush" Value="{DynamicResource HdLineBrush}" />
    <Setter Property="BorderThickness" Value="1" />
    <Setter Property="CornerRadius" Value="8" />
  </Style>
  <Style Selector="Grid.row">
    <Setter Property="MinHeight" Value="30" />
    <Setter Property="Margin" Value="10,4" />
  </Style>
  <Style Selector="Border.divided">
    <Setter Property="BorderBrush" Value="{DynamicResource HdLineBrush}" />
    <Setter Property="BorderThickness" Value="0,1,0,0" />
  </Style>

  <!-- Icons (polish spec §4.4). -->
  <Style Selector="local|IconView.row-icon">
    <Setter Property="Foreground" Value="{DynamicResource HdSecondaryBrush}" />
  </Style>
  <Style Selector="local|IconView.folder">
    <Setter Property="Foreground" Value="{DynamicResource HdAccentTextBrush}" />
  </Style>
  <Style Selector="local|IconView.star">
    <Setter Property="Foreground" Value="{DynamicResource HdSecondaryBrush}" />
  </Style>
  <Style Selector="local|IconView.star.on">
    <Setter Property="Foreground" Value="{DynamicResource HdStarBrush}" />
    <Setter Property="Fill" Value="{DynamicResource HdStarBrush}" />
  </Style>

  <!-- Who holds a document: a lock and today's words, yours green, someone else's red (polish spec §6.2). -->
  <Style Selector="StackPanel.holder TextBlock, StackPanel.holder local|IconView">
    <Setter Property="Foreground" Value="{DynamicResource HdTheirsBrush}" />
  </Style>
  <Style Selector="StackPanel.holder TextBlock">
    <Setter Property="FontSize" Value="11" />
  </Style>
  <Style Selector="StackPanel.holder.mine TextBlock, StackPanel.holder.mine local|IconView">
    <Setter Property="Foreground" Value="{DynamicResource HdYoursBrush}" />
  </Style>

  <!-- The lists: alternating rows; the Mac's selection, grey when the list isn't focused (polish spec §4.3). -->
  <Style Selector="ListBox.rows, ListBox.places, ListBox.versions, ListBox.segmented">
    <Setter Property="Background" Value="Transparent" />
    <Setter Property="BorderThickness" Value="0" />
  </Style>
  <Style Selector="ListBox.rows ListBoxItem">
    <Setter Property="Padding" Value="10,4" />
    <Setter Property="MinHeight" Value="24" />
  </Style>
  <Style Selector="ListBox.rows ListBoxItem:nth-child(2n)">
    <Setter Property="Background" Value="{DynamicResource HdAltRowBrush}" />
  </Style>
  <Style Selector="ListBox.rows ListBoxItem:selected /template/ ContentPresenter#PART_ContentPresenter, ListBox.rows ListBoxItem:selected:pointerover /template/ ContentPresenter#PART_ContentPresenter">
    <Setter Property="Background" Value="{DynamicResource HdInactiveSelectionBrush}" />
  </Style>
  <Style Selector="ListBox.rows:focus-within ListBoxItem:selected /template/ ContentPresenter#PART_ContentPresenter, ListBox.rows:focus-within ListBoxItem:selected:pointerover /template/ ContentPresenter#PART_ContentPresenter">
    <Setter Property="Background" Value="{DynamicResource HdAccentBrush}" />
  </Style>
  <Style Selector="ListBox.rows:focus-within ListBoxItem:selected TextBlock, ListBox.rows:focus-within ListBoxItem:selected local|IconView">
    <Setter Property="Foreground" Value="{DynamicResource HdOnAccentBrush}" />
  </Style>

  <!-- The places: Finder's sidebar, a pill for the one shown (polish spec §6.2). -->
  <Style Selector="ListBox.places ListBoxItem">
    <Setter Property="Padding" Value="8,4" />
    <Setter Property="Margin" Value="6,1" />
    <Setter Property="MinHeight" Value="24" />
    <Setter Property="CornerRadius" Value="6" />
  </Style>
  <Style Selector="ListBox.places ListBoxItem local|IconView">
    <Setter Property="Foreground" Value="{DynamicResource HdAccentTextBrush}" />
  </Style>
  <Style Selector="ListBox.places ListBoxItem:selected /template/ ContentPresenter#PART_ContentPresenter, ListBox.places ListBoxItem:selected:pointerover /template/ ContentPresenter#PART_ContentPresenter">
    <Setter Property="Background" Value="{DynamicResource HdPillBrush}" />
  </Style>
  <Style Selector="ListBox.places ListBoxItem.group">
    <Setter Property="Padding" Value="8,10,8,3" />
    <Setter Property="MinHeight" Value="0" />
    <Setter Property="Background" Value="Transparent" />
  </Style>
  <Style Selector="ListBox.places ListBoxItem.group TextBlock">
    <Setter Property="FontSize" Value="11" />
    <Setter Property="FontWeight" Value="SemiBold" />
    <Setter Property="Foreground" Value="{DynamicResource HdSecondaryBrush}" />
  </Style>

  <!-- Versions: the pill, as the places (polish spec §6.2). -->
  <Style Selector="ListBox.versions ListBoxItem">
    <Setter Property="Padding" Value="7,4" />
    <Setter Property="CornerRadius" Value="5" />
  </Style>
  <Style Selector="ListBox.versions ListBoxItem:selected /template/ ContentPresenter#PART_ContentPresenter, ListBox.versions ListBoxItem:selected:pointerover /template/ ContentPresenter#PART_ContentPresenter">
    <Setter Property="Background" Value="{DynamicResource HdPillBrush}" />
  </Style>

  <!-- The segmented control: a track and a raised segment (polish spec §4.3). -->
  <Style Selector="ListBox.segmented">
    <Setter Property="Background" Value="{DynamicResource HdPillBrush}" />
    <Setter Property="CornerRadius" Value="6" />
    <Setter Property="Padding" Value="2" />
  </Style>
  <Style Selector="ListBox.segmented ListBoxItem">
    <Setter Property="Padding" Value="0,2" />
    <Setter Property="MinHeight" Value="20" />
    <Setter Property="CornerRadius" Value="5" />
    <Setter Property="FontSize" Value="12" />
    <Setter Property="HorizontalContentAlignment" Value="Center" />
  </Style>
  <Style Selector="ListBox.segmented ListBoxItem:selected /template/ ContentPresenter#PART_ContentPresenter, ListBox.segmented ListBoxItem:selected:pointerover /template/ ContentPresenter#PART_ContentPresenter">
    <Setter Property="Background" Value="{DynamicResource HdSegmentBrush}" />
    <Setter Property="BorderBrush" Value="{DynamicResource HdLineBrush}" />
    <Setter Property="BorderThickness" Value="1" />
  </Style>

  <!-- Buttons: icon-only tools, links, footers. -->
  <Style Selector="Button.tool">
    <Setter Property="Padding" Value="5,3" />
    <Setter Property="MinWidth" Value="26" />
    <Setter Property="Background" Value="Transparent" />
    <Setter Property="BorderThickness" Value="0" />
  </Style>
  <Style Selector="Button.tool local|IconView">
    <Setter Property="Foreground" Value="{DynamicResource HdTextBrush}" />
  </Style>
  <Style Selector="Button.tool:disabled local|IconView">
    <Setter Property="Foreground" Value="{DynamicResource HdMutedBrush}" />
  </Style>
  <Style Selector="Button.tool:disabled /template/ ContentPresenter#PART_ContentPresenter">
    <Setter Property="Background" Value="Transparent" />
  </Style>
  <Style Selector="Button.link">
    <Setter Property="Background" Value="Transparent" />
    <Setter Property="BorderThickness" Value="0" />
    <Setter Property="Padding" Value="0" />
    <Setter Property="Foreground" Value="{DynamicResource HdAccentTextBrush}" />
  </Style>
  <Style Selector="Button.link:pointerover /template/ ContentPresenter#PART_ContentPresenter, Button.link:pressed /template/ ContentPresenter#PART_ContentPresenter">
    <Setter Property="Background" Value="Transparent" />
    <Setter Property="Foreground" Value="{DynamicResource HdAccentTextBrush}" />
  </Style>
  <Style Selector="Button.footer">
    <Setter Property="MinWidth" Value="80" />
    <Setter Property="HorizontalContentAlignment" Value="Center" />
  </Style>
</Styles>
```

- [ ] **Step 5: Bring the renderer in step.**
  - Copy `App.axaml`'s new palettes into `$R/RenderApp.axaml`.
  - Copy the macOS font style from Step 3 into `$R/RenderApp.axaml.cs`'s `Initialize`.
  - Remove the spike's `HD_FONT` block from `$R/Harness.cs`.

- [ ] **Step 6: Build, test and render**

Run: `dotnet build clients/helper/Healdan.Helper.sln && dotnet test clients/helper/Healdan.Helper.sln && $R/render.sh`
Expected: PASS with no new warnings.
- Every window renders in San Francisco with the new colours, buttons and fields, and dark-mode fields are lighter than the window.
- The layouts are still the old ones: Tasks 3–7 change them.
- Check Review Focus 2 across the twenty images.

- [ ] **Step 7: Commit** — "Give the helper Healdan's colours, the Mac's font and Mac-sized controls"

---

### Task 3: The alerts and the forms

**Files:**
- Create: `clients/helper/Healdan.Helper.Core/Ui/CrashedWarning.cs`, `clients/helper/Healdan.Helper/Windows/AlertMark.axaml(.cs)`
- Modify: `clients/helper/Healdan.Helper.Core/Ui/Warnings.cs`; `clients/helper/Healdan.Helper/Windows/HeldElsewhereWindow.axaml`, `SignOutHereWindow.axaml`, `CrashWindow.axaml(.cs)`, `SignInWindow.axaml`, `NoticesWindow.axaml`, `AppWindows.cs` (the crash notification)
- Test: `clients/helper/Healdan.Helper.Core.Tests/Browse/HoldsAndTimesTests.cs` (`AfterACrash`)

**Interfaces:**
- Consumes: the classes `headline`, `muted`, `title`, `warn`, `footer`, `notices` (Task 2); `IconView`, `Icon.alert-triangle` (Task 1).
- Produces:
  - `public sealed record CrashedWarning(string Headline, string Body)`, with `ToString()` returning `$"{Headline} {Body}"`;
  - `Warnings.Crashed(string title, bool readOnly)` returning `CrashedWarning`;
  - the `AlertMark` control.

- [ ] **Step 1: Write the failing test.** Replace `AfterACrash` in `HoldsAndTimesTests.cs`:

```csharp
[Fact]
public void AfterACrash()
{
    var crashed = Warnings.Crashed("Lease agreement #1234 v2.1", readOnly: false);
    Assert.Equal("Lease agreement #1234 v2.1 closed unexpectedly.", crashed.Headline);
    Assert.Equal("It's still checked out to you, and your local copy is kept.", crashed.Body);
    Assert.Equal("Lease agreement #1234 v2.1 closed unexpectedly. It's still checked out to you, and your local copy is kept.",
        crashed.ToString()); // the notification's sentence, as before

    var readOnly = Warnings.Crashed("Lease agreement #1234 v2.1 (read-only)", readOnly: true);
    Assert.Equal("Lease agreement #1234 v2.1 (read-only) closed unexpectedly.", readOnly.Headline);
    Assert.Equal("It was read-only, so nothing is lost.", readOnly.Body);
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `dotnet test clients/helper/Healdan.Helper.sln --filter "FullyQualifiedName~HoldsAndTimesTests"`
Expected: FAIL to compile (`string` has no `Headline`).

- [ ] **Step 3: Write the record, and change `Warnings.Crashed`**

`clients/helper/Healdan.Helper.Core/Ui/CrashedWarning.cs`:

```csharp
namespace Healdan.Helper.Core.Ui;

/// <summary>After a Crash's words (polish spec §6.8): its first sentence as the headline, the rest as the body.</summary>
public sealed record CrashedWarning(string Headline, string Body)
{
    /// <summary>The whole sentence, as the crash notification says it.</summary>
    public override string ToString() => $"{Headline} {Body}";
}
```

In `Warnings.cs`, replace `Crashed`:

```csharp
/// <summary>After a Crash: "<c>title</c> closed unexpectedly." then what became of it (polish spec §6.8).</summary>
public static CrashedWarning Crashed(string title, bool readOnly) => new(
    $"{title} closed unexpectedly.",
    readOnly ? "It was read-only, so nothing is lost." : "It's still checked out to you, and your local copy is kept.");
```

In `AppWindows.cs`, the crash notification's `Warnings.Crashed(copy.Title, copy.ReadOnly) + " Choose it in the Healdan Helper menu to reopen it."` becomes `Warnings.Crashed(copy.Title, copy.ReadOnly).ToString() + " Choose it in the Healdan Helper menu to reopen it."`. The text is unchanged, and the `.ToString()` shows a reader that it's the whole sentence.

- [ ] **Step 4: Run the test to see it pass**

Run: `dotnet test clients/helper/Healdan.Helper.sln --filter "FullyQualifiedName~HoldsAndTimesTests"`
Expected: PASS.

- [ ] **Step 5: Write `AlertMark`** (`clients/helper/Healdan.Helper/Windows/AlertMark.axaml`, and a `.cs` with only `InitializeComponent`, as `HealdanMark.axaml.cs` has)

```xml
<UserControl xmlns="https://github.com/avaloniaui"
             xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml"
             xmlns:local="using:Healdan.Helper.Windows"
             x:Class="Healdan.Helper.Windows.AlertMark"
             Width="52" Height="52">
  <!-- The alerts' icon (polish spec §5.2): the mark, with a caution badge on a window-coloured disc. -->
  <Panel>
    <local:HealdanMark Width="48" Height="48" HorizontalAlignment="Left" VerticalAlignment="Top" />
    <Border Width="22" Height="22" CornerRadius="11" Background="{DynamicResource HdWindowBrush}"
            HorizontalAlignment="Right" VerticalAlignment="Bottom">
      <local:IconView Data="{StaticResource Icon.alert-triangle}" Width="17" Height="17"
                      Foreground="{DynamicResource HdCautionBrush}" HorizontalAlignment="Center" VerticalAlignment="Center" />
    </Border>
  </Panel>
</UserControl>
```

- [ ] **Step 6: Rebuild the three alerts in the alert shell** (§5.2). Keep each window's attributes (`Title`, `Width`, `SizeToContent`, `CanResize`, and so on), its `x:Name`s and its comment. Add `xmlns:local="using:Healdan.Helper.Windows"`. The body becomes:

`HeldElsewhereWindow.axaml`:

```xml
<StackPanel Margin="20,20,20,16">
  <Grid ColumnDefinitions="Auto,*">
    <local:AlertMark VerticalAlignment="Top" Margin="0,0,14,0" />
    <StackPanel Grid.Column="1" Spacing="6">
      <TextBlock x:Name="TitleText" Classes="headline" TextWrapping="Wrap" />
      <TextBlock x:Name="LastSavedText" TextWrapping="Wrap" />
      <Border Classes="warn" Margin="0,2,0,0">
        <StackPanel Spacing="4">
          <TextBlock x:Name="EmphasisText" FontWeight="SemiBold" TextWrapping="Wrap" />
          <TextBlock x:Name="DetailText" TextWrapping="Wrap" />
        </StackPanel>
      </Border>
    </StackPanel>
  </Grid>
  <StackPanel Orientation="Horizontal" HorizontalAlignment="Right" Spacing="8" Margin="0,16,0,0">
    <Button x:Name="ReadOnlyButton" Classes="footer" Content="Open Read-Only" />
    <Button x:Name="EditHereButton" Classes="footer" Content="Edit Here Instead" />
    <Button x:Name="CancelButton" Classes="footer accent" Content="Cancel" IsCancel="True" IsDefault="True" />
  </StackPanel>
</StackPanel>
```

`SignOutHereWindow.axaml`: the same shell. The text column holds `TitleText` (`headline`), `DetailText`, and `UnsentBox` (`Border.warn` around `UnsentText`). The buttons are `SignOutHereButton` and `StayButton`, as today.

`CrashWindow.axaml`: the same shell. The text column holds `TitleText` (`headline`), `MessageText` and `HintText` (`muted`). The buttons are `CheckInButton` and `ReopenButton`, as today. In `CrashWindow.axaml.cs`:

```csharp
var crashed = Warnings.Crashed(copy.Title, copy.ReadOnly);
TitleText.Text = crashed.Headline;
MessageText.Text = crashed.Body;
```

- [ ] **Step 7: Rebuild Sign In** (§5.1, §6.1). Keep the window's attributes and every `x:Name`. The body becomes:

```xml
<StackPanel Margin="24,20,24,16">
  <local:HealdanMark Width="48" Height="48" HorizontalAlignment="Center" />
  <TextBlock Text="Sign In to Healdan" Classes="title" HorizontalAlignment="Center" Margin="0,10,0,2" />
  <TextBlock Classes="muted" FontSize="11" TextWrapping="Wrap" TextAlignment="Center"
             Text="Healdan Helper keeps a sign-in for this computer in the keychain, never your password." />
  <StackPanel Margin="0,16,0,0" Spacing="9">
    <StackPanel>
      <TextBlock Text="Server Address" Classes="label" Margin="0,0,0,3" />
      <TextBox x:Name="Server" PlaceholderText="https://healdan.example" />
    </StackPanel>
    <StackPanel>
      <TextBlock Text="Username" Classes="label" Margin="0,0,0,3" />
      <TextBox x:Name="User" />
    </StackPanel>
    <StackPanel>
      <TextBlock Text="Password" Classes="label" Margin="0,0,0,3" />
      <TextBox x:Name="Password" PasswordChar="&#x2022;" />
    </StackPanel>
    <TextBlock x:Name="Problem" Classes="problem" FontSize="12" IsVisible="False" />
  </StackPanel>
  <StackPanel Orientation="Horizontal" HorizontalAlignment="Right" Spacing="8" Margin="0,18,0,0">
    <Button x:Name="CancelButton" Classes="footer" Content="Cancel" IsCancel="True" />
    <Button x:Name="SignInButton" Classes="footer accent" Content="Sign In" IsDefault="True" />
  </StackPanel>
</StackPanel>
```

- [ ] **Step 8: Open-Source Notices** (§6.7). In `NoticesWindow.axaml`:
  - the footer `Border` becomes `Classes="footer line"`;
  - the `TextBox` gets `Classes="notices"` and `TextWrapping="NoWrap"`;
  - `ScrollViewer.HorizontalScrollBarVisibility="Auto"` is added.

- [ ] **Step 9: Render and look**

Run: `$R/render.sh sign-in && $R/render.sh held-elsewhere && $R/render.sh sign-out-here && $R/render.sh crash && $R/render.sh notices`

Open each PNG in light and dark and compare it with `window-shells.html`:
- the mark sits top left with its badge;
- the headline is semibold;
- the buttons sit bottom right with the accent rightmost;
- no grey bands remain;
- Notices has no stranded words.

Check Review Focus 2 in dark.

- [ ] **Step 10: Commit** — "Give the helper's alerts and forms the Mac's layouts and the mark". Note in the body that Open, Save and Properties still use the old icons until Task 4.

---

### Task 4: Open's and Save's chrome and list

**Files:**
- Create: `clients/helper/Healdan.Helper.Core/Browse/FileIcons.cs`, `clients/helper/Healdan.Helper/Windows/BrowserChrome.cs`
- Modify:
  - `clients/helper/Healdan.Helper.Core/Browse/Entry.cs` (`Icon`) and `Browse/Browser.cs` (`SearchPlaceholder`);
  - `clients/helper/Healdan.Helper/Windows/OpenWindow.axaml(.cs)` and `SaveWindow.axaml(.cs)` (toolbar, list template, footer);
  - `PlacesPane.cs` and `BrowseControls.cs` (crumb separator, star icon).
- Test: `clients/helper/Healdan.Helper.Core.Tests/Browse/FileIconsTests.cs`, `Browse/BrowserTests.cs`

**Interfaces:**
- Consumes: `IconView`, `Icons` (Task 1); classes from Task 2.
- Produces:
  - `public static class FileIcons { public static string For(string name) }`;
  - `Entry.Icon` (`string`);
  - `internal static class BrowserChrome { public static void Apply(Window window, Border toolbar, TextBlock title, IEnumerable<Control> controls) }`;
  - `BrowseControls.Parts.StarIcon` changes from `Path` to `IconView`.

- [ ] **Step 1: Write the failing tests**

`FileIconsTests.cs`:

```csharp
using Healdan.Helper.Core.Browse;

namespace Healdan.Helper.Core.Tests.Browse;

/// <summary>Each row's icon by its file's kind (polish spec §4.4): the office kinds as Settings has them, PDFs, images, and the rest.</summary>
public sealed class FileIconsTests
{
    [Theory]
    [InlineData("Lease agreement.docx", "file-text")]
    [InlineData("Minutes.ODT", "file-text")]
    [InlineData("Letter.rtf", "file-text")]
    [InlineData("Schedule of dilapidations.xlsx", "file-spreadsheet")]
    [InlineData("Costs.ods", "file-spreadsheet")]
    [InlineData("Pitch.pptx", "presentation")]
    [InlineData("Signed NDA.pdf", "file-type-pdf")]
    [InlineData("Site photo.JPG", "photo")]
    [InlineData("Plan.heic", "photo")]
    [InlineData("archive.zip", "file")]
    [InlineData("docx", "file")]
    public void EachFileGetsItsKindsIcon(string name, string icon) => Assert.Equal(icon, FileIcons.For(name));
}
```

In `BrowserTests.cs`:
- in `SearchesWithinTheMatterShown`, change `"Search documents within 998.1 – Healdan Demo"` to `"Search documents within 998.1 – Healdan Demo, or type #1234"`;
- add the two tests below.

```csharp
[Fact]
public void TheDocumentsScopeSaysADocNumberJumps()
{
    var open = Make(BrowseMode.Open);
    Assert.Equal("Search documents, or type #1234", open.SearchPlaceholder);
    open.Scope = SearchScope.Matters;
    Assert.Equal("Search matters", open.SearchPlaceholder); // the other scopes stay short (call 2)
    Assert.Equal("Search folders", Make(BrowseMode.Save).SearchPlaceholder);
}

[Fact]
public async Task EntriesCarryTheirIcons()
{
    var browser = Make(BrowseMode.Save);
    await browser.OpenFolderAsync(11);

    Assert.Equal(["folder", "file-text"], browser.Entries.Select(e => e.Icon));
}
```

- [ ] **Step 2: Run them to see them fail**

Run: `dotnet test clients/helper/Healdan.Helper.sln --filter "FullyQualifiedName~FileIconsTests|FullyQualifiedName~BrowserTests"`
Expected: FAIL to compile (`FileIcons`, `Entry.Icon`).

- [ ] **Step 3: Implement**

`FileIcons.cs`:

```csharp
using Healdan.Helper.Core.Settings;

namespace Healdan.Helper.Core.Browse;

/// <summary>A document's icon by its file's kind (polish spec §4.4), as Tabler names it.</summary>
public static class FileIcons
{
    private static readonly HashSet<string> Images = new([".png", ".jpg", ".jpeg", ".gif", ".tif", ".tiff", ".heic", ".webp", ".bmp"],
        StringComparer.OrdinalIgnoreCase);

    public static string For(string name)
    {
        if (FileKinds.OfFile(name) is { } kind)
            return kind == FileKinds.Spreadsheets ? "file-spreadsheet" : kind == FileKinds.Presentations ? "presentation" : "file-text";
        var ext = Path.GetExtension(name);
        return ext.Equals(".pdf", StringComparison.OrdinalIgnoreCase) ? "file-type-pdf" : Images.Contains(ext) ? "photo" : "file";
    }
}
```

In `Entry.cs`, after `IsFolder`:

```csharp
/// <summary>The row's icon (polish spec §4.4): a folder for folders and matters, else the file's kind.</summary>
public string Icon => Kind == EntryKind.Document ? FileIcons.For(Title) : "folder";
```

In `Browser.cs`, `SearchPlaceholder` becomes:

```csharp
/// <summary>The search box's hint. Only the Documents scope mentions that a typed doc # jumps (polish spec §6.2, call 2).</summary>
public string SearchPlaceholder => Scope switch
{
    SearchScope.Clients => "Search clients by number or name",
    SearchScope.Matters => "Search matters",
    SearchScope.Documents when _withinName is not null => $"Search documents within {_withinName}, or type #1234",
    SearchScope.Documents => "Search documents, or type #1234",
    _ when _withinName is not null => $"Search {Scope.ApiName()} within {_withinName}",
    _ => $"Search {Scope.ApiName()}"
};
```

- [ ] **Step 4: Run them to see them pass**

Run: `dotnet test clients/helper/Healdan.Helper.sln --filter "FullyQualifiedName~FileIconsTests|FullyQualifiedName~BrowserTests"`
Expected: PASS.

- [ ] **Step 5: Write `BrowserChrome`** (`clients/helper/Healdan.Helper/Windows/BrowserChrome.cs`)

```csharp
using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.Chrome;
using Avalonia.Input;

namespace Healdan.Helper.Windows;

/// <summary>
/// Open's and Save's toolbar in the title bar on macOS, as Finder's (polish spec §5.3): the window's name at its left,
/// after the system's buttons, and the bar's empty space dragging the window. Elsewhere the system's title bar stays,
/// with the toolbar under it.
/// </summary>
internal static class BrowserChrome
{
    /// <summary>Room for the system's three buttons at the bar's left.</summary>
    private const double SystemButtons = 78;

    private const double BarHeight = 44;

    /// <param name="controls">The bar's controls, which take clicks rather than dragging the window.</param>
    public static void Apply(Window window, Border toolbar, TextBlock title, IEnumerable<Control> controls)
    {
        if (!OperatingSystem.IsMacOS())
            return;
        window.ExtendClientAreaToDecorationsHint = true;
        window.ExtendClientAreaTitleBarHeightHint = BarHeight;
        toolbar.MinHeight = BarHeight;
        toolbar.Padding = new Thickness(SystemButtons, 0, 12, 0);
        (title.Text, title.IsVisible) = (window.Title, true);
        WindowDecorationProperties.SetElementRole(toolbar, WindowDecorationsElementRole.TitleBar);
        foreach (var control in controls)
            WindowDecorationProperties.SetElementRole(control, WindowDecorationsElementRole.User);
    }
}
```

- [ ] **Step 6: The toolbar, in both windows.** In `OpenWindow.axaml` and `SaveWindow.axaml`:
  - add `xmlns:local="using:Healdan.Helper.Windows"`;
  - replace the toolbar `Border` with the markup below;
  - in each code-behind, after `InitializeComponent` and after `Title` is set (Save sets it for Save as New Document), call `BrowserChrome.Apply(this, Toolbar, WindowTitle, [BackButton, ForwardButton, UpButton, ScopePicker, SearchBox]);`.

```xml
<Border x:Name="Toolbar" Classes="toolbar" DockPanel.Dock="Top">
  <Grid ColumnDefinitions="Auto,Auto,*,Auto,Auto" VerticalAlignment="Center">
    <TextBlock x:Name="WindowTitle" Classes="heading" VerticalAlignment="Center" Margin="0,0,12,0" IsVisible="False" />
    <StackPanel Grid.Column="1" Orientation="Horizontal" Spacing="2">
      <Button x:Name="BackButton" Classes="tool" AutomationProperties.Name="Back">
        <local:IconView Data="{StaticResource Icon.chevron-left}" />
      </Button>
      <Button x:Name="ForwardButton" Classes="tool" AutomationProperties.Name="Forward">
        <local:IconView Data="{StaticResource Icon.chevron-right}" />
      </Button>
      <Button x:Name="UpButton" Classes="tool" AutomationProperties.Name="Up">
        <local:IconView Data="{StaticResource Icon.chevron-up}" />
      </Button>
    </StackPanel>
    <ComboBox x:Name="ScopePicker" Grid.Column="3" MinWidth="112" Margin="10,0,6,0" VerticalAlignment="Center" />
    <TextBox x:Name="SearchBox" Grid.Column="4" Width="320" VerticalAlignment="Center">
      <TextBox.InnerLeftContent>
        <local:IconView Data="{StaticResource Icon.search}" Width="13" Height="13" Margin="6,0,0,0"
                        Foreground="{DynamicResource HdSecondaryBrush}" />
      </TextBox.InnerLeftContent>
    </TextBox>
  </Grid>
</Border>
```

- [ ] **Step 7: The crumb row's star, and the list.** In both windows:

The crumb row's star becomes:

```xml
<Button x:Name="StarButton" Grid.Column="1" Classes="tool" AutomationProperties.Name="Favourite">
  <local:IconView x:Name="StarIcon" Classes="star" Data="{StaticResource Icon.star}" />
</Button>
```

The column headers `Grid` takes `Margin="10,6,10,3"`, and each header keeps `Classes="column"`.

Each `DataTemplate`'s first column and title cell become the markup below (Open shown; Save's columns stay as they are). In `BrowseControls.cs`, `Parts.StarIcon` becomes `IconView StarIcon`.

```xml
<local:IconView Data="{Binding Icon, Converter={x:Static local:Icons.ByName}}" Classes="row-icon" Classes.folder="{Binding IsFolder}"
                VerticalAlignment="Top" HorizontalAlignment="Left" Margin="0,1,0,0" />
<StackPanel Grid.Column="1" Margin="0,0,8,0">
  <TextBlock Text="{Binding Title}" TextTrimming="CharacterEllipsis" />
  <StackPanel Orientation="Horizontal" Spacing="3" IsVisible="{Binding HasHolder}" Classes="holder" Classes.mine="{Binding HeldByMe}">
    <local:IconView Data="{StaticResource Icon.lock}" Width="11" Height="11" VerticalAlignment="Center" />
    <TextBlock Text="{Binding Holder}" />
  </StackPanel>
</StackPanel>
```

In `BrowseControls.CrumbControl`, the separator `TextBlock` "  ›  " becomes an icon:

```csharp
step.Children.Add(new IconView
{
    Data = Icons.Get("chevron-right"), Width = 12, Height = 12, Margin = new Avalonia.Thickness(4, 0),
    Classes = { "row-icon" }, VerticalAlignment = VerticalAlignment.Center
});
```

- [ ] **Step 8: The places** (`PlacesPane.cs`)
  - Each place's item is an icon and its label. The Browse header item takes `Classes = { "group" }` and the text "Browse", not "BROWSE", with no `LetterSpacing`.
  - Give each place's `ListBoxItem` the content below, and add the `Icon` method.

```csharp
Content = new StackPanel
{
    Orientation = Orientation.Horizontal,
    Spacing = 7,
    Children = { Icons.View(Icon(places[i].Kind), 15), new TextBlock { Text = places[i].Label, VerticalAlignment = VerticalAlignment.Center } }
},
```

```csharp
/// <summary>Each place's icon (polish spec §4.4).</summary>
private static string Icon(PlaceKind kind) => kind switch
{
    PlaceKind.RecentDocuments => "clock",
    PlaceKind.CheckedOutToMe => "lock",
    PlaceKind.Favorites => "star",
    PlaceKind.RecentMatters => "history",
    PlaceKind.MyMatters => "briefcase",
    PlaceKind.Clients => "building",
    _ => "folder"
};
```

- [ ] **Step 9: The footers.**
  - Each footer `Border` becomes `Classes="footer line"`.
  - `LocalButton`'s content becomes **Open from Local Computer…** in Open and **Save to Local Computer…** in Save.
  - Save's **New Folder** button gets the icon:

```xml
<Button x:Name="NewFolderButton" Grid.Column="2" Margin="6,0,0,0">
  <StackPanel Orientation="Horizontal" Spacing="5">
    <local:IconView Data="{StaticResource Icon.folder-plus}" Width="14" Height="14" />
    <TextBlock Text="New Folder" />
  </StackPanel>
</Button>
```

- [ ] **Step 10: Build, test and render**

Run: `dotnet build clients/helper/Healdan.Helper.sln && dotnet test clients/helper/Healdan.Helper.sln && $R/render.sh open && $R/render.sh save`

Expected: PASS. Compare the renders with `open-window.html`: icons in the sidebar, the pill, the alternating rows, the lock badges, the toolbar's chevrons and magnifier. Open's details pane is still the old one until Task 5.

- [ ] **Step 11: Check the title bar by hand** (Review Focus 3). Run the real helper (Conventions), sign in to a test stack, and choose **Open from Healdan…**. Check that:
  - the bar drags the window, double-click zooms, and the system buttons sit left of the window's name;
  - the chevrons, scope pop-up and search take clicks;
  - full screen works.

  If any of these fails and can't be fixed in `BrowserChrome`, make `Apply` return early on macOS too (spec §13), and record it in the decision log.

- [ ] **Step 12: Commit** — "Give Open and Save Finder's toolbar, sidebar and list"

---

### Task 5: Open's details pane in tabs

**Files:**
- Modify: `clients/helper/Healdan.Helper/Windows/OpenWindow.axaml(.cs)`

**Interfaces:**
- Consumes: `FileIcons.For` (Task 4), `Icons` (Task 1), the `segmented` and `versions` classes and `Border.thumbnail` (Task 2).
- Produces: nothing new for later tasks.

- [ ] **Step 1: Replace the right-hand pane** (the third `Grid` column's `ScrollViewer`) with:

```xml
<DockPanel Grid.Column="2" Margin="14,12,14,10">
  <!-- The details pane (polish spec §6.2): a fixed header, then Properties and Versions. -->
  <StackPanel x:Name="DetailsHeader" DockPanel.Dock="Top" Spacing="10" IsVisible="False">
    <Grid ColumnDefinitions="Auto,*,Auto">
      <local:IconView x:Name="DocIcon" Width="32" Height="32" Foreground="{DynamicResource HdAccentTextBrush}"
                      VerticalAlignment="Top" Margin="0,0,9,0" />
      <StackPanel Grid.Column="1" Spacing="1">
        <TextBlock x:Name="DocTitle" Classes="heading" TextWrapping="Wrap" />
        <TextBlock x:Name="DocSubtitle" Classes="muted" FontSize="11" />
        <StackPanel x:Name="OlderLine" Orientation="Horizontal" Spacing="3" IsVisible="False">
          <local:IconView Data="{StaticResource Icon.eye}" Width="12" Height="12" Foreground="{DynamicResource HdCautionBrush}" />
          <TextBlock Text="Older version: opens read-only" FontSize="11" Foreground="{DynamicResource HdCautionBrush}" />
        </StackPanel>
      </StackPanel>
      <Button x:Name="DocStarButton" Grid.Column="2" Classes="tool" VerticalAlignment="Top" AutomationProperties.Name="Favourite">
        <local:IconView x:Name="DocStarIcon" Classes="star" Data="{StaticResource Icon.star}" />
      </Button>
    </Grid>
    <ListBox x:Name="DetailTabs" Classes="segmented" SelectedIndex="0">
      <ListBox.ItemsPanel>
        <ItemsPanelTemplate>
          <UniformGrid Rows="1" />
        </ItemsPanelTemplate>
      </ListBox.ItemsPanel>
      <ListBoxItem Content="Properties" />
      <ListBoxItem x:Name="VersionsTab" Content="Versions" />
    </ListBox>
  </StackPanel>
  <TextBlock x:Name="DetailsNote" DockPanel.Dock="Top" Classes="muted" TextWrapping="Wrap" TextAlignment="Center"
             Margin="0,40,0,0" Text="Choose a document to see its properties and versions." />
  <Panel Margin="0,10,0,0">
    <ScrollViewer x:Name="PropertiesTab" HorizontalScrollBarVisibility="Disabled" IsVisible="False">
      <StackPanel Spacing="10">
        <Border x:Name="ThumbnailFrame" Classes="thumbnail" MaxHeight="140" HorizontalAlignment="Center" IsVisible="False">
          <Image x:Name="Thumbnail" Stretch="Uniform" />
        </Border>
        <TextBlock x:Name="KindNote" Classes="muted" FontSize="12" TextWrapping="Wrap" IsVisible="False"
                   Text="Only office documents can be edited from here: this one opens read-only." />
        <ItemsControl x:Name="DetailRows">
          <ItemsControl.ItemTemplate>
            <DataTemplate x:DataType="b:Detail">
              <Grid ColumnDefinitions="84,*" Margin="0,2">
                <TextBlock Text="{Binding Label}" Classes="label" />
                <TextBlock Grid.Column="1" Text="{Binding Value}" FontSize="12" TextWrapping="Wrap" />
              </Grid>
            </DataTemplate>
          </ItemsControl.ItemTemplate>
        </ItemsControl>
      </StackPanel>
    </ScrollViewer>
    <ListBox x:Name="VersionList" Classes="versions" IsVisible="False" />
  </Panel>
</DockPanel>
```

- [ ] **Step 2: Rewire the code-behind** (`OpenWindow.axaml.cs`)
  - `VersionsHeading`, `VersionNote` and `ThumbnailNote` go.
  - In the constructor, add:

```csharp
DetailTabs.SelectionChanged += (_, _) => ShowTab();
```

  - Add `ShowTab`:

```csharp
/// <summary>The tab chosen stays as the user left it while they choose other documents (polish spec §6.2).</summary>
private void ShowTab()
{
    var shown = _details is not null;
    PropertiesTab.IsVisible = shown && DetailTabs.SelectedIndex == 0;
    VersionList.IsVisible = shown && DetailTabs.SelectedIndex == 1;
}
```

  - In `ShowDetailsAsync`, replace the lines from `DocTitle.Text = doc.Name;` to `DetailRows.ItemsSource = null;` with:

```csharp
ShowHeader(doc);
(DetailsNote.Text, DetailsNote.IsVisible, DetailsNote.Margin) = ("Loading…", true, new Avalonia.Thickness(0, 12, 0, 0));
PropertiesTab.IsVisible = VersionList.IsVisible = false;
```

  - Its `catch` sets `(DetailsNote.Text, DetailsNote.IsVisible)` as today.
  - Add `ShowHeader`:

```csharp
private void ShowHeader(DocumentView doc, string? version = null)
{
    DetailsHeader.IsVisible = true;
    DocIcon.Data = Icons.Get(FileIcons.For(doc.Name));
    DocTitle.Text = doc.Name;
    DocSubtitle.Text = $"#{doc.DocNumber} · v{version ?? doc.FileVersion}";
    OlderLine.IsVisible = version is not null && version != doc.FileVersion;
}
```

  - `ShowDetails` becomes:

```csharp
private void ShowDetails(DocumentDetails details, string? version)
{
    _details = details;
    var doc = details.Document;
    DetailsNote.IsVisible = false;
    KindNote.IsVisible = FileKinds.Of(doc.Name) is null;
    DetailRows.ItemsSource = details.Rows;
    ShowThumbnail(details.Thumbnail);
    ShowStar();

    var now = DateTimeOffset.Now;
    var rows = details.Saves.Select(s => new VersionRow(s, $"v{s.Version}" + (s.Version == doc.FileVersion ? " (current)" : ""),
        string.Join(" · ", new[] { s.Date is { } d ? When.InSentence(d, now) : null, s.By?.Name, s.Comment }.Where(t => !string.IsNullOrWhiteSpace(t))))).ToList();
    VersionList.ItemsSource = rows;
    VersionsTab.Content = $"Versions ({rows.Count})";
    VersionList.SelectedItem = rows.FirstOrDefault(r => r.Save.Version == (version ?? doc.FileVersion)) ?? rows.FirstOrDefault();
    ShowTab();
    VersionChanged();
}
```

  - `ClearDetails(note)` becomes:

```csharp
private void ClearDetails(string note)
{
    ++_detailLoad;
    _loadingFor = null;
    _details = null;
    DetailsHeader.IsVisible = false;
    (DetailsNote.Text, DetailsNote.IsVisible, DetailsNote.Margin) = (note, true, new Avalonia.Thickness(0, 40, 0, 0));
    DetailRows.ItemsSource = null;
    VersionList.ItemsSource = null;
    ShowTab();
    ShowThumbnail(null);
    OpenButton.IsEnabled = false;
    SetReadOnly(forced: false);
}
```

  - In `ShowThumbnail`, `ThumbnailNote.IsVisible = image is null;` becomes `ThumbnailFrame.IsVisible = image is not null;`.
  - In `ShowStar`, `DocStarButton.IsVisible = true;` stays.
  - `VersionChanged` becomes:

```csharp
/// <summary>An older version opens read-only, as does a file no office app edits; the header says which version (polish spec §6.2).</summary>
private void VersionChanged()
{
    var older = SelectedVersion();
    if (_details is { } d)
        ShowHeader(d.Document, older);
    SetReadOnly(forced: older is not null || (_details is { } e && FileKinds.Of(e.Document.Name) is null));
}
```

- [ ] **Step 3: Build, test and render**

Run: `dotnet build clients/helper/Healdan.Helper.sln && dotnet test clients/helper/Healdan.Helper.sln && $R/render.sh open`

Expected: PASS. Compare the render with `open-details-tabs.html`'s left pane. Then temporarily set `DetailTabs.SelectedIndex = 1` and the second version in the renderer (or by hand in the real helper) to check the right pane: the header reads `v2.0`, the amber line shows, and Open Read-Only is ticked and disabled. Undo any temporary change.

- [ ] **Step 4: Keyboard check** (Review Focus 1). In the real helper's Open window, Tab from the list reaches the star, the segmented control (the arrow keys switch tabs), and the version list. Escape cancels, and Return opens.

- [ ] **Step 5: Commit** — "Show Open's details in Properties and Versions tabs"

---

### Task 6: Save's properties pane, and Properties

**Files:**
- Modify: `clients/helper/Healdan.Helper/Windows/SaveWindow.axaml(.cs)`, `PropertiesForm.cs`, `PropertiesWindow.axaml(.cs)`

**Interfaces:**
- Consumes: `FileIcons` (Task 4), `Icons`, `SelectableTextBlock.value`, `footer` (Task 2).
- Produces: nothing new for later tasks.

- [ ] **Step 1: Save's right pane** (`SaveWindow.axaml`)
  - Its heading becomes left-aligned: `Margin="14,12,14,8"` with no `HorizontalAlignment`.
  - `FormatPanel` is replaced by:

```xml
<StackPanel Spacing="2" Margin="0,4,0,0">
  <TextBlock Text="File Format" Classes="label" Margin="0,0,0,3" />
  <ComboBox x:Name="FormatPicker" HorizontalAlignment="Stretch" />
  <TextBlock x:Name="FormatProblem" Classes="problem" IsVisible="False" Text="LibreOffice didn't say which formats it can save in." />
</StackPanel>
```

- [ ] **Step 2: `AddFormats`** (`SaveWindow.axaml.cs`) becomes:

```csharp
/// <summary>The formats LibreOffice can save in, as a File Format pop-up (polish spec §6.3): the same choices, order and default.</summary>
private void AddFormats(SaveNewRequest request)
{
    _format = request.DefaultFormat();
    if (request.Formats.Count == 0)
    {
        (FormatPicker.IsVisible, FormatProblem.IsVisible) = (false, true);
        return;
    }
    FormatPicker.ItemTemplate = new FuncDataTemplate<string>((format, _) => new TextBlock { Text = format is null ? "" : FileFormats.Label(format) });
    FormatPicker.ItemsSource = request.Formats;
    FormatPicker.SelectedItem = _format;
    FormatPicker.SelectionChanged += (_, _) =>
    {
        if (FormatPicker.SelectedItem is string format)
        {
            _format = format;
            UpdateSave();
        }
    };
}
```

Add `using Avalonia.Controls.Templates;`.

- [ ] **Step 3: Read-only values as text** (`PropertiesForm.cs`, spec §6.3). `MatterRow`'s value, and `ReadOnly(field)`, become `SelectableTextBlock`s:

```csharp
var value = new SelectableTextBlock { Text = matter, Classes = { "value" } };
```

```csharp
private static Control ReadOnly(FormField field) => new SelectableTextBlock
{
    Text = field.Options is null ? field.Text : field.LabelOf(field.Text),
    Classes = { "value" }
};
```

- [ ] **Step 4: Properties' header** (`PropertiesWindow.axaml`, spec §6.4)
  - Replace the toolbar `Border` with the markup below.
  - The footer `Border` keeps `Classes="footer"`.
  - The `ScrollViewer`'s `StackPanel` takes `Margin="20,8,20,12"`.

```xml
<Grid DockPanel.Dock="Top" ColumnDefinitions="Auto,*" Margin="20,16,20,4">
  <local:IconView x:Name="DocIcon" Width="32" Height="32" Foreground="{DynamicResource HdAccentTextBrush}"
                  Data="{StaticResource Icon.file-text}" VerticalAlignment="Top" Margin="0,0,9,0" />
  <StackPanel Grid.Column="1" Spacing="1">
    <TextBlock x:Name="DocTitle" Classes="heading" TextWrapping="Wrap" />
    <TextBlock x:Name="Subtitle" Classes="muted" FontSize="11" Text="Document Properties" />
  </StackPanel>
</Grid>
```

In `PropertiesWindow.axaml.cs`'s `LoadAsync`, replace `var matter = (await doc).Matter?.Display;` with:

```csharp
var d = await doc;
var matter = d.Matter?.Display;
DocTitle.Text = d.Name;
Subtitle.Text = $"#{d.DocNumber} · v{d.FileVersion}";
DocIcon.Data = Icons.Get(FileIcons.For(d.Name));
```

- [ ] **Step 5: Remove the old icons.** No window uses `HdFolderIcon`, `HdDocumentIcon`, `HdStarIcon` or `Path.*` now. Check with `git grep -n "HdFolderIcon\|HdDocumentIcon\|HdStarIcon\|Path\.\|Classes=\"star\"" -- clients/helper/Healdan.Helper`, which should list only `IconView`s with `Classes="star"`. Then delete the "Until Task 6" block from `HelperStyles.axaml`.

- [ ] **Step 6: Build, test and render**

Run: `dotnet build clients/helper/Healdan.Helper.sln && dotnet test clients/helper/Healdan.Helper.sln && $R/render.sh save && $R/render.sh properties`

Expected: PASS.
- Save shows its left-aligned heading, Client / Matter as text with its chip, and a File Format pop-up showing OpenDocument Text (.odt).
- Properties shows the header with the name and `#1234 · v2.1`.
- Check both in dark (Review Focus 2).

- [ ] **Step 7: Commit** — "Show Save's format as a pop-up, and read-only values as text"

---

### Task 7: Settings and About

**Files:**
- Create: `clients/helper/Healdan.Helper.Core/Platform/ExtensionSummary.cs`
- Modify:
  - `clients/helper/Healdan.Helper.Core/Platform/ExtensionCheck.cs` (`Summary()`);
  - `clients/helper/Healdan.Helper/Platform/LoginItem.cs` (`Summary()`, `LoginItemSummary`);
  - `Windows/SettingsWindow.axaml(.cs)`, `Windows/AboutWindow.axaml(.cs)`, `Windows/AppWindows.cs` (`ShowSettings`).
- Test: `clients/helper/Healdan.Helper.Core.Tests/Platform/ExtensionCheckTests.cs`

**Interfaces:**
- Produces:
  - `public sealed record ExtensionSummary(string Value, string? Note, bool CanInstall)` with `static ExtensionSummary NotInThisBuild`;
  - `ExtensionCheck.Summary()`;
  - `internal sealed record LoginItemSummary(string Value, bool On, string Tip)`;
  - `LoginItem.Summary()`;
  - `SettingsWindow(string? server, bool signedIn, HelperSettings settings, LoginItemSummary login, ExtensionSummary extension, string extensionTip, Func<Task<ExtensionSummary>>? install)`.

- [ ] **Step 1: Write the failing tests** (in `ExtensionCheckTests.cs`, under the macOS tests)

```csharp
[Fact]
public async Task MacSettingsRowOffersInstallWhileItIsntInstalled()
{
    Save(new ExtensionRecord { OpenedFor = "0.2.0", OpenedBy = Helper });
    var check = Mac(Oxt("0.2.0"));
    await check.RunAsync();

    Assert.Equal(new ExtensionSummary("Not Installed", "Waiting for you to accept version 0.2.0 in LibreOffice.", true), check.Summary());

    _tools.Registry = new InstalledExtension("0.2.0", true); // the user clicked OK
    var installed = check.Summary();
    Assert.Equal("0.2.0", installed.Value);
    Assert.StartsWith("Installed on ", installed.Note);
    Assert.False(installed.CanInstall);
}

[Fact]
public async Task MacSettingsRowBeforeLibreOfficeWasAsked()
{
    var check = Mac(Oxt("0.2.0"));
    Assert.Equal(new ExtensionSummary("Not Installed", null, false), check.Summary()); // before the start's check: nothing to offer yet

    await check.RunAsync(); // opens it in LibreOffice once
    Assert.Equal(new ExtensionSummary("Not Installed", "Waiting for you to accept version 0.2.0 in LibreOffice.", true), check.Summary());
}

[Fact]
public void SettingsRowWithoutTheOxt() => Assert.Equal(ExtensionSummary.NotInThisBuild, Mac(null).Summary());
```

- [ ] **Step 2: Run them to see them fail**

Run: `dotnet test clients/helper/Healdan.Helper.sln --filter "FullyQualifiedName~ExtensionCheckTests"`
Expected: FAIL to compile (`ExtensionSummary`).

- [ ] **Step 3: Implement**

`ExtensionSummary.cs`:

```csharp
namespace Healdan.Helper.Core.Platform;

/// <summary>
/// Settings' row for the LibreOffice extension (polish spec §6.5): the value on the right, the line under its name, and
/// whether Install… shows.
/// </summary>
public sealed record ExtensionSummary(string Value, string? Note, bool CanInstall)
{
    public static readonly ExtensionSummary NotInThisBuild = new("Not in This Build", "The installed app installs it.", false);
}
```

In `ExtensionCheck.cs`, after `Describe()`:

```csharp
/// <summary>Settings' row (polish spec §6.5); <see cref="Describe"/> is its tooltip.</summary>
public ExtensionSummary Summary()
{
    if (bundledOxt is null || !File.Exists(bundledOxt))
        return ExtensionSummary.NotInThisBuild;
    var missing = Refresh(); // reads the registry first, which may record what the user accepted
    var record = Record();
    if (missing)
        return new("Not Installed", record.OpenedFor is { } waiting
            ? $"Waiting for you to accept version {waiting} in LibreOffice."
            : "Adds Healdan's menu and toolbar to LibreOffice.", true);
    return record.Version is { } version
        ? new(version, record.At is { } at ? "Installed on " + at.ToLocalTime().ToString("d MMMM yyyy", CultureInfo.InvariantCulture) : "Installed", false)
        : new("Not Installed", null, false);
}
```

Add `using System.Globalization;` if it isn't there.

- [ ] **Step 4: Run them to see them pass**

Run: `dotnet test clients/helper/Healdan.Helper.sln --filter "FullyQualifiedName~ExtensionCheckTests"`
Expected: PASS. If `MacSettingsRowBeforeLibreOfficeWasAsked`'s second assertion finds `OpenedFor` unset after `RunAsync`, read `OneClickAsync` and `Open`: the record they write is the source of truth. Fix the test's expectation to match it, not the code.

- [ ] **Step 5: `LoginItem.Summary`** (`Platform/LoginItem.cs`), after `Describe()`:

```csharp
/// <summary>Settings' row (polish spec §6.5): a short value, whether it's on, and <see cref="Describe"/> as its tooltip.</summary>
public static LoginItemSummary Summary()
{
    var tip = Describe();
    if (!Available)
        return new("Not in This Build", false, tip);
    if (Passing() is not null)
        return new("Off", false, tip);
    try
    {
        return Status() switch
        {
            Enabled => new("On", true, tip),
            RequiresApproval => new("Waiting for Approval", false, tip),
            _ => new("Off", false, tip)
        };
    }
    catch (Exception e) when (e is DllNotFoundException or EntryPointNotFoundException)
    {
        return new("Unknown", false, tip);
    }
}
```

At the end of the file:

```csharp
/// <summary>Start at Sign-In's row in Settings.</summary>
internal sealed record LoginItemSummary(string Value, bool On, string Tip);
```

- [ ] **Step 6: Settings' layout** (`SettingsWindow.axaml`). Keep the window's attributes, add `Background="{DynamicResource HdGroupedBrush}"` and `xmlns:local="using:Healdan.Helper.Windows"`. The body becomes:

```xml
<StackPanel Margin="20,6,20,16">
  <!-- Settings (polish spec §6.5): grouped rows, as the Mac's System Settings. -->
  <TextBlock Text="Server" Classes="group-header" />
  <Border Classes="group">
    <Grid Classes="row" ColumnDefinitions="Auto,*,Auto">
      <local:IconView Data="{StaticResource Icon.server}" Classes="folder" Margin="0,0,8,0" VerticalAlignment="Center" />
      <TextBlock Grid.Column="1" Text="Address" VerticalAlignment="Center" />
      <SelectableTextBlock x:Name="ServerValue" Grid.Column="2" Foreground="{DynamicResource HdSecondaryBrush}" VerticalAlignment="Center" />
    </Grid>
  </Border>
  <TextBlock x:Name="ServerNote" Classes="footnote" />
  <TextBlock Text="Open Each Kind of File In" Classes="group-header" />
  <Border Classes="group">
    <StackPanel x:Name="KindsGroup" />
  </Border>
  <TextBlock Classes="footnote" Text="Other files open read-only in the computer's default app." />
  <TextBlock Text="This Computer" Classes="group-header" />
  <Border Classes="group">
    <StackPanel>
      <Grid x:Name="LoginRow" Classes="row" ColumnDefinitions="Auto,*,Auto">
        <local:IconView Data="{StaticResource Icon.power}" Classes="folder" Margin="0,0,8,0" VerticalAlignment="Center" />
        <TextBlock Grid.Column="1" Text="Start at Sign-In" VerticalAlignment="Center" />
        <StackPanel Grid.Column="2" Orientation="Horizontal" Spacing="5" VerticalAlignment="Center">
          <Ellipse x:Name="LoginDot" Width="7" Height="7" />
          <TextBlock x:Name="LoginValue" Foreground="{DynamicResource HdSecondaryBrush}" />
        </StackPanel>
      </Grid>
      <Border Classes="divided">
        <Grid x:Name="ExtensionRow" Classes="row" ColumnDefinitions="Auto,*,Auto">
          <local:IconView Data="{StaticResource Icon.puzzle}" Classes="folder" Margin="0,0,8,0" VerticalAlignment="Center" />
          <StackPanel Grid.Column="1" VerticalAlignment="Center">
            <TextBlock Text="LibreOffice Extension" />
            <TextBlock x:Name="ExtensionNote" Classes="muted" FontSize="11" TextWrapping="Wrap" />
          </StackPanel>
          <StackPanel Grid.Column="2" Orientation="Horizontal" Spacing="8" VerticalAlignment="Center">
            <TextBlock x:Name="ExtensionValue" Foreground="{DynamicResource HdSecondaryBrush}" VerticalAlignment="Center" />
            <Button x:Name="InstallButton" Content="Install…" IsVisible="False" />
          </StackPanel>
        </Grid>
      </Border>
    </StackPanel>
  </Border>
  <Button x:Name="DoneButton" Classes="footer accent" Content="Done" IsDefault="True" IsCancel="True"
          HorizontalAlignment="Right" Margin="0,16,0,0" />
</StackPanel>
```

- [ ] **Step 7: Settings' code** (`SettingsWindow.axaml.cs`). Update the class comment to say the extension's row can install. The constructor becomes:

```csharp
internal SettingsWindow(string? server, bool signedIn, HelperSettings settings, LoginItemSummary login, ExtensionSummary extension,
    string extensionTip, Func<Task<ExtensionSummary>>? install) : this()
{
    ServerValue.Text = server is null ? "Not Set Yet" : HealdanLink.HostAndPort(new Uri(server));
    ServerNote.Text = signedIn
        ? "To use another server, sign out from the Healdan Helper menu, then sign in with its address."
        : "Sign in from the Healdan Helper menu to choose the server.";
    for (var i = 0; i < FileKinds.All.Count; i++)
        KindsGroup.Children.Add(KindRow(FileKinds.All[i], settings.AppFor(FileKinds.All[i].Extensions[0]), divided: i > 0));

    LoginValue.Text = login.Value;
    LoginDot.Fill = (IBrush?)Application.Current!.FindResource(login.On ? "HdOnBrush" : "HdOffBrush");
    ToolTip.SetTip(LoginRow, login.Tip);
    ShowExtension(extension);
    ToolTip.SetTip(ExtensionRow, extensionTip);
    InstallButton.Click += async (_, _) =>
    {
        if (install is null)
            return;
        InstallButton.IsEnabled = false;
        try
        {
            ShowExtension(await install());
        }
        catch (Exception e)
        {
            Program.Log.Error("Settings' Install… failed", e);
        }
        InstallButton.IsEnabled = true;
    };
    DoneButton.Click += (_, _) => Close();
}

private void ShowExtension(ExtensionSummary extension)
{
    ExtensionValue.Text = extension.Value;
    (ExtensionNote.Text, ExtensionNote.IsVisible) = (extension.Note, extension.Note is not null);
    InstallButton.IsVisible = extension.CanInstall;
}

/// <summary>A kind of file, its extensions under it, and the app that opens it as plain text: no disabled pop-up.</summary>
private static Control KindRow(FileKind kind, string app, bool divided)
{
    var grid = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,*,Auto"), Classes = { "row" } };
    var icon = Icons.View(FileIcons.For("x" + kind.Extensions[0]));
    icon.Classes.Add("folder");
    icon.Margin = new Thickness(0, 0, 8, 0);
    icon.VerticalAlignment = VerticalAlignment.Center;
    var name = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
    name.Children.Add(new TextBlock { Text = kind.Label });
    name.Children.Add(new TextBlock { Text = string.Join(" ", kind.Extensions), Classes = { "muted" }, FontSize = 11 });
    var value = new TextBlock { Text = app, Foreground = (IBrush?)Application.Current!.FindResource("HdSecondaryBrush"), VerticalAlignment = VerticalAlignment.Center };
    Grid.SetColumn(name, 1);
    Grid.SetColumn(value, 2);
    grid.Children.Add(icon);
    grid.Children.Add(name);
    grid.Children.Add(value);
    return divided ? new Border { Classes = { "divided" }, Child = grid } : grid;
}
```

- **The dot's colours** are two tokens. Add them to `HelperStyles.axaml`'s theme dictionaries beside `HdYoursBrush`:
  - `HdOnBrush`: `#28A745` light, `#30D158` dark;
  - `HdOffBrush`: `#8E8E93` in both.

  They aren't in spec §4.1's table. Record them in the decision log as detail the spec leaves to the builder.
- The code needs `using Avalonia; using Avalonia.Layout; using Avalonia.Media; using Healdan.Helper.Core.Browse; using Healdan.Helper.Core.Links; using Healdan.Helper.Core.Platform; using Healdan.Helper.Core.Settings; using Healdan.Helper.Platform;`.

- [ ] **Step 8: `AppWindows.ShowSettings`** passes the new arguments:

```csharp
var check = Extension;
var summary = check?.Summary() ?? ExtensionSummary.NotInThisBuild;
var tip = check?.Describe() ?? "Not in this build: the installed app installs it.";
Func<Task<ExtensionSummary>>? install = check is null ? null : () => Task.Run(() =>
{
    check.InstallFromMenu();
    return check.Summary(); // what it says now, such as "Waiting for you to accept …" (call 4)
});
var window = new SettingsWindow(server, Session?.SignedIn == true, HelperSettings.Load(paths), LoginItem.Summary(), summary, tip, install);
```

- [ ] **Step 9: About** (`AboutWindow.axaml(.cs)`, spec §6.6)
  - The mark is `Width="64" Height="64"`.
  - The links row holds `DocsLink`, `LicenceLink`, and a new `<HyperlinkButton x:Name="NoticesLink" Content="Open-Source Notices" />`.
  - The footer `DockPanel` holds `CopyButton` (left) and `DoneButton` (right). `NoticesButton` goes.
  - In the code-behind, `NoticesButton.Click` becomes `NoticesLink.Click`.
  - The copyright line is unchanged.

- [ ] **Step 10: Build, test and render**

Run: `dotnet build clients/helper/Healdan.Helper.sln && dotnet test clients/helper/Healdan.Helper.sln && $R/render.sh settings && $R/render.sh about`

Expected: PASS.
- Settings shows three groups, with the app names as plain text.
- About shows the 64 pt mark and three links.
- Compare both with `settings-and-about.html`, in light and dark.

- [ ] **Step 11: Check Install… by hand.** Run the real helper with the extension removed from LibreOffice (**Tools › Extensions**, then remove Healdan). Open **Settings…**, then:
  - the row says Not Installed with **Install…**;
  - **Install…** opens the `.oxt` in LibreOffice, and the row then says it's waiting for you to accept.

  A Debug helper run from source carries no `.oxt`, so the row shows Not in This Build. Do this check in Task 14 with the packaged app instead, and say so in the task's report.

- [ ] **Step 12: Commit** — "Show Settings as grouped rows, with Install… for the extension"

---

### Task 8: The menu bar icon and its dot

**Files:**
- Create: `clients/helper/Healdan.Helper.Core/Ui/Attention.cs`, `clients/helper/Healdan.Helper/Tray/TrayIcons.cs`
- Modify: `clients/helper/Healdan.Helper/Windows/HealdanMark.axaml` (`x:Name="Shapes"`), `HealdanMark.axaml.cs`, `Tray/TrayMenu.cs`
- Delete: `clients/helper/Healdan.Helper/Assets/tray.png`, `Assets/tray-light.png`
- Test: `clients/helper/Healdan.Helper.Core.Tests/Ui/AttentionTests.cs`

**Interfaces:**
- Produces:
  - `public static class Attention`, with `string? Reason(bool signedIn, bool startFailed, bool crashed, int kept, bool extensionMissing)` and `string ToolTip(string? reason)`;
  - `HealdanMark.Silhouette()` and `HealdanMark.Shield()`, both `Geometry` in the 260-point square;
  - `internal static class TrayIcons { public static WindowIcon Draw(bool dot, Color color) }`.
  - Task 10 uses `Shield()`.

- [ ] **Step 1: Write the failing tests** (`AttentionTests.cs`)

```csharp
using Healdan.Helper.Core.Ui;

namespace Healdan.Helper.Core.Tests.Ui;

/// <summary>The menu bar icon's dot (polish spec §7.2): shown while the helper needs the user, and why.</summary>
public sealed class AttentionTests
{
    [Fact]
    public void NothingNeedsYouWhenAllIsWell() =>
        Assert.Null(Attention.Reason(signedIn: true, startFailed: false, crashed: false, kept: 0, extensionMissing: false));

    [Theory]
    [InlineData(false, false, false, 0, false, "Not Signed In")]
    [InlineData(true, true, false, 0, false, "Couldn't Start")]
    [InlineData(true, false, true, 0, false, "A Document Closed Unexpectedly")]
    [InlineData(true, false, false, 2, false, "Not Yet in Healdan")]
    [InlineData(true, false, false, 0, true, "LibreOffice Extension Not Installed")]
    public void EachReasonShowsTheDot(bool signedIn, bool startFailed, bool crashed, int kept, bool missing, string reason) =>
        Assert.Equal(reason, Attention.Reason(signedIn, startFailed, crashed, kept, missing));

    [Fact]
    public void TheFirstReasonIsNamed() =>
        Assert.Equal("Not Signed In", Attention.Reason(signedIn: false, startFailed: true, crashed: true, kept: 3, extensionMissing: true));

    [Fact]
    public void TheToolTipSaysWhy()
    {
        Assert.Equal("Healdan Helper", Attention.ToolTip(null));
        Assert.Equal("Healdan Helper: Not Signed In", Attention.ToolTip("Not Signed In"));
    }
}
```

- [ ] **Step 2: Run them to see them fail**

Run: `dotnet test clients/helper/Healdan.Helper.sln --filter "FullyQualifiedName~AttentionTests"`
Expected: FAIL to compile (`Attention`).

- [ ] **Step 3: Implement** (`Attention.cs`)

```csharp
namespace Healdan.Helper.Core.Ui;

/// <summary>
/// Why the menu bar icon shows its dot (polish spec §7.2), the first reason first; null while nothing needs the user.
/// Saves waiting to be sent don't count: they clear by themselves.
/// </summary>
public static class Attention
{
    public static string? Reason(bool signedIn, bool startFailed, bool crashed, int kept, bool extensionMissing) =>
        !signedIn ? "Not Signed In"
        : startFailed ? "Couldn't Start"
        : crashed ? "A Document Closed Unexpectedly"
        : kept > 0 ? "Not Yet in Healdan"
        : extensionMissing ? "LibreOffice Extension Not Installed"
        : null;

    /// <summary>The icon's tooltip: the app's name, and the reason when there is one.</summary>
    public static string ToolTip(string? reason) => reason is null ? "Healdan Helper" : $"Healdan Helper: {reason}";
}
```

- [ ] **Step 4: Run them to see them pass**

Run: `dotnet test clients/helper/Healdan.Helper.sln --filter "FullyQualifiedName~AttentionTests"`
Expected: PASS.

- [ ] **Step 5: The mark in one colour** (`HealdanMark.axaml`, `.cs`). In the AXAML, name the inner canvas `x:Name="Shapes"` (the one at `Canvas.Left="22" Canvas.Top="5"`). Nothing else changes, so `MarkTests` still passes. The code-behind becomes:

```csharp
using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.Shapes;
using Avalonia.Media;
using Path = Avalonia.Controls.Shapes.Path;

namespace Healdan.Helper.Windows;

/// <summary>
/// Healdan's mark, as the docs site's favicon draws it, for About (#89), Sign In and the alerts; and, from the same
/// shapes, in one colour for the menu bar and badges (polish spec §7.1, §8.2), so MarkTests covers those too (call 6).
/// </summary>
public partial class HealdanMark : UserControl
{
    /// <summary>Where the shapes sit in the mark's 260-point square.</summary>
    private static readonly Vector Offset = new(22, 5);

    public HealdanMark() => InitializeComponent();

    /// <summary>
    /// The mark in one colour, its pages cut out: the coloured shapes united, each white page taken away, and a clipped
    /// group's shapes put back inside its clip, in the XAML's order. In the 260-point square.
    /// </summary>
    public Geometry Silhouette()
    {
        Geometry? mark = null;
        foreach (var child in Shapes.Children)
        {
            switch (child)
            {
                case Path path:
                    mark = Union(mark, path.Data!);
                    break;
                case Polygon page:
                    mark = new CombinedGeometry(GeometryCombineMode.Exclude, mark!, new PolylineGeometry(page.Points, true));
                    break;
                case Canvas { Clip: { } clip } group:
                    foreach (var part in group.Children.OfType<Path>())
                        mark = Union(mark, new CombinedGeometry(GeometryCombineMode.Intersect, part.Data!, clip));
                    break;
            }
        }
        return Placed(mark!);
    }

    /// <summary>The shield's outline alone, its halves united, for badges. In the 260-point square.</summary>
    public Geometry Shield() => Placed(Shapes.Children.OfType<Path>().Select(p => p.Data!).Aggregate((a, b) => Union(a, b)));

    private static Geometry Union(Geometry? a, Geometry b) => a is null ? b : new CombinedGeometry(GeometryCombineMode.Union, a, b);

    private static Geometry Placed(Geometry shape) => new GeometryGroup { Children = { shape }, Transform = new TranslateTransform(Offset.X, Offset.Y) };
}
```

- [ ] **Step 6: The icons** (`Tray/TrayIcons.cs`)

```csharp
using Avalonia;
using Avalonia.Controls;
using Avalonia.Media;
using Avalonia.Media.Imaging;
using Healdan.Helper.Windows;

namespace Healdan.Helper.Tray;

/// <summary>
/// The menu bar icon (polish spec §7.1, §7.2): the mark in one colour, 22 points at 2x, with or without the attention dot
/// at its top right, which a ring cuts free of the shield.
/// </summary>
internal static class TrayIcons
{
    private const int Pixels = 44;
    private const double Points = 22;
    private const double Square = 260;

    /// <summary>The dot's centre and radii in the mark's square.</summary>
    private static readonly Point Dot = new(214, 46);
    private const double DotRadius = 38;
    private const double RingRadius = 56;

    /// <param name="color">Black on macOS, which tints it as a template image; white on Linux, for GNOME's dark top bar.</param>
    public static WindowIcon Draw(bool dot, Color color)
    {
        var mark = new HealdanMark().Silhouette();
        if (dot)
            mark = new CombinedGeometry(GeometryCombineMode.Union,
                new CombinedGeometry(GeometryCombineMode.Exclude, mark, Circle(RingRadius)), Circle(DotRadius));
        var bitmap = new RenderTargetBitmap(new PixelSize(Pixels, Pixels), new Vector(96 * Pixels / Points, 96 * Pixels / Points));
        using (var context = bitmap.CreateDrawingContext())
        using (context.PushTransform(Matrix.CreateScale(Points / Square, Points / Square)))
            context.DrawGeometry(new SolidColorBrush(color), null, mark);
        var png = new MemoryStream();
        bitmap.Save(png);
        png.Position = 0;
        return new WindowIcon(png);
    }

    private static EllipseGeometry Circle(double radius) => new(new Rect(Dot.X - radius, Dot.Y - radius, radius * 2, radius * 2));
}
```

- [ ] **Step 7: Use them** (`TrayMenu.cs`)
  - Fields: `private readonly WindowIcon _plain; private readonly WindowIcon _attention; private bool? _dotShown;`
  - In the constructor, the asset lines go, and the icon is drawn:

```csharp
// Black on transparent, used as a template image so the menu bar tints it; GNOME's dark top bar takes white (polish spec §7.1).
var color = OperatingSystem.IsLinux() ? Colors.White : Colors.Black;
(_plain, _attention) = (TrayIcons.Draw(false, color), TrayIcons.Draw(true, color));
_icon = new TrayIcon { Icon = _plain, ToolTipText = Attention.ToolTip(null), Menu = _menu, IsVisible = true };
```

  - In `Build()`, read the extension once at the top, and set the icon:

```csharp
var missing = _extension.Refresh(); // reads LibreOffice's registry, so the item goes once the user has accepted it
var copies = _session.OpenCopies();
var reason = Attention.Reason(_session.SignedIn, _session.StartFailed, copies.Any(_session.HasCrashed), _session.KeptCopies().Count, missing);
ShowDot(reason);
```

  - The later `if (_extension.Refresh())` becomes `if (missing)`, and the later `var copies = …` goes, since it's read above.
  - Add `ShowDot`:

```csharp
/// <summary>The dot while the helper needs the user, and the tooltip saying why (polish spec §7.2).</summary>
private void ShowDot(string? reason)
{
    _icon.ToolTipText = Attention.ToolTip(reason);
    if (_dotShown == (reason is not null))
        return;
    _dotShown = reason is not null;
    _icon.Icon = reason is null ? _plain : _attention;
    if (OperatingSystem.IsMacOS())
        MacOSProperties.SetIsTemplateIcon(_icon, true);
}
```

  - Update the class comment to mention the dot, and add `using Avalonia.Media; using Healdan.Helper.Core.Ui;`.
  - Delete `Assets/tray.png` and `Assets/tray-light.png`.

- [ ] **Step 8: Build and test**

Run: `dotnet build clients/helper/Healdan.Helper.sln && dotnet test clients/helper/Healdan.Helper.sln`
Expected: PASS, `MarkTests` included.

- [ ] **Step 9: Check by hand** (Review Focus 4). Run the real helper.
  - Signed out, the menu bar shows the mark with a dot, and hovering says **Healdan Helper: Not Signed In**.
  - Sign in: the dot goes once the extension is installed.
  - Check light and dark menu bars, and the highlighted state when the menu is open.
  - If the shield's pages blur at 22 pt, or the dot crowds the shield, adjust `Dot`, `DotRadius` and `RingRadius` and record the values in the decision log. Don't change the mark.

- [ ] **Step 10: Commit** — "Draw the menu bar icon from the mark, with a dot when the helper needs you"

---

### Task 9: The helper's docs, review and PR 1

**Files:**
- Modify:
  - `site/content/user/office/installing-the-helper/index.md` (and its `sign-in.png`, `settings.png`);
  - `site/content/user/office/opening-documents/index.md` (and `open-from-healdan.png`, `checked-out-on-another-computer.png`);
  - `site/content/user/office/saving-to-healdan/index.md` (and `save-to-healdan.png`);
  - `site/content/user/office/editing-and-versions/index.md` (and `properties.png`);
  - `site/content/user/office/when-things-go-wrong/index.md` (and `after-a-crash.png`);
  - `site/content/developer/desktop-clients.md`, `CLAUDE.md`, and this plan's status and decision log.
- Modify (scratch): `$R/FakeServer.cs` (call 9)

- [ ] **Step 1: Match the renderer's data to the docs' pictures** (call 9). In `$R/FakeServer.cs`:
  - the matter is `998.1 – Healdan Demo` in Store `Healdan`;
  - the user is `Robert Example`, and the other holder is `Jane Smith`;
  - the device is `MacBook`;
  - the server is `https://healdan.example`;
  - the documents are those in today's pictures' alt text (Lease agreement.docx #1234 v2.1, Heads of terms.docx #1231 v3.2, Board minutes.docx #1207 v1.4, Engagement letter.docx #1229 v1.0, Signed NDA.pdf #1188 v1.0).

  Then run `$R/render.sh` for all twenty.

- [ ] **Step 2: The owner's review.** Send the owner the twenty after-renders next to the baseline (`$R/baseline/` and `$R/out/`), as one message of images. Ask: "Do these match what you approved?" Fix what they ask for in the task it belongs to, re-render, and ask again. Go on only once they've said yes.

- [ ] **Step 3: Copy the seven pictures** (light) into the docs: `sign-in`, `settings`, `open` as `open-from-healdan`, `held-elsewhere` as `checked-out-on-another-computer`, `save` as `save-to-healdan`, `properties` and `crash` as `after-a-crash`. Keep each page's file name.

- [ ] **Step 4: Update the pages.**
  - **Alt text** describes the new pictures:
    - Open's details pane in Properties and Versions tabs;
    - Save's File Format pop-up;
    - Settings' three groups;
    - the alerts' mark.
  - **Installing the Helper:**
    - After "Healdan's icon appears in the menu bar…", add: "A dot on the icon means the helper needs you: you're signed out, it couldn't start, a document closed unexpectedly, a copy is Not Yet in Healdan, or LibreOffice's extension isn't installed. Point at the icon to see which."
    - In **Settings**, describe the groups: Server; Open Each Kind of File In; This Computer, with Start at Sign-In and LibreOffice Extension. Add: "While the extension isn't installed, **Install…** asks LibreOffice to add it, as the menu's **Install the LibreOffice Extension…** does."
  - **Opening Documents:**
    - Where it says how to pick an older version, add choosing **Versions** above the details first.
    - Say that the header warns **Older version: opens read-only**.
    - Say that a typed doc # jumps, as the search box's hint now says.
  - **Saving to Healdan:** "the format" becomes **File Format**, a pop-up under the properties.
  - **When Things Go Wrong:** in the crash section, add that the menu bar icon shows a dot until you choose the document.
  - **Developer Guide, Desktop Clients:**
    - In **The Helper**, add a bullet: "**The look** (polish spec): the tokens and control styles in `Windows/HelperStyles.axaml`, over Fluent; Tabler's icons in `Windows/HelperIcons.axaml`, which `python3 clients/icons/write-helper-icons.py` writes from `clients/icons/tabler/` (`IconsTests` fails until it's run); and the menu bar icon, drawn at run time from `HealdanMark`'s shapes (`Tray/TrayIcons.cs`)."
    - In **Tests**, add `IconsTests` and `AttentionTests` beside `MarkTests`.
    - In **Releases**' icon bullet, add that the menu bar icon follows the mark too.
  - **`CLAUDE.md`,** in "Healdan's clients":
    - after the macOS-icon bullet, add: "The menu bar icon is the same mark, drawn in one colour at run time from `HealdanMark`'s shapes (`Tray/TrayIcons.cs`), so it follows the favicon with About's mark."
    - add: "The helper's icons are Tabler's, in `clients/icons/tabler/`. `python3 clients/icons/write-helper-icons.py` writes `HelperIcons.axaml` from them, and `IconsTests` fails until it's run after an icon is added or changed."

- [ ] **Step 5: Check the site and the tests**

Run: `python3 -m unittest discover -s .github/scripts/tests && hugo --source site --panicOnWarning --destination "$(mktemp -d)" && python3 .github/scripts/check-site-sources.py && python3 .github/scripts/check-site-links.py && dotnet test clients/helper/Healdan.Helper.sln`
Expected: all pass. Check each script's usage line first, and follow it if it differs from this.

- [ ] **Step 6: Rebase the renderer's baseline.** Run `$R/compare.sh --rebase`. Task 10's PNGs are judged on their own.

- [ ] **Step 7: Records.**
  - In this plan, set **Status** to "Tasks 1–9 built on <date>, in PR 1" once the PR exists, and fill in the decision log.
  - Commit: "Document the helper's new look".

- [ ] **Step 8: PR 1, with the owner's go-ahead.**
  - Push the branch and open the PR to `master`. Its title is "Polish the Healdan Helper's look".
  - The body links the spec and this plan, lists Tasks 1–9, attaches before-and-after renders of Open and Sign In, and ends with the attribution line.
  - Then follow the session's PR instructions (bind it, read CI).

---

### Task 10: The extension's images

**Files:**
- Create: `clients/helper/tools/IconRender/IconRender.csproj`, `clients/helper/tools/IconRender/Program.cs`
- Create (rendered, committed): `clients/libreoffice/healdan/icons/<command>-16.png`, `-26.png`, `-16-hc.png` and `-26-hc.png` for the six commands, plus `healdan-42.png` and `healdan-42-hc.png`
- Modify: `clients/helper/Directory.Packages.props` (versions for the tool's two packages)

**Interfaces:**
- Consumes: `Icon.*` (Task 1), `HealdanMark.Silhouette()` and `Shield()` (Task 8).
- Produces: the PNG files that Task 11 wires in. The command file names are `save-to-healdan`, `open-from-healdan`, `save-as-new-version`, `save-as-new-document`, `properties` and `insert-doc-id`.

- [ ] **Step 1: The project** (`clients/helper/tools/IconRender/IconRender.csproj`). It isn't in the solution, so `dotnet test` and CI don't build it (call 3).

```xml
<Project Sdk="Microsoft.NET.Sdk">
  <!-- Renders the LibreOffice extension's images from the helper's icons and mark (polish spec §8.5). By hand, on a Mac:
       dotnet run --project clients/helper/tools/IconRender. Not in the solution, so CI doesn't build it. -->
  <PropertyGroup>
    <OutputType>Exe</OutputType>
    <TargetFramework>net10.0</TargetFramework>
    <Nullable>enable</Nullable>
    <ImplicitUsings>enable</ImplicitUsings>
  </PropertyGroup>
  <ItemGroup>
    <PackageReference Include="Avalonia.Headless" />
    <PackageReference Include="Avalonia.Skia" />
  </ItemGroup>
  <ItemGroup>
    <ProjectReference Include="../../Healdan.Helper/Healdan.Helper.csproj" />
  </ItemGroup>
</Project>
```

In `Directory.Packages.props`, add to the test-only group: `<PackageVersion Include="Avalonia.Headless" Version="12.1.4" />` and `<PackageVersion Include="Avalonia.Skia" Version="12.1.4" />`. The helper doesn't reference either, so its restore and `NoticesTests` don't change. Check this with the tests in Step 4.

- [ ] **Step 2: The renderer** (`Program.cs`)

```csharp
using Avalonia;
using Avalonia.Headless;
using Avalonia.Markup.Xaml;
using Avalonia.Media;
using Avalonia.Media.Imaging;
using Avalonia.Threading;
using Healdan.Helper.Windows;

// The LibreOffice extension's images (polish spec §8.2, §8.4), rendered from the helper's Tabler icons and Healdan's
// mark into clients/libreoffice/healdan/icons/. Run by hand when an icon changes, and commit what it writes.

AppBuilder.Configure<Application>().UseSkia().UseHeadless(new AvaloniaHeadlessPlatformOptions { UseHeadlessDrawing = false }).SetupWithoutStarting();

var root = FindRoot();
var output = Path.Combine(root, "clients", "libreoffice", "healdan", "icons");
Directory.CreateDirectory(output);
var icons = (ResourceDictionary)AvaloniaXamlLoader.Load(new Uri("avares://HealdanHelper/Windows/HelperIcons.axaml"));

(string File, string Icon, bool Shield)[] commands =
[
    ("save-to-healdan", "device-floppy", true),
    ("open-from-healdan", "folder-open", true),
    ("save-as-new-version", "versions", false),
    ("save-as-new-document", "file-plus", false),
    ("properties", "list-details", false),
    ("insert-doc-id", "hash", false)
];

var grey = Color.Parse("#7C7C80");
var shieldBlue = Color.Parse("#1A74C4");
Dispatcher.UIThread.Invoke(() =>
{
    var mark = new HealdanMark();
    foreach (var (file, icon, shield) in commands)
    {
        var drawing = (Geometry)icons["Icon." + icon]!;
        foreach (var size in new[] { 16, 26 })
        {
            Save(Icon(drawing, size, grey, shield ? (mark.Shield(), shieldBlue, Colors.White) : null), $"{file}-{size}.png");
            Save(Icon(drawing, size, Colors.White, shield ? (mark.Shield(), Colors.White, Colors.Black) : null), $"{file}-{size}-hc.png");
        }
    }
    Save(Mark(42, null), "healdan-42.png");
    Save(Mark(42, Colors.White), "healdan-42-hc.png");
});
Console.WriteLine($"Wrote the extension's images to {output}");

void Save(RenderTargetBitmap bitmap, string name)
{
    bitmap.Save(Path.Combine(output, name));
    bitmap.Dispose();
}

// One toolbar image: the 24-unit icon stroked 1.5 px at 16 and 2 px at 26, and the shield at its lower right, 7 and 11 px
// across, inside a 1 px ring that keeps it clear of the strokes.
static RenderTargetBitmap Icon(Geometry drawing, int size, Color stroke, (Geometry Shape, Color Fill, Color Ring)? shield)
{
    var bitmap = new RenderTargetBitmap(new PixelSize(size, size), new Vector(96, 96));
    using var context = bitmap.CreateDrawingContext();
    var scale = size / 24.0;
    var width = size == 16 ? 1.5 : 2;
    using (context.PushTransform(Matrix.CreateScale(scale, scale)))
        context.DrawGeometry(null, new Pen(new SolidColorBrush(stroke), width / scale, lineCap: PenLineCap.Round, lineJoin: PenLineJoin.Round), drawing);
    if (shield is { } s)
    {
        var across = size == 16 ? 7 : 11;
        var badge = Matrix.CreateScale(across / 260.0, across / 260.0) * Matrix.CreateTranslation(size - across, size - across);
        using (context.PushTransform(badge))
            context.DrawGeometry(new SolidColorBrush(s.Fill), new Pen(new SolidColorBrush(s.Ring), 260.0 / across), s.Shape);
    }
    return bitmap;
}

// The Extension Manager's icon: the mark in its colours, or in one colour for high contrast.
static RenderTargetBitmap Mark(int size, Color? one)
{
    var bitmap = new RenderTargetBitmap(new PixelSize(size, size), new Vector(96, 96));
    using var context = bitmap.CreateDrawingContext();
    var mark = new HealdanMark { Width = size, Height = size };
    if (one is { } color)
    {
        using (context.PushTransform(Matrix.CreateScale(size / 260.0, size / 260.0)))
            context.DrawGeometry(new SolidColorBrush(color), null, mark.Silhouette());
        return bitmap;
    }
    mark.Measure(new Size(size, size));
    mark.Arrange(new Rect(0, 0, size, size));
    bitmap.Render(mark);
    return bitmap;
}

static string FindRoot()
{
    var dir = new DirectoryInfo(AppContext.BaseDirectory);
    while (dir is not null && !File.Exists(Path.Combine(dir.FullName, "clients", "helper", "Healdan.Helper.sln")))
        dir = dir.Parent;
    return dir?.FullName ?? throw new InvalidOperationException("Run it from inside the repository.");
}
```

If `RenderTargetBitmap.Render` draws a control with no visual root blank, render the full-colour mark by walking `HealdanMark`'s shapes with their fills instead, mirroring `Silhouette()`. Record which approach was used in the decision log.

- [ ] **Step 3: Render, and look**

Run: `dotnet run --project clients/helper/tools/IconRender`
Expected: it prints the folder and writes 26 PNGs.
- Open the 26 px images at 800% in Preview:
  - strokes are crisp and even;
  - the shield is legible in the lower right of Save and Open;
  - the HC set is white.
- Run it twice and check that `git status` shows no change after the second run, so the output is stable.

- [ ] **Step 4: Run the helper's tests**

Run: `dotnet test clients/helper/Healdan.Helper.sln`
Expected: PASS, `NoticesTests` included.

- [ ] **Step 5: Commit** — "Render the LibreOffice extension's images from the helper's icons"

---

### Task 11: The extension's toolbar, menu and identity

**Files:**
- Create: `clients/libreoffice/healdan/THIRD-PARTY-NOTICES.txt`
- Modify: `clients/libreoffice/healdan/Addons.xcu`, `clients/libreoffice/healdan/description.xml`
- Test: `clients/libreoffice/tests/test_package.py`

**Interfaces:**
- Consumes: Task 10's PNGs.

- [ ] **Step 1: Write the failing tests.** In `test_package.py`:
  - add a PNG helper beside `prop`, and the constants below;
  - change `test_the_manifest_declares_the_files`' last assertion;
  - change `test_the_toolbar` to ignore separators;
  - add three tests.

```python
ICONS = {"SaveToHealdan": "save-to-healdan", "OpenFromHealdan": "open-from-healdan", "SaveAsNewVersion": "save-as-new-version",
         "SaveAsNewDocument": "save-as-new-document", "Properties": "properties", "InsertDocId": "insert-doc-id"}
IMAGES = {"ImageSmallURL": ("", 16), "ImageBigURL": ("", 26), "ImageSmallHCURL": ("-hc", 16), "ImageBigHCURL": ("-hc", 26)}


def png_size(data):
    """A PNG's width and height, from its header."""
    assert data[:8] == b"\x89PNG\r\n\x1a\n", "not a PNG"
    return int.from_bytes(data[16:20], "big"), int.from_bytes(data[20:24], "big")
```

In `test_the_manifest_declares_the_files`:

```python
icons = {name for name in self.files if name.startswith("icons/")}
self.assertEqual({"META-INF/manifest.xml", "description.xml", "description-en.txt", "healdan.py", "THIRD-PARTY-NOTICES.txt",
                  "pythonpath/healdan_logic.py"} | xcus | icons, self.files)
```

In `test_the_toolbar`, the `urls` line becomes:

```python
urls = [prop(item, "URL") for item in nodes(toolbars[0]) if prop(item, "URL") != "private:separator"]
```

New tests:

```python
def test_the_toolbar_has_three_groups(self):
    items = [prop(item, "URL") for item in nodes(nodes(node(self.xml("Addons.xcu"), "AddonUI", "OfficeToolBar"))[0])]
    groups = [[url[len(logic.PROTOCOL):] for url in group] for group in
              (g.split("|") for g in "|".join(items).split("|private:separator|"))]
    self.assertEqual([["SaveToHealdan", "OpenFromHealdan"], ["SaveAsNewVersion", "SaveAsNewDocument"],
                      ["Properties", "InsertDocId"]], groups)

def test_every_command_has_its_images(self):
    images = node(self.xml("Addons.xcu"), "AddonUI", "Images")
    found = {prop(entry, "URL"): node(entry, "UserDefinedImages") for entry in nodes(images)}
    self.assertEqual({logic.PROTOCOL + name for name in logic.BUTTONS}, set(found))
    for name in logic.BUTTONS:
        for key, (suffix, size) in IMAGES.items():
            url = prop(found[logic.PROTOCOL + name], key)
            self.assertEqual("%%origin%%/icons/%s-%d%s.png" % (ICONS[name], size, suffix), url, (name, key))
            self.assertEqual((size, size), png_size(self.zip.read(url[len("%origin%/"):])), url)

def test_the_extension_has_its_icon_and_notice(self):
    icon = self.xml("description.xml").find(DESCRIPTION + "icon")
    for kind in ("default", "high-contrast"):
        href = icon.find(DESCRIPTION + kind).get(XLINK + "href")
        self.assertEqual((42, 42), png_size(self.zip.read(href)), kind)
    notices = self.zip.read("THIRD-PARTY-NOTICES.txt").decode("utf-8")
    self.assertIn("Tabler Icons", notices)
    self.assertIn("Permission is hereby granted, free of charge", notices)
```

- [ ] **Step 2: Run them to see them fail**

Run: `python3 -m unittest discover -s clients/libreoffice/tests -v`
Expected: the new tests FAIL. There are no `Images`, no separators and no icon yet, and the manifest test fails on the icon files.

- [ ] **Step 3: `Addons.xcu`** (spec §8.1, §8.2).
  - Renumber the toolbar's nodes, and insert separators: `t01` Save to Healdan, `t02` Open from Healdan, `t03` separator, `t04` Save as New Version, `t05` Save as New Document, `t06` separator, `t07` Properties, `t08` Insert Doc ID. Each separator is:

```xml
<node oor:name="t03" oor:op="replace">
  <prop oor:name="URL" oor:type="xs:string">
    <value>private:separator</value>
  </prop>
  <prop oor:name="Context" oor:type="xs:string">
    <value>com.sun.star.text.TextDocument,com.sun.star.sheet.SpreadsheetDocument,com.sun.star.presentation.PresentationDocument</value>
  </prop>
</node>
```

  - After `OfficeToolBar`, inside `AddonUI`, add an `Images` node with one entry per command. Save to Healdan's is shown below; repeat it for each of `logic.BUTTONS` with its file name.
  - Update the file's top comment to mention the images.

```xml
<node oor:name="Images">
  <node oor:name="org.healdan.libreoffice.image.SaveToHealdan" oor:op="replace">
    <prop oor:name="URL" oor:type="xs:string">
      <value>org.healdan.libreoffice:SaveToHealdan</value>
    </prop>
    <node oor:name="UserDefinedImages">
      <prop oor:name="ImageSmallURL" oor:type="xs:string"><value>%origin%/icons/save-to-healdan-16.png</value></prop>
      <prop oor:name="ImageBigURL" oor:type="xs:string"><value>%origin%/icons/save-to-healdan-26.png</value></prop>
      <prop oor:name="ImageSmallHCURL" oor:type="xs:string"><value>%origin%/icons/save-to-healdan-16-hc.png</value></prop>
      <prop oor:name="ImageBigHCURL" oor:type="xs:string"><value>%origin%/icons/save-to-healdan-26-hc.png</value></prop>
    </node>
  </node>
</node>
```

- [ ] **Step 4: `description.xml`:** after `display-name`, add the icon:

```xml
<icon>
  <default xlink:href="icons/healdan-42.png"/>
  <high-contrast xlink:href="icons/healdan-42-hc.png"/>
</icon>
```

- [ ] **Step 5: The notice** (`clients/libreoffice/healdan/THIRD-PARTY-NOTICES.txt`)

```text
Third-Party Notices for Healdan's LibreOffice Extension
=======================================================

The extension's toolbar and menu images are drawn from Tabler Icons <version> (https://tabler.io/icons):

<clients/icons/LICENSE-tabler.txt, verbatim>
```

- [ ] **Step 6: Run the tests to see them pass**

Run: `python3 -m unittest discover -s clients/libreoffice/tests -v`
Expected: PASS, including `test_the_same_sources_build_the_same_bytes`.

- [ ] **Step 7: Commit** — "Give the LibreOffice toolbar and menu Healdan's icons, in three groups"

---

### Task 12: Infobar headlines

**Files:**
- Modify: `clients/libreoffice/healdan_logic.py` (`notice_parts`), `clients/libreoffice/healdan/healdan.py` (`tell`)
- Test: `clients/libreoffice/tests/test_logic.py`

**Interfaces:**
- Produces: `notice_parts(text) -> (headline, detail)`, where `detail` may be `""`.

- [ ] **Step 1: Write the failing tests** (a new class in `test_logic.py`)

```python
class NoticePartsTest(unittest.TestCase):
    """An infobar's bold headline and regular detail (polish spec 8.3, call 1): the first sentence, then the rest."""

    def test_the_first_sentence_is_the_headline(self):
        self.assertEqual(("Healdan Helper isn't running.",
                          "This save is kept on this computer, and goes to Healdan once Healdan Helper is running again."),
                         logic.notice_parts(logic.HELPER_DOWN_SAVE))
        self.assertEqual(("This is a read-only copy, so it can't be saved as a new version.",
                          "Use Save as New Document instead."), logic.notice_parts(logic.READ_ONLY_VERSION))

    def test_one_sentence_has_no_detail(self):
        self.assertEqual((logic.STORE_FAILED.format(error="disk full"), ""),
                         logic.notice_parts(logic.STORE_FAILED.format(error="disk full")))

    def test_a_full_stop_inside_brackets_doesnt_split(self):
        text = logic.FAILED_AFTER.format(error="No such file. Try again")
        self.assertEqual(("The Healdan extension hit a problem (No such file. Try again).", "Its log has the details."),
                         logic.notice_parts(text))

    def test_a_version_number_doesnt_split(self):
        text = logic.NO_COPY.format(ref="#1234 v2.1", it="#1234 v2.1")
        headline, detail = logic.notice_parts(text)
        self.assertEqual("#1234 v2.1 is filed in Healdan, but Healdan Helper gave no local copy to save this window as.", headline)
        self.assertEqual("Close this window without saving, and open #1234 v2.1 from Healdan.", detail)

    def test_every_notice_has_a_headline(self):
        for name in ("HELPER_DOWN_BOX", "HELPER_DOWN_SAVE", "HELPER_DOWN_OTHER", "READ_ONLY_VERSION", "NOT_FILED_DOC_ID",
                     "WRITE_FAILED", "STORE_FAILED", "ADOPT_FAILED", "NO_COPY", "ALREADY_FILED", "FAILED_BEFORE", "FAILED_AFTER"):
            text = getattr(logic, name).format(error="x", ref="#1", it="#1", as_ref="")
            headline, detail = logic.notice_parts(text)
            self.assertTrue(headline.endswith("."), name)
            self.assertEqual(text, (headline + " " + detail).strip(), name)  # nothing lost, nothing reworded
```

- [ ] **Step 2: Run them to see them fail**

Run: `python3 -m unittest discover -s clients/libreoffice/tests -v`
Expected: FAIL with `AttributeError: module 'healdan_logic' has no attribute 'notice_parts'`.

- [ ] **Step 3: Implement** (`healdan_logic.py`, after the notices)

```python
def notice_parts(text):
    """
    A notice as an infobar shows it (polish spec 8.3): its first sentence as the bold headline, the rest as the regular
    detail, which may be empty. A full stop inside brackets, as in an error's own words, or inside a number such as a
    version, doesn't end the sentence.
    """
    depth = 0
    for i, ch in enumerate(text):
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth = max(0, depth - 1)
        elif ch == "." and depth == 0 and text[i + 1:i + 2] == " ":
            return text[:i + 1], text[i + 2:]
    return text, ""
```

- [ ] **Step 4: Use it in `tell`** (`healdan/healdan.py`)

```python
headline, detail = logic.notice_parts(text)
...
controller.appendInfobar(INFOBAR, headline, detail, INFOBAR_WARNING if warning else INFOBAR_INFO, (), True)
...
toolkit.createMessageBox(parent, kind, 1, "Healdan", headline + ("\n\n" + detail if detail else "")).execute()
```

Compute `headline, detail` once at the top of `tell`. Update its docstring: "the first sentence bold, the rest regular".

- [ ] **Step 5: Run the tests to see them pass**

Run: `python3 -m unittest discover -s clients/libreoffice/tests -v`
Expected: PASS, including `test_the_python_compiles_and_is_ascii`.

- [ ] **Step 6: Commit** — "Show each LibreOffice notice as a short headline and its detail"

---

### Task 13: The extension's docs, records and PR 2

**Files:**
- Modify:
  - `site/content/user/office/installing-the-helper/index.md` (and new `toolbar-*.png`);
  - `site/content/developer/desktop-clients.md`, `CLAUDE.md`, `docs/ROADMAP.md`;
  - `docs/superpowers/specs/2026-10-24-m3-office-integration-design.md` (§5.8, §6.2), and the polish spec (status, and calls 1–4);
  - this plan's status and decision log.

- [ ] **Step 1: The toolbar in the User Guide.**
  - Copy the six 26 px PNGs into `installing-the-helper/` as `toolbar-<command>.png`.
  - After "The same commands are on the Healdan toolbar…", add a table. Its `Button` column shows each image with its command's name as alt text, and its `Command` column gives the name: Save to Healdan, Open from Healdan, Save as New Version, Save as New Document, Properties, Insert Doc ID (Writer only).
  - Add: "Point at a button to see its name. Save to Healdan and Open from Healdan carry Healdan's shield, so they aren't mistaken for LibreOffice's own Save and Open."

- [ ] **Step 2: The Developer Guide**, in **The LibreOffice Extension**:
  - its images are in `healdan/icons/`, rendered by `dotnet run --project clients/helper/tools/IconRender` on a Mac from the helper's icons and mark, and committed;
  - `Addons.xcu`'s `Images` node wires them in;
  - the package carries `THIRD-PARTY-NOTICES.txt`;
  - each notice shows as a bold first sentence and a regular rest (`notice_parts`).

- [ ] **Step 3: `CLAUDE.md`'s build list** gains:

```bash
dotnet run --project clients/helper/tools/IconRender      # the extension's toolbar images and icon, from the helper's icons (by hand, on a Mac)
```

- [ ] **Step 4: Records.**
  - **`docs/ROADMAP.md`,** M3's row: append "The helper's and the extension's look was polished on <date> ([spec](superpowers/specs/2026-10-27-helper-ui-polish-design.md)), in [Healdan-Project/healdan#<PR 1>](…) and [#<PR 2>](…)."
  - **The M3 spec,** §5.8 and §6.2: add a line to each: "The look changed in the polish: see [the polish spec](2026-10-27-helper-ui-polish-design.md)."
  - **The polish spec:**
    - its **Status** becomes "built on <date>, in PR 1 and PR 2" (linked);
    - fold this plan's calls 1–4 into §8.3, §6.2, §12 and §6.5, each marked "(changed in the build)";
    - fold what Tasks 1–9 changed, each marked "(changed in the build)":
      - §4.1's table gains `HdCautionTextBrush` (`#9A5B00` light, `#F5A623` dark; Caution text, with `HdCautionBrush` kept for icons), `HdOnBrush` (`#28A745` light, `#30D158` dark) and `HdOffBrush` (`#8E8E93`), which Settings' status dot uses;
      - §6.6 loses "LogicalDOC's credit included": the helper's About has no LogicalDOC credit line (the credit is the web GUI's), and keeps `About.Copyright` as it is;
      - §6.8 and §10.1 name `HoldsAndTimesTests.AfterACrash`, not `WarningsTests`, as the test that pins After a Crash's headline and body.
  - **This plan:** set **Status**, and fill in the decision log.

- [ ] **Step 5: Check**

Run: `python3 -m unittest discover -s .github/scripts/tests && hugo --source site --panicOnWarning --destination "$(mktemp -d)" && python3 .github/scripts/check-site-sources.py && python3 .github/scripts/check-site-links.py && python3 -m unittest discover -s clients/libreoffice/tests -v`
Expected: all pass.

- [ ] **Step 6: Commit** — "Document the extension's toolbar icons and notices"

- [ ] **Step 7: PR 2, with the owner's go-ahead.**
  - Push, and open the PR to `master`, stacked on PR 1. Its title is "Give the LibreOffice extension Healdan's icons".
  - The body links the spec and this plan, lists Tasks 10–13, attaches a picture of the 26 px images, and ends with the attribution line.

---

### Task 14: The owner's checks on the Mac (spec §10.3)

Run by the owner with the packaged helper (`clients/helper/packaging/mac/package.sh`), which carries the extension. The results go in the decision log.

- [ ] **Step 1: The title bar** (Review Focus 3): dragging, double-click to zoom, the system buttons, full screen, and every toolbar control clickable, in Open and Save.
- [ ] **Step 2: The menu bar icon** (Review Focus 4):
  - light and dark menu bars;
  - highlighted while the menu is open;
  - the dot while signed out, and while the extension isn't installed;
  - the tooltip's reason.
- [ ] **Step 3: Settings' Install…** with the extension removed (Task 7, Step 11).
- [ ] **Step 4: LibreOffice on Retina** (Review Focus 5): the toolbar and the Healdan menu, in light and dark, and with **Tools › Options › Accessibility** high contrast on.
  - If the 16 px images are soft, try an SVG:
    - have `IconRender` also write `<command>.svg`: the Tabler SVG's paths, stroked `#7C7C80` at 1.5, with the shield as a path, translated and scaled as in `Icon()`;
    - point one command's `ImageSmallURL` and `ImageBigURL` at it in an installed copy;
    - restart LibreOffice.
  - If LibreOffice draws the SVG sharply, ship SVGs for all six, update `test_package.py`'s `IMAGES` and `test_every_command_has_its_images` to expect `.svg`, and commit "Ship the toolbar images as SVG for Retina screens" on PR 2's branch.
  - Record the result in the decision log either way.
- [ ] **Step 5: The Extension Manager** shows the mark beside Healdan.
- [ ] **Step 6: The infobars:** quit the helper, save in Writer, and check that the infobar's first sentence is bold and the rest regular.
- [ ] **Step 7: Keyboard reach** (Review Focus 1; Task 5, Step 4): in the packaged helper:
  - Tab through Open, Save, Properties, Settings and each alert, and check every control takes focus in turn;
  - in Open's details, the Left and Right arrow keys switch Properties and Versions;
  - read-only values skip Tab, and can still be selected with the mouse and copied with Cmd+C: Save's and Properties' read-only values and their Client / Matter, Settings' Address, and About's version and server lines. (Open's detail values are plain text, not selectable, so they don't take part.)
  - with VoiceOver on (Cmd+F5), each of these is read by its name: the places in Open and Save (such as "Recent Documents", not "StackPanel"), Save's **New Folder**, the **File Format** pop-up and its choices (such as "OpenDocument Text (.odt)", not "odt"), the favourite stars ("Add to Favorites" or "Remove from Favorites", as their tooltips say), and Open's **Properties** and **Versions** tabs.
- [ ] **Step 8: Banding while a list scrolls** (Task 2): in Open and Save, with a list long enough to scroll, check each alternating band stays with its row as the rows move.
- [ ] **Step 9: Sign In's keychain line:** check it breaks as "…in the keychain," and "never your password.", with no word alone on a line, in light and dark.
- [ ] **Step 10: Every trailing "…"** (the ellipsis fix): Settings' **Install…**, **Open from Local Computer…** and **Save to Local Computer…** each show three whole dots.
- [ ] **Step 11: The File Format drop-down** in Save: open its list in light and dark, and check the choices are legible and the chosen one is marked.
- [ ] **Step 12: The menu bar dot's size** (Task 8): the icon is drawn for 22 pt, and macOS may show it smaller (a figure to check on the Mac is about 17 pt), so check the dot is still plain on light and dark menu bars.
  - If it's too small, raise `DotRadius` and `RingRadius` together in `Tray/TrayIcons.cs`, keeping the ring clear of the back page.
  - Record the result in the decision log either way.
- [ ] **Step 13: Letter spacing** (note only): look at San Francisco's spacing in the windows. The owner has made it a separate follow-up (see Follow-ups), so record what you see here and fix nothing.

---

## Decision log (filled in while executing)

Every call made while building, in order. "Where" is the task or review that raised it; "Cost if wrong" is what undoing it would take.

| Date | Where | Decision | Why | Cost if wrong |
|---|---|---|---|---|
| 2026-10-27 | Task 1 | Tabler 3.50.0's 30 SVGs and its licence came from `cdn.jsdelivr.net/npm/@tabler/icons@3.50.0/`, with the owner's approval, not from `npm pack` | npm isn't installed here, and jsDelivr serves the npm package's own files | nothing: the files are byte-identical to the tarball's |
| 2026-10-27 | Task 1 | Each commit's trailer names the model that did the work (subagents on Sonnet sign "Claude Sonnet 5.5"), not the plan's single trailer | accurate attribution matters more than one name on every commit | a reword if the owner wants one name |
| 2026-10-27 | Task 1 | Every implementer runs `dotnet build clients/helper/Healdan.Helper.sln` as well as `dotnet test` | `dotnet test` doesn't compile the app project, which Task 1 found | nothing |
| 2026-10-27 | Task 2 | Text inside a grey selection (the places list, the Versions pill, a rows list without focus) takes `HdTextBrush`, while icons keep their colours | keeps spec §4.1's AA promise without changing the approved tokens, and icons need only 3:1 | a style tweak if the owner prefers darker tokens instead |
| 2026-10-27 | Task 2 | `App.axaml`'s two FluentTheme palette accents are the one place a hex colour may sit outside `HelperStyles.axaml` | Fluent's palette can't take a resource | nothing |
| 2026-10-27 | Task 4 | The by-hand title bar check (Task 4, Step 11), and Task 2's check that the list's banding holds while it scrolls, move to Task 14 | subagents can't see the screen | a later fix if the unified title bar misbehaves; spec §13's fallback stands |
| 2026-10-27 | Task 4 | The search box takes the toolbar's spare width (a star column, `MinWidth` 200) instead of a fixed 320 pt | spec §6.2 says "at least 200 pt", and the hint must be visible within a matter | a width tweak if the owner prefers Finder's fixed field |
| 2026-10-27 | Task 4 | The scope pop-up sits next to the chevrons, with the search box filling the rest, instead of far right as in the mockup | the hint's visibility outweighs the mockup's spacing; raised for the owner's review in Task 9, which kept it (below) | a spacer and a `MaxWidth` if the owner prefers the mockup |
| 2026-10-27 | Task 5 | Step 4's keyboard check moves to Task 14 with the other by-hand checks | subagents can't see the screen | a later fix if keyboard reach is broken |
| 2026-10-27 | Task 5 | The details tabs handle Left and Right in a `KeyDown` handler that moves `SelectedIndex` | `UniformGrid` isn't a navigable container, so the arrow keys never reached Versions; the grid keeps the segments equal | a `NavigableUniformGrid` if a reviewer prefers it |
| 2026-10-27 | Task 5 | The favourite star in the details header stays hidden until the document's details have loaded | restores how the window behaved before the task | nothing |
| 2026-10-27 | Task 5 | New token `HdCautionTextBrush` (`#9A5B00` light, `#F5A623` dark) for Caution text, with `HdCautionBrush` kept for icons | amber text on the window needs a darker shade to meet AA, and icons need only 3:1; Task 13 adds it to spec §4.1's table | a colour tweak if the owner dislikes the darker amber |
| 2026-10-27 | Task 6 | The calendar date picker is brought to the 22 pt field height | Task 6 owns Save's and Properties' forms, spec §4.3 sets 22 pt fields, and Task 2 had deferred it | a revert if the template resists |
| 2026-10-27 | Task 6 | Read-only values (`SelectableTextBlock.value`) take `IsTabStop=false` and stay focusable, not `Focusable=false` | macOS's selectable labels aren't in the key-view loop. `Focusable=false` was the first ruling, but the implementer's test showed it loses keyboard copy, where `IsTabStop=false` keeps mouse selection, the context menu's Copy and Ctrl or Cmd+C | nothing |
| 2026-10-27 | Task 7 | About's `VersionText` and `ServerText` also take `IsTabStop=false` | the same Tab-stop issue Task 6 fixed, and Task 7 owns About | nothing |
| 2026-10-27 | Task 7 | Step 11 (Install… by hand) moves to Task 14 | subagents can't see the screen | a later fix if the wiring fails in the packaged app |
| 2026-10-27 | Task 7 | About widens from 400 to 440 pt | its three links (Healdan Docs, the licence and Open-Source Notices) don't fit in a row at 400, and the spec gives no width | nothing |
| 2026-10-27 | Task 7 | Settings' status dot gets two tokens, `HdOnBrush` (`#28A745` light, `#30D158` dark) and `HdOffBrush` (`#8E8E93` in both) | spec §4.1's table leaves the dot's colours to the builder; Task 13 adds them to the table | a colour tweak |
| 2026-10-27 | Task 7 | Spec §6.6's "LogicalDOC's credit included" is an error: About keeps its copyright line unchanged | the helper's `About.Copyright` never had a LogicalDOC credit. It's the web GUI's (`HealdanCredits.java`), and the helper is Healdan's own .NET code. Task 13 corrects §6.6 | a credit line if the owner wants one |
| 2026-10-27 | Task 8 | The by-hand menu bar check (Step 9) moves to Task 14, and the implementer renders the four icons headless instead | subagents can't see the screen | a later tweak of the dot's geometry |
| 2026-10-27 | Task 8 | The attention dot sits at (226, 34) in the mark's 260-unit square, with a radius of 30 and a ring of 44, where the plan had (214, 46), 38 and 56 | the plan's ring cut the shield's shoulder and opened the back page in the renders; this one cuts the shoulder only. The plan allowed tuning | a retune after the owner's menu bar check in Task 14 |
| 2026-10-27 | Task 9 | Task 9 splits at its owner gate: a subagent does Step 1 (the renderer's data and the renders), the controller runs Step 2 (the owner's review), and a second subagent does Steps 3–7 after approval. Step 8, the PR, waits for the owner's go-ahead | the owner's review can't be delegated | nothing |
| 2026-10-27 | Task 9 | The clipped trailing "…" (Install…, Open from Local Computer…, Save to Local Computer…) is fixed before the owner's review, as its own fix with its own review | it was likely real on the Mac too (same Skia and font), and visible on three controls | one small commit |
| 2026-10-27 | Task 9, ellipsis fix | One style turns `ClipToBounds` off for text blocks (`AccessText` and `SelectableTextBlock` included) and buttons (26e7a7232) | HarfBuzz lays San Francisco out at the font's default Display optical size (28), but Skia draws it through CoreText at Text size, so the glyph's ink runs about 1.7 pt past the box it was measured into. Avalonia 12 clips every `TextBlock` and templated control to its bounds, so the last dot of "…" was cut off. Nothing is laid out differently: across the 22 renders only the restored dots, and the odd last pixel column of a word, changed | the dots clip again if the style goes; the real Mac is only checked in Task 14 |
| 2026-10-27 | Task 9, ellipsis fix | Text that used to be cut off at a fixed cell's edge now trims with "…" and keeps 4 pt clear of its neighbour (9e2bed4b9): Open's detail labels, its Doc #, Version and Matter cells, Save's Version and Doc # cells, and Settings' Address, whose value is now right-aligned in the star column and trimmed | with no clipping, text runs into the next cell. "Client Reference Number" (an administrator names a property) printed over its value, an 8-digit doc # over the Version, and a long host took all of the Address row. A 7-digit doc # still fits (51.4 pt in 52) | a width tweak if the 4 pt is wrong |
| 2026-10-27 | Owner's review | The owner approved the after-renders of all ten windows, light and dark, with one change: Sign In's keychain line wrapped and left "password." alone, so it's capped at 300 pt and centred, and breaks as "…in the keychain," and "never your password." (8be87be0e) | the owner's call; the words and the size are unchanged | nothing |
| 2026-10-27 | Owner's review | The scope pop-up stays beside the chevrons, as built | the owner accepted the hint's visibility over the mockup's spacing | a spacer and a `MaxWidth` |
| 2026-10-27 | Owner's review | The letter spacing left by the optical-size mismatch above is a follow-up, not part of PR 1 | the owner's call at the review. It's in Follow-ups | nothing now |
| 2026-10-27 | Task 9 | The docs' seven pictures are the light renders halved to the site's 1× size (420 to 1000 px wide), not the renderer's 2× files | the site's pictures were halved in M3 (its log, 2026-10-25), so each window shows at its real size in the page | 2× copies if the owner wants sharper pictures; no file name changes |
| 2026-10-27 | Task 10 | `IconRender` draws the 42 px full-colour mark by walking `HealdanMark`'s shapes with their fills (`DrawShapes`), not by rendering the control | `RenderTargetBitmap.Render` draws a control with no visual root blank, and a `Window`'s captured frame has no alpha, so it can't make a transparent PNG. The walk is pixel-identical to the window capture flattened on white. The white high-contrast mark uses `HealdanMark.Silhouette()` | a new kind of shape in `HealdanMark` needs a case in `DrawShapes`, which would otherwise leave it out of the 42 px mark |
| 2026-10-27 | Task 12 | The extension's `description.xml` version goes from 0.1.0 to 0.2.0, in its own commit (96b20094a) | its images, toolbar and notices change in PR 2, and the helper installs a bundled extension only when it's newer than the one installed, so without a new version existing installs, and the owner's Task 14 checks, would keep the old toolbar | a renumber if the owner prefers another version |
| 2026-10-27 | Task 13 | The spec's fold records what the build changed beyond calls 1–4: `HdCautionTextBrush`, `HdOnBrush` and `HdOffBrush` in §4.1's table; text in a grey selection taking the Text colour (§4.3); read-only values skipping Tab (§9); Open's arrow keys switching tabs, and the toolbar's layout (§5.3, §6.2); San Francisco's optical size and what it led to (§4.2); and the corrections to §6.6 (the credit line, and "and credit lines") and to §6.8 and §10.1 (the test's name) | they're builder detail the spec should record, each marked "(changed in the build)" | nothing |
| 2026-10-27 | Task 13 | The records link PR 1 ([Healdan-Project/healdan#93](https://github.com/Healdan-Project/healdan/pull/93)) and write PR 2 as "PR 2", with no number. A one-line commit adds its link once it opens | PR 2's number doesn't exist until it opens | one tiny commit |
| 2026-10-27 | Final review | An installed extension older than the helper's reads as that version in Settings, with **Update…**, which runs the same install as **Install…**, and the line "Version 0.2.0 is waiting for you to accept it in LibreOffice." once the helper has opened it there, or "Version 0.2.0 is ready to add to LibreOffice." before. The menu bar's reason is "LibreOffice Extension Needs Updating". Not Installed and Install… stay for no extension, or a turned-off one. The row's tooltip says "Version 0.1.0 is installed. To update it to 0.2.0, choose Install the LibreOffice Extension… in the Healdan Helper menu." | the ruling on the final review's Important 2: the helper counts an older extension as missing, so Settings said Not Installed while the older toolbar was in LibreOffice, and 0.2.0 makes that every existing install's path. The tooltip's wording is the fix wave's own, so it doesn't contradict the row | a wording change |
| 2026-10-27 | Final review | Placeholders draw at Opacity 1, not Fluent's 0.5, and Open's and Save's User Guide pictures are re-rendered in the same commit. Sign In's re-render was pixel-identical, so its picture is unchanged | the ruling on the final review's Minor 7: at 0.5, Muted came out at about 1.7:1 on white, half what §4.1 meant | a revert if the owner finds the placeholders too dark |
| 2026-10-27 | Final review | The review's fixes go where what they fix lives: the helper's code, tests, User Guide text and pictures, and PR 1's plan text in PR 1; the extension's logger typo, the spec's fold and these rows in PR 2, which merges PR 1's fixes first | each PR stays whole for its reviewer, and the spec's fold of the build's changes is in PR 2 already | nothing |

## Follow-ups

- **Letter spacing in the helper's San Francisco type** (owner, at Task 9's review). HarfBuzz lays the font out at its default Display optical size (28) while Skia draws it at Text size, so the spacing doesn't match the glyphs drawn. The ellipsis fix (26e7a7232) stops the overhang clipping but leaves the spacing as it is. Look for a way to make layout and drawing agree (the font's optical-size axis, or another text-measuring path), and check the packaged app on the Mac in Task 14, since the renders and the app share Skia and the font.
- **Fluent's part names fail silently on an Avalonia upgrade.** Styles in `HelperStyles.axaml` reach into Fluent's templates by part name: the pop-up button's `DropDownGlyph`, the checkbox's `NormalRectangle`, the date picker's `PART_TextBox`, `PART_Button` and `LayoutRoot`, and the text box's `PART_BorderElement` and `PART_Placeholder`. A renamed part drops its style without an error, so check them in the renders whenever the Avalonia pins in `Directory.Packages.props` move, or add a comment beside the pins saying so.
- **`default` cases for the mark's shapes.** `HealdanMark.Silhouette()` and `IconRender`'s `DrawShapes` walk the mark's shapes by kind. A new kind of shape would be left out of the menu bar icon and the extension's 42 px mark without a word, so each could throw on a kind it doesn't know.
- **Settings reads LibreOffice's registry twice** as it opens: `ExtensionCheck.Summary()` for the row and `ExtensionCheck.Describe()` for its tooltip each call `Refresh()`. One read, shared by both, would do.
- **Tabler's version in three places.** `clients/icons/README.md` and both `THIRD-PARTY-NOTICES.txt` files (the helper's and the extension's) name the Tabler release. Moving to a new release changes all three, and nothing checks that they agree.
