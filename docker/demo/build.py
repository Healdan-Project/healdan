#!/usr/bin/env python3
"""Builds the demo stack's sample files from their text sources (demo spec section 6).

Every file docker/demo/manifest.json's timeline names is built into
docker/demo/files/ from the one source in docker/demo/sources/ with the same
stem:

  .html  -> .odt .docx .pdf .png .jpg  (Writer, through LibreOffice's HTML import)
  .fodt  -> .odt .docx .pdf
  .fods  -> .ods .xlsx .pdf
  .fodg  -> .odg .png .pdf
  .eml   -> .eml                       (each X-Demo-Attach: header becomes an attachment)

LibreOffice and ImageMagick run inside the Healdan image, so everyone gets the
same versions. Build the image first, then:

  python3 docker/demo/build.py [--image healdan-demo:local] [STEM ...]

LibreOffice stamps the time into what it writes, so the output isn't
byte-for-byte reproducible: rebuild in the same commit as a source change.
"""

import argparse
import email
import email.policy
import json
import mimetypes
import os
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile
from collections import defaultdict
from email.message import EmailMessage
from pathlib import Path

HERE = Path(__file__).resolve().parent
SOURCES = HERE / "sources"
FILES = HERE / "files"
SOURCE_KINDS = (".html", ".fodt", ".fods", ".fodg", ".eml")

# LibreOffice's export filter for each output, by the source's kind
TARGETS = {
    ".html": {".odt": "odt:writer8", ".docx": "docx:MS Word 2007 XML", ".pdf": "pdf:writer_pdf_Export",
              ".png": "png:writer_png_Export"},
    ".fodt": {".odt": "odt:writer8", ".docx": "docx:MS Word 2007 XML", ".pdf": "pdf:writer_pdf_Export"},
    ".fods": {".ods": "ods:calc8", ".xlsx": "xlsx:Calc MS Excel 2007 XML", ".pdf": "pdf:calc_pdf_Export"},
    ".fodg": {".odg": "odg:draw8", ".png": "png:draw_png_Export", ".pdf": "pdf:draw_pdf_Export"},
}
# HTML must open in Writer, not Writer/Web, which can't save .docx
INFILTERS = {".html": "HTML (StarWriter)"}


def wanted(manifest):
    """Every file the timeline names"""
    return sorted({step["file"] for step in manifest.get("timeline", []) if "file" in step})


def source_for(output):
    stem = Path(output).stem
    found = [SOURCES / (stem + kind) for kind in SOURCE_KINDS if (SOURCES / (stem + kind)).exists()]
    if len(found) != 1:
        sys.exit(f"{output}: expected one source named {stem}.*, found {len(found)}")
    return found[0]


def docker(image, mounts, entrypoint, args):
    # HOME points at /tmp because the user has no home inside the image, and fontconfig and dconf complain without one
    cmd = ["docker", "run", "--rm", "--entrypoint", entrypoint, "--user", f"{os.getuid()}:{os.getgid()}",
           "-e", "HOME=/tmp"]
    for host, inside in mounts:
        cmd += ["-v", f"{host}:{inside}"]
    subprocess.run(cmd + [image] + args, check=True)


def convert(image, sources, target, infilter, out):
    """One LibreOffice run for every source with the same target, into out"""
    args = ["-env:UserInstallation=file:///tmp/lo", "--headless"]
    if infilter:
        args.append(f"--infilter={infilter}")
    args += ["--convert-to", target, "--outdir", "/out"] + [f"/in/{s.name}" for s in sources]
    docker(image, [(SOURCES, "/in:ro"), (out, "/out")], "soffice", args)


def comments_in(path):
    """The text of each comment in a built .docx or .odt"""
    with zipfile.ZipFile(path) as z:
        if path.suffix == ".docx":
            if "word/comments.xml" not in z.namelist():
                return []
            root = ET.fromstring(z.read("word/comments.xml"))
            return [" ".join("".join(c.itertext()).split())
                    for c in root.iter("{http://schemas.openxmlformats.org/wordprocessingml/2006/main}comment")]
        root = ET.fromstring(z.read("content.xml"))
    # An ODF annotation also holds its author and date, so read only its paragraphs
    para = "{urn:oasis:names:tc:opendocument:xmlns:text:1.0}p"
    return [" ".join(" ".join("".join(p.itertext()) for p in a.iter(para)).split())
            for a in root.iter("{urn:oasis:names:tc:opendocument:xmlns:office:1.0}annotation")]


def refuse_stray_comments(source, built):
    """LibreOffice's HTML import turns a <meta> it doesn't read, such as http-equiv, into a Word comment,
    which a headless PDF or PNG doesn't show. Only a .fodt source is meant to carry comments."""
    for text in comments_in(built):
        sys.exit(f"{built.name}: LibreOffice made a comment from something in {source.name}: {text!r}. "
                 "Remove the <meta> tag it came from (only author, created, changedby, changed, keywords and "
                 "description are read), and build again.")


def build_email(source, output):
    message = email.message_from_bytes(source.read_bytes(), policy=email.policy.SMTP)
    attachments = message.get_all("X-Demo-Attach") or []
    del message["X-Demo-Attach"]
    if attachments:
        built = EmailMessage(policy=email.policy.SMTP)
        for name, value in message.items():
            if name.lower() not in ("content-type", "content-transfer-encoding", "mime-version"):
                built[name] = value
        built.set_content(message.get_content())
        for name in attachments:
            ctype = mimetypes.guess_type(name)[0] or "application/octet-stream"
            maintype, subtype = ctype.split("/", 1)
            built.add_attachment((FILES / name).read_bytes(), maintype=maintype, subtype=subtype, filename=name)
        # A fixed boundary keeps the email's bytes the same from build to build
        built.set_boundary(f"demo-{source.stem}")
        message = built
    output.write_bytes(message.as_bytes())


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--image", default="healdan-demo:local", help="a built Healdan image")
    parser.add_argument("stems", nargs="*", help="build only these files' stems")
    options = parser.parse_args()
    if subprocess.run(["docker", "image", "inspect", options.image], capture_output=True).returncode:
        sys.exit(f"No image {options.image}: build it first, for example with docker build -t {options.image} .")

    outputs = wanted(json.loads((HERE / "manifest.json").read_text()))
    if options.stems:
        outputs = [o for o in outputs if Path(o).stem in options.stems]
    FILES.mkdir(exist_ok=True)

    # Group the conversions, so LibreOffice starts once per kind and target
    groups = defaultdict(list)
    emails, jpgs = [], []
    for output in outputs:
        source, ext = source_for(output), Path(output).suffix.lower()
        if source.suffix == ".eml":
            emails.append((source, FILES / output))
        elif ext == ".jpg" and source.suffix == ".html":
            jpgs.append(output)
            groups[(source.suffix, TARGETS[".html"][".png"])].append(source)
        elif ext in TARGETS[source.suffix]:
            groups[(source.suffix, TARGETS[source.suffix][ext])].append(source)
        else:
            sys.exit(f"{output}: a {source.suffix} source can't make a {ext}")

    with tempfile.TemporaryDirectory() as tmp:
        out = Path(tmp)
        for (kind, target), sources in sorted(groups.items()):
            convert(options.image, sorted(set(sources)), target, INFILTERS.get(kind), out)
        for output in jpgs:
            stem = Path(output).stem
            docker(options.image, [(out, "/out")], "convert", [f"/out/{stem}.png", "-quality", "85", f"/out/{output}"])
        for output in outputs:
            source = source_for(output)
            if source.suffix == ".html" and Path(output).suffix.lower() in (".docx", ".odt") and (out / output).exists():
                refuse_stray_comments(source, out / output)
        for output in outputs:
            if (out / output).exists():
                shutil.copyfile(out / output, FILES / output)
    for source, output in emails:
        build_email(source, output)
    print(f"Built {len(outputs)} files in {FILES.relative_to(HERE.parent.parent)}")


if __name__ == "__main__":
    main()
