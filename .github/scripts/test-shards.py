#!/usr/bin/env python3
#
# Splits the tests CI's build check runs into shards that run side by side, and records how long each test class took,
# so the next run can balance the shards. Run by .github/workflows/build-logicaldoc.yml.
#
# The tests are every test class in the reactor's healdan-* modules, plus the upstream tests the workflow's
# UPSTREAM_TESTS names, which cover Healdan's edits to upstream code. A test class is a file under src/test/java whose
# name Surefire includes by default (Test*, *Test, *Tests or *TestCase), unless it's abstract. An upstream test is a
# class name, or Class#method (Class#a+b for two methods); it's looked for in every module that isn't Healdan's.
#
# Usage:
#   test-shards.py plan [--times FILE] [--minutes M] [--max-shards N] [--root DIR] UPSTREAM_TESTS
#       Prints the plan, and when $GITHUB_OUTPUT is set writes shards=<JSON list> to it: one entry per shard, with
#       the modules it tests and its -Dtest list. A class weighs the seconds FILE records for it, or 3 per @Test when
#       FILE has none. There are enough shards for about M minutes of tests each (default 5), but at most N (default
#       8). The heaviest classes go first, each to the shard it leaves lightest, counting SECONDS_PER_MODULE for a
#       module the shard doesn't test yet.
#   test-shards.py times DIR...
#       Prints "<seconds> <class>" for each Surefire report (TEST-*.xml) in the DIRs: the lines FILE holds.
# Exit status: 0, or 1 when an upstream test names a class no module has.

import argparse
import json
import math
import os
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

SUREFIRE_INCLUDES = re.compile(r"^(Test\w*|\w*Test|\w*Tests|\w*TestCase)\.java$")

# What a class without a recorded time weighs: about what a test with a Spring context takes in healdan-core
SECONDS_PER_TEST = 3

# What testing one more module costs a shard (Maven compiles it again and starts another JVM), so that a module's
# small classes tend to share a shard
SECONDS_PER_MODULE = 10

TEST_ANNOTATION = re.compile(r"@Test\b")


class Test:
    """A test class in a module, run whole (methods is None) or only for the named methods."""

    def __init__(self, module, name, path, methods=None):
        self.module = module
        self.name = name
        self.path = path
        self.methods = methods
        self.seconds = 0.0

    def selector(self):
        """The test's entry in Surefire's -Dtest list."""
        return self.name + ("#" + "+".join(self.methods) if self.methods else "")

    def estimate(self):
        """The seconds the test should take when no run has recorded them."""
        if self.methods:
            count = len(self.methods)
        else:
            count = len(TEST_ANNOTATION.findall(self.path.read_text(encoding="iso-8859-1")))
        return max(1, SECONDS_PER_TEST * count)


def reactor_modules(root):
    """The modules the root POM lists, in its order."""
    project = ET.parse(root / "pom.xml").getroot()
    namespace = project.tag[:project.tag.index("}") + 1] if project.tag.startswith("{") else ""
    return [module.text.strip() for module in project.iterfind(f"{namespace}modules/{namespace}module")]


def test_sources(root, module):
    """(class name, path) of each test class Surefire would run in the module."""
    sources = root / module / "src" / "test" / "java"
    found = []
    for path in sorted(sources.rglob("*.java")):
        if not SUREFIRE_INCLUDES.match(path.name):
            continue
        if re.search(rf"\babstract\s+(\w+\s+)*class\s+{path.stem}\b", path.read_text(encoding="iso-8859-1")):
            continue
        name = ".".join(path.relative_to(sources).with_suffix("").parts)
        found.append((name, path))
    return found


def healdan_tests(root, modules):
    return [Test(module, name, path) for module in modules if module.startswith("healdan-")
            for name, path in test_sources(root, module)]


def upstream_tests(root, modules, spec):
    """The tests UPSTREAM_TESTS names (separated by commas or white space). Raises ValueError on an unknown class."""
    upstream = [module for module in modules if not module.startswith("healdan-")]
    by_simple_name = {}
    for module in upstream:
        for name, path in test_sources(root, module):
            by_simple_name.setdefault(name.rsplit(".", 1)[-1], []).append((module, name, path))

    tests = {}
    for entry in re.split(r"[\s,]+", spec.strip()):
        if not entry:
            continue
        simple, _, methods = entry.partition("#")
        if simple not in by_simple_name:
            raise ValueError(f"UPSTREAM_TESTS names {simple}, but no upstream module has a test class of that name")
        for module, name, path in by_simple_name[simple]:
            test = tests.get(name)
            if test is None:
                tests[name] = Test(module, name, path, methods.split("+") if methods else None)
            elif test.methods is not None:
                # A class named whole runs whole; otherwise its methods add up
                test.methods = None if not methods else sorted(set(test.methods) | set(methods.split("+")))
    return list(tests.values())


def read_times(path):
    """Seconds per class from a file of "<seconds> <class>" lines; empty when there's no file. A line that isn't one
    is skipped, so that a bad file in the cache can't fail every run until a good one replaces it."""
    times = {}
    if path and pathlib.Path(path).is_file():
        for line in pathlib.Path(path).read_text(encoding="utf-8", errors="replace").splitlines():
            seconds, _, name = line.strip().partition(" ")
            try:
                value = float(seconds)
            except ValueError:
                continue
            if name and math.isfinite(value):
                times[name] = max(times.get(name, 0.0), value)
    return times


def plan(tests, modules, times, minutes=5, max_shards=8):
    """Splits the tests into shards, the JSON-ready entries the workflow's matrix takes."""
    for test in tests:
        test.seconds = times[test.name] if test.name in times else test.estimate()
    total = sum(test.seconds for test in tests)
    count = max(1, min(max_shards, len(tests), math.ceil(total / (minutes * 60))))

    shards = [[] for _ in range(count)]
    loads = [0.0] * count

    def load_with(index, test):
        new_module = all(other.module != test.module for other in shards[index])
        return loads[index] + test.seconds + (SECONDS_PER_MODULE if new_module else 0)

    for test in sorted(tests, key=lambda test: (-test.seconds, test.module, test.name)):
        # An empty shard comes first, so that none is left without tests
        empty = [index for index in range(count) if not shards[index]]
        lightest = empty[0] if empty else min(range(count), key=lambda index: (load_with(index, test), index))
        loads[lightest] = load_with(lightest, test)
        shards[lightest].append(test)

    # Only one shard saves the Maven repository cache on master: the first that tests logicaldoc-webapp, the module the
    # install leaves out, so that the cache also holds the dependencies only its tests use.
    webapp = [index for index, shard in enumerate(shards) if any(t.module == "logicaldoc-webapp" for t in shard)]
    saver = webapp[0] if webapp else 0
    order = {module: index for index, module in enumerate(modules)}
    return [{
        "shard": index + 1,
        "of": count,
        "modules": ",".join(sorted({test.module for test in shard}, key=order.get)),
        "tests": ",".join(test.selector() for test in shard),
        "minutes": round(loads[index] / 60, 1),
        "saves_m2": index == saver,
    } for index, shard in enumerate(shards)], shards


def describe(entries, shards, timed):
    lines = []
    classes = sum(len(shard) for shard in shards)
    total = sum(entry["minutes"] for entry in entries)
    lines.append(f"{len(entries)} shards for {classes} test classes ({timed} with a recorded time), "
                 f"about {total:.1f} minutes of tests in all")
    for entry, shard in zip(entries, shards):
        lines.append(f"Shard {entry['shard']}: about {entry['minutes']} minutes, in {entry['modules']}")
        for test in shard:
            lines.append(f"  {test.seconds:7.1f} s  {test.selector()}")
    return "\n".join(lines)


def report_times(directories):
    """The "<seconds> <class>" lines for the Surefire reports in the directories."""
    times = {}
    for directory in directories:
        for report in sorted(pathlib.Path(directory).glob("TEST-*.xml")):
            suite = ET.parse(report).getroot()
            times[suite.get("name")] = float(suite.get("time", "0").replace(",", ""))
    return [f"{seconds:.3f} {name}" for name, seconds in sorted(times.items())]


def main(argv):
    parser = argparse.ArgumentParser(description="Plans the build check's test shards, or records their times.")
    commands = parser.add_subparsers(dest="command", required=True)
    plan_command = commands.add_parser("plan")
    plan_command.add_argument("upstream")
    plan_command.add_argument("--times")
    plan_command.add_argument("--minutes", type=float, default=5)
    plan_command.add_argument("--max-shards", type=int, default=8)
    plan_command.add_argument("--root", default=pathlib.Path(__file__).resolve().parents[2])
    times_command = commands.add_parser("times")
    times_command.add_argument("directories", nargs="*")
    args = parser.parse_args(argv)

    if args.command == "times":
        for line in report_times(args.directories):
            print(line)
        return 0

    root = pathlib.Path(args.root)
    modules = reactor_modules(root)
    try:
        tests = healdan_tests(root, modules) + upstream_tests(root, modules, args.upstream)
    except ValueError as error:
        print(f"::error file=.github/workflows/build-logicaldoc.yml::{error}")
        return 1
    times = read_times(args.times)
    entries, shards = plan(tests, modules, times, args.minutes, args.max_shards)
    print(describe(entries, shards, sum(1 for test in tests if test.name in times)))
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
            output.write(f"shards={json.dumps(entries, separators=(',', ':'))}\n")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
