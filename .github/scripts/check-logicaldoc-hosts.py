#!/usr/bin/env python3
#
# Checks that nothing Healdan builds or runs names one of LogicalDOC's servers. Healdan never contacts LogicalDOC: CE
# posted usage statistics to stat.logicaldoc.com and tested the proxy against activation.logicaldoc.com, and an
# upstream sync can bring a call like those back. Run by .github/workflows/build-logicaldoc.yml.
#
# A LogicalDOC server is any host, URL or email address at logicaldoc.com, .it, .net or .org. Java packages
# (com.logicaldoc.*) aren't, nor are the XML namespaces in NAMESPACES: identifiers clients match, never fetched.
#
# Checks every file git tracks, except:
#   - docs/ and Markdown files, which are prose;
#   - site/, the docs site, which credits LogicalDOC and whose links the site checks and the doc-links check cover;
#   - tests and their fixtures (src/test/ in any module, and .github/scripts/tests/), which never ship;
#   - logicaldoc-webservicesamples/, which isn't in the reactor, and upstream's release notes;
#   - the checkers that look for LogicalDOC's hosts (SKIPPED_FILES);
#   - lines in ALLOWED, each with the reason nothing contacts it. A line that changes no longer matches, and is
#     flagged again.
# Binary files (with a NUL byte) are skipped.
#
# Usage: check-logicaldoc-hosts.py [repo root]
# Exit status: 0 when nothing names a LogicalDOC server, 1 otherwise.

import pathlib
import re
import subprocess
import sys

LOGICALDOC_HOST = re.compile(r"\b(?:[a-z0-9-]+\.)*(?<!\bcom\.)logicaldoc\.(?:com|it|net|org)\b", re.IGNORECASE)

# The SOAP API's, CMIS's and WebDAV's XML namespaces. Changing one would break the clients that match it.
NAMESPACES = re.compile(r"http://(?:ws\.logicaldoc\.com|logicaldoc\.com/(?:cmis|ns))(?![\w/.-])")

SKIPPED_PREFIXES = ("docs/", "site/", ".github/scripts/tests/", "logicaldoc-webservicesamples/")

SKIPPED_FILES = {
    "ReleaseNotes.txt",  # upstream's release history
    ".github/scripts/check-logicaldoc-doc-links.py",
    ".github/scripts/check-logicaldoc-hosts.py",
    ".github/scripts/check-site-sources.py",
}

REPLACED = "a SystemInfo default, which HealdanSystemInfo replaces"
COPIED = "a default that SOAP and REST getInfo overwrite with SystemInfo's, which HealdanSystemInfo replaces"
GOOGLE = "the privacy policy the Google integration's dialog links to; CE has no server side for the integration"
API_DOCS = "text in the REST API's OpenAPI description, shown to readers"
POM = "the POM's metadata, crediting LogicalDOC and its developers; Maven never contacts it"

ALLOWED = (
    ("logicaldoc-core/src/main/java/com/logicaldoc/core/SystemInfo.java",
     'protected String help = "https://docs.logicaldoc.com";', REPLACED),
    ("logicaldoc-core/src/main/java/com/logicaldoc/core/SystemInfo.java",
     'protected String bugs = "https://bugs.logicaldoc.com";', REPLACED),
    ("logicaldoc-core/src/main/java/com/logicaldoc/core/SystemInfo.java",
     'protected String url = "https://www.logicaldoc.com";', REPLACED),
    ("logicaldoc-core/src/main/java/com/logicaldoc/core/SystemInfo.java",
     'protected String support = "support@logicaldoc.com";', REPLACED),
    ("logicaldoc-webservice/src/main/java/com/logicaldoc/webservice/model/WSSystemInfo.java",
     'private String help = "https://docs.logicaldoc.com";', COPIED),
    ("logicaldoc-webservice/src/main/java/com/logicaldoc/webservice/model/WSSystemInfo.java",
     'private String bugs = "https://bugs.logicaldoc.com";', COPIED),
    ("logicaldoc-webservice/src/main/java/com/logicaldoc/webservice/model/WSSystemInfo.java",
     'private String url = "https://www.logicaldoc.com";', COPIED),
    ("logicaldoc-webservice/src/main/java/com/logicaldoc/webservice/model/WSSystemInfo.java",
     'private String forum = "https://forums.logicaldoc.com";', COPIED),
    ("logicaldoc-webservice/src/main/java/com/logicaldoc/webservice/model/WSSystemInfo.java",
     'private String support = "support@logicaldoc.com";', COPIED),
    ("logicaldoc-core/src/main/java/com/logicaldoc/core/stats/StatsCollector.java",
     'HttpPost post = new HttpPost("http://stat.logicaldoc.com/stats/collect");',
     "the statistics upload, which nothing calls; StatsCollectorTest.testSendsNothingOut fails if anything does"),
    ("logicaldoc-cmis/src/main/java/com/logicaldoc/cmis/LDRepository.java",
     'String ns = "http://www.logicaldoc.com";',
     "the namespace of CMIS's extension elements: an identifier clients match, never fetched"),
    ("logicaldoc-gui/src/main/java/com/logicaldoc/gui/frontend/client/google/GoogleApiAuthorization.java",
     'policyUrl = "https://www.logicaldoc.com/google-integration-privacy-policy";', GOOGLE),
    ("logicaldoc-webapp/src/main/resources/context.properties",
     "policy.google = https://www.logicaldoc.com/google-integration-privacy-policy", GOOGLE),
    ("logicaldoc-webservicedoc/src/main/resources/context/context-webservicedoc.xml",
     '<property name="contactEmail" value="devel@logicaldoc.com"/>', API_DOCS),
    ("logicaldoc-webservicedoc/src/main/resources/context/context-webservicedoc.xml",
     '<property name="description" value="This is the interactive documentation of the REST APIs exposed by '
     'LogicalDOC. You can find more information about LogicalDOC at https://www.logicaldoc.com or on X @logicaldoc.'
     '&lt;br/&gt;', API_DOCS),
    ("logicaldoc-webservicedoc/src/main/resources/context/context-webservicedoc.xml",
     '**Find out more about LogicalDOC** https://www.logicaldoc.com"/>', API_DOCS),
    ("pom.xml", "<url>http://www.logicaldoc.com</url>", POM),
    ("pom.xml", "<url>http://bugs.logicaldoc.com</url>", POM),
    ("pom.xml", "<organizationUrl>http://www.logicaldoc.com</organizationUrl>", POM),
    ("pom.xml", "<email>m.meschieri@logicaldoc.com</email>", POM),
    ("pom.xml", "<email>a.gasparini@logicaldoc.com</email>", POM),
    ("pom.xml", "<email>g.desiato@logicaldoc.com</email>", POM),
)

FIX = ("names a LogicalDOC server, and Healdan never contacts LogicalDOC. Remove it, or add the line to ALLOWED with "
       "the reason nothing contacts it.")


def tracked_files(root):
    listing = subprocess.run(["git", "ls-files", "-z"], cwd=root, check=True, capture_output=True).stdout
    return sorted(name.decode("utf-8", errors="replace") for name in listing.split(b"\0") if name)


def skipped(name):
    return (name.startswith(SKIPPED_PREFIXES) or name in SKIPPED_FILES or name.endswith(".md")
            or "/src/test/" in f"/{name}")


def names_a_host(line):
    return LOGICALDOC_HOST.search(NAMESPACES.sub("", line)) is not None


def find_hosts(root):
    root = pathlib.Path(root)
    allowed = {(path, line) for path, line, _ in ALLOWED}
    problems = []
    for name in tracked_files(root):
        if skipped(name):
            continue
        path = root / name
        if not path.is_file():
            continue
        data = path.read_bytes()
        text = data.decode("iso-8859-1")
        if b"\0" in data or not LOGICALDOC_HOST.search(text):
            continue
        for number, line in enumerate(text.splitlines(), start=1):
            if names_a_host(line) and (name, line.strip()) not in allowed:
                problems.append(f"{name}:{number}: {FIX}")
    return problems


def main(argv):
    root = pathlib.Path(argv[0]) if argv else pathlib.Path(__file__).resolve().parents[2]
    problems = find_hosts(root)
    for problem in problems:
        print(problem)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
