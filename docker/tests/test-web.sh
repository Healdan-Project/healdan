#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 The Healdan Project
# SPDX-License-Identifier: LGPL-3.0-only

# The User app in Chromium and WebKit against the smoke stack (M6 spec §15.1): the specs in
# healdan-web/web/e2e/ run in Playwright's Docker image, whose tag is the version of @playwright/test in the
# workspace's package-lock.json. Then curl checks the forgery refusals a browser can't make: a cross-site sign-out
# and a sign-out without the app's header, and that the web session's GET works. And the Admin app's refusals (admin
# spec §17.1): webuser's /admin/ is 403, and a signed-out request for a file of its build isn't 200.
# Needs the smoke fixture, whose SmokeWebUserSeed makes webuser (test-api.sh's apiuser is blocked by its throttle
# check, so the browser tests use another user), a running stack, Docker, curl and python3.
# Usage: BASE=http://127.0.0.1:18080 docker/tests/test-web.sh
# The browser container shares the host's network (--network host), which Linux and CI allow. Docker Desktop on a Mac
# doesn't reach the host's ports that way: there, set WEB_NETWORK=bridge. The browser container then joins the network
# of the container that publishes BASE's port (another WEB_NETWORK names the network to join), and still opens
# http://127.0.0.1:<port>, which a small proxy in its container forwards to that container's own address and port,
# because the web session refuses a request whose Origin isn't the server's own address (a different name for it fails
# every spec). With no container publishing the port (a server on the host itself), the proxy goes to
# host.docker.internal:<port>, from WEB_NETWORK.
# Otherwise the proxy doesn't go through the host, as it did until 2026-11-19. When the server is
# the first to close a connection made that way, as Tomcat is after a request with Connection: close (every call of
# helpers.ts' apiContext), Docker Desktop's network remembers its source port for 60 seconds and answers no new
# connection from it. Linux picks its source ports afresh every 10 seconds, so one comes round again within the minute,
# and the connection opens only when the kernel tries again after that: 11, 19, 35 or 67 seconds late, about one
# connection in 700. WebKit sends every POST and PATCH on a new connection, so a save waited and its test failed at 30
# seconds, a different one each run; Chromium sends them on connections it has, and never failed.
# finding.spec searches for documents it has just filed, which the search index only has once the Indexer has run
# (every 30 minutes). docker/smoke-test.sh sets it to every 5 seconds before running this; for a run by hand, do the
# same (Developer Guide, Tests) and set HEALDAN_INDEXER_FAST=1, or finding.spec waits 80 s and fails.
# The browser container is removed when it ends, and what Playwright kept for the failed tests goes with it. To keep
# it, set WEB_RESULTS to a folder, empty or not yet there (in a folder that is, and that Docker can mount): when a test
# fails, each failed test's folder is copied there, with its trace (trace.zip: every action, the page before and after
# it, the console and each request with its times), its screenshot and its error-context.md. A run that passes leaves
# no folder. Open a trace with npx playwright show-trace <folder>/trace.zip, or at trace.playwright.dev.
# The browser tests may run 30 minutes at most, so a run that hangs fails, and keeps what it can, before CI's job limit
# would cancel it with nothing kept.
set -euo pipefail
# Before the cd, so a relative folder is the caller's
WEB_RESULTS=${WEB_RESULTS:-}
case $WEB_RESULTS in '' | /*) ;; *) WEB_RESULTS=$PWD/$WEB_RESULTS ;; esac
cd "$(dirname "$0")/../.."
. docker/tests/lib.sh
if [ -n "$WEB_RESULTS" ]; then results_folder WEB_RESULTS "$WEB_RESULTS" || exit 1; fi

BASE=${BASE:-http://127.0.0.1:18080}
WEB_NETWORK=${WEB_NETWORK:-host}
port=${BASE##*:}
# The network the browser's container joins
network=$WEB_NETWORK
if [ "$WEB_NETWORK" = host ]; then
  WEB_BROWSER_BASE=${WEB_BROWSER_BASE:-$BASE}
  PROXY=
else
  WEB_BROWSER_BASE=${WEB_BROWSER_BASE:-http://127.0.0.1:$port}
  # The container that publishes the port, with bridge the first network it is on, its address there and the port it
  # listens on; with no such container, the host
  app=$(docker ps -q --filter "publish=$port" | head -n 1)
  if [ -z "$app" ]; then
    echo "WARNING: no running container publishes port $port, so the proxy goes through the host (host.docker.internal), where single WebKit tests can fail"
    target=host.docker.internal
    target_port=$port
  else
    [ "$network" != bridge ] \
      || network=$(docker inspect -f '{{range $name, $_ := .NetworkSettings.Networks}}{{println $name}}{{end}}' "$app" | head -n 1)
    target=$(docker inspect -f "{{with index .NetworkSettings.Networks \"$network\"}}{{.IPAddress}}{{end}}" "$app")
    target_port=$(docker port "$app" | sed -n "s|^\([0-9]*\)/tcp -> .*:$port\$|\1|p" | head -n 1)
    if [ -z "$target" ] || [ -z "$target_port" ]; then
      echo "Couldn't find the address and port of the container that publishes port $port (container $app) on network '$network'"
      exit 1
    fi
  fi
  PROXY="node -e \"require('net').createServer(c=>{const u=require('net').connect($target_port,'$target');c.pipe(u);u.pipe(c);c.on('error',()=>u.destroy());u.on('error',()=>c.destroy())}).listen($port,'127.0.0.1')\" &"
fi
[ -n "${HEALDAN_INDEXER_FAST:-}" ] || echo "WARNING: HEALDAN_INDEXER_FAST isn't set: unless the Indexer runs every few seconds, finding.spec's searches fail"
# A throwaway password for a throwaway user on a throwaway stack: healdan-core's SmokeWebUserSeed.PASSWORD.
WEBUSER_PASSWORD='Web-Smoke-Pw-42!x'
export WEBUSER_PASSWORD
# The smoke stack's own administrator, whose published password the smoke stack itself uses (docker/smoke-test.sh): for what only
# an administrator sees, such as trash.spec's check of the Trash's Removed view.
ADMIN_PASSWORD=admin
export ADMIN_PASSWORD

lock=healdan-web/web/package-lock.json
version=$(python3 -c "import json,sys;print(json.load(open(sys.argv[1]))['packages']['node_modules/@playwright/test']['version'])" "$lock")

browsers() {
  local keep=() status=0
  if [ -n "$WEB_RESULTS" ]; then
    # Made here, just before the container that writes to it, and not by Docker, which would make it as root
    [ "$RESULTS_MADE" = 0 ] || mkdir "$WEB_RESULTS" || return 1
    # Mounted for the failed tests' results. The container runs as root, so it hands them to this user.
    keep=(-v "$WEB_RESULTS:/results" -e "RESULTS_OWNER=$(id -u):$(id -g)")
  fi
  # The workspace is copied in, without node_modules, so npm ci's Linux packages never replace a host's own. The label
  # lets docker/smoke-test.sh's cleanup remove a container a stopped run left on its stack's network.
  # (An empty array's expansion is spelt for bash 3.2, which takes a plain one as an unset variable.)
  # Playwright gets 30 minutes, about three times a run in CI, and is then stopped as Ctrl-C stops it, so that it ends
  # its tests and the results below are still copied; 124 is timeout's status for that.
  # The container's script is in single quotes from mkdir on: an apostrophe in it, in a comment too, ends them.
  docker run --rm --label healdan.web-browsers --network "$network" --ipc=host -e BASE="$WEB_BROWSER_BASE" -e WEBUSER_PASSWORD -e ADMIN_PASSWORD \
    ${keep[@]+"${keep[@]}"} -v "$PWD/healdan-web/web:/src:ro" "mcr.microsoft.com/playwright:v${version}-noble" \
    bash -c "$PROXY "'mkdir /work && cd /src && tar cf - --exclude=node_modules --exclude=dist --exclude=e2e-results . | tar xf - -C /work \
      && cd /work && npm ci --ignore-scripts --no-audit --no-fund \
      && { timeout --signal=INT --kill-after=2m 30m npx playwright test --reporter=line || { status=$?
        [ "$status" != 124 ] || echo "== the browser tests were stopped after 30 minutes, the longest they may run"
        # The container goes with its results: the page of each failed test, as Playwright saw it, says what was on screen
        for f in e2e-results/*/error-context.md; do [ -f "$f" ] && { echo "== $f"; head -c 8000 "$f"; echo; }; done
        # And, with WEB_RESULTS, everything it kept for them goes to the host: each failed test has a folder there
        if [ -d /results ] && [ -d e2e-results ]; then
          # Not the scratch folders of a run stopped at its tenth failure: they hold no finished test. Whatever was
          # copied is handed over, a copy cut short too, which the user on the host could not remove otherwise
          cp -R e2e-results/. /results/
          rm -rf /results/.playwright-artifacts-*
          chown -R "$RESULTS_OWNER" /results
        fi
        exit $status; }; }' || status=$?
  if [ -n "$WEB_RESULTS" ]; then
    results_finish "$WEB_RESULTS" || true
    [ -z "$(ls -A "$WEB_RESULTS" 2>/dev/null)" ] \
      || echo "   a folder for each failed test, with its trace: npx playwright show-trace <folder>/trace.zip"
  fi
  return "$status"
}
check "every spec in healdan-web/web/e2e/, in Chromium and WebKit" browsers

COOKIES=$(mktemp)
trap 'rm -f "$COOKIES"' EXIT
# Every call has a deadline, so a stack that stopped answering fails here rather than holding the job
code() { curl -s --max-time 30 -b "$COOKIES" -o /dev/null -w '%{http_code}' "$@"; }

# The sign-in page's form, as a browser posts it. LogicalDOC answers one that works with the session's id in a SID header,
# and one that fails with a redirect to the context's root
signs_in() {
  local headers
  curl -s --max-time 30 -c "$COOKIES" -o /dev/null "$BASE/login.jsp" || return 1
  headers=$(curl -s --max-time 30 -b "$COOKIES" -c "$COOKIES" -o /dev/null -D - \
    -d 'j_username=webuser' --data-urlencode "j_password=$WEBUSER_PASSWORD" "$BASE/j_spring_security_check") || return 1
  grep -qi '^SID: ' <<<"$headers"
}
check "curl signs in as webuser" signs_in
check "the web session's GET answers 200" test "$(code -H 'Sec-Fetch-Site: same-origin' "$BASE/api/v1/me")" = 200
check "a cross-site sign-out is refused with 401" \
  test "$(code -H 'Sec-Fetch-Site: cross-site' -H 'X-Healdan-Client: web' -X DELETE "$BASE/api/v1/session")" = 401
check "a sign-out without the app's header is refused with 403" \
  test "$(code -H 'Sec-Fetch-Site: same-origin' -X DELETE "$BASE/api/v1/session")" = 403
check "the session is still there after the refusals" test "$(code -H 'Sec-Fetch-Site: same-origin' "$BASE/api/v1/me")" = 200

# The Admin app is for administrators only: webuser, who isn't one, gets 403 at its page
check "webuser gets 403 at /admin/" test "$(code "$BASE/admin/")" = 403
# A signed-out caller gets none of its build. It can't read the real files' names, so a made-up one stands for them:
# the servlet answers any path under /admin/assets/ by the path alone, 401 whether the build holds that file or not.
# No answer at all fails too
signed_out_asset() {
  local status
  status=$(curl -s --max-time 30 -o /dev/null -w '%{http_code}' "$BASE/admin/assets/x.js") || return 1
  [ "$status" != 200 ]
}
check "a signed-out request for a file of the Admin app isn't 200" signed_out_asset

finish
