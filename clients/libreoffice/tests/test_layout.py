"""The extension's tests folder, until Task 18 brings the extension itself.

unittest fails a run that finds no tests (Python 3.12 and later exit 5, "NO TESTS RAN"), so the Clients workflow's
extension job needs one. This one checks what Task 18's tests rely on: that this folder imports as a package, and
that it sits in clients/libreoffice, where healdan_logic.py goes. Task 18's test_logic.py replaces it.
"""

import importlib.util
import pathlib
import unittest

HERE = pathlib.Path(__file__).resolve().parent


class LayoutTest(unittest.TestCase):
    def test_tests_folder_imports_as_a_package(self):
        spec = importlib.util.spec_from_file_location("tests", HERE / "__init__.py")
        self.assertIsNotNone(spec)
        package = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(package)
        self.assertEqual("tests", package.__name__)

    def test_tests_folder_is_in_the_extension_folder(self):
        self.assertEqual(("clients", "libreoffice", "tests"), HERE.parts[-3:])


if __name__ == "__main__":
    unittest.main()
