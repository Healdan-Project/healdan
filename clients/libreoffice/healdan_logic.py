"""What the Healdan extension for LibreOffice decides without UNO (spec 6), so it can be tested anywhere.

healdan.py, inside LibreOffice, is a thin layer over this: it intercepts LibreOffice's commands, and hands each one
to Flows with an adapter for the document and one for LibreOffice's user interface. Everything here uses only the
standard library: the commands and what each does, where the helper keeps its files, the channel to the helper
(plan: "The channel"), and the flows of Open, Save, Save As and the Healdan buttons.

The extension holds no token and calls no server: the helper does the work, and draws the windows.
"""

import http.client
import json
import logging
import os
import re
import sys
import time
import unicodedata
import urllib.parse

APP = "LibreOffice"

# The document property Insert Doc ID sets, and that the server stamps (spec 5.9; plan: "The property's name").
DOC_ID_PROPERTY = "Healdan Doc ID"

# LibreOffice's Open has a name for each app (spec 3.1); the Start Center and Base send .uno:Open.
OPENS = (".uno:Open", ".uno:OpenFromWriter", ".uno:OpenFromCalc", ".uno:OpenFromImpress", ".uno:OpenFromDraw")
SAVE = ".uno:Save"
SAVE_AS = ".uno:SaveAs"
INTERCEPTED = OPENS + (SAVE, SAVE_AS)

# The formats a new document can be filed in, the app's own family first (spec 5.6).
_FORMATS = {
    "writer": ("odt", "docx"),
    "calc": ("ods", "xlsx"),
    "impress": ("odp", "pptx"),
}

# LibreOffice's export filter for each format, as LibreOffice 26.8's registry names them.
FILTERS = {
    "odt": "writer8",
    "docx": "MS Word 2007 XML",
    "ods": "calc8",
    "xlsx": "Calc MS Excel 2007 XML",
    "odp": "impress8",
    "pptx": "Impress MS PowerPoint 2007 XML",
}

# LibreOffice's module for each app. Writer/Web and master documents aren't filed: Writer's formats don't hold them.
_KINDS = {
    "com.sun.star.text.TextDocument": "writer",
    "com.sun.star.sheet.SpreadsheetDocument": "calc",
    "com.sun.star.presentation.PresentationDocument": "impress",
    "com.sun.star.drawing.DrawingDocument": "draw",
    "com.sun.star.formula.FormulaProperties": "math",
    "com.sun.star.sdb.OfficeDatabaseDocument": "base",
    "com.sun.star.frame.StartModule": "start",
}

# The Healdan menu's and toolbar's commands (Addons.xcu), served by healdan.py's protocol handler.
PROTOCOL = "org.healdan.libreoffice:"
BUTTONS = ("SaveToHealdan", "OpenFromHealdan", "SaveAsNewVersion", "SaveAsNewDocument", "Properties", "InsertDocId")

SECRET_HEADER = "X-Healdan-Secret"

# Seconds. The window routes wait while the user works in the helper's window, which can be minutes. /v1/file waits
# for the server's filing, whose upload the helper gives 10 minutes. /v1/saved answers within 120 s.
WINDOW_TIMEOUT = 3600.0
FILE_TIMEOUT = 660.0
SAVED_TIMEOUT = 135.0
QUICK_TIMEOUT = 10.0

# Seconds between /v1/saved calls while the helper says a save is still on its way ("pending", as while Healdan can't
# be reached), the last repeating: each call itself waits up to 120 s for Healdan.
FOLLOW_BACKOFF = (2.0, 5.0, 15.0, 30.0, 60.0)

# What the user is told (British English).
HELPER_DOWN_BOX = ("Healdan Helper isn't running, so LibreOffice's own box is shown. "
                   "Start Healdan Helper to open from and save to Healdan.")
HELPER_DOWN_SAVE = ("Healdan Helper isn't running. This save is kept on this computer, and goes to Healdan once "
                    "Healdan Helper is running again.")
HELPER_DOWN_OTHER = "Healdan Helper isn't running. Start it to use Healdan's commands."
READ_ONLY_VERSION = ("This is a read-only copy, so it can't be saved as a new version. "
                     "Use Save as New Document instead.")
NOT_FILED_DOC_ID = ("This document gets its doc ID once it's saved to Healdan, and the footer shows it from then on.")
WRITE_FAILED = "LibreOffice couldn't write the document for Healdan ({error}), so LibreOffice's own Save As box is shown."
STORE_FAILED = "LibreOffice couldn't save this document ({error})."
ADOPT_FAILED = ("{ref} is filed in Healdan, but LibreOffice couldn't save this window as its local copy ({error}). "
                "Close this window without saving, and open {it} from Healdan.")
NO_COPY = ("{ref} is filed in Healdan, but Healdan Helper gave no local copy to save this window as. "
           "Close this window without saving, and open {it} from Healdan.")
ALREADY_FILED = ("This window's document is already filed in Healdan{as_ref}, but the window isn't its local copy, so "
                 "LibreOffice's own Save As box is shown to keep your changes on this computer. Open {it} from "
                 "Healdan to go on there.")
FAILED_BEFORE = "The Healdan extension hit a problem ({error}), so LibreOffice's own command runs instead."
FAILED_AFTER = "The Healdan extension hit a problem ({error}). Its log has the details."


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


_LOG = logging.getLogger("healdan")

_SECRET = re.compile(r"[0-9a-fA-F]{48}")


# --- Commands --------------------------------------------------------------------------------------------------------

def _main(command):
    """The command without its arguments: ".uno:Save?FailOnWarning:bool=true" is ".uno:Save"."""
    return (command or "").split("?", 1)[0]


def intercepted(command):
    """Whether the extension takes the command over: every Open, Save and Save As."""
    return _main(command) in INTERCEPTED


def action(command, is_copy, has_location, read_only=False, can_file=True):
    """
    What the command does (spec 6.2): "open-dialog" (Open from Healdan), "store-copy" (save a Healdan copy in its own
    format), "save-new" (Save to Healdan, or Save as New Document on a copy) or "local" (LibreOffice's own command).

    A read-only copy can't be saved over, so its Save is Save as New Document. Where Healdan can't file the document
    (can_file is false: Draw, Math, Base), Save and Save As stay LibreOffice's, apart from a copy's own save.
    """
    command = _main(command)
    if command in OPENS:
        return "open-dialog"
    if command == SAVE:
        if is_copy and not read_only:
            return "store-copy"
        if has_location and not is_copy:
            return "local"
        return "save-new" if can_file else "local"
    if command == SAVE_AS:
        return "save-new" if can_file else "local"
    return "local"


def formats(kind):
    """The formats a new document of this kind can be filed in, the app's own first; none for other apps."""
    return list(_FORMATS.get(kind, ()))


def kind_of(module):
    """The app for LibreOffice's module identifier (ModuleManager.identify), or None."""
    return _KINDS.get(module or "")


def button(url):
    """The Healdan command a protocol handler URL names, such as "SaveToHealdan", or None."""
    url = url or ""
    if not url.startswith(PROTOCOL):
        return None
    name = _main(url[len(PROTOCOL):])
    return name if name in BUTTONS else None


# --- Paths -----------------------------------------------------------------------------------------------------------

def support_dir(platform=None, environ=None, home=None):
    """The helper's own folder (plan: "The helper's files"), as Healdan.Helper.Core's HelperPaths finds it."""
    platform = sys.platform if platform is None else platform
    environ = os.environ if environ is None else environ
    home = os.path.expanduser("~") if home is None else home
    if platform == "darwin":
        return os.path.join(home, "Library", "Application Support", "Healdan Helper")
    data = environ.get("XDG_DATA_HOME") or os.path.join(home, ".local", "share")
    return os.path.join(data, "healdan-helper")


def copies_dir(support):
    """The one flat folder holding every local copy."""
    return os.path.join(support, "Copies")


def log_file(platform, home, support):
    """The extension's log, beside the helper's."""
    if platform == "darwin":
        return os.path.join(home, "Library", "Logs", "Healdan Helper", "extension.log")
    return os.path.join(support, "extension.log")


def file_url(path):
    """A file URL for a path, encoded as LibreOffice encodes them."""
    return "file://" + urllib.parse.quote(path, safe="/")


def url_to_path(url):
    """The local path a file URL names, or None for anything else (a new document's empty URL, a remote file)."""
    if not url:
        return None
    try:
        parts = urllib.parse.urlsplit(url)
    except ValueError:
        return None
    if parts.scheme.lower() != "file" or parts.netloc.lower() not in ("", "localhost"):
        return None
    try:
        path = urllib.parse.unquote(parts.path, encoding="utf-8", errors="strict")
    except UnicodeDecodeError:
        return None
    return path or None


def _norm(path):
    """A path in one form for comparing: NFC, as macOS can hand either form back, without a trailing separator."""
    return unicodedata.normalize("NFC", os.path.normpath(path))


def _same_folder(a, b):
    if _norm(a) == _norm(b):
        return True
    try:
        return _norm(os.path.realpath(a)) == _norm(os.path.realpath(b))
    except (OSError, ValueError):
        return False


def is_copy(url, copies):
    """
    Whether the document at url is a Healdan copy: a file directly in the copies folder. Only the folder counts, never
    the name, so a local file named like a copy ("x #1 v1.0.odt") isn't one.
    """
    path = url_to_path(url)
    if not path or not copies:
        return False
    folder, name = os.path.split(path)
    if not name or name.startswith(".~lock.") or name.endswith(".healdan.json"):
        return False
    return _same_folder(folder, copies)


def lock_file(path):
    """LibreOffice's lock file beside a file it has open for editing (plan: "LibreOffice's lock file")."""
    folder, name = os.path.split(path)
    return os.path.join(folder, ".~lock." + name + "#")


def same_file(a, b):
    """Whether two paths name the same file, in either normalisation form."""
    return bool(a) and bool(b) and _norm(a) == _norm(b)


def recent_entries_to_forget(urls, copies):
    """The Recent Documents entries that are Healdan copies, which LibreOffice mustn't keep (spec 6.2), once each."""
    forget = []
    for url in urls:
        if url not in forget and is_copy(url, copies):
            forget.append(url)
    return forget


def pick_list_after(order, forget):
    """
    How LibreOffice's PickList OrderList changes once the forgotten entries go. order is its (name, url) pairs, named
    "0" to "n-1" in the order shown; LibreOffice reads them by those names, so what's left is named afresh, as its
    own DeleteItem does. Answers the new order as {name: url}, and the names to remove; nothing at all when nothing
    is forgotten.
    """
    forget = set(forget)
    if not any(url in forget for _, url in order):
        return {}, []

    def position(item):
        name = item[0]
        return (0, int(name), name) if name.isdigit() else (1, 0, name)

    ordered = sorted(order, key=position)
    refs = {str(i): url for i, url in enumerate(url for _, url in ordered if url not in forget)}
    return refs, [name for name, _ in ordered if name not in refs]


def document_name(title, url):
    """The name the Save window starts with: a local file's name without its extension, else the window's title."""
    path = url_to_path(url)
    name = os.path.basename(path) if path else (title or "").strip()
    stem, ext = os.path.splitext(name)
    if ext and stem and (path or ext[1:].lower() in _ALL_EXTENSIONS):
        name = stem
    return name.strip() or "Untitled"


_ALL_EXTENSIONS = {"odt", "ott", "docx", "docm", "dotx", "dotm", "doc", "rtf", "ods", "xlsx", "xlsm", "xls", "odp",
                   "pptx", "pptm", "ppt"}

_DOC_REF = re.compile(r"#(\d+) v(\d+(?:\.\d+)*)(?: \(read-only\))?(?:\.[A-Za-z0-9]+)?$")


def doc_ref_from(text):
    """The doc ID value ("#1234 v2.1") a copy's title or file name ends with, or None."""
    match = _DOC_REF.search(text or "")
    return "#%s v%s" % match.groups() if match else None


# --- The channel -----------------------------------------------------------------------------------------------------

class ChannelUnreachable(Exception):
    """The helper can't be reached: no channel.json, its process gone, or no answer. The extension falls back."""


class ChannelError(Exception):
    """The helper answered with an error status."""

    def __init__(self, status, detail=""):
        super().__init__("Healdan Helper answered %s %s" % (status, detail))
        self.status = status


def _alive(pid):
    if os.name != "posix":
        return True
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except OSError:
        return True  # it exists, though it isn't ours to signal
    return True


def _whole(value, low):
    return isinstance(value, int) and not isinstance(value, bool) and value >= low


class Channel:
    """
    The helper's local channel (spec 10): HTTP on 127.0.0.1, at the port in <support>/channel.json, with its secret in
    every request. The file is read at each call, so a helper that restarted is found at its new port.
    """

    def __init__(self, support):
        self.info_path = os.path.join(support, "channel.json")

    def info(self):
        """channel.json's {"port", "secret", "pid"}, or None when it's missing or damaged."""
        try:
            with open(self.info_path, "rb") as f:
                data = json.loads(f.read().decode("utf-8"))
        except (OSError, ValueError):
            return None
        if not isinstance(data, dict):
            return None
        port, secret, pid = data.get("port"), data.get("secret"), data.get("pid")
        if not (_whole(port, 1) and port < 65536 and isinstance(secret, str) and _SECRET.fullmatch(secret)
                and _whole(pid, 1)):
            return None
        return {"port": port, "secret": secret, "pid": pid}

    def call(self, route, body=None, timeout=QUICK_TIMEOUT):
        """POST /v1/<route> with a JSON body, answering the helper's JSON object."""
        info = self.info()
        if info is None:
            raise ChannelUnreachable("Healdan Helper's channel.json is missing")
        if not _alive(info["pid"]):
            raise ChannelUnreachable("Healdan Helper isn't running")
        payload = json.dumps({} if body is None else body).encode("utf-8")
        connection = http.client.HTTPConnection("127.0.0.1", info["port"], timeout=timeout)
        try:
            connection.request("POST", "/v1/" + route, body=payload, headers={
                "Content-Type": "application/json; charset=utf-8",
                SECRET_HEADER: info["secret"],
            })
            response = connection.getresponse()
            status, raw = response.status, response.read()
        except (OSError, http.client.HTTPException, ValueError) as e:
            # Only the exception's type: http.client's ValueError for a bad header would quote the secret.
            raise ChannelUnreachable("Healdan Helper didn't answer /v1/%s (%s)" % (route, type(e).__name__))
        finally:
            connection.close()
        if status != 200:
            raise ChannelError(status, "to /v1/" + route)
        try:
            answer = json.loads(raw.decode("utf-8")) if raw.strip() else {}
        except ValueError:
            raise ChannelError(status, "with something that isn't JSON")
        if not isinstance(answer, dict):
            raise ChannelError(status, "with something that isn't a JSON object")
        return answer


# --- The flows -------------------------------------------------------------------------------------------------------

def _remove(path):
    try:
        if path and os.path.isfile(path):
            os.remove(path)
    except OSError:
        pass


class Flows:
    """
    Open, Save, Save As and the Healdan buttons, over two adapters healdan.py gives (tests give fakes).

    ui:
      wait(fn)               runs fn away from LibreOffice's thread, LibreOffice going on meanwhile, and answers its
                             result (or raises its exception); for the window routes and the steps of a filing
      later(fn)              runs fn in the background; if it answers a callable, that runs on LibreOffice's thread
      tell(doc, text, warning=True)   a short notice in the document's window (doc None: the Start Center)

    doc (None for the Start Center):
      model                       the document, compared with == (UNO's own for LibreOffice's)
      url, kind, title, read_only, modified, alive
      store(), write_to(path, filter_name) (storeToURL), adopt(path, filter_name) (storeAsURL)
      set_title(title)
      set_doc_id(value) -> bool   sets the doc ID property if the document has it, refreshes its fields, and keeps
                                  the modified flag as it was; false when it has no such property
      insert_doc_id(value)        Insert Doc ID; None keeps the property's value, if it has one
      locked()                    a context manager: no typing into the document while it's held

    Each flow answers True when it did what was asked, False when it was cancelled or failed, or what LibreOffice's
    own command answered when it ran that.
    """

    def __init__(self, channel, copies, ui, log=None, sleep=None):
        self.channel = channel
        self.copies = copies
        self.ui = ui
        self.log = log or _LOG
        self._sleep = sleep or time.sleep  # in the background, between /v1/saved calls
        self._told_down = False
        self._states = {}  # the latest state the helper gave for each copy, by its path
        self._following = {}  # the newest save followed for each copy, by its path: an older follow stops
        self._windows_asked = 0  # window routes asked for, so a surprise knows whether the user saw one
        # Windows whose document the helper filed but that never became its local copy, as (doc.model, doc ID):
        # saving one again mustn't file it a second time.
        self._filed = []

    # -- the commands LibreOffice sends

    def command(self, command, doc, original):
        """An intercepted command; original() runs LibreOffice's own."""
        return self._guarded(doc, original, lambda own: self._command(command, doc, own), fall_back=True)

    def button(self, name, doc, open_box, save_as_box):
        """A Healdan menu or toolbar command; open_box and save_as_box run LibreOffice's own Open and Save As."""
        if name == "OpenFromHealdan":
            return self._guarded(doc, open_box, lambda own: self.open_from_healdan(doc, own), fall_back=True)
        if doc is None:
            return False
        flows = {
            "SaveToHealdan": (self.save_to_healdan, True),
            "SaveAsNewDocument": (self.save_as_new_document, True),
            "SaveAsNewVersion": (self.save_as_new_version, False),
            "Properties": (self.properties, False),
            "InsertDocId": (lambda d, own: self.insert_doc_id(d), False),
        }
        if name not in flows:
            return False
        flow, fall_back = flows[name]
        return self._guarded(doc, save_as_box, lambda own: flow(doc, own), fall_back)

    def _guarded(self, doc, box, run, fall_back):
        """
        run(own), where own runs LibreOffice's own command (box), with surprises caught so the user is never left
        with nothing: an unexpected exception before any helper window was asked for runs LibreOffice's own command
        instead (when fall_back), and either way the user is told briefly. LibreOffice's own command is never run
        twice: an exception from it goes on to the caller.
        """
        asked, ran = self._windows_asked, []

        def own():
            ran.append(True)
            return box()

        try:
            return run(own)
        except Exception as e:
            if ran:
                raise
            self.log.exception("A Healdan command failed")
            if fall_back and self._windows_asked == asked:
                self.ui.tell(doc, FAILED_BEFORE.format(error=said(e)))
                return own()
            self.ui.tell(doc, FAILED_AFTER.format(error=said(e)))
            return False

    def _command(self, command, doc, original):
        command = _main(command)
        if command in OPENS:
            return self.open_from_healdan(doc, original)
        if doc is None:
            return original()
        copy = self._copy_path(doc)
        what = action(command, copy is not None, bool(doc.url), doc.read_only, bool(formats(doc.kind)))
        self.log.info("%s on %s: %s", command, "a copy" if copy else "a local or new document", what)
        if what == "store-copy":
            return self.store_copy(doc)
        if what == "save-new":
            return self._save_new(doc, original, copy)
        return original()

    # -- the buttons

    def open_from_healdan(self, doc, open_box):
        answer = self._window("open-dialog", {"app": APP}, doc, HELPER_DOWN_BOX)
        if answer is None:
            return open_box()
        what = answer.get("action")
        if what == "local":
            return open_box()
        return what == "opened"

    def save_to_healdan(self, doc, save_as_box):
        copy = self._copy_path(doc)
        if copy is not None and not doc.read_only:
            return self.store_copy(doc)
        return self._save_new(doc, save_as_box, copy)

    def save_as_new_document(self, doc, save_as_box):
        return self._save_new(doc, save_as_box, self._copy_path(doc))

    def save_as_new_version(self, doc, save_as_box):
        copy = self._copy_path(doc)
        if copy is None:
            return self._save_new(doc, save_as_box, None)
        if doc.read_only:
            self.ui.tell(doc, READ_ONLY_VERSION)
            return False
        try:
            self.ui.wait(lambda: self.channel.call("new-version", {"path": copy}, QUICK_TIMEOUT))
        except ChannelUnreachable:
            self._down(doc, HELPER_DOWN_SAVE)
            if doc.modified:
                self._store(doc)  # kept on this computer; the helper sends it once it's back
            return False
        except ChannelError as e:
            self.log.warning("Save as New Version: %s", e)
            return False
        self._up()
        if doc.modified and not self._store(doc):
            return False
        self._follow(doc, copy)
        return True

    def properties(self, doc, save_as_box):
        copy = self._copy_path(doc)
        answer = self._window("properties", {"path": copy}, doc, HELPER_DOWN_OTHER)
        if answer is None:
            return False
        what = answer.get("action")
        if what == "file":
            return self._save_new(doc, save_as_box, None)
        if what == "saved" and copy is not None:
            self._refresh(doc, copy)
        return what == "saved"

    def insert_doc_id(self, doc):
        if doc is None or doc.kind != "writer":
            return False
        ref = self._doc_ref(doc)
        doc.insert_doc_id(ref)
        if ref is None:
            self.ui.tell(doc, NOT_FILED_DOC_ID, warning=False)
        return True

    # -- LibreOffice's events

    def loaded(self, doc):
        """A document opened: a copy's window takes its title from the helper (spec 5.7)."""
        copy = self._copy_path(doc)
        if copy is not None:
            self._refresh(doc, copy)

    def closed(self, url, still_open):
        """
        A document closed (OnUnload): the helper checks a copy in and deletes it (spec 5.5). Not when another window
        has the same file, as File > Reload unloads the old document after loading the new one.
        """
        path = url_to_path(url) if is_copy(url, self.copies) else None
        if path is None:
            return
        self._states.pop(_norm(path), None)
        self._following.pop(_norm(path), None)
        if still_open:
            return

        def work():
            try:
                self.channel.call("closed", {"path": path}, QUICK_TIMEOUT)
            except (ChannelUnreachable, ChannelError) as e:
                self.log.info("Couldn't tell Healdan Helper a copy closed (%s); it watches the lock file too", e)
            return None

        self.ui.later(work)

    # -- the steps

    def store_copy(self, doc):
        """Save on a copy: store it in its own format, with no question about it (spec 6.2), and follow the save."""
        if not self._store(doc):
            return False
        self._follow(doc, self._copy_path(doc))
        return True

    def _store(self, doc):
        try:
            doc.store()
            return True
        except Exception as e:
            self.log.warning("Storing a copy failed: %s", e)
            self.ui.tell(doc, STORE_FAILED.format(error=said(e)))
            return False

    def _save_new(self, doc, save_as_box, original):
        """
        Save to Healdan, or Save as New Document when original is a copy's path (spec 5.5, 5.6): the helper's window,
        the content written to its temporary file, filed, then this window saved as the new local copy.
        """
        kinds = formats(doc.kind)
        if not kinds:
            return save_as_box()
        filed = self._filed_as(doc)
        if filed is not None:
            self.ui.tell(doc, ALREADY_FILED.format(as_ref=" as " + filed if filed else "", it=filed or "it"))
            return save_as_box()
        body = {"app": APP, "name": document_name(doc.title, doc.url), "formats": kinds, "path": original}
        answer = self._window("save-new", body, doc, HELPER_DOWN_BOX)
        if answer is None:
            return save_as_box()
        what = answer.get("action")
        if what == "local":
            return save_as_box()
        if what != "save" or not doc.alive:
            return False

        fmt = answer.get("format")
        filter_name = FILTERS.get(fmt)
        temp = answer.get("tempPath")
        if filter_name is None or fmt not in kinds or not isinstance(temp, str) or not temp:
            self.log.warning("Save to Healdan answered a format or temporary file that can't be written")
            return save_as_box()
        try:
            doc.write_to(temp, filter_name)
        except Exception as e:
            self.log.warning("Writing the temporary file failed: %s", e)
            _remove(temp)  # the helper deletes it only after /v1/file
            self.ui.tell(doc, WRITE_FAILED.format(error=said(e)))
            return save_as_box()

        filing = {"tempPath": temp, "folderId": answer.get("folderId"), "name": answer.get("name"), "format": fmt,
                  "properties": answer.get("properties") or {}, "app": APP, "replaces": original}
        # Typing while it's filed, or before the helper hears the copy is adopted, would land in the copy's
        # starting point, which the helper doesn't send. Notices and LibreOffice's own box come once the window
        # takes input again.
        failed = None
        with doc.locked():
            try:
                state = self.ui.wait(lambda: self.channel.call("file", filing, FILE_TIMEOUT))
            except ChannelUnreachable:
                failed = "unreachable"
            except ChannelError as e:
                self.log.warning("Filing failed: %s", e)  # the helper told the user and deleted the file
                failed = "refused"
            if failed is None:
                self._up()
                path, ref = state.get("path"), state.get("docRef") or ""
                if not state.get("known") or not isinstance(path, str) or not path:
                    self.log.warning("Filing answered no copy to save as")
                    failed = NO_COPY.format(ref=ref or "The document", it=ref or "it")
                else:
                    try:
                        doc.set_doc_id(ref)
                        doc.adopt(path, filter_name)
                    except Exception as e:
                        self.log.warning("Saving the window as its copy failed: %s", e)
                        failed = ADOPT_FAILED.format(ref=ref or "The document", it=ref or "it", error=said(e))
                    else:
                        state = self._adopted(path, state)
        if failed == "unreachable":
            _remove(temp)
            self._down(doc, HELPER_DOWN_BOX)
            return save_as_box()
        if failed == "refused":
            return save_as_box()
        if failed is not None:
            # Filed and checked out in Healdan, but this window isn't its copy.
            self._filed.append((doc.model, ref))
            self.ui.tell(doc, failed)
            return False
        self._remember(path, state)
        try:
            doc.set_title(state.get("title"))
        except Exception as e:
            self.log.warning("The new copy's title couldn't be set: %s", e)  # it's filed and saved all the same
        return True

    def _adopted(self, path, state):
        """
        Tells the helper this window is now the new document's copy, while it still takes no input (final review C1):
        the file as it is now is the copy's starting point, so a save straight after counts as one. An older helper,
        or one that can't be reached, takes the first write that settles instead. Answers the copy's state.
        """
        try:
            answer = self.ui.wait(lambda: self.channel.call("adopted", {"path": path}, QUICK_TIMEOUT))
        except (ChannelUnreachable, ChannelError) as e:
            self.log.warning("/v1/adopted: %s", e)
            return state
        return answer if answer.get("known") and same_file(answer.get("path"), path) else state

    def _window(self, route, body, doc, down_notice):
        """A window route: the helper's window, waited for; None when the helper can't be reached or refused."""
        self._windows_asked += 1
        try:
            answer = self.ui.wait(lambda: self.channel.call(route, body, WINDOW_TIMEOUT))
        except ChannelUnreachable as e:
            self.log.info("/v1/%s: %s", route, e)
            self._down(doc, down_notice)
            return None
        except ChannelError as e:
            self.log.warning("/v1/%s: %s", route, e)
            return None
        self._up()
        return answer

    def _follow(self, doc, path):
        """
        After a save: the helper's answer once Healdan has it, waited for in the background, moves the title and footer
        on (final review I1). While the helper says a save is still on its way (pending, as while Healdan can't be
        reached), it's asked again after a pause that grows to a minute, so the title moves on once Healdan has the
        save, however long that takes. A newer save of the copy takes the following over, and closing it ends it.
        """
        key = _norm(path)
        generation = self._following.get(key, 0) + 1
        self._following[key] = generation
        self._poll(doc, path, key, generation, 0)

    def _poll(self, doc, path, key, generation, round_):
        def work():
            if round_:
                self._sleep(FOLLOW_BACKOFF[min(round_, len(FOLLOW_BACKOFF)) - 1])
            try:
                state = self.channel.call("saved", {"path": path}, SAVED_TIMEOUT)
            except ChannelUnreachable:
                return lambda: self._down(doc, HELPER_DOWN_SAVE)
            except ChannelError as e:
                self.log.warning("/v1/saved: %s", e)
                return None

            def then():
                # Each answer's version is what Healdan has, pending or not: the title and footer show it.
                self._apply(doc, path, state, confirmed=True)
                if state.get("pending") is True and self._following.get(key) == generation and doc.alive \
                        and same_file(self._copy_path(doc), path):
                    self._poll(doc, path, key, generation, round_ + 1)

            return then

        self.ui.later(work)

    def _refresh(self, doc, path):
        """The copy's state as the helper has it, for its title, in the background."""

        def work():
            try:
                state = self.channel.call("state", {"path": path}, QUICK_TIMEOUT)
            except (ChannelUnreachable, ChannelError) as e:
                self.log.info("/v1/state: %s", e)
                return None
            return lambda: self._apply(doc, path, state, confirmed=False)

        self.ui.later(work)

    def _apply(self, doc, path, state, confirmed):
        """A state from the helper, on LibreOffice's thread: the title, and after a confirmed save the doc ID."""
        self._up()
        if not state.get("known") or not same_file(state.get("path"), path):
            return
        if not doc.alive or not same_file(self._copy_path(doc), path):
            return  # closed, or saved somewhere else since
        self._remember(path, state)
        if state.get("title"):
            doc.set_title(state["title"])
        if confirmed and state.get("docRef"):
            doc.set_doc_id(state["docRef"])

    def _remember(self, path, state):
        self._states[_norm(path)] = state

    def _doc_ref(self, doc):
        copy = self._copy_path(doc)
        if copy is None:
            return None
        state = self._states.get(_norm(copy))
        if state and state.get("docRef"):
            return state["docRef"]
        return doc_ref_from(doc.title) or doc_ref_from(os.path.basename(copy))

    def _copy_path(self, doc):
        if doc is None or not is_copy(doc.url, self.copies):
            return None
        return url_to_path(doc.url)

    def _filed_as(self, doc):
        """The doc ID ("" when unknown) of the document the helper filed from this window, which isn't its copy; None."""
        for model, ref in self._filed:
            try:
                if model == doc.model:
                    return ref
            except Exception:
                continue
        return None

    def window_closed(self, model):
        """A document's window closed: nothing more to remember about it."""
        kept = []
        for known, ref in self._filed:
            try:
                if known == model:
                    continue
            except Exception:
                continue
            kept.append((known, ref))
        self._filed = kept

    def _down(self, doc, text):
        """Tells the user once that the helper isn't running, until it has answered again."""
        if not self._told_down:
            self._told_down = True
            self.ui.tell(doc, text)

    def _up(self):
        self._told_down = False


def said(e):
    """An exception as a few words for the user."""
    text = str(getattr(e, "Message", "") or e).strip()
    return text.splitlines()[0][:200] if text else type(e).__name__
