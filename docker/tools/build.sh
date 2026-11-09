#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 The Healdan Project
# SPDX-License-Identifier: LGPL-3.0-only

# Compiles the container init tool against the WAR's libraries, runs its tests and packages it. WarParsersTest also
# parses a .pptx, an .xlsx and an .xls with the server's parsers on those libraries, which catches a dependency of
# POI's that no plugin zip ships. WarStorageTest builds the S3 client and Tink's streaming AEAD on them the same way.
# Usage: build.sh <WEB-INF/lib dir> <default context.properties> <output jar>
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
lib=$1
defaults=$2
jar=$3
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

javac --release 21 -encoding UTF-8 -cp "$lib/*" -d "$work/classes" "$here"/src/*.java
javac --release 21 -encoding UTF-8 -cp "$work/classes:$lib/*" -d "$work/test-classes" "$here"/test/*.java

run_test() { java -cp "$work/test-classes:$work/classes:$lib/*" "$@"; }
run_test DbInitPlanTest
run_test ConfigPlanTest
run_test TrustedProxiesTest
run_test ConfigFileTest "$defaults"
run_test WarParsersTest "$here/test/fixtures"
run_test WarStorageTest

# PluginInstallTest starts the WAR's plugins twice, as DockerInit and then Tomcat do: with a conf folder on the
# classpath, as the entrypoint has it, and copies of the WAR's jars and web.xml, which installs edit. Not links: the
# installs follow them back to the WAR's own web.xml.
mkdir -p "$work/conf" "$work/webapp/WEB-INF/lib"
cp "$(dirname "$defaults")/log.xml" "$work/conf/"
cp "$lib"/*.jar "$work/webapp/WEB-INF/lib/"
cp "$lib/../web.xml" "$work/webapp/WEB-INF/web.xml"
run_plugins() { java -cp "$work/test-classes:$work/classes:$work/webapp/WEB-INF/lib/*:$work/conf" PluginInstallTest "$@"; }
run_plugins first "$defaults" "$work" "$lib/../web.xml"
run_plugins again

jar cf "$jar" -C "$work/classes" .
echo "built $jar"
