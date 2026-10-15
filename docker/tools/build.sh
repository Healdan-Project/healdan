#!/usr/bin/env bash
# Compiles the container init tool against the WAR's libraries, runs its tests and packages it.
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
run_test ConfigFileTest "$defaults"

jar cf "$jar" -C "$work/classes" .
echo "built $jar"
