"""Checks the healdan.oxt that build.sh makes: it holds what its manifest declares, its XML parses, and its parts agree.

LibreOffice says little when an extension's XML is wrong (a menu is missing, a job never runs), so these catch it here.
"""

import os
import pathlib
import re
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
import zipfile

HERE = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(HERE))

import healdan_logic as logic  # noqa: E402

OOR = "{http://openoffice.org/2001/registry}"
MANIFEST = "{http://openoffice.org/2001/manifest}"
DESCRIPTION = "{http://openoffice.org/extensions/description/2006}"
LO = "{http://libreoffice.org/extensions/description/2011}"
XLINK = "{http://www.w3.org/1999/xlink}"

DOCUMENTS = ("com.sun.star.text.TextDocument", "com.sun.star.sheet.SpreadsheetDocument",
             "com.sun.star.presentation.PresentationDocument")
START_CENTER = "com.sun.star.frame.StartModule"
EVENTS = ("OnStartApp", "onFirstVisibleTask", "OnNew", "OnLoad", "OnViewCreated", "OnUnload", "OnCloseApp")


def node(element, *names):
    """The registry node under element at the path of names, or None."""
    for name in names:
        element = next((child for child in element if child.tag == "node" and child.get(OOR + "name") == name), None)
        if element is None:
            return None
    return element


def nodes(element):
    return [child for child in element if child.tag == "node"]


def prop(element, name):
    """A registry node's property's value, or None."""
    for child in element:
        if child.tag == "prop" and child.get(OOR + "name") == name:
            value = child.find("value")
            return value.text if value is not None else None
    return None


class PackageTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.oxt = os.path.join(cls.tmp.name, "healdan.oxt")
        subprocess.run(["sh", str(HERE / "build.sh"), cls.oxt], check=True, stdout=subprocess.DEVNULL)
        cls.zip = zipfile.ZipFile(cls.oxt)
        cls.files = {name for name in cls.zip.namelist() if not name.endswith("/")}

    @classmethod
    def tearDownClass(cls):
        cls.zip.close()
        cls.tmp.cleanup()

    def xml(self, name):
        return ET.fromstring(self.zip.read(name))

    def source(self):
        return self.zip.read("healdan.py").decode("utf-8")

    def test_every_xml_file_parses(self):
        for name in self.files:
            if name.endswith((".xml", ".xcu")):
                self.xml(name)

    def test_the_manifest_declares_the_files(self):
        entries = {entry.get(MANIFEST + "full-path"): entry.get(MANIFEST + "media-type")
                   for entry in self.xml("META-INF/manifest.xml")}
        self.assertEqual("application/vnd.sun.star.uno-component;type=Python", entries.pop("healdan.py"))
        xcus = {name for name in self.files if name.endswith(".xcu")}
        self.assertEqual({name: "application/vnd.sun.star.configuration-data" for name in xcus}, entries)
        self.assertEqual({"META-INF/manifest.xml", "description.xml", "description-en.txt", "healdan.py",
                          "pythonpath/healdan_logic.py"} | xcus, self.files)

    def test_the_description(self):
        description = self.xml("description.xml")
        self.assertEqual("org.healdan.libreoffice", description.find(DESCRIPTION + "identifier").get("value"))
        self.assertRegex(description.find(DESCRIPTION + "version").get("value"), r"^\d+\.\d+\.\d+$")
        minimal = description.find(DESCRIPTION + "dependencies/" + LO + "LibreOffice-minimal-version")
        self.assertEqual("26.2", minimal.get("value"))
        self.assertEqual("Healdan Project", description.find(DESCRIPTION + "publisher/" + DESCRIPTION + "name").text)
        text = description.find(DESCRIPTION + "extension-description/" + DESCRIPTION + "src").get(XLINK + "href")
        self.assertIn(text, self.files)

    def test_the_python_compiles_and_is_ascii(self):
        for name in ("healdan.py", "pythonpath/healdan_logic.py"):
            data = self.zip.read(name)
            data.decode("ascii")
            compile(data.decode("utf-8"), name, "exec")
        self.assertEqual((HERE / "healdan_logic.py").read_bytes(), self.zip.read("pythonpath/healdan_logic.py"))

    def test_the_job_runs_on_every_event(self):
        job = re.search(r'^JOB = "([^"]+)"', self.source(), re.M).group(1)
        jobs = self.xml("Jobs.xcu")
        self.assertEqual(job, prop(node(jobs, "Jobs", job), "Service"))
        self.assertEqual(sorted(EVENTS), sorted(event.get(OOR + "name") for event in nodes(node(jobs, "Events"))))
        for event in EVENTS:
            self.assertIsNotNone(node(jobs, "Events", event, "JobList", job), event)
        self.assertIn("addImplementation(Job, JOB, (JOB,", self.source())

    def test_the_protocol_handler_serves_the_commands(self):
        handler = re.search(r'^HANDLER = "([^"]+)"', self.source(), re.M).group(1)
        entry = node(self.xml("ProtocolHandler.xcu"), "HandlerSet", handler)
        self.assertEqual(logic.PROTOCOL + "*", entry.find("prop/value").text)
        self.assertIn('addImplementation(Handler, HANDLER, (HANDLER, "com.sun.star.frame.ProtocolHandler"))',
                      self.source())

    def test_the_menu(self):
        menu = nodes(node(self.xml("Addons.xcu"), "AddonUI", "OfficeMenuBar"))
        self.assertEqual(1, len(menu))
        self.assertEqual(set(DOCUMENTS + (START_CENTER,)), set(prop(menu[0], "Context").split(",")))
        items = [(prop(item, "URL"), set((prop(item, "Context") or "").split(",")), prop(item, "Title"))
                 for item in nodes(node(menu[0], "Submenu"))]
        commands = [url for url, _, _ in items if url != "private:separator"]
        self.assertEqual([logic.PROTOCOL + name for name in logic.BUTTONS], commands)
        for url, context, title in items:
            if url == logic.PROTOCOL + "OpenFromHealdan":
                self.assertEqual(set(DOCUMENTS + (START_CENTER,)), context)  # the Start Center's one item
            elif url == logic.PROTOCOL + "InsertDocId":
                self.assertEqual({DOCUMENTS[0]}, context)
            elif url != "private:separator":
                self.assertEqual(set(DOCUMENTS), context, url)
            if url != "private:separator":
                self.assertTrue(title, url)

    def test_the_toolbar(self):
        addons = self.xml("Addons.xcu")
        toolbars = nodes(node(addons, "AddonUI", "OfficeToolBar"))
        self.assertEqual(1, len(toolbars))
        urls = [prop(item, "URL") for item in nodes(toolbars[0])]
        self.assertEqual([logic.PROTOCOL + name for name in logic.BUTTONS], urls)
        for item in nodes(toolbars[0]):
            self.assertNotIn(START_CENTER, prop(item, "Context"))
        resource = "private:resource/toolbar/addon_" + toolbars[0].get(OOR + "name")
        for app in ("Writer", "Calc", "Impress"):
            state = self.xml(app + "WindowState.xcu")
            self.assertEqual(app + "WindowState", state.get(OOR + "name"))
            self.assertEqual("Healdan", prop(node(state, "UIElements", "States", resource), "UIName"), app)


if __name__ == "__main__":
    unittest.main()
