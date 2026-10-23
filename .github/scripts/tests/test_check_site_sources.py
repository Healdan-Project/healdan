import contextlib
import io
import pathlib
import tempfile
import textwrap
import unittest

import _scripts

checker = _scripts.load("check-site-sources")


class CheckSiteSourcesTest(unittest.TestCase):

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.site = pathlib.Path(self._tmp.name)
        (self.site / "content").mkdir()

    def tearDown(self):
        self._tmp.cleanup()

    def page(self, name, text):
        path = self.site / "content" / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(textwrap.dedent(text).lstrip("\n"), encoding="utf-8")

    def problems(self):
        return checker.find_problems(self.site)

    def test_unquoted_front_matter_hash_reported(self):
        self.page("user/a.md", """
            ---
            title: Finding by doc #
            weight: 1
            ---
            Body.
            """)
        problems = self.problems()
        self.assertEqual(1, len(problems), problems)
        self.assertIn("content/user/a.md:2:", problems[0])
        self.assertIn("quote", problems[0])

    def test_quoted_front_matter_hash_is_fine(self):
        self.page("user/b.md", """
            ---
            title: "Finding by Doc #"
            linkTitle: 'Finding by Doc #'
            ---
            Body.
            """)
        self.assertEqual([], self.problems())

    def test_heading_ending_in_hash_reported(self):
        self.page("user/c.md", """
            ---
            title: Versions
            ---
            ## Legacy Doc #
            Text.
            """)
        problems = self.problems()
        self.assertEqual(1, len(problems), problems)
        self.assertIn("content/user/c.md:4:", problems[0])
        self.assertIn("\\#", problems[0])

    def test_escaped_heading_and_code_blocks_are_fine(self):
        self.page("user/d.md", """
            ---
            title: Versions
            ---
            ## Legacy Doc \\#

            ```bash
            ## comment ending in #
            ```
            """)
        self.assertEqual([], self.problems())

    def test_logicaldoc_doc_link_outside_interim_reported(self):
        self.page("admin/e.md", """
            ---
            title: Stores
            ---
            See LogicalDOC's [guide](https://docs.logicaldoc.com/en/installation).
            """)
        problems = self.problems()
        self.assertEqual(1, len(problems), problems)
        self.assertIn("content/admin/e.md:4:", problems[0])
        self.assertIn("interim", problems[0])

    def test_interim_and_other_logicaldoc_hosts_are_fine(self):
        self.page("admin/f.md", """
            ---
            title: Stores
            ---
            {{< interim href="https://docs.logicaldoc.com/en/installation" title="Installation Guide" >}}

            Healdan is based on [LogicalDOC](https://www.logicaldoc.com). Its SOAP namespace is http://ws.logicaldoc.com.
            """)
        self.assertEqual([], self.problems())

    def test_wiki_help_and_forum_links_reported(self):
        self.page("developer/g.md", """
            ---
            title: API
            ---
            http://wiki.logicaldoc.com/wiki/Bindings_And_Samples
            http://help.logicaldoc.com
            https://forums.logicaldoc.com
            """)
        self.assertEqual(3, len(self.problems()))

    def test_clean_site_exits_zero(self):
        self.page("install/h.md", """
            ---
            title: Quick Start
            ---
            ## Get the Code
            """)
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, checker.main([str(self.site)]))

    def test_bad_site_exits_one_and_prints_problems(self):
        self.page("install/i.md", """
            ---
            title: Quick Start
            ---
            ## Step #
            """)
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            self.assertEqual(1, checker.main([str(self.site)]))
        self.assertIn("content/install/i.md:4:", out.getvalue())


if __name__ == "__main__":
    unittest.main()
