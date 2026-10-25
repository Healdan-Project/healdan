"""Tests for healdan_logic: the extension's commands, paths, channel client and flows, with no LibreOffice."""

import contextlib
import http.server
import json
import logging
import os
import pathlib
import socket
import socketserver
import subprocess
import sys
import tempfile
import threading
import unicodedata
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent))

import healdan_logic as logic  # noqa: E402

QUIET = logging.getLogger("healdan-tests")
QUIET.addHandler(logging.NullHandler())
QUIET.propagate = False

COPIES = "/Users/rob/Library/Application Support/Healdan Helper/Copies"
COPIES_URL = "file:///Users/rob/Library/Application%20Support/Healdan%20Helper/Copies"


class CommandsTest(unittest.TestCase):
    def test_every_open_variant_intercepted(self):
        self.assertEqual(
            (".uno:Open", ".uno:OpenFromWriter", ".uno:OpenFromCalc", ".uno:OpenFromImpress", ".uno:OpenFromDraw"),
            logic.OPENS)
        for command in logic.OPENS:
            self.assertTrue(logic.intercepted(command), command)
            for is_copy, has_location in ((False, False), (False, True), (True, True)):
                self.assertEqual("open-dialog", logic.action(command, is_copy, has_location), command)

    def test_save_and_save_as_intercepted_with_or_without_arguments(self):
        self.assertTrue(logic.intercepted(".uno:Save"))
        self.assertTrue(logic.intercepted(".uno:SaveAs"))
        self.assertTrue(logic.intercepted(".uno:Save?FailOnWarning:bool=true"))

    def test_other_commands_left_alone(self):
        for command in (".uno:SaveAll", ".uno:SaveACopy", ".uno:ExportToPDF", ".uno:Print", ".uno:CloseDoc", "", None):
            self.assertFalse(logic.intercepted(command), command)
            self.assertEqual("local", logic.action(command, False, False), command)

    def test_save_on_copy_stores(self):
        self.assertEqual("store-copy", logic.action(".uno:Save", True, True))

    def test_save_on_local_file_stays_local(self):
        self.assertEqual("local", logic.action(".uno:Save", False, True))

    def test_save_on_new_document_saves_new(self):
        self.assertEqual("save-new", logic.action(".uno:Save", False, False))

    def test_save_as_on_copy_is_new_document(self):
        self.assertEqual("save-new", logic.action(".uno:SaveAs", True, True))

    def test_save_as_on_a_document_not_in_healdan_saves_to_healdan(self):
        self.assertEqual("save-new", logic.action(".uno:SaveAs", False, True))
        self.assertEqual("save-new", logic.action(".uno:SaveAs", False, False))

    def test_save_on_read_only_copy_is_new_document(self):
        self.assertEqual("save-new", logic.action(".uno:Save", True, True, read_only=True))

    def test_saves_stay_local_where_healdan_cant_file(self):
        # Draw, Math, Base and the like have no formats to file in, so their saves stay LibreOffice's.
        self.assertEqual("local", logic.action(".uno:Save", False, False, can_file=False))
        self.assertEqual("local", logic.action(".uno:SaveAs", True, True, can_file=False))
        self.assertEqual("local", logic.action(".uno:Save", True, True, read_only=True, can_file=False))
        # A copy's own save still stores it, whatever app it's in.
        self.assertEqual("store-copy", logic.action(".uno:Save", True, True, can_file=False))

    def test_formats_writer(self):
        self.assertEqual(["odt", "docx"], logic.formats("writer"))

    def test_formats_calc(self):
        self.assertEqual(["ods", "xlsx"], logic.formats("calc"))

    def test_formats_impress(self):
        self.assertEqual(["odp", "pptx"], logic.formats("impress"))

    def test_formats_elsewhere_none(self):
        for kind in ("draw", "math", "base", "start", None):
            self.assertEqual([], logic.formats(kind), kind)

    def test_formats_are_a_fresh_list(self):
        logic.formats("writer").append("pdf")
        self.assertEqual(["odt", "docx"], logic.formats("writer"))

    def test_every_format_has_a_filter(self):
        self.assertEqual({
            "odt": "writer8", "docx": "MS Word 2007 XML",
            "ods": "calc8", "xlsx": "Calc MS Excel 2007 XML",
            "odp": "impress8", "pptx": "Impress MS PowerPoint 2007 XML",
        }, logic.FILTERS)
        for kind in ("writer", "calc", "impress"):
            for fmt in logic.formats(kind):
                self.assertIn(fmt, logic.FILTERS)

    def test_kind_of(self):
        self.assertEqual("writer", logic.kind_of("com.sun.star.text.TextDocument"))
        self.assertEqual("calc", logic.kind_of("com.sun.star.sheet.SpreadsheetDocument"))
        self.assertEqual("impress", logic.kind_of("com.sun.star.presentation.PresentationDocument"))
        self.assertEqual("draw", logic.kind_of("com.sun.star.drawing.DrawingDocument"))
        self.assertEqual("start", logic.kind_of("com.sun.star.frame.StartModule"))
        # Writer/Web and master documents aren't filed in Writer's formats.
        self.assertIsNone(logic.kind_of("com.sun.star.text.WebDocument"))
        self.assertIsNone(logic.kind_of("com.sun.star.text.GlobalDocument"))
        self.assertIsNone(logic.kind_of(""))
        self.assertIsNone(logic.kind_of(None))

    def test_buttons(self):
        self.assertEqual("SaveToHealdan", logic.button("org.healdan.libreoffice:SaveToHealdan"))
        self.assertEqual("InsertDocId", logic.button("org.healdan.libreoffice:InsertDocId"))
        self.assertIsNone(logic.button("org.healdan.libreoffice:Nonsense"))
        self.assertIsNone(logic.button(".uno:Save"))
        self.assertEqual(
            ("SaveToHealdan", "OpenFromHealdan", "SaveAsNewVersion", "SaveAsNewDocument", "Properties", "InsertDocId"),
            logic.BUTTONS)


class PathsTest(unittest.TestCase):
    def test_support_dir_macos(self):
        self.assertEqual("/Users/rob/Library/Application Support/Healdan Helper",
                         logic.support_dir("darwin", {}, "/Users/rob"))

    def test_support_dir_linux(self):
        self.assertEqual("/data/healdan-helper", logic.support_dir("linux", {"XDG_DATA_HOME": "/data"}, "/home/rob"))
        self.assertEqual("/home/rob/.local/share/healdan-helper", logic.support_dir("linux", {}, "/home/rob"))
        self.assertEqual("/home/rob/.local/share/healdan-helper",
                         logic.support_dir("linux", {"XDG_DATA_HOME": ""}, "/home/rob"))

    def test_copies_and_log(self):
        support = "/Users/rob/Library/Application Support/Healdan Helper"
        self.assertEqual(COPIES, logic.copies_dir(support))
        self.assertEqual("/Users/rob/Library/Logs/Healdan Helper/extension.log",
                         logic.log_file("darwin", "/Users/rob", support))
        self.assertEqual("/home/rob/.local/share/healdan-helper/extension.log",
                         logic.log_file("linux", "/home/rob", "/home/rob/.local/share/healdan-helper"))

    def test_lock_file(self):
        self.assertEqual(COPIES + "/.~lock.Lease #1234 v2.1.odt#", logic.lock_file(COPIES + "/Lease #1234 v2.1.odt"))

    def test_url_to_path(self):
        self.assertEqual(COPIES + "/Lease #1234 v2.1.odt", logic.url_to_path(COPIES_URL + "/Lease%20%231234%20v2.1.odt"))
        self.assertEqual("/tmp/a b.odt", logic.url_to_path("file://localhost/tmp/a%20b.odt"))
        for url in ("", None, "private:factory/swriter", "vnd.sun.star.webdav://host/a.odt", "file://server/a.odt",
                    "https://example.com/a.odt"):
            self.assertIsNone(logic.url_to_path(url), url)

    def test_is_copy_by_folder_not_name(self):
        self.assertFalse(logic.is_copy("file:///Users/rob/Documents/x%20%231%20v1.0.odt", COPIES))
        self.assertTrue(logic.is_copy(COPIES_URL + "/x.odt", COPIES))

    def test_is_copy_with_url_encoded_path(self):
        url = COPIES_URL + "/Re-%20A-B%20%E2%80%93%20%C3%9Cn%C3%AFc%C3%B8d%C3%A9%20%231234%20v2.1.docx"
        self.assertTrue(logic.is_copy(url, COPIES))
        self.assertTrue(logic.is_copy(url.replace("%E2%80%93", "%e2%80%93"), COPIES))
        self.assertTrue(logic.is_copy("file:///Users/rob/Library/Application%20Support/Healdan%20Helper/Copie%73/a.odt",
                                      COPIES))
        support = "/Users/rob/Library/Application Support/Healdan Helper \u2013 \u00dcn\u00efc\u00f8d\u00e9"
        self.assertTrue(logic.is_copy(logic.file_url(logic.copies_dir(support) + "/a #1 v1.0.odt"),
                                      logic.copies_dir(support)))

    def test_is_copy_with_nfd_path(self):
        support = "/Users/ren\u00e9/Library/Application Support/Healdan Helper"
        nfc_copies = logic.copies_dir(support)
        nfd_url = logic.file_url(unicodedata.normalize("NFD", nfc_copies) + "/Caf\u00e9 #7 v1.0.odt")
        self.assertIn("e%CC%81", nfd_url)
        self.assertTrue(logic.is_copy(nfd_url, nfc_copies))
        nfc_url = logic.file_url(nfc_copies + "/a.odt")
        self.assertTrue(logic.is_copy(nfc_url, unicodedata.normalize("NFD", nfc_copies)))

    def test_is_copy_only_directly_in_the_folder(self):
        self.assertFalse(logic.is_copy(COPIES_URL + "/sub/x.odt", COPIES))
        self.assertFalse(logic.is_copy(COPIES_URL, COPIES))
        self.assertFalse(logic.is_copy(COPIES_URL + "/", COPIES))
        self.assertFalse(logic.is_copy("file:///Users/rob/Copies/x.odt", COPIES))
        self.assertFalse(logic.is_copy(COPIES_URL + "%20old/x.odt", COPIES))
        self.assertFalse(logic.is_copy(COPIES_URL + "/x.odt", ""))
        self.assertFalse(logic.is_copy("", COPIES))
        self.assertFalse(logic.is_copy("private:factory/swriter", COPIES))

    def test_is_copy_through_a_link(self):
        with tempfile.TemporaryDirectory() as root:
            real = os.path.join(root, "real", "Copies")
            os.makedirs(real)
            os.symlink(os.path.join(root, "real"), os.path.join(root, "link"))
            linked = os.path.join(root, "link", "Copies")
            self.assertTrue(logic.is_copy(logic.file_url(os.path.join(real, "a.odt")), linked))
            self.assertTrue(logic.is_copy(logic.file_url(os.path.join(linked, "a.odt")), real))

    def test_recent_entries(self):
        urls = [
            "file:///Users/rob/Documents/Board%20minutes.odt",
            COPIES_URL + "/Lease%20agreement%20%231234%20v2.1.odt",
            "file:///Users/rob/Documents/Lease%20agreement%20%231234%20v2.1.odt",
            COPIES_URL + "/Budget%20%2312%20v1.0%20(read-only).xlsx",
        ]
        self.assertEqual([urls[1], urls[3]], logic.recent_entries_to_forget(urls, COPIES))

    def test_recent_entries_in_the_pick_lists_url_forms(self):
        support = "/Users/ren\u00e9/Library/Application Support/Healdan Helper"
        copies = logic.copies_dir(support)
        forms = [
            "file://localhost/Users/ren%C3%A9/Library/Application%20Support/Healdan%20Helper/Copies/a%20%231%20v1.0.odt",
            "file:///Users/ren%c3%a9/Library/Application%20Support/Healdan%20Helper/Copies/b%20%232%20v1.0.docx",
            "file:///Users/rene%CC%81/Library/Application%20Support/Healdan%20Helper/Copies/c%20%233%20v1.0.odp",
            "file:///Users/ren%C3%A9/Library/Application%20Support/Healdan%20Helper/Copies/d%20%234%20v1.0.ods",
        ]
        keep = [
            "file:///Users/ren%C3%A9/Library/Application%20Support/Healdan%20Helper/e.odt",
            "file:///Users/ren%C3%A9/Copies/f.odt",
            "vnd.sun.star.webdav://host/Copies/g.odt",
            "private:factory/swriter",
            "",
        ]
        self.assertEqual(forms, logic.recent_entries_to_forget(keep[:2] + forms + keep[2:] + forms[:1], copies))

    def test_pick_list_after(self):
        order = [("0", "file:///a.odt"), ("1", COPIES_URL + "/b.odt"), ("2", "file:///c.odt"), ("3", COPIES_URL + "/d.odt")]
        refs, removed = logic.pick_list_after(order, logic.recent_entries_to_forget([r for _, r in order], COPIES))
        self.assertEqual({"0": "file:///a.odt", "1": "file:///c.odt"}, refs)
        self.assertEqual(["2", "3"], removed)

    def test_pick_list_after_numbers_the_order_afresh(self):
        # LibreOffice reads the order as "0" to "n-1"; a list with a gap comes back without it.
        order = [("10", "file:///z.odt"), ("0", "file:///a.odt"), ("2", COPIES_URL + "/b.odt"), ("x", "file:///y.odt")]
        refs, removed = logic.pick_list_after(order, [COPIES_URL + "/b.odt"])
        self.assertEqual({"0": "file:///a.odt", "1": "file:///z.odt", "2": "file:///y.odt"}, refs)
        self.assertEqual(["10", "x"], removed)

    def test_pick_list_after_nothing_to_forget(self):
        order = [("0", "file:///a.odt"), ("1", "file:///c.odt")]
        self.assertEqual(({}, []), logic.pick_list_after(order, []))

    def test_document_name(self):
        self.assertEqual("Untitled 1", logic.document_name("Untitled 1", ""))
        self.assertEqual("Board minutes", logic.document_name("Board minutes.docx", "file:///Users/rob/Board%20minutes.docx"))
        self.assertEqual("notes", logic.document_name("notes.rtf", "file:///Users/rob/notes.rtf"))
        self.assertEqual("Untitled", logic.document_name("", ""))
        self.assertEqual("Lease", logic.document_name("Lease.odt", "vnd.sun.star.webdav://host/Lease.odt"))

    def test_doc_ref_from(self):
        self.assertEqual("#1234 v2.1", logic.doc_ref_from("Lease agreement #1234 v2.1"))
        self.assertEqual("#1234 v2.1", logic.doc_ref_from("Lease agreement #1234 v2.1 (read-only)"))
        self.assertEqual("#1234 v10.0", logic.doc_ref_from("Lease #2 agreement #1234 v10.0.odt"))
        self.assertEqual("#7 v1.0", logic.doc_ref_from("Budget #7 v1.0 (read-only).xlsx"))
        for text in ("Lease agreement", "Lease #1234", "Untitled 1", "", None):
            self.assertIsNone(logic.doc_ref_from(text), text)


class _Recorder(http.server.BaseHTTPRequestHandler):
    answers = {}
    seen = []

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        body = json.loads(self.rfile.read(length).decode("utf-8"))
        route = self.path
        self.seen.append((self.command, route, dict(self.headers), body))
        status, answer = self.answers.get(route, (404, {"error": "not-found"}))
        data = json.dumps(answer).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *args):
        pass


class _Server(http.server.HTTPServer):
    def server_bind(self):
        # HTTPServer's own looks its name up in DNS (getfqdn), which can take half a minute.
        socketserver.TCPServer.server_bind(self)
        self.server_name, self.server_port = self.server_address[:2]


class ChannelTest(unittest.TestCase):
    SECRET = "ab" * 24

    def setUp(self):
        self.support = tempfile.TemporaryDirectory()
        self.addCleanup(self.support.cleanup)
        _Recorder.answers = {}
        _Recorder.seen = []

    def serve(self):
        server = _Server(("127.0.0.1", 0), _Recorder)
        thread = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True)
        thread.start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        return server.server_address[1]

    def write_info(self, port, pid=None):
        with open(os.path.join(self.support.name, "channel.json"), "w") as f:
            json.dump({"port": port, "secret": self.SECRET, "pid": pid or os.getpid()}, f)

    def test_channel_sends_secret(self):
        port = self.serve()
        self.write_info(port)
        _Recorder.answers["/v1/state"] = (200, {"known": True, "title": "Lease #1 v1.0"})
        answer = logic.Channel(self.support.name).call("state", {"path": "/x/Copies/a.odt"}, timeout=5)
        self.assertEqual({"known": True, "title": "Lease #1 v1.0"}, answer)
        method, route, headers, body = _Recorder.seen[0]
        self.assertEqual(("POST", "/v1/state"), (method, route))
        self.assertEqual(self.SECRET, headers.get("X-Healdan-Secret"))
        self.assertTrue(headers.get("Content-Type", "").startswith("application/json"))
        self.assertEqual({"path": "/x/Copies/a.odt"}, body)

    def test_channel_without_info_is_unreachable(self):
        with self.assertRaises(logic.ChannelUnreachable):
            logic.Channel(self.support.name).call("state", {"path": "x"}, timeout=2)

    def test_channel_with_damaged_info_is_unreachable(self):
        path = os.path.join(self.support.name, "channel.json")
        for content in ("", "{", "[]", '{"port": 0, "secret": "s", "pid": 1}', '{"port": 1, "secret": "", "pid": 1}',
                        '{"port": "1", "secret": "s", "pid": 1}', '{"port": 1, "secret": "s", "pid": true}',
                        json.dumps({"port": 1, "secret": "x" * 48, "pid": os.getpid()}),
                        json.dumps({"port": 1, "secret": "ab" * 23, "pid": os.getpid()}),
                        json.dumps({"port": 1, "secret": "ab" * 24 + "\r\nX-Other: 1", "pid": os.getpid()})):
            with open(path, "w") as f:
                f.write(content)
            with self.assertRaises(logic.ChannelUnreachable, msg=content):
                logic.Channel(self.support.name).call("state", {}, timeout=2)

    def test_channel_with_dead_pid_is_unreachable(self):
        port = self.serve()
        child = subprocess.Popen([sys.executable, "-c", "pass"])
        child.wait()
        self.write_info(port, pid=child.pid)
        with self.assertRaises(logic.ChannelUnreachable):
            logic.Channel(self.support.name).call("state", {}, timeout=2)
        self.assertEqual([], _Recorder.seen)

    def test_channel_nobody_listening_is_unreachable(self):
        probe = socket.socket()
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
        probe.close()  # nothing listens there now
        self.write_info(port)
        with self.assertRaises(logic.ChannelUnreachable):
            logic.Channel(self.support.name).call("state", {}, timeout=2)

    def test_a_header_http_client_refuses_is_unreachable_and_never_quoted(self):
        port = self.serve()
        bad = "ab" * 24 + "\r\nX-Other: 1"
        channel = logic.Channel(self.support.name)
        channel.info = lambda: {"port": port, "secret": bad, "pid": os.getpid()}
        with self.assertRaises(logic.ChannelUnreachable) as raised:
            channel.call("state", {}, timeout=5)
        self.assertNotIn("ab" * 24, str(raised.exception))
        self.assertEqual([], _Recorder.seen)

    def test_channel_error_answer_is_an_error(self):
        port = self.serve()
        self.write_info(port)
        _Recorder.answers["/v1/file"] = (500, {"error": "internal-error"})
        with self.assertRaises(logic.ChannelError) as raised:
            logic.Channel(self.support.name).call("file", {}, timeout=5)
        self.assertEqual(500, raised.exception.status)
        self.assertNotIn(self.SECRET, str(raised.exception))

    def test_channel_reads_the_info_at_each_call(self):
        # A helper that restarted has a new port and secret.
        first = self.serve()
        self.write_info(first)
        _Recorder.answers["/v1/state"] = (200, {"known": False})
        channel = logic.Channel(self.support.name)
        channel.call("state", {}, timeout=5)
        second = self.serve()
        self.write_info(second)
        channel.call("state", {}, timeout=5)
        self.assertEqual(2, len(_Recorder.seen))


# --- The flows, with a fake LibreOffice ------------------------------------------------------------------------------

class FakeChannel:
    """Answers each route from a script: a dict, a list of answers in turn, or an exception to raise."""

    def __init__(self, **answers):
        self.answers = {route.replace("_", "-"): answer for route, answer in answers.items()}
        self.calls = []

    def call(self, route, body=None, timeout=None):
        self.calls.append((route, body, timeout))
        answer = self.answers.get(route, {})
        if isinstance(answer, list):
            answer = answer.pop(0)
        if isinstance(answer, BaseException):
            raise answer
        return answer

    def routes(self):
        return [route for route, _, _ in self.calls]

    def body(self, route):
        return [body for r, body, _ in self.calls if r == route][-1]


class FakeUi:
    def __init__(self, log):
        self.log = log
        self.told = []

    def wait(self, fn):
        self.log.append("wait")
        return fn()

    def later(self, fn):
        self.log.append("later")
        then = fn()
        if callable(then):
            then()

    def tell(self, doc, text, warning=True):
        self.told.append(text)


class FakeDoc:
    def __init__(self, log, url="", kind="writer", title="Untitled 1", read_only=False, modified=True,
                 has_doc_id=False):
        self.log = log
        self.model = object()
        self.url = url
        self.kind = kind
        self.title = title
        self.read_only = read_only
        self.modified = modified
        self.alive = True
        self.has_doc_id = has_doc_id
        self.doc_id = None
        self.fail = {}
        self.in_lock = False

    def _do(self, what, *args):
        self.log.append((what,) + args + (("locked",) if self.in_lock else ()))
        if what in self.fail:
            raise self.fail[what]

    def store(self):
        self._do("store")
        self.modified = False

    def write_to(self, path, filter_name):
        self._do("write_to", path, filter_name)
        with open(path, "w") as f:
            f.write("content")

    def adopt(self, path, filter_name):
        self._do("adopt", path, filter_name)
        self.url = logic.file_url(path)
        self.modified = False

    def set_title(self, title):
        self._do("set_title", title)
        self.title = title

    def set_doc_id(self, value):
        self._do("set_doc_id", value)
        if self.has_doc_id:
            self.doc_id = value
        return self.has_doc_id

    def insert_doc_id(self, value):
        self._do("insert_doc_id", value)
        self.has_doc_id = True
        self.doc_id = value if value is not None else (self.doc_id or "")

    @contextlib.contextmanager
    def locked(self):
        self.in_lock = True
        try:
            yield
        finally:
            self.in_lock = False


class FlowsTest(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.TemporaryDirectory()
        self.addCleanup(self.root.cleanup)
        self.copies = os.path.join(self.root.name, "Copies")
        self.temp = os.path.join(self.root.name, "Temp")
        os.makedirs(self.copies)
        os.makedirs(self.temp)
        self.log = []
        self.ui = FakeUi(self.log)

    def flows(self, channel):
        return logic.Flows(channel, self.copies, self.ui, QUIET)

    def copy_url(self, name):
        return logic.file_url(os.path.join(self.copies, name))

    def state(self, name, n=1234, version="1.0", read_only=False):
        stem = os.path.splitext(name)[0]
        return {"known": True, "docNumber": n, "title": stem, "docRef": "#%d v%s" % (n, version),
                "readOnly": read_only, "path": os.path.join(self.copies, name), "holdBy": None}

    def original(self, result=True):
        def run():
            self.log.append("original")
            return result
        return run

    def save_answer(self, fmt="odt", name="Lease agreement"):
        return {"action": "save", "folderId": 42, "name": name, "format": fmt, "properties": {"comments": "Draft"},
                "tempPath": os.path.join(self.temp, "71041937fe3843e5b148dfe7c639152f." + fmt)}

    # Open

    def test_open_shows_open_from_healdan(self):
        channel = FakeChannel(open_dialog={"action": "opened"})
        self.assertTrue(self.flows(channel).command(".uno:OpenFromWriter", FakeDoc(self.log), self.original()))
        self.assertEqual([("open-dialog", {"app": "LibreOffice"}, logic.WINDOW_TIMEOUT)], channel.calls)
        self.assertNotIn("original", self.log)
        self.assertIn("wait", self.log)

    def test_open_from_the_start_center(self):
        channel = FakeChannel(open_dialog={"action": "cancel"})
        self.assertFalse(self.flows(channel).command(".uno:Open", None, self.original()))
        self.assertEqual(["open-dialog"], channel.routes())
        self.assertNotIn("original", self.log)

    def test_open_local_runs_libreoffices_own_box_at_once(self):
        channel = FakeChannel(open_dialog={"action": "local"})
        self.assertTrue(self.flows(channel).command(".uno:Open", None, self.original()))
        self.assertEqual(["wait", "original"], self.log)

    def test_channel_unreachable_falls_back(self):
        channel = FakeChannel(open_dialog=[logic.ChannelUnreachable("gone"), logic.ChannelUnreachable("gone"),
                                           {"action": "opened"}, logic.ChannelUnreachable("gone")])
        flows = self.flows(channel)
        self.assertTrue(flows.command(".uno:Open", None, self.original()))
        self.assertEqual(["wait", "original"], self.log)
        self.assertEqual([logic.HELPER_DOWN_BOX], self.ui.told)
        # Told once while it stays down...
        flows.command(".uno:OpenFromCalc", None, self.original())
        self.assertEqual(2, self.log.count("original"))
        self.assertEqual(1, len(self.ui.told))
        # ...and again once it has been back.
        flows.command(".uno:Open", None, self.original())
        flows.command(".uno:Open", None, self.original())
        self.assertEqual(2, len(self.ui.told))
        self.assertEqual(3, self.log.count("original"))

    def test_channel_error_falls_back_quietly(self):
        channel = FakeChannel(open_dialog=logic.ChannelError(500, "internal-error"))
        self.assertTrue(self.flows(channel).command(".uno:Open", None, self.original()))
        self.assertIn("original", self.log)
        self.assertEqual([], self.ui.told)

    # Save to Healdan

    def test_save_new_document_files_it(self):
        answer = self.save_answer()
        state = self.state("Lease agreement #1234 v1.0.odt")
        channel = FakeChannel(save_new=answer, file=state)
        doc = FakeDoc(self.log, has_doc_id=True)
        self.assertTrue(self.flows(channel).command(".uno:Save", doc, self.original()))
        self.assertEqual(["save-new", "file"], channel.routes())
        self.assertEqual({"app": "LibreOffice", "name": "Untitled 1", "formats": ["odt", "docx"], "path": None},
                         channel.body("save-new"))
        self.assertEqual(logic.WINDOW_TIMEOUT, channel.calls[0][2])
        self.assertEqual({"tempPath": answer["tempPath"], "folderId": 42, "name": "Lease agreement", "format": "odt",
                          "properties": {"comments": "Draft"}, "app": "LibreOffice", "replaces": None},
                         channel.body("file"))
        self.assertEqual(logic.FILE_TIMEOUT, channel.calls[1][2])
        steps = [entry for entry in self.log if isinstance(entry, tuple)]
        self.assertEqual([
            ("write_to", answer["tempPath"], "writer8"),
            ("set_doc_id", "#1234 v1.0", "locked"),
            ("adopt", state["path"], "writer8", "locked"),
            ("set_title", "Lease agreement #1234 v1.0"),
        ], steps)
        self.assertNotIn("original", self.log)
        self.assertEqual("#1234 v1.0", doc.doc_id)
        self.assertTrue(logic.is_copy(doc.url, self.copies))

    def test_save_new_writes_the_chosen_format(self):
        answer = self.save_answer("pptx")
        state = self.state("Pitch #9 v1.0.pptx", n=9)
        channel = FakeChannel(save_new=answer, file=state)
        doc = FakeDoc(self.log, kind="impress", title="Untitled 2")
        self.assertTrue(self.flows(channel).command(".uno:SaveAs", doc, self.original()))
        self.assertEqual(["odp", "pptx"], channel.body("save-new")["formats"])
        self.assertIn(("write_to", answer["tempPath"], "Impress MS PowerPoint 2007 XML"), self.log)
        self.assertIn(("adopt", state["path"], "Impress MS PowerPoint 2007 XML", "locked"), self.log)

    def test_save_new_names_a_local_file_by_its_file_name(self):
        channel = FakeChannel(save_new={"action": "cancel"})
        doc = FakeDoc(self.log, url="file:///Users/rob/Board%20minutes.docx", title="Board minutes.docx", modified=False)
        self.assertFalse(self.flows(channel).command(".uno:SaveAs", doc, self.original()))
        self.assertEqual("Board minutes", channel.body("save-new")["name"])
        self.assertNotIn("original", self.log)

    def test_save_new_local_runs_libreoffices_own_box(self):
        channel = FakeChannel(save_new={"action": "local"})
        self.assertTrue(self.flows(channel).command(".uno:Save", FakeDoc(self.log), self.original()))
        self.assertEqual(["save-new"], channel.routes())
        self.assertEqual(["wait", "original"], self.log)

    def test_save_new_cancel_does_nothing(self):
        channel = FakeChannel(save_new={"action": "cancel"})
        self.assertFalse(self.flows(channel).command(".uno:Save", FakeDoc(self.log), self.original()))
        self.assertEqual(["wait"], self.log)

    def test_save_new_with_helper_down_runs_libreoffices_save_as(self):
        channel = FakeChannel(save_new=logic.ChannelUnreachable("gone"))
        self.assertTrue(self.flows(channel).command(".uno:Save", FakeDoc(self.log), self.original()))
        self.assertEqual(["wait", "original"], self.log)
        self.assertEqual([logic.HELPER_DOWN_BOX], self.ui.told)

    def test_save_new_after_the_document_closed_does_nothing(self):
        doc = FakeDoc(self.log)
        channel = FakeChannel(save_new=self.save_answer())

        def closing(route, body=None, timeout=None):
            doc.alive = False
            return FakeChannel.call(channel, route, body, timeout)

        channel.call = closing
        self.assertFalse(self.flows(channel).command(".uno:Save", doc, self.original()))
        self.assertEqual(["save-new"], channel.routes())
        self.assertNotIn("original", self.log)

    def test_save_new_write_failure_removes_temp_and_falls_back(self):
        answer = self.save_answer()
        channel = FakeChannel(save_new=answer)
        doc = FakeDoc(self.log)
        doc.fail["write_to"] = IOError("disk full")
        open(answer["tempPath"], "w").close()  # storeToURL may leave part of a file
        self.assertTrue(self.flows(channel).command(".uno:Save", doc, self.original()))
        self.assertFalse(os.path.exists(answer["tempPath"]))
        self.assertEqual(["save-new"], channel.routes())
        self.assertIn("original", self.log)
        self.assertEqual(1, len(self.ui.told))

    def test_save_new_filing_refused_falls_back(self):
        # The helper has told the user and deleted the temporary file.
        answer = self.save_answer()
        channel = FakeChannel(save_new=answer, file=logic.ChannelError(500, "internal-error"))
        doc = FakeDoc(self.log)
        self.assertTrue(self.flows(channel).command(".uno:Save", doc, self.original()))
        self.assertIn("original", self.log)
        self.assertFalse(any(entry[0] == "adopt" for entry in self.log if isinstance(entry, tuple)))
        self.assertEqual([], self.ui.told)

    def test_save_new_filing_unreachable_removes_temp(self):
        answer = self.save_answer()
        channel = FakeChannel(save_new=answer, file=logic.ChannelUnreachable("gone"))
        self.assertTrue(self.flows(channel).command(".uno:Save", FakeDoc(self.log), self.original()))
        self.assertFalse(os.path.exists(answer["tempPath"]))
        self.assertIn("original", self.log)
        self.assertEqual([logic.HELPER_DOWN_BOX], self.ui.told)

    def test_libreoffices_box_and_notices_come_after_the_lock(self):
        for failure in (logic.ChannelUnreachable("gone"), logic.ChannelError(500, "internal-error")):
            doc = FakeDoc(self.log)
            during = []
            self.ui.tell = lambda d, text, warning=True: during.append(("tell", doc.in_lock))
            channel = FakeChannel(save_new=self.save_answer(), file=failure)

            def box():
                during.append(("box", doc.in_lock))
                return True

            self.assertTrue(logic.Flows(channel, self.copies, self.ui, QUIET).command(".uno:Save", doc, box))
            self.assertIn(("box", False), during)
            self.assertNotIn(("box", True), during)
            self.assertNotIn(("tell", True), during)
        doc = FakeDoc(self.log)
        doc.fail["adopt"] = IOError("no room")
        told = []
        self.ui.tell = lambda d, text, warning=True: told.append(doc.in_lock)
        channel = FakeChannel(save_new=self.save_answer(), file=self.state("Lease agreement #1234 v1.0.odt"))
        self.assertFalse(logic.Flows(channel, self.copies, self.ui, QUIET).command(".uno:Save", doc, self.original()))
        self.assertEqual([False], told)

    def test_a_surprise_before_any_window_runs_libreoffices_own_command(self):
        class Broken(FakeDoc):
            @property
            def kind(self):
                raise RuntimeError("boom")

            @kind.setter
            def kind(self, value):
                pass

        channel = FakeChannel()
        doc = Broken(self.log)
        self.assertEqual("own", self.flows(channel).command(".uno:Save", doc, self.original("own")))
        self.assertEqual(["original"], self.log)
        self.assertEqual([logic.FAILED_BEFORE.format(error="boom")], self.ui.told)
        self.assertEqual([], channel.calls)

    def test_a_surprise_after_a_window_is_told_and_runs_nothing_more(self):
        class Breaks(FakeDoc):
            broken = False

            @property
            def alive(self):
                if self.broken:
                    raise RuntimeError("gone wrong")
                return True

            @alive.setter
            def alive(self, value):
                pass

        doc = Breaks(self.log)
        channel = FakeChannel(save_new=self.save_answer())
        answer = channel.call

        def breaking(route, body=None, timeout=None):
            doc.broken = True  # after the helper's window
            return answer(route, body, timeout)

        channel.call = breaking
        self.assertFalse(self.flows(channel).command(".uno:Save", doc, self.original()))
        self.assertNotIn("original", self.log)
        self.assertEqual([logic.FAILED_AFTER.format(error="gone wrong")], self.ui.told)

    def test_libreoffices_own_command_failing_isnt_run_twice(self):
        runs = []

        def failing():
            runs.append(1)
            raise RuntimeError("LibreOffice said no")

        with self.assertRaises(RuntimeError):
            self.flows(FakeChannel()).command(".uno:Save", FakeDoc(self.log, url="file:///Users/rob/a.odt"), failing)
        self.assertEqual([1], runs)
        self.assertEqual([], self.ui.told)

    def test_buttons(self):
        channel = FakeChannel(open_dialog={"action": "cancel"}, save_new={"action": "cancel"},
                              properties={"action": "cancel"}, new_version={}, saved={"known": False})
        flows = self.flows(channel)
        copy = FakeDoc(self.log, url=self.copy_url("Lease #1 v1.0.odt"), title="Lease #1 v1.0", modified=False)
        self.assertFalse(flows.button("OpenFromHealdan", None, self.original(), self.original()))
        self.assertFalse(flows.button("SaveToHealdan", None, self.original(), self.original()))
        self.assertFalse(flows.button("SaveAsNewDocument", copy, self.original(), self.original()))
        self.assertTrue(flows.button("SaveAsNewVersion", copy, self.original(), self.original()))
        self.assertFalse(flows.button("Properties", copy, self.original(), self.original()))
        self.assertTrue(flows.button("InsertDocId", copy, self.original(), self.original()))
        self.assertFalse(flows.button("Nonsense", copy, self.original(), self.original()))
        self.assertEqual(["open-dialog", "save-new", "new-version", "saved", "properties"], channel.routes())
        self.assertEqual("#1 v1.0", copy.doc_id)

    def test_a_surprise_in_a_button(self):
        doc = FakeDoc(self.log)
        doc.fail["insert_doc_id"] = RuntimeError("no footer")
        boxes = []
        flows = self.flows(FakeChannel())
        self.assertFalse(flows.button("InsertDocId", doc, lambda: boxes.append("open"), lambda: boxes.append("save as")))
        self.assertEqual([], boxes)  # nothing of LibreOffice's to run instead
        self.assertEqual([logic.FAILED_AFTER.format(error="no footer")], self.ui.told)

        class Broken(FakeDoc):
            kind = property(lambda d: (_ for _ in ()).throw(RuntimeError("boom")), lambda d, v: None)

        self.ui.told.clear()
        flows.button("SaveToHealdan", Broken(self.log), lambda: boxes.append("open"), lambda: boxes.append("save as"))
        self.assertEqual(["save as"], boxes)
        self.assertEqual([logic.FAILED_BEFORE.format(error="boom")], self.ui.told)

    def test_a_filing_with_no_copy_is_told_and_never_filed_twice(self):
        channel = FakeChannel(save_new=self.save_answer(), file={"known": True, "docNumber": 1234, "docRef": "#1234 v1.0"})
        doc = FakeDoc(self.log)
        flows = self.flows(channel)
        self.assertFalse(flows.command(".uno:Save", doc, self.original()))
        self.assertEqual([logic.NO_COPY.format(ref="#1234 v1.0", it="#1234 v1.0")], self.ui.told)
        self.assertNotIn("original", self.log)
        # The next Save on that window keeps the changes on this computer instead of filing it again.
        self.assertTrue(flows.command(".uno:Save", doc, self.original()))
        self.assertTrue(flows.save_to_healdan(doc, self.original()))
        self.assertEqual(["save-new", "file"], channel.routes())
        self.assertEqual(2, self.log.count("original"))
        self.assertIn("already filed in Healdan as #1234 v1.0", self.ui.told[1])
        # Another window is filed as usual, and so is this one's document once its window has closed.
        flows.command(".uno:Save", FakeDoc(self.log), self.original())
        self.assertEqual(["save-new", "file", "save-new", "file"], channel.routes())
        flows.window_closed(doc.model)
        channel.answers["save-new"] = {"action": "cancel"}
        flows.command(".uno:Save", doc, self.original())
        self.assertEqual(["save-new", "file", "save-new", "file", "save-new"], channel.routes())

    def test_a_filing_with_an_unknown_state_names_no_number(self):
        channel = FakeChannel(save_new=self.save_answer(), file={"known": False})
        doc = FakeDoc(self.log)
        flows = self.flows(channel)
        self.assertFalse(flows.command(".uno:Save", doc, self.original()))
        self.assertEqual([logic.NO_COPY.format(ref="The document", it="it")], self.ui.told)
        flows.command(".uno:SaveAs", doc, self.original())
        self.assertEqual(["save-new", "file"], channel.routes())
        self.assertIn("already filed in Healdan, but", self.ui.told[1])

    def test_a_window_that_couldnt_adopt_its_copy_isnt_filed_twice(self):
        channel = FakeChannel(save_new=self.save_answer(), file=self.state("Lease agreement #1234 v1.0.odt"))
        doc = FakeDoc(self.log)
        doc.fail["adopt"] = IOError("no room")
        flows = self.flows(channel)
        flows.command(".uno:Save", doc, self.original())
        flows.command(".uno:Save", doc, self.original())
        self.assertEqual(["save-new", "file"], channel.routes())

    def test_a_title_that_cant_be_set_still_counts_as_filed(self):
        state = self.state("Lease agreement #1234 v1.0.odt")
        channel = FakeChannel(save_new=self.save_answer(), file=state)
        doc = FakeDoc(self.log)
        doc.fail["set_title"] = RuntimeError("no title")
        self.assertTrue(self.flows(channel).command(".uno:Save", doc, self.original()))
        self.assertEqual([], self.ui.told)
        self.assertNotIn("original", self.log)
        self.assertTrue(logic.is_copy(doc.url, self.copies))

    def test_save_new_adopt_failure_is_told(self):
        channel = FakeChannel(save_new=self.save_answer(), file=self.state("Lease agreement #1234 v1.0.odt"))
        doc = FakeDoc(self.log)
        doc.fail["adopt"] = IOError("no room")
        self.assertFalse(self.flows(channel).command(".uno:Save", doc, self.original()))
        self.assertNotIn("original", self.log)
        self.assertEqual(1, len(self.ui.told))
        self.assertIn("#1234", self.ui.told[0])

    def test_save_new_answer_without_a_format_falls_back(self):
        channel = FakeChannel(save_new={"action": "save", "folderId": 1, "name": "x", "format": "pdf", "tempPath": "/t/x.pdf"})
        self.assertTrue(self.flows(channel).command(".uno:Save", FakeDoc(self.log), self.original()))
        self.assertEqual(["save-new"], channel.routes())
        self.assertIn("original", self.log)

    def test_save_as_new_document_sends_the_copy(self):
        copy = os.path.join(self.copies, "Lease agreement #1234 v2.1.docx")
        answer = self.save_answer("docx")
        state = self.state("Lease agreement #1240 v1.0.docx", n=1240)
        channel = FakeChannel(save_new=answer, file=state)
        doc = FakeDoc(self.log, url=self.copy_url("Lease agreement #1234 v2.1.docx"),
                      title="Lease agreement #1234 v2.1", modified=False)
        self.assertTrue(self.flows(channel).command(".uno:SaveAs", doc, self.original()))
        self.assertEqual(copy, channel.body("save-new")["path"])
        self.assertEqual(copy, channel.body("file")["replaces"])
        self.assertIn(("adopt", state["path"], "MS Word 2007 XML", "locked"), self.log)
        self.assertEqual("Lease agreement #1240 v1.0", doc.title)

    def test_save_on_a_read_only_copy_is_save_as_new_document(self):
        channel = FakeChannel(save_new={"action": "cancel"})
        doc = FakeDoc(self.log, url=self.copy_url("Budget #7 v1.0 (read-only).ods"), kind="calc", read_only=True)
        self.flows(channel).command(".uno:Save", doc, self.original())
        self.assertEqual(os.path.join(self.copies, "Budget #7 v1.0 (read-only).ods"), channel.body("save-new")["path"])
        self.assertEqual(["ods", "xlsx"], channel.body("save-new")["formats"])

    def test_draw_saves_stay_libreoffices(self):
        channel = FakeChannel()
        self.assertTrue(self.flows(channel).command(".uno:Save", FakeDoc(self.log, kind="draw"), self.original()))
        self.assertEqual([], channel.calls)
        self.assertEqual(["original"], self.log)

    # Saving a copy

    def test_save_on_copy_stores_and_follows_the_confirmation(self):
        state = self.state("Lease agreement #1234 v2.2.docx", version="2.2")
        state["path"] = os.path.join(self.copies, "Lease agreement #1234 v2.1.docx")
        channel = FakeChannel(saved=state)
        doc = FakeDoc(self.log, url=self.copy_url("Lease agreement #1234 v2.1.docx"), has_doc_id=True)
        self.assertTrue(self.flows(channel).command(".uno:Save", doc, self.original()))
        self.assertEqual(("store",), self.log[0])
        self.assertEqual([("saved", {"path": state["path"]}, logic.SAVED_TIMEOUT)], channel.calls)
        self.assertIn("later", self.log)
        self.assertNotIn("wait", self.log)
        self.assertEqual("Lease agreement #1234 v2.2", doc.title)
        self.assertEqual("#1234 v2.2", doc.doc_id)
        self.assertNotIn("original", self.log)

    def test_save_on_copy_with_the_helper_down_still_stores(self):
        channel = FakeChannel(saved=logic.ChannelUnreachable("gone"))
        doc = FakeDoc(self.log, url=self.copy_url("Lease #1 v1.0.odt"))
        self.assertTrue(self.flows(channel).command(".uno:Save", doc, self.original()))
        self.assertEqual(("store",), self.log[0])
        self.assertEqual([logic.HELPER_DOWN_SAVE], self.ui.told)
        self.assertNotIn("original", self.log)

    def test_save_on_copy_that_fails_is_told(self):
        channel = FakeChannel()
        doc = FakeDoc(self.log, url=self.copy_url("Lease #1 v1.0.odt"))
        doc.fail["store"] = IOError("read-only file system")
        self.assertFalse(self.flows(channel).command(".uno:Save", doc, self.original()))
        self.assertEqual([], channel.calls)
        self.assertEqual(1, len(self.ui.told))

    def test_a_confirmation_for_another_file_changes_nothing(self):
        state = self.state("Other #2 v1.1.odt", n=2, version="1.1")
        channel = FakeChannel(saved=state)
        doc = FakeDoc(self.log, url=self.copy_url("Lease #1 v1.0.odt"), title="Lease #1 v1.0")
        self.flows(channel).command(".uno:Save", doc, self.original())
        self.assertEqual("Lease #1 v1.0", doc.title)

    def test_save_on_local_file_runs_libreoffices_save(self):
        channel = FakeChannel()
        doc = FakeDoc(self.log, url="file:///Users/rob/Documents/x%20%231%20v1.0.odt")
        self.assertTrue(self.flows(channel).command(".uno:Save", doc, self.original()))
        self.assertEqual(["original"], self.log)
        self.assertEqual([], channel.calls)

    # The buttons

    def test_save_to_healdan_on_a_copy_saves_it(self):
        channel = FakeChannel(saved={"known": False})
        doc = FakeDoc(self.log, url=self.copy_url("Lease #1 v1.0.odt"))
        self.assertTrue(self.flows(channel).save_to_healdan(doc, self.original()))
        self.assertEqual(("store",), self.log[0])
        self.assertEqual(["saved"], channel.routes())

    def test_save_to_healdan_on_a_local_file_files_it(self):
        channel = FakeChannel(save_new={"action": "cancel"})
        doc = FakeDoc(self.log, url="file:///Users/rob/Documents/Memo.odt", title="Memo.odt")
        self.flows(channel).save_to_healdan(doc, self.original())
        self.assertEqual({"app": "LibreOffice", "name": "Memo", "formats": ["odt", "docx"], "path": None},
                         channel.body("save-new"))

    def test_open_from_healdan_button(self):
        channel = FakeChannel(open_dialog={"action": "local"})
        self.assertTrue(self.flows(channel).open_from_healdan(None, self.original()))
        self.assertIn("original", self.log)

    def test_new_version_marks_then_stores_unsaved_changes(self):
        path = os.path.join(self.copies, "Lease #1 v1.2.odt")
        channel = FakeChannel(new_version={}, saved=self.state("Lease #1 v2.0.odt", n=1, version="2.0"))
        doc = FakeDoc(self.log, url=self.copy_url("Lease #1 v1.2.odt"), modified=True)
        self.assertTrue(self.flows(channel).save_as_new_version(doc, self.original()))
        self.assertEqual(["new-version", "saved"], channel.routes())
        self.assertEqual({"path": path}, channel.body("new-version"))
        self.assertEqual(["wait", ("store",), "later"], self.log[:3])

    def test_new_version_without_changes_sends_the_file_as_it_is(self):
        channel = FakeChannel(new_version={}, saved={"known": False})
        doc = FakeDoc(self.log, url=self.copy_url("Lease #1 v1.2.odt"), modified=False)
        self.assertTrue(self.flows(channel).save_as_new_version(doc, self.original()))
        self.assertEqual(["new-version", "saved"], channel.routes())
        self.assertNotIn(("store",), self.log)

    def test_new_version_with_the_helper_down_keeps_the_changes(self):
        channel = FakeChannel(new_version=logic.ChannelUnreachable("gone"))
        doc = FakeDoc(self.log, url=self.copy_url("Lease #1 v1.2.odt"), modified=True)
        self.assertFalse(self.flows(channel).save_as_new_version(doc, self.original()))
        self.assertIn(("store",), self.log)
        self.assertEqual([logic.HELPER_DOWN_SAVE], self.ui.told)

    def test_new_version_on_a_read_only_copy_is_refused(self):
        channel = FakeChannel()
        doc = FakeDoc(self.log, url=self.copy_url("Lease #1 v1.2 (read-only).odt"), read_only=True)
        self.assertFalse(self.flows(channel).save_as_new_version(doc, self.original()))
        self.assertEqual([], channel.calls)
        self.assertEqual([logic.READ_ONLY_VERSION], self.ui.told)

    def test_new_version_on_a_new_document_saves_it_to_healdan(self):
        channel = FakeChannel(save_new={"action": "cancel"})
        self.flows(channel).save_as_new_version(FakeDoc(self.log), self.original())
        self.assertEqual(["save-new"], channel.routes())

    def test_save_as_new_document_button(self):
        channel = FakeChannel(save_new={"action": "cancel"})
        doc = FakeDoc(self.log, url=self.copy_url("Lease #1 v1.2.odt"))
        self.flows(channel).save_as_new_document(doc, self.original())
        self.assertEqual(os.path.join(self.copies, "Lease #1 v1.2.odt"), channel.body("save-new")["path"])

    def test_properties_on_a_copy(self):
        path = os.path.join(self.copies, "Lease #1 v1.2.odt")
        channel = FakeChannel(properties={"action": "saved"}, state=self.state("Lease #1 v1.2.odt", n=1, version="1.2"))
        doc = FakeDoc(self.log, url=self.copy_url("Lease #1 v1.2.odt"), title="Lease #1 v1.2.odt")
        self.assertTrue(self.flows(channel).properties(doc, self.original()))
        self.assertEqual([("properties", {"path": path}, logic.WINDOW_TIMEOUT), ("state", {"path": path}, logic.QUICK_TIMEOUT)],
                         channel.calls)
        self.assertEqual("Lease #1 v1.2", doc.title)

    def test_properties_on_a_document_not_in_healdan_runs_save_to_healdan(self):
        channel = FakeChannel(properties={"action": "file"}, save_new={"action": "cancel"})
        self.flows(channel).properties(FakeDoc(self.log), self.original())
        self.assertEqual(["properties", "save-new"], channel.routes())
        self.assertEqual({"path": None}, channel.body("properties"))

    def test_properties_with_the_helper_down_is_told(self):
        channel = FakeChannel(properties=logic.ChannelUnreachable("gone"))
        self.assertFalse(self.flows(channel).properties(FakeDoc(self.log), self.original()))
        self.assertEqual([logic.HELPER_DOWN_OTHER], self.ui.told)
        self.assertNotIn("original", self.log)

    def test_insert_doc_id_on_a_copy_uses_its_latest_title(self):
        doc = FakeDoc(self.log, url=self.copy_url("Lease #1 v1.0.odt"), title="Lease #1 v1.3")
        self.assertTrue(self.flows(FakeChannel()).insert_doc_id(doc))
        self.assertEqual("#1 v1.3", doc.doc_id)
        self.assertEqual([], self.ui.told)

    def test_insert_doc_id_after_a_confirmation_uses_it(self):
        state = self.state("Lease #1 v1.4.odt", n=1, version="1.4")
        state["path"] = os.path.join(self.copies, "Lease #1 v1.0.odt")
        doc = FakeDoc(self.log, url=self.copy_url("Lease #1 v1.0.odt"), title="Lease #1 v1.0.odt")
        flows = self.flows(FakeChannel(saved=state))
        flows.command(".uno:Save", doc, self.original())
        doc.title = "renamed by hand"
        flows.insert_doc_id(doc)
        self.assertEqual("#1 v1.4", doc.doc_id)

    def test_insert_doc_id_on_a_document_not_in_healdan(self):
        doc = FakeDoc(self.log)
        self.assertTrue(self.flows(FakeChannel()).insert_doc_id(doc))
        self.assertIn(("insert_doc_id", None), self.log)
        self.assertEqual([logic.NOT_FILED_DOC_ID], self.ui.told)

    def test_insert_doc_id_is_writers(self):
        doc = FakeDoc(self.log, kind="calc", url=self.copy_url("Budget #7 v1.0.ods"))
        self.assertFalse(self.flows(FakeChannel()).insert_doc_id(doc))
        self.assertEqual([], self.log)

    # Events

    def test_a_copy_that_opens_gets_its_title(self):
        path = os.path.join(self.copies, "Lease agreement #1234 v2.1.odt")
        channel = FakeChannel(state=self.state("Lease agreement #1234 v2.1.odt", version="2.1"))
        doc = FakeDoc(self.log, url=self.copy_url("Lease agreement #1234 v2.1.odt"),
                      title="Lease agreement #1234 v2.1.odt", modified=False)
        self.flows(channel).loaded(doc)
        self.assertEqual([("state", {"path": path}, logic.QUICK_TIMEOUT)], channel.calls)
        self.assertEqual("Lease agreement #1234 v2.1", doc.title)

    def test_a_local_file_that_opens_is_left_alone(self):
        channel = FakeChannel()
        self.flows(channel).loaded(FakeDoc(self.log, url="file:///Users/rob/a%20%231%20v1.0.odt"))
        self.flows(channel).loaded(FakeDoc(self.log))
        self.assertEqual([], channel.calls)

    def test_an_unknown_copy_keeps_its_title(self):
        channel = FakeChannel(state={"known": False})
        doc = FakeDoc(self.log, url=self.copy_url("Lease #1 v1.0.odt"), title="Lease #1 v1.0.odt")
        self.flows(channel).loaded(doc)
        self.assertEqual("Lease #1 v1.0.odt", doc.title)

    def test_a_copy_that_closes_tells_the_helper(self):
        url = self.copy_url("Lease #1 v1.0.odt")
        channel = FakeChannel(closed={})
        self.flows(channel).closed(url, still_open=False)
        self.assertEqual([("closed", {"path": os.path.join(self.copies, "Lease #1 v1.0.odt")}, logic.QUICK_TIMEOUT)],
                         channel.calls)
        self.assertEqual(["later"], self.log)

    def test_a_copy_still_open_elsewhere_isnt_closed(self):
        # File > Reload unloads the old document after loading the new one.
        channel = FakeChannel()
        self.flows(channel).closed(self.copy_url("Lease #1 v1.0.odt"), still_open=True)
        self.assertEqual([], channel.calls)

    def test_a_local_file_that_closes_tells_nobody(self):
        channel = FakeChannel()
        flows = self.flows(channel)
        flows.closed("file:///Users/rob/a.odt", still_open=False)
        flows.closed("", still_open=False)
        self.assertEqual([], channel.calls)

    def test_a_close_with_the_helper_down_is_quiet(self):
        channel = FakeChannel(closed=logic.ChannelUnreachable("gone"))
        self.flows(channel).closed(self.copy_url("Lease #1 v1.0.odt"), still_open=False)
        self.assertEqual([], self.ui.told)


if __name__ == "__main__":
    unittest.main()
