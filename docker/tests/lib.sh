# SPDX-FileCopyrightText: 2026 The Healdan Project
# SPDX-License-Identifier: LGPL-3.0-only

# Shared helpers for the Docker test scripts (bash 3.2 compatible). Source it; it runs nothing itself.

FAILED=0

pass() { echo "PASS  $*"; }
# The time, in UTC, finds the server's log lines from around a failure.
fail() { echo "FAIL  $* ($(date -u +%H:%M:%S) UTC)"; FAILED=1; }

# check <description> <command...>: runs the command and records PASS or FAIL.
check() {
  local what=$1
  shift
  if "$@"; then pass "$what"; else fail "$what"; fi
}

# wait_healthy <container> <timeout seconds>: waits for Docker's health status to become "healthy".
wait_healthy() {
  local cid=$1 deadline=$((SECONDS + $2)) status
  while :; do
    status=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$cid" 2>/dev/null || echo missing)
    case $status in
      healthy) return 0 ;;
      none | missing) echo "container '$cid' has no health status ($status)"; return 1 ;;
    esac
    if [ "$SECONDS" -ge "$deadline" ]; then
      echo "container $cid is '$status' after $2s"
      return 1
    fi
    sleep 5
  done
}

# lacks <pattern> <text>: succeeds when the text does not contain the pattern.
lacks() { ! grep -q "$1" <<<"$2"; }

# http_code <url> [curl args...]: prints the HTTP status code only.
http_code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }

# HEALDAN_BUILD_CA=<file>: CA certificates (PEM) the image's build trusts besides the JDK's and Node's own, for a machine
# whose HTTPS a proxy re-signs with a CA of its own, such as a Claude Code cloud session (/root/.ccr/ca-bundle.crt). The
# scripts that build pass it as the Dockerfile's secret build_ca (docker/with-build-ca.sh), which leaves nothing in the
# image and isn't in BuildKit's cache key, and test-web.sh gives it to npm ci in the browsers' container. Unset, they
# run as they always have.

# build_ca_usable: refuses a HEALDAN_BUILD_CA that isn't an absolute path to a file this user can read, saying why on
# stderr, before anything is built. Absolute, as the scripts run from the checkout's root, not from the caller's folder.
build_ca_usable() {
  [ -n "${HEALDAN_BUILD_CA:-}" ] || return 0
  case $HEALDAN_BUILD_CA in
    /*) ;;
    *) echo "HEALDAN_BUILD_CA '$HEALDAN_BUILD_CA' isn't an absolute path: name the file from /" >&2; return 1 ;;
  esac
  if [ ! -f "$HEALDAN_BUILD_CA" ] || [ ! -r "$HEALDAN_BUILD_CA" ]; then
    echo "HEALDAN_BUILD_CA '$HEALDAN_BUILD_CA' isn't a file this user can read: name the file of CA certificates" >&2
    return 1
  fi
}

# docker_build <docker build arguments...>: docker build, given HEALDAN_BUILD_CA's file as the secret build_ca when it's
# set.
docker_build() {
  build_ca_usable || return 1
  if [ -n "${HEALDAN_BUILD_CA:-}" ]; then
    docker build --secret "id=build_ca,src=$HEALDAN_BUILD_CA" "$@"
  else
    docker build "$@"
  fi
}

# build_ca_overlay: for a docker compose that builds the app, the overlay that gives its build the secret,
# "-f docker/tests/compose.build-ca.yaml" when HEALDAN_BUILD_CA is set and nothing otherwise. Expand it unquoted, after
# the command's other files: compose $(build_ca_overlay) build. Check build_ca_usable first.
build_ca_overlay() {
  if [ -n "${HEALDAN_BUILD_CA:-}" ]; then echo "-f docker/tests/compose.build-ca.yaml"; fi
}

# results_folder <setting> <absolute folder>: checks, before a run starts, the folder it keeps what it can't print in
# (smoke-test.sh's SMOKE_RESULTS, test-web.sh's WEB_RESULTS). The folder has to be empty or not there yet, so nothing in
# it is another run's, and in a folder that is there, so the one named is the only one a run makes, and which Docker
# can mount: an engine in a VM sees only the host's folders it shares (Docker Desktop's /Users, /private, /tmp and
# /var/folders by default, Colima's home folder only). It makes nothing, says why it refuses one, and sets RESULTS_MADE
# to 1 when the folder isn't there yet, for results_finish.
RESULTS_MADE=0
results_folder() {
  local parent probe seen=0
  RESULTS_MADE=0
  parent=$(dirname "$2")
  if [ ! -d "$parent" ]; then
    echo "$1 '$2' is in a folder that isn't there: make that folder, or name another"
    return 1
  fi
  if [ ! -e "$2" ]; then
    RESULTS_MADE=1
  elif [ ! -d "$2" ] || [ -n "$(ls -A "$2")" ]; then
    echo "$1 '$2' holds files already, or isn't a folder: name an empty folder, or one that isn't there yet"
    return 1
  fi
  # A file made beside it here has to be there in a container
  if ! probe=$(mktemp "$parent/.results-probe.XXXXXX" 2>/dev/null); then
    echo "$1 '$2' is in a folder this user can't write to: name another"
    return 1
  fi
  docker run --rm --network none -v "$parent:/probe:ro" busybox:1.37 test -f "/probe/${probe##*/}" >/dev/null 2>&1 \
    && seen=1
  rm -f "$probe"
  if [ "$seen" = 0 ]; then
    echo "$1 '$2' is in a folder Docker can't mount, or Docker isn't answering: name one in a folder its engine shares"
    return 1
  fi
}

# results_finish <folder>: a run's end for the folder results_folder checked: one that holds anything is named, and one
# the run made and left empty is taken away.
results_finish() {
  [ -d "$1" ] || return 0
  if [ -n "$(ls -A "$1")" ]; then
    echo "== kept in $1: $(ls -A "$1" | tr '\n' ' ')"
  elif [ "$RESULTS_MADE" = 1 ]; then
    rmdir "$1"
  fi
}

# finish: prints the overall result and exits non-zero if any check failed.
finish() {
  if [ "$FAILED" = 0 ]; then echo "ALL PASSED"; else echo "SOME CHECKS FAILED"; exit 1; fi
}
