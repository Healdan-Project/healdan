# Shared helpers for the Docker test scripts (bash 3.2 compatible). Source it; it runs nothing itself.

FAILED=0

pass() { echo "PASS  $*"; }
fail() { echo "FAIL  $*"; FAILED=1; }

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

# finish: prints the overall result and exits non-zero if any check failed.
finish() {
  if [ "$FAILED" = 0 ]; then echo "ALL PASSED"; else echo "SOME CHECKS FAILED"; exit 1; fi
}
