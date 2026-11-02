# Server messages as text in the error popup: design

- **Date:** 2026-11-02
- **Status:** approved (owner, 2026-11-02), as one small upstream edit in its own pull request. It follows the
  [group names as text](2026-11-02-group-names-as-text-design.md) change, after a hands-on check of the merged M4 and
  that change found it.

As in the roadmap, **(owner)** marks what the owner decided, and **(call)** marks Claude's proposal.

## 1. The change

When the server refuses something, the old UI shows the refusal's message in a popup at the bottom of the screen,
through `GuiLog.serverError`, `EventPanel.error` and SmartGWT's `Notify`, which writes its text as HTML. Many of
Healdan's refusals name what they refuse: a group, a folder, a file or a person. So a name holding markup was rendered
in the popup instead of shown as typed. The hands-on check saw it with a folder Security change that Healdan's
permission rules refused, naming a group.

**`GuiLog.serverError` shows the server's message as text.** (owner) It escapes the message with Healdan's
`HealdanText.escape` before it reaches the popup, the status bar and the Messages window. It's one hook into CE's code,
marked `Healdan:`, kept small so upstream syncs stay cheap.

- **At the one place server messages reach the GUI, not in each message.** Escaping inside Healdan's messages would
  put HTML entities into the API's problems and the server's log, which read them as text. The one hook also covers
  CE's own messages. (call)
- **No server message relies on HTML.** None of CE's or Healdan's `ServerException`s carries markup on purpose, so
  nothing shows its tags now. Messages the GUI makes itself, which can carry markup on purpose, are unchanged. (call)
- **Nothing is escaped twice.** The status bar already turns `<` and `>` into entities, and an escaped message has
  none left. The Messages window and the popup show the message as given.

## 2. Upstream edits

| File | Edit | Why |
|---|---|---|
| `logicaldoc-gui/src/main/java/com/logicaldoc/gui/common/client/log/GuiLog.java` | In `serverError`, the server's message goes through `HealdanText.escape` before `GuiLog.error`, marked `Healdan:` | A refusal that names something shows it as text, not HTML |

## 3. Tests

`HealdanTextTest` already covers the escaping. The popup is GWT client code that JUnit can't load, so the edit adds no
test of its own; it was checked by hand on a local stack, where the refusal that ran a group name's markup before shows
the name as text after.
