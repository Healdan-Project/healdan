#!/usr/bin/env bash
# Provider sign-in through the stand-in Keycloak in the real Docker image (M4 spec section 16.1): the web page's flow
# with a cookie jar, linking by email, the gate, the helper's flow with a pretend loopback address and its code's
# exchange, and a person disabled at the provider losing their device token at its next re-check.
# Needs a fresh stack with the sign-in overlay (docker/tests/compose.signin.yaml), whose Keycloak imports
# docker/tests/keycloak/healdan-realm.json and whose smoke-signin seed sets Healdan up to use it (SmokeSignInSeed).
# Usage: BASE=http://127.0.0.1:18080 KEYCLOAK=127.0.0.1:18180 docker/tests/test-signin.sh   (needs curl, jq, python3)
set -euo pipefail
cd "$(dirname "$0")/../.."
. docker/tests/lib.sh

BASE=${BASE:-http://127.0.0.1:8080}
# Keycloak names itself http://keycloak:8080 in its discovery document and tokens, the address the app reaches it at
# on the stack's network; from here, that name is sent to its host port.
KEYCLOAK=${KEYCLOAK:-127.0.0.1:18180}
KC=(--connect-to "keycloak:8080:$KEYCLOAK")
KC_URL=http://keycloak:8080
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
OUT=$WORK/body
JSON=(-H 'Content-Type: application/json')
# Throwaway passwords on a throwaway stack: the realm's people's, sso-user's local one (SmokeSignInSeed) and
# Keycloak's admin's (compose.signin.yaml).
SSO_PW='Smoke-Sso-Pw-42!x'
LOCAL_PW='Smoke-Local-Pw-42!x'
KC_ADMIN_PW=smoke-admin-pw
# The helper's loopback address: nothing listens on port 9, and the last redirect to it isn't followed.
LOOPBACK=http://127.0.0.1:9/

# hop <url> [curl args...]: one request with the cookie jar $JAR, not following a redirect. Sets code and next (the
# redirect's address, empty if it isn't one) and leaves the body in $OUT.
code=
next=
hop() {
  local url=$1 got
  shift
  got=$(curl -sS "${KC[@]}" -b "$JAR" -c "$JAR" -o "$OUT" -w '%{http_code} %{redirect_url}' "$@" "$url") \
    || got="000 "
  code=${got%% *}
  next=${got#* }
}
# login_action <page>: prints the address Keycloak's login form posts to; fails if the page has no such form.
login_action() {
  python3 - "$1" <<'PY'
import html.parser, sys

class Form(html.parser.HTMLParser):
    action = None

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == "form" and attrs.get("id") == "kc-form-login":
            self.action = attrs.get("action")

form = Form()
with open(sys.argv[1], encoding="utf-8", errors="replace") as page:
    form.feed(page.read())
if not form.action:
    sys.exit(1)
print(form.action)
PY
}
# sign_in <start address> <username> [stop]: a browser's way through a sign-in. Follows redirects with the jar, fills
# in Keycloak's login form once as the username, and stops at an answer that isn't a redirect, or at a redirect to an
# address that starts with stop, which isn't followed. Sets url (the address it stopped at) and code.
url=
sign_in() {
  local user=$2 stop=${3:-} hops=0 posted= action
  url=$1
  hop "$url"
  while :; do
    hops=$((hops + 1))
    [ "$hops" -le 20 ] || { echo "  more than 20 hops, at $url"; return 1; }
    if [ -n "$next" ]; then
      url=$next
      if [ -n "$stop" ] && [ "${url#"$stop"}" != "$url" ]; then return 0; fi
      hop "$url"
      continue
    fi
    if [ -z "$posted" ] && action=$(login_action "$OUT"); then
      posted=1
      url=$action
      hop "$url" --data-urlencode "username=$user" --data-urlencode "password=$SSO_PW" --data-urlencode credentialId=
      continue
    fi
    return 0
  done
}
# param <address> <name>: prints the query parameter's value, empty if there's none.
param() {
  python3 -c 'import sys, urllib.parse as u; print(u.parse_qs(u.urlsplit(sys.argv[1]).query).get(sys.argv[2], [""])[0])' "$1" "$2"
}

# req <expected status> <curl args...>: calls the API and leaves the body in $OUT. Sends the bearer token when set.
TOKEN=
status=
req() {
  local auth=()
  [ -z "$TOKEN" ] || auth=(-H "Authorization: Bearer $TOKEN")
  status=$(curl -s -o "$OUT" -w '%{http_code}' ${auth[@]+"${auth[@]}"} "$@") || status=000
}
# expect <status> <what>: succeeds when the last req answered that status; otherwise says what came back.
expect() {
  [ "$status" = "$1" ] && return 0
  echo "  $2: HTTP $status, expected $1: $(head -c 300 "$OUT")"
  return 1
}
# json_is <jq filter> <expected>: the last body's value at the filter equals the expected text.
json_is() {
  local got
  got=$(jq -r "$1" "$OUT" 2>/dev/null || echo '<not JSON>')
  [ "$got" = "$2" ] && return 0
  echo "  $1 is '$got', expected '$2': $(head -c 300 "$OUT")"
  return 1
}
# password_sign_in <username> <password> <device id>: POST /auth/tokens with a password.
password_sign_in() {
  req "${JSON[@]}" -d "$(jq -n --arg u "$1" --arg p "$2" --arg d "$3" \
    '{username: $u, password: $p, name: "smoke sign-in", device: {id: $d}}')" "$BASE/api/v1/auth/tokens"
}

LOCAL_TOKEN=
HELPER_TOKEN=

# Before its first sign-in through Keycloak, sso-user is a local account with a password, as the seed made it.
setup() {
  password_sign_in sso-user "$LOCAL_PW" ci-signin-local
  expect 201 "POST /auth/tokens with sso-user's local password" || return 1
  LOCAL_TOKEN=$(jq -r .token "$OUT")
  TOKEN=$LOCAL_TOKEN
  req "$BASE/api/v1/me"
  TOKEN=
  expect 200 "GET /me with sso-user's token" && json_is .username sso-user
}
# The web page's sign-in: Healdan sends the browser to Keycloak, which sends it back to the callback, which signs it in.
step1() {
  JAR=$WORK/user.jar
  sign_in "$BASE/api/signin/smoke" sso-user || return 1
  if [ "$url" != "$BASE/frontend.jsp" ] || [ "$code" != 200 ]; then
    echo "  ended at $url with HTTP $code: $(head -c 300 "$OUT")"
    return 1
  fi
  awk -F '\t' '$6 == "ldoc-sid" && $7 != "" { found = 1 } END { exit !found }' "$JAR" \
    || { echo "  the jar holds no ldoc-sid"; return 1; }
}
# Linked by email, sso-user is a provider account: no password, and the device token its password got is revoked.
step2() {
  password_sign_in sso-user "$LOCAL_PW" ci-signin-local
  expect 401 "POST /auth/tokens with sso-user's local password" && json_is .code sign-in-failed || return 1
  TOKEN=$LOCAL_TOKEN
  req "$BASE/api/v1/me"
  TOKEN=
  expect 401 "GET /me with the token sso-user's password got before the link"
}
# sso-outsider is in none of the groups the provider's map names, so the gate refuses it.
step3() {
  JAR=$WORK/outsider.jar
  sign_in "$BASE/api/signin/smoke" sso-outsider || return 1
  [ "$url" = "$BASE/login.jsp?signin=no-group" ] && [ "$code" = 200 ] && return 0
  echo "  ended at $url with HTTP $code"
  return 1
}
# The helper's sign-in (spec section 6.2): the code goes to the loopback address with the helper's state, and the
# exchange, with the PKCE verifier, gives a device token.
step4() {
  local verifier challenge start one_time
  JAR=$WORK/helper.jar
  verifier=$(python3 -c 'import secrets; print(secrets.token_urlsafe(48))')
  challenge=$(python3 -c 'import base64, hashlib, sys
print(base64.urlsafe_b64encode(hashlib.sha256(sys.argv[1].encode()).digest()).decode().rstrip("="))' "$verifier")
  start=$(python3 -c 'import sys, urllib.parse as u
print(sys.argv[1] + "?" + u.urlencode({"client": "helper", "redirect_uri": sys.argv[2], "state": "s1",
                                       "code_challenge": sys.argv[3], "code_challenge_method": "S256"}))' \
    "$BASE/api/signin/smoke" "$LOOPBACK" "$challenge")
  sign_in "$start" sso-helper "$LOOPBACK" || return 1
  one_time=$(param "$url" code)
  if [ "${url%%\?*}" != "$LOOPBACK" ] || [ -z "$one_time" ] || [ "$(param "$url" state)" != s1 ]; then
    echo "  ended at $url with HTTP $code, not $LOOPBACK with a code and state s1"
    return 1
  fi
  req "${JSON[@]}" -d "$(jq -n --arg c "$one_time" --arg v "$verifier" --arg r "$LOOPBACK" \
    '{code: $c, codeVerifier: $v, redirectUri: $r, name: "smoke helper", device: {id: "ci-signin-helper"}}')" \
    "$BASE/api/v1/auth/tokens"
  expect 201 "POST /auth/tokens with the code" || return 1
  HELPER_TOKEN=$(jq -r .token "$OUT")
  grep -Eq '^hd_[A-Za-z0-9_-]{43}$' <<<"$HELPER_TOKEN" || { echo "  no token in the answer"; HELPER_TOKEN=; return 1; }
  # signin.recheck.minutes is 0, so this request checks the token with Keycloak first, which accepts it
  TOKEN=$HELPER_TOKEN
  req "$BASE/api/v1/me"
  TOKEN=
  expect 200 "GET /me with the helper's token" && json_is .username sso-helper
}
# A leaver: disabled at Keycloak, whose refresh then answers invalid_grant, so the next request's re-check revokes
# the helper's token.
step5() {
  local admin id put
  admin=$(curl -sS "${KC[@]}" -d grant_type=password -d client_id=admin-cli -d username=admin \
    --data-urlencode "password=$KC_ADMIN_PW" "$KC_URL/realms/master/protocol/openid-connect/token" \
    | jq -r '.access_token // empty') || true
  [ -n "$admin" ] || { echo "  no admin token from Keycloak"; return 1; }
  id=$(curl -sS "${KC[@]}" -H "Authorization: Bearer $admin" \
    "$KC_URL/admin/realms/healdan/users?username=sso-helper&exact=true" | jq -r '.[0].id // empty') || true
  [ -n "$id" ] || { echo "  Keycloak has no sso-helper"; return 1; }
  put=$(curl -sS "${KC[@]}" -o "$OUT" -w '%{http_code}' -X PUT -H "Authorization: Bearer $admin" "${JSON[@]}" \
    -d '{"enabled": false}' "$KC_URL/admin/realms/healdan/users/$id") || put=000
  [ "$put" = 204 ] || { echo "  disabling sso-helper: HTTP $put: $(head -c 300 "$OUT")"; return 1; }
  TOKEN=$HELPER_TOKEN
  req "$BASE/api/v1/me"
  TOKEN=
  expect 401 "GET /me with the helper's token once sso-helper is disabled"
}

check "setup: sso-user signs in with its local password before it's linked" setup
check "1. the web page's sign-in through Keycloak ends at frontend.jsp, signed in" step1
check "2. linked by email: sso-user's local password is refused, and its token revoked" step2
check "3. the gate: sso-outsider ends at login.jsp?signin=no-group" step3
check "4. the helper's sign-in: a code at the loopback address, exchanged for a token naming sso-helper" step4
if [ -n "$HELPER_TOKEN" ]; then
  check "5. disabled at Keycloak, sso-helper's token is refused at its next re-check" step5
else
  fail "5. disabled at Keycloak, sso-helper's token is refused at its next re-check (no token from 4)"
fi

finish
