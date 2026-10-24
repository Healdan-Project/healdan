"""Checks test-shards.py's plan on a throwaway reactor, and the times it reads from Surefire reports."""

import contextlib
import io
import json
import os
import pathlib
import tempfile
import unittest
import unittest.mock

import _scripts

shards = _scripts.load("test-shards")

POM = """<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modules>
{modules}
  </modules>
</project>
"""

TEST_CLASS = "package {package};\n\npublic class {name} {{\n{methods}}}\n"


class TestShardsTest(unittest.TestCase):

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self._tmp.name)
        self.pom("logicaldoc-core", "healdan-core", "logicaldoc-webapp")

    def tearDown(self):
        self._tmp.cleanup()

    def pom(self, *modules):
        lines = "\n".join(f"    <module>{module}</module>" for module in modules)
        (self.root / "pom.xml").write_text(POM.format(modules=lines), encoding="utf-8")

    def write(self, name, text):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")

    def add_class(self, module, name, tests=1, package="com.logicaldoc.x"):
        methods = "".join(f"  @Test\n  public void test{index}() {{}}\n" for index in range(tests))
        path = f"{module}/src/test/java/{package.replace('.', '/')}/{name}.java"
        self.write(path, TEST_CLASS.format(package=package, name=name, methods=methods))

    def discovered(self, upstream=""):
        modules = shards.reactor_modules(self.root)
        return shards.healdan_tests(self.root, modules) + shards.upstream_tests(self.root, modules, upstream)

    def plan(self, upstream="", times=None, minutes=5, max_shards=8):
        modules = shards.reactor_modules(self.root)
        entries, _ = shards.plan(self.discovered(upstream), modules, times or {}, minutes, max_shards)
        return entries

    def selectors(self, entries):
        return sorted(selector for entry in entries for selector in entry["tests"].split(","))

    def test_finds_healdan_classes_surefire_runs(self):
        for name in ("ATest", "BTests", "CTestCase", "TestD"):
            self.add_class("healdan-core", name)
        self.write("healdan-core/src/test/java/com/logicaldoc/x/Helper.java", "class Helper {}\n")
        self.write("healdan-core/src/test/java/com/logicaldoc/x/AbstractFooTestCase.java",
                   "package com.logicaldoc.x;\npublic abstract class AbstractFooTestCase {}\n")
        self.add_class("logicaldoc-core", "UpstreamTest")
        self.assertEqual(["com.logicaldoc.x.ATest", "com.logicaldoc.x.BTests", "com.logicaldoc.x.CTestCase",
                          "com.logicaldoc.x.TestD"], sorted(test.name for test in self.discovered()))

    def test_ignores_healdan_folders_outside_the_reactor(self):
        self.add_class("healdan-core", "ATest")
        self.add_class("healdan-unlisted", "BTest")
        self.assertEqual(["com.logicaldoc.x.ATest"], [test.name for test in self.discovered()])

    def test_upstream_tests_by_simple_name_and_method(self):
        self.add_class("logicaldoc-core", "HooksTest", package="com.logicaldoc.core")
        self.add_class("logicaldoc-webapp", "ServiceTest", tests=3, package="com.logicaldoc.web")
        tests = self.discovered("HooksTest\nServiceTest#testA+testB\n")
        self.assertEqual({"com.logicaldoc.core.HooksTest": "logicaldoc-core",
                          "com.logicaldoc.web.ServiceTest#testA+testB": "logicaldoc-webapp"},
                         {test.selector(): test.module for test in tests})

    def test_upstream_tests_separated_by_commas(self):
        self.add_class("logicaldoc-core", "ATest")
        self.add_class("logicaldoc-core", "BTest")
        self.assertEqual(["com.logicaldoc.x.ATest", "com.logicaldoc.x.BTest"],
                         sorted(test.selector() for test in self.discovered("ATest, BTest")))

    def test_upstream_class_named_twice(self):
        self.add_class("logicaldoc-core", "ATest", tests=3)
        self.add_class("logicaldoc-core", "BTest", tests=3)
        tests = self.discovered("ATest#one ATest#two BTest#one BTest")
        self.assertEqual(["com.logicaldoc.x.ATest#one+two", "com.logicaldoc.x.BTest"],
                         sorted(test.selector() for test in tests))

    def test_unknown_upstream_test_is_an_error(self):
        with self.assertRaisesRegex(ValueError, "UPSTREAM_TESTS names MissingTest"):
            self.discovered("MissingTest")

    def test_healdan_classes_are_not_upstream_tests(self):
        self.add_class("healdan-core", "ATest")
        with self.assertRaises(ValueError):
            self.discovered("ATest")

    def test_every_test_runs_once(self):
        for index in range(20):
            self.add_class("healdan-core", f"H{index}Test", tests=index + 1)
        for index in range(5):
            self.add_class("logicaldoc-webapp", f"W{index}Test", tests=10)
        entries = self.plan(" ".join(f"W{index}Test" for index in range(5)), minutes=1)
        expected = sorted([f"com.logicaldoc.x.H{index}Test" for index in range(20)]
                          + [f"com.logicaldoc.x.W{index}Test" for index in range(5)])
        self.assertEqual(expected, self.selectors(entries))
        self.assertEqual(list(range(1, len(entries) + 1)), [entry["shard"] for entry in entries])
        self.assertTrue(all(entry["of"] == len(entries) for entry in entries))

    def test_shard_count_follows_the_minutes(self):
        for index in range(10):
            self.add_class("healdan-core", f"H{index}Test")
        times = {f"com.logicaldoc.x.H{index}Test": 120.0 for index in range(10)}
        self.assertEqual(4, len(self.plan(times=times, minutes=5)))
        self.assertEqual(3, len(self.plan(times=times, minutes=5, max_shards=3)))
        self.assertEqual(1, len(self.plan(times=times, minutes=60)))

    def test_no_more_shards_than_classes(self):
        self.add_class("healdan-core", "ATest")
        self.add_class("healdan-core", "BTest")
        times = {"com.logicaldoc.x.ATest": 3000.0, "com.logicaldoc.x.BTest": 3000.0}
        self.assertEqual(2, len(self.plan(times=times, minutes=1)))

    def test_balances_by_recorded_times(self):
        seconds = {"ATest": 280, "BTest": 200, "CTest": 120, "DTest": 80, "ETest": 70, "FTest": 50}
        for name in seconds:
            self.add_class("healdan-core", name)
        times = {f"com.logicaldoc.x.{name}": float(value) for name, value in seconds.items()}
        entries = self.plan(times=times, minutes=7, max_shards=2)
        self.assertEqual(["com.logicaldoc.x.ATest,com.logicaldoc.x.DTest,com.logicaldoc.x.FTest",
                          "com.logicaldoc.x.BTest,com.logicaldoc.x.CTest,com.logicaldoc.x.ETest"],
                         [entry["tests"] for entry in entries])

    def test_recorded_zero_seconds_is_kept(self):
        self.add_class("healdan-core", "ATest", tests=10)
        tests = self.discovered()
        shards.plan(tests, shards.reactor_modules(self.root), {"com.logicaldoc.x.ATest": 0.0})
        self.assertEqual(0.0, tests[0].seconds)

    def test_estimates_classes_without_a_time(self):
        self.add_class("healdan-core", "ATest", tests=4)
        self.add_class("healdan-core", "NoTestsTest", tests=0)
        self.add_class("logicaldoc-core", "UpstreamTest", tests=9)
        tests = {test.name: test for test in self.discovered("UpstreamTest#one+two")}
        self.assertEqual(12, tests["com.logicaldoc.x.ATest"].estimate())
        self.assertEqual(1, tests["com.logicaldoc.x.NoTestsTest"].estimate())
        self.assertEqual(6, tests["com.logicaldoc.x.UpstreamTest"].estimate())

    def test_a_shard_keeps_to_few_modules(self):
        # Without the module's cost, W2Test would go to the second shard, which would be 5 s lighter
        self.add_class("healdan-core", "H1Test")
        self.add_class("healdan-core", "H2Test")
        self.add_class("logicaldoc-core", "CTest")
        self.add_class("logicaldoc-webapp", "W1Test")
        self.add_class("logicaldoc-webapp", "W2Test")
        seconds = {"H1Test": 300, "H2Test": 300, "CTest": 50, "W1Test": 55, "W2Test": 5}
        times = {f"com.logicaldoc.x.{name}": float(value) for name, value in seconds.items()}
        entries = self.plan("CTest W1Test W2Test", times=times, minutes=5, max_shards=2)
        self.assertEqual(["healdan-core,logicaldoc-webapp", "logicaldoc-core,healdan-core"],
                         [entry["modules"] for entry in entries])
        self.assertTrue(entries[0]["tests"].endswith("W2Test"))

    def test_modules_in_reactor_order(self):
        self.add_class("logicaldoc-webapp", "WTest")
        self.add_class("logicaldoc-core", "CTest")
        self.add_class("healdan-core", "HTest")
        entries = self.plan("WTest CTest", max_shards=1)
        self.assertEqual("logicaldoc-core,healdan-core,logicaldoc-webapp", entries[0]["modules"])

    def test_the_first_webapp_shard_saves_the_maven_cache(self):
        self.add_class("healdan-core", "ATest")
        self.add_class("healdan-core", "BTest")
        self.add_class("logicaldoc-webapp", "WTest")
        times = {"com.logicaldoc.x.ATest": 300.0, "com.logicaldoc.x.BTest": 200.0, "com.logicaldoc.x.WTest": 50.0}
        entries = self.plan("WTest", times=times, minutes=5, max_shards=2)
        self.assertEqual([False, True], [entry["saves_m2"] for entry in entries])
        self.assertIn("logicaldoc-webapp", entries[1]["modules"])

    def test_shard_one_saves_the_maven_cache_without_webapp_tests(self):
        self.add_class("healdan-core", "ATest")
        self.add_class("healdan-core", "BTest")
        times = {"com.logicaldoc.x.ATest": 300.0, "com.logicaldoc.x.BTest": 300.0}
        self.assertEqual([True, False], [entry["saves_m2"] for entry in self.plan(times=times)])

    def test_reads_times_from_surefire_reports(self):
        self.write("a/TEST-com.x.ATest.xml", '<testsuite name="com.x.ATest" time="130.388" tests="2"/>')
        self.write("a/TEST-com.x.BTest.xml", '<testsuite name="com.x.BTest" time="1,234.5" tests="2"/>')
        self.write("a/com.x.ATest.txt", "not a report")
        self.write("b/TEST-com.x.CTest.xml", '<testsuite name="com.x.CTest" time="0.016" tests="1"/>')
        lines = shards.report_times([self.root / "a", self.root / "b", self.root / "missing"])
        self.assertEqual(["130.388 com.x.ATest", "1234.500 com.x.BTest", "0.016 com.x.CTest"], lines)

    def test_times_file_round_trip(self):
        self.write("times.txt", "130.388 com.x.ATest\n0.016 com.x.CTest\n\n")
        self.assertEqual({"com.x.ATest": 130.388, "com.x.CTest": 0.016}, shards.read_times(self.root / "times.txt"))
        self.assertEqual({}, shards.read_times(self.root / "missing.txt"))

    def test_main_writes_the_matrix(self):
        self.add_class("healdan-core", "ATest")
        self.add_class("logicaldoc-core", "UpstreamTest")
        output = self.root / "github-output"
        with unittest.mock.patch.dict(os.environ, {"GITHUB_OUTPUT": str(output)}), \
                contextlib.redirect_stdout(io.StringIO()) as stdout:
            status = shards.main(["plan", "--root", str(self.root), "UpstreamTest"])
        self.assertEqual(0, status)
        self.assertIn("1 shards for 2 test classes", stdout.getvalue())
        name, _, value = output.read_text(encoding="utf-8").strip().partition("=")
        self.assertEqual("shards", name)
        self.assertEqual([{"shard": 1, "of": 1, "modules": "logicaldoc-core,healdan-core",
                           "tests": "com.logicaldoc.x.ATest,com.logicaldoc.x.UpstreamTest", "minutes": 0.4,
                           "saves_m2": True}], json.loads(value))

    def test_main_fails_on_an_unknown_upstream_test(self):
        with contextlib.redirect_stdout(io.StringIO()) as stdout:
            status = shards.main(["plan", "--root", str(self.root), "MissingTest"])
        self.assertEqual(1, status)
        self.assertIn("::error file=.github/workflows/build-logicaldoc.yml::UPSTREAM_TESTS names MissingTest",
                      stdout.getvalue())


if __name__ == "__main__":
    unittest.main()
