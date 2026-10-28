# Healdan Helper and LibreOffice extension UI polish: design

- **Follows:** M3 in [`docs/ROADMAP.md`](../../ROADMAP.md), whose [spec](2026-10-24-m3-office-integration-design.md) §5.8 set the windows' layout and §6.2 the extension's menu and toolbar. This spec changes how they look, not what they do.
- **Date:** 2026-10-27
- **Status:** design approved by the owner on 2026-10-27, section by section, against the mockups in [`2026-10-27-helper-polish-mockups/`](2026-10-27-helper-polish-mockups/). Built on 2026-10-27, the helper in [Healdan-Project/healdan#93](https://github.com/Healdan-Project/healdan/pull/93) (PR 1) and the extension in PR 2. Where the build changed the design, the section says so, marked "(changed in the build)", and the [plan](../plans/2026-10-27-helper-ui-polish.md)'s decision log has the reasons.
- **Inputs:**
  - a design conversation with the owner on 2026-10-27, in which Claude worked as the designer;
  - offscreen renders of all ten helper windows in light and dark, from a scratch renderer like M3's (its plan's decision R23), and the docs site's pictures of them;
  - a survey of `clients/` at `7510601c0`. Line numbers drift.

As in the roadmap, **(owner)** marks what the owner decided, and **(call)** marks Claude's proposal. Anything unmarked follows from those, or is detail the builder can change without changing what gets built.

## 1. Intent

The helper's windows and the extension work, but they look plain and a little rough (owner). They don't look like Healdan, and they don't look like Mac apps:

- **Not Healdan's.** The accent is indigo `#3B5BDB`, but Healdan's mark is `#0b5dab` and `#22a2e0`, and the docs site's primary is hue 209°. The mark appears only in About. The menu bar icon is a generic document outline. The LibreOffice toolbar is six text labels with no icons, and the Extension Manager shows its generic puzzle piece.
- **Not a Mac app.** The text is Helvetica, which Skia falls back to on macOS, not San Francisco. Fluent's grey square buttons and its combo boxes read as Windows controls. In dark mode the input fields are near-black, darker than the window.
- **No common anatomy.** Sign In and Properties have header bands; Settings and About don't. The three alerts have no icon or header, and After a Crash has no headline at all.
- **Text doing an icon's job.** Back, forward and up are the font's `← → ↑`. Helvetica lacks them, so they come from whatever fallback font is loaded, and they change size once Open-Source Notices has loaded Menlo. The places list has no icons, the search field has no magnifier, and BROWSE is a grey band.
- **Weak hierarchy.** Open's details pane leads with a large "No preview yet" box, and its versions run past the bottom. Save's properties heading is centred over left-aligned fields. Settings' fixed choices are disabled combo boxes, which look broken. Every LibreOffice infobar is one long bold sentence.

The goal: the helper looks at home on a Mac, in Healdan's colours, and the extension's commands are recognisable at a glance.

## 2. Decisions

1. **A native Mac look in Healdan's colours (owner).** San Francisco type, Mac-sized controls, a Finder-like sidebar and grouped settings, with Healdan's blue as the accent and the mark on the menu bar, Sign In, About and the alerts. GNOME gets the same look, which reads cleanly there.
2. **The look, plus small UX fixes (owner).** Fixes where the layout is the problem: the details pane's tabs, read-only settings as rows, one alert layout, short infobar headlines. Nothing in the copy cycle, the channel's routes, the API or the server changes.
3. **Restyle Fluent in place (owner).** FluentTheme stays the base. Its resources are overridden, and a handful of styles make the controls that read as Windows look like the Mac's. The alternatives were owning every control template under Avalonia's Simple theme, or a third-party theme; none known targets the Mac for Avalonia 12.
4. **Tabler Icons (owner).** One line-style set, MIT-licensed, for the helper and the extension alike (§4.4).
5. **Unified title bar on macOS (owner).** Open and Save draw their toolbar in the title bar, as Finder does. Linux keeps its own title bar.
6. **Open's details pane in two tabs (owner).** A fixed header, then Properties and Versions (§6.2). The owner asked for this in review.
7. **Save's format as a File Format pop-up (owner),** as in the Mac's own Save panel, instead of radio buttons.
8. **Install… in Settings (owner)** while the extension isn't installed.
9. **An attention dot on the menu bar icon (owner)** while the helper needs the user (§7.2).
10. **An icon-only LibreOffice toolbar, with tooltips (owner),** in three groups.
11. **A floppy disk and a folder for Save to Healdan and Open from Healdan (owner),** in LibreOffice's grey with a small blue Healdan shield, so they don't pass for LibreOffice's own Save and Open. The owner chose the shield over colouring them blue: it differs by shape, so it works in high contrast and for colour-blind users.

## 3. Out of scope

- The copy cycle, the channel, its routes, the API, the server and upstream files. **No upstream file changes.**
- New windows, new commands, keyboard shortcuts, onboarding.
- Icons on the helper menu's items, and the menu's order and wording.
- Windows. GNOME isn't styled separately: phase 2 of M3 checks it (§10.3).
- Wording, except where §6, §7 and §8 say so. The Checked Out on Another Computer wording the owner approved in M3 stays as it is.

## 4. Foundations

All colours are tokens in `clients/helper/Healdan.Helper/Windows/HelperStyles.axaml`, with a light and a dark set, as today. The section's mockup is `foundations.html`.

### 4.1 Colour

| Token | Light | Dark | Use |
|---|---|---|---|
| Window | `#FFFFFF` | `#1E1E1E` | window background |
| Bar | `#F6F6F6` | `#2A2A2C` | the unified title bar and toolbar |
| Sidebar | `#F5F5F7` | `#262628` | the places pane |
| Grouped | `#F2F2F4` | `#1E1E1E` | behind Settings' groups |
| Group | `#FFFFFF` | `#2A2A2C` | a Settings group |
| Line | `#E5E5EA` | `#38383A` | hairlines |
| Alternate row | `#F4F5F5` | `#232325` | every other list row |
| Pill | `#E1E1E6` | `#3A3A3C` | the sidebar's and the versions' selection |
| Inactive selection | `#DCDCDC` | `#464646` | a list's selected row when the list isn't focused |
| Text | `#1D1D1F` | `#F2F2F7` | body |
| Secondary | `#6E6E73` | `#AEAEB2` | labels, columns, group headers, details' labels |
| Muted | `#8E8E93` | `#8E8E93` | placeholders and disabled only |
| Accent fill | `#0C69C0` | `#1A74C4` | default buttons, selected rows, checkboxes |
| Accent text | `#0C69C0` | `#5CB3F0` | links, folder and sidebar icons, crumbs |
| Focus | `#22A2E0` | `#22A2E0` | the focus ring, at 40% |
| Field | `#FFFFFF` | `#2C2C2E` | text fields; lighter than the window in dark |
| Field line | `#C7C7CC` | `#48484A` | |
| Read-only field | `#F5F5F7` | `#2A2A2C` | |
| Secondary button | `#FFFFFF` with Field line | `#4A4A4D` | |
| Yours | `#1E7B34` | `#69DB7C` | "checked out to you" |
| Someone else's | `#B3261E` | `#FF8A80` | "checked out to …" |
| Caution | `#C77700` | `#F5A623` | alert badges and other icons (`HdCautionBrush`). (changed in the build) Not text: see Caution text |
| Caution text | `#9A5B00` | `#F5A623` | caution text, such as "Older version: opens read-only" (`HdCautionTextBrush`). (changed in the build) Caution's light value is 3.5:1 on the window, enough for an icon but not for text; this one is 5.4:1 |
| On | `#28A745` | `#30D158` | Settings' status dot when it's on (`HdOnBrush`). (changed in the build) |
| Off | `#8E8E93` | `#8E8E93` | Settings' status dot when it's off (`HdOffBrush`). (changed in the build) |
| Warning box | `#FFF6E5`, line `#F2C26B`, text `#6B3D00` | `#3B2A14`, line `#A0611B`, text `#FFD8A8` | Checked Out on Another Computer, Sign Out Here |

Required (`#D70015` / `#FF6961`), the star (`#F59F00` / `#FCC419`) and the chips keep today's values.

- **`#0C69C0` is the docs site's primary** (`hsl(209 88% 40%)`), so the helper and the site match. White on it is 5.5:1. The dark fill, `#1A74C4`, keeps white text at 4.8:1. (call)
- **The mark's `#22A2E0` is the focus ring:** a pale blue reads well there, and nowhere else is it legible as text. (call)
- **Every text colour passes WCAG AA** on its background, except Muted, which is only for placeholders and disabled text. The sidebar's group headers use Secondary, not Muted. (call)

### 4.2 Type

- **macOS: San Francisco,** asked for as `.AppleSystemUIFont`. A scratch render confirmed Skia finds it. It also fixes most of the text sitting 2–3 pt high in fields. (call)
- **Linux: the desktop's default font,** as today.
- **Sizes:** body 13; secondary and labels 11; group headers 11 semibold; the details pane's and alerts' headlines 13 semibold; window headings (Sign In, About) 15 semibold.
- **(changed in the build) San Francisco's optical size.** Avalonia lays the font out with HarfBuzz at its default Display optical size (28), but Skia draws it through CoreText at Text size. The glyphs are drawn wider than the room measured for them, so the ink of a text block's last character ran about 1.7 pt past its box, and the letter spacing doesn't match the glyphs.
  - **`TextBlock` and `Button` don't clip.** Avalonia 12 clips each to its bounds, which cut the last dot of "…" in Install… and in Open from Local Computer…. One style in `HelperStyles.axaml` turns `ClipToBounds` off for text blocks (`AccessText` and `SelectableTextBlock` included) and buttons.
  - **Long labels and cells trim.** With no clipping, text that overflowed a fixed cell ran into its neighbour, so those cells trim with "…" and keep 4 pt clear of the next: Open's detail labels and its Doc #, Version and Matter cells, Save's Version and Doc # cells, and Settings' Address value.
  - **The spacing fix is a follow-up** (the plan's Follow-ups), not part of this design.

### 4.3 Controls

Set through Fluent's resources where it has them, and through styles in `HelperStyles.axaml` where it doesn't. (call)

- **Push buttons** are 22 pt tall with 6 pt corners. A plain `Button` is the secondary style, and `Button.accent` is the accent fill. Each window has one accent button, its default.
- **Pop-up buttons** (`ComboBox`) look like a secondary button with Tabler's `selector` chevrons in place of Fluent's arrow.
- **Text fields** are 22 pt tall with 5 pt corners. Focus is a 1 pt accent border and a 3 pt Focus ring.
- **Checkboxes and radio buttons** are 14 pt, with an accent fill when on.
- **A segmented control** (§6.2) is a styled `TabControl` strip: a Pill-coloured track and a raised white segment. Tab and the arrow keys reach it.
- **List rows** are 24 pt, or 34 pt with a badge line, and alternate rows take the Alternate row colour. The selected row is the accent fill with white text, or Inactive selection when the list isn't focused, as on the Mac.
- **The sidebar's selection** is a Pill-coloured rounded row with the accent icon, inset 6 pt from each side.
- **(changed in the build) Text inside a grey selection takes the Text colour:** the sidebar's and the versions' Pill, and a list's Inactive selection. Icons there keep their colours, since 3:1 suffices for an icon. This keeps §4.1's promise that every text colour passes AA without changing its tokens.
- **Icon-only buttons** have a tooltip and an accessible name: back, forward, up, the stars, New Folder's icon and the toolbar's.

### 4.4 Icons

**Tabler Icons (owner),** the outline set, at the latest release when built. Its version goes in the notices.

- **Sources.** The SVG of each icon used is committed in `clients/icons/tabler/`, with a README naming the release and the licence.
- **The helper** gets each icon as a `StreamGeometry` in a new `Windows/HelperIcons.axaml`, drawn as a stroke with round caps and joins, 1.5 pt at 16 pt. `IconsTests` checks that each geometry matches its SVG's path data, as `MarkTests` does for the mark. Tabler's invisible bounding-box path is left out.
- **The extension** gets PNGs rendered from the same geometry (§8.5).
- **The notice.** A Tabler section goes in `THIRD-PARTY-NOTICES.txt`. It has no `Packages:` line, since Tabler isn't a NuGet package, so `NoticesTests` is unaffected. The extension carries the same notice (§8.4).

| Where | Icons |
|---|---|
| Toolbar | `chevron-left`, `chevron-right`, `chevron-up`, `search`, `selector` |
| Open's places | `clock` Recent Documents, `lock` Checked Out to Me, `star` Favorites, `history` Recent Matters, `briefcase` My Matters, `folder` Recent Folders |
| Save's places | `folder` Recent Folders, `history` Recent Matters, `briefcase` My Matters, `building` Clients, `star` Favorites |
| Rows and the details pane | `folder` (accent), `file-text` (documents), `file-spreadsheet`, `presentation`, `file-type-pdf`, `photo` (images), `file` (anything else), `lock` (the holder badge), `star` |
| Save | `folder-plus` (New Folder) |
| Open's details | `eye` (older version) |
| Alerts | `alert-triangle` (the badge) |
| Settings | `server`, `file-text`, `file-spreadsheet`, `presentation`, `power`, `puzzle` |
| LibreOffice | `device-floppy`, `folder-open`, `versions`, `file-plus`, `list-details`, `hash` |

## 5. Window shells

Three shapes, for every window (owner). The section's mockup is `window-shells.html`.

### 5.1 Forms: Sign In, Properties, Settings, About

- **No header or footer bands.** The title bar names the window, and the content sits on one surface with 20 pt margins.
- **The buttons sit at the bottom right,** with the default rightmost and Escape and Return as today.
- **Sign In leads with the mark,** at 48 pt and centred above "Sign In to Healdan" and today's line about the keychain. It's the first window a new user sees.

### 5.2 Alerts: Checked Out on Another Computer, Sign Out Here, After a Crash

The Mac alert layout:

- the mark at the left, 48 pt, with a Caution `alert-triangle` badge on a window-coloured disc at its lower right;
- the headline in 13 pt semibold, then the body, then the warning box where there is one;
- the buttons at the bottom right, with today's default (Cancel, Stay Signed In, Reopen) rightmost.

The window titles stay, for the window list and screen readers.

### 5.3 Browsers: Open and Save

- **macOS (owner):** the window draws its toolbar in the title bar (`ExtendClientAreaToDecorationsHint`, with a title bar height of about 44 pt). It holds:
  - the system's buttons;
  - the window's name in 13 pt semibold;
  - back, forward and up as chevron buttons;
  - the scope pop-up;
  - the search field, at least 200 pt wide, with a magnifier.

  (changed in the build, at the owner's review) There's no flexible space: the scope pop-up sits beside the chevrons, and the search field takes the rest of the bar's width, so that the placeholder's doc # hint (§6.2) shows whole beside its field. The owner kept this over the mockup's pop-up at the far right.

  The bar's empty space drags the window, and double-clicking it zooms, as a title bar does.
- **Linux:** the system's title bar, with the same toolbar row under it, without the name.
- **The footer** has a hairline above it, not a band: the local-computer link on the left and the controls on the right.

## 6. The windows

### 6.1 Sign In

The form shell with the mark (§5.1). The fields and the busy state ("Signing In…") are unchanged.

### 6.2 Open from Healdan

The mockups are `open-window.html` and `open-details-tabs.html`.

- **Places:** each has its icon (§4.4), and "Browse" is a group header (11 pt semibold, Secondary, Title Case), not a band.
- **Crumb row:** the place or crumbs in 13 pt semibold, and the favourite star at the right.
- **List:** the columns as today (Title, Doc #, Version, Matter, Edited), in 11 pt Secondary over a hairline. Alternating rows, the Mac's selection (§4.3), and the file-kind icon by extension.
- **The holder line** becomes a badge: a `lock` icon and today's text from `Holds.Text`, in Yours or Someone else's colour. On a selected row it turns white.
- **The details pane** is 260 pt wide, with a fixed header and two tabs (owner):
  - **Header:** the file-kind icon at 32 pt, the name in 13 pt semibold, `#1234 · v2.1` in Secondary, and the favourite star.
  - **When an older version is chosen,** the header shows its number, and a Caution line with `eye` reads **Older version: opens read-only**. It replaces today's "An older version opens read-only." under the versions. Choosing another document goes back to its current version.
  - **A segmented control:** **Properties** and **Versions (n)**. Each new Open window starts on Properties. The tab then stays as the user left it while they choose other documents. (changed in the build) The Left and Right arrow keys switch between them, through a `KeyDown` handler on the control: its equal-width `UniformGrid` strip isn't a navigable container, so the arrow keys never reached Versions without one.
  - **Properties:** the thumbnail, when the server has one, at its own aspect ratio up to 140 pt tall, with 6 pt corners and a hairline. Then today's label and value rows, with labels in Secondary 11 pt and an 84 pt label column. With no thumbnail there's nothing in its place: the header's icon stands for the file, and the "No preview yet" box goes.
  - **Versions:** today's list, with the Pill selection, filling the pane's height.
  - **Before a document is chosen,** the pane shows only today's "Choose a document to see its properties and versions.", centred and muted, with no header or tabs.
- **Search's placeholder** adds **", or type #1234"** in the scopes where a typed doc # jumps to the document (`Browser.SearchAsync`). It comes from `Browser.SearchPlaceholder`, so a test pins it. (changed in the build) Only the Documents scope's placeholder says it. A typed doc # jumps from any scope, but Folders, Matters and Clients keep their placeholders, so those stay short. The search field takes the toolbar's spare width, with the scope pop-up beside the chevrons (§5.3), so the hint shows whole.
- **Footer:** **Open from Local Computer…**, now with an ellipsis because it opens another window, then Open Read-Only, Cancel and Open.

### 6.3 Save to Healdan

As Open (§6.2) for the toolbar, places, crumbs and list, plus:

- **New Folder** gets the `folder-plus` icon beside its label. The new-folder bar is restyled, and works as today.
- **The right pane** is headed **New Document Properties**, left-aligned, as a 13 pt semibold section heading.
- **Client / Matter** shows as read-only text (a label, the value and its "from the matter" chip), not as a greyed-out field.
- **Format** becomes a **File Format** pop-up (owner) with the same choices, in the same order and with the same default as today's radio buttons.
- **Footer:** **Save to Local Computer…**, Cancel, and Save.

### 6.4 Properties

The form shell, with the same header as Open's details pane (the file-kind icon, the name, `#1234` and the version when known), then the same form as Save's right pane. Any problem shows above Cancel and Save, as today.

### 6.5 Settings

The mockup is `settings-and-about.html`. Grouped rows, as in the Mac's System Settings, on the Grouped colour:

- **Server:** Address, with the host on the right. Today's note goes under the group.
- **Open Each Kind of File In:** a row for each kind, with its icon, its name, and its extensions under the name. The app's name is plain text on the right: no disabled pop-up. When a later milestone makes the choice real, the value becomes a pop-up in the same row. Today's note about other files goes under the group.
- **This Computer:**
  - **Start at Sign-In,** with an On or Off status dot (changed in the build: `HdOnBrush` and `HdOffBrush`, §4.1).
  - **LibreOffice Extension,** with the version on the right and "Installed on …" under the name.
  - **Where the rows' data comes from.** They take the login item's state and the extension's record as data, not by parsing today's sentences. `LoginItem.Describe()` and `ExtensionCheck.Describe()` stay, as the rows' tooltips.
  - **While the extension isn't installed (owner),** the row reads **Not Installed** with an **Install…** button. The button runs what the menu's Install item runs (`ExtensionCheck.InstallFromMenu`), and the row then shows that outcome's text. (changed in the build) The row shows `ExtensionCheck.Summary()` again after the install, not a text mapped from the install's outcome: the summary already says "Waiting for you to accept version … in LibreOffice".
- **Done** stays at the bottom right.

### 6.6 About

- **The mark** at 64 pt, then "Healdan Helper", the version, and the copyright line exactly as `About` has it today (`About.Copyright`). Then the server line. (changed in the build) The helper's About has no LogicalDOC credit line: that credit is the web GUI's (`HealdanCredits.java`), and the helper is Healdan's own .NET code. About keeps `About.Copyright` as it is.
- **The links** sit in one row: Healdan Docs, the licence, and **Open-Source Notices** (a button today).
- **Copy for Support** sits at the bottom left, and Done at the bottom right.

### 6.7 Open-Source Notices

The text sits on the window's colours in both themes (no near-black panel) and doesn't soft-wrap. The file is wrapped at 80 columns, so soft wrapping only strands single words; a wider line scrolls sideways.

### 6.8 The alerts

- **Checked Out on Another Computer:** the alert shell. The headline, line and warning box keep their M3 wording.
- **Sign Out on This Computer Only?:** the alert shell; the wording is unchanged.
- **After a Crash:** the alert shell. `Warnings.Crashed` returns a headline and a body: the headline is its first sentence (for example **Lease agreement #1234 v2.1 closed unexpectedly.**), and the body the rest. The hint stays below them in Secondary, and the buttons are unchanged. `HoldsAndTimesTests.AfterACrash` pins the split. (changed in the build: not `WarningsTests`, which holds Sign Out Here's tests)

## 7. The menu bar

### 7.1 The icon

- **The mark, in one colour (owner).** It's drawn at run time from `HealdanMark`'s shapes, in a single colour with the pages cut out, onto a 44 by 44 px bitmap (22 pt at 2x). macOS takes it as a template image and tints it; Linux gets it in white, for GNOME's dark top bar.
- **`tray.png` and `tray-light.png` go.** `MarkTests` already pins the shapes, so the icon can't drift from the favicon. (call)

### 7.2 The attention dot (owner)

- **The dot.** A second version of the icon has a dot at its top right, in the same single colour, with a cut-out ring around it.
- **When it shows:** while any of these is true:
  - the helper isn't signed in;
  - the start failed;
  - a copy closed unexpectedly;
  - a copy is Not Yet in Healdan;
  - the extension isn't installed.
  
  Saves waiting to be sent don't count, since they clear by themselves.
- **The tooltip** names the first reason, for example **Healdan Helper: Not Signed In**.
- **The rule** is a pure function in `Healdan.Helper.Core` (`Ui/Attention.cs`), with tests. `TrayMenu` swaps the icon whenever it rebuilds.

## 8. The LibreOffice extension

The mockups are `menubar-and-libreoffice.html` and `libreoffice-open-save-icons.html`.

### 8.1 The toolbar

- **Icons only, with LibreOffice's tooltips (owner),** which are the items' existing titles. A `private:separator` divides the toolbar into three groups:
  - **Save to Healdan** (`device-floppy`) and **Open from Healdan** (`folder-open`), each with the shield (§8.2);
  - **Save as New Version** (`versions`) and **Save as New Document** (`file-plus`);
  - **Properties** (`list-details`) and **Insert Doc ID** (`hash`), the latter in Writer only, as today.
- **The window-state files are unchanged.** LibreOffice's default for a toolbar whose items have images is icons only.

### 8.2 The images

- **Strokes** are mid-grey `#7C7C80`, which reads on LibreOffice's light and dark toolbars alike: add-ons get only one image, with no dark variant. (call)
- **The shield** on Save to Healdan and Open from Healdan is the mark's outline, filled `#1A74C4` with a light ring, at the icon's lower right: 7 px on the 16 px image and 11 px on the 26 px one (owner, option B).
- **Sizes.** 16 and 26 px PNGs (`ImageSmallURL`, `ImageBigURL`), plus white high-contrast versions (`ImageSmallHCURL`, `ImageBigHCURL`), in `clients/libreoffice/healdan/icons/`.
- **Wiring.** `Addons.xcu` gains an `Images` node mapping each command URL to its files. The same images show beside the Healdan menu's items wherever LibreOffice shows menu icons.
- **To check on a Retina Mac (§10.3):** whether the 16 px PNG is sharp. If it's soft, try `ImageSmallURL` pointing at an SVG built from the same sources. Ship SVGs only if LibreOffice draws them sharply; otherwise ship the PNGs. The plan's decision log records which.

### 8.3 Infobars

- **A headline and a detail.** Each notice in `healdan_logic.py` becomes a headline and a detail, split at its first sentence, and the wording doesn't change. `tell` passes them as the infobar's primary message (bold) and secondary message (regular).
  - (changed in the build) **The split is made at display time.** The notices stay single strings, so the flows and their tests don't change. A new pure function, `notice_parts(text)`, splits one at the end of its first sentence, and `tell` calls it. A full stop inside brackets, as in an error's own words, or inside a number such as a version, doesn't end the sentence.
- **The message box fallback** in the Start Center shows the headline, a blank line, and the detail.
- **A one-sentence notice** (such as `STORE_FAILED`) has a headline and no detail.
- `test_logic.py` pins the split.

### 8.4 The extension's identity

- **`description.xml` gains an icon:** the mark at 42 px (`icon/default`), plus a high-contrast version, for the Extension Manager.
- **The Tabler notice** goes in the package as `THIRD-PARTY-NOTICES.txt`. `test_package.py` checks that it's there and that every image `Addons.xcu` names is in the package at its size.

### 8.5 Rendering the images

- **A small tool renders the PNGs** from `HelperIcons.axaml`'s geometry and the mark's shapes: `clients/helper/tools/IconRender`, using Avalonia headless with Skia, as the scratch renderer did. This keeps one source for each icon.
- **It's run by hand on a Mac** when an icon changes, and its output is committed, so `build.sh` stays sh and Python only and its output stays the same from the same sources.
- **It isn't in the solution,** so `dotnet test` and CI don't build it. The Developer Guide says how to run it. (call)

## 9. Accessibility

- **Contrast:** text meets AA (§4.1), and icons and focus rings meet 3:1 against their backgrounds.
- **Keyboard:** every control a mouse reaches can be reached with Tab. The segmented control also takes the arrow keys. Escape and Return keep today's meanings.
  - (changed in the build) **Read-only values skip Tab.** A detail's value and About's version and server lines are `SelectableTextBlock`s with `IsTabStop = false`, as the Mac's selectable labels aren't in the key-view loop. They stay selectable with the mouse and copyable (Cmd+C, or the context menu's Copy). `Focusable = false` would have lost the keyboard copy.
- **Names:** icon-only buttons have accessible names and tooltips (§4.3). The attention dot's reason is in the tray's tooltip (§7.2).
- **High contrast:** LibreOffice gets HC images (§8.2), and the shield marks Healdan's two commands by shape, not colour.

## 10. Testing

### 10.1 Automated

- **`Healdan.Helper.Core.Tests`:**
  - `IconsTests`: each icon geometry matches its Tabler source;
  - `AttentionTests`: the dot's rule and its tooltip reason;
  - `HoldsAndTimesTests.AfterACrash`: After a Crash's headline and body (changed in the build: not `WarningsTests`);
  - `BrowserTests`: the search placeholder's doc # hint;
  - `MarkTests` unchanged; `NoticesTests` unchanged.
- **The extension's tests:**
  - `test_logic.py`: each notice's headline and detail;
  - `test_package.py`: the images, their sizes, the notice and the description's icon.

### 10.2 Visual review

The scratch renderer from this design session draws all ten windows in light and dark, before and after. The plan has a review step comparing them, and the owner reviews the after set before the helper's PR merges. (call)

### 10.3 By hand on the Mac (owner)

- the unified title bar: dragging, double-click to zoom, the system buttons, and full screen;
- the menu bar icon and its dot, on light and dark menu bars and when highlighted;
- the LibreOffice toolbar and menu on a Retina screen, in light and dark, and in high contrast (§8.2);
- the Extension Manager's icon;
- Settings' Install… while the extension isn't installed.

GNOME's checks go with M3's phase 2, which hasn't started.

## 11. Docs

Docs change in the same PRs as the code (CLAUDE.md).

- **User Guide, Working in LibreOffice:**
  - **The pictures.** Re-render the seven pictures of the helper's windows the way M3 made them (offscreen renders of the real windows, R23). Update their alt text: Open's details in tabs, Save's File Format, and Settings' grouped rows.
  - **Installing the helper:**
    - describe the menu bar icon and what its dot means;
    - add Settings' Install… button;
    - add a picture of the Healdan toolbar with its six buttons named, since they no longer show their names.
  - **Pages that name toolbar buttons** keep the names, which are the tooltips.
  - **When Things Go Wrong:** the dot, and After a Crash's new layout.
- **Developer Guide, Desktop Clients:**
  - where the look lives (the tokens in `HelperStyles.axaml`, the icons in `HelperIcons.axaml` and `clients/icons/tabler/`);
  - that the menu bar icon is drawn from the mark;
  - how to re-render the extension's images (§8.5).
- **`CLAUDE.md`:** the clients bullets gain `IconsTests`, the menu bar icon drawn from the mark (next to the macOS icon's line), and the render tool.
- **The M3 spec's §5.8 and §6.2** get a line pointing here once this is built.

## 12. Delivery

Two pull requests, each touching only `clients/`, the docs and these records. CI's Java and Docker builds skip them, and its `Clients` job runs. (call)

1. **The helper.** The tokens, controls, icons and sources, the window shells and windows, the menu bar icon and dot, the tests and notices, and the helper's docs.
2. **The extension.** The render tool, the rendered images and their sources, `Addons.xcu`, `description.xml`, the infobar split, the tests and notice, and the extension's docs.

(changed in the build) The render tool ships with the extension, in the second pull request, not the helper's: its only output is the extension's images, which the first pull request's reviewer couldn't judge (§8.5). The second also numbers the extension 0.2.0, since the helper installs a bundled extension only when it's newer than the installed one, and this one has new images, a new toolbar and new notices.

The roadmap's M3 row notes the polish when the second PR merges. (changed in the build) It notes it in the second pull request itself, linking both.

## 13. Risks

- **The unified title bar** is Avalonia's to draw on macOS. A quirk in dragging, zooming or full screen falls back to §5.3's Linux layout on the Mac too (call), and the plan's decision log records it.
- **`.AppleSystemUIFont`** is a private CoreText name. Skia resolves it today. If a later macOS stops resolving it, the text falls back to Helvetica, which is today's look, not a breakage.
- **LibreOffice's add-on images** may be soft on Retina (§8.2), and a single grey has to serve light and dark toolbars. Both are checked by hand.
