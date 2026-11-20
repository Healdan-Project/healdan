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
