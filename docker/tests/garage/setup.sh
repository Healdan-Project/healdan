#!/bin/sh
# SPDX-FileCopyrightText: 2026 The Healdan Project
# SPDX-License-Identifier: LGPL-3.0-only

# Smoke test only (docker/tests/compose.storage.yaml): sets the stand-in S3 server up through Garage's admin API v2,
# with the calls the S3 plan's trial checked on v2.5.0 (2026-11-07), from busybox's wget alone, as Garage's image has
# no shell. It shares Garage's network, so the admin API is on 127.0.0.1. Each step runs only if it isn't done yet, as
# smoke step 8 runs this again on Garage's kept volume:
#   1. the one-node layout, assigned and applied while the cluster has none (layout version 0);
#   2. the fixed test key, imported unless Garage has it;
#   3. the bucket, made unless it's there, and the key allowed on it (which allowing again changes nothing).
# Then it waits for Garage's /health, which answers 200 once the layout is applied.
set -eu

ADMIN=http://127.0.0.1:3903
# garage.toml's admin_token, and the overlay's key and bucket: fixed test values
TOKEN=smoke-admin-token-0123456789abcdef
KEY=GK0123456789abcdef01234567
SECRET=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
BUCKET=healdan-smoke
STARTED=$(date +%s)

# call <operation> [JSON body]: GETs the operation, or POSTs the body to it; prints the answer, and fails on an error
call() {
  if [ $# -gt 1 ]; then
    wget -q -O - --header "Authorization: Bearer $TOKEN" --header "Content-Type: application/json" \
      --post-data "$2" "$ADMIN/v2/$1"
  else
    wget -q -O - --header "Authorization: Bearer $TOKEN" "$ADMIN/v2/$1"
  fi
}
# first <name> <JSON>: the first value of a field of that name, a string or a number, however the JSON is laid out
first() {
  echo "$2" | tr ',{}[]' '\n\n\n\n\n' | sed -n "s/^ *\"$1\": *\"\{0,1\}\([^\"]*\)\"\{0,1\} *\$/\1/p" | head -n 1
}
# wait_for <what> <command...>: runs the command every second until it succeeds, for at most a minute
wait_for() {
  what=$1
  shift
  i=0
  until "$@" >/dev/null 2>&1; do
    i=$((i + 1))
    if [ "$i" -ge 60 ]; then
      echo "Garage: $what after 60 s" >&2
      exit 1
    fi
    sleep 1
  done
}

wait_for "the admin API doesn't answer" call GetClusterStatus
NODE=$(first id "$(call GetClusterStatus)")
case $NODE in
  "" | *[!0-9a-f]*) echo "Garage: no node id in GetClusterStatus's answer" >&2; exit 1 ;;
esac

if [ "$(first version "$(call GetClusterLayout)")" = 0 ]; then
  call UpdateClusterLayout "{\"roles\":[{\"id\":\"$NODE\",\"zone\":\"dc1\",\"capacity\":1000000000,\"tags\":[]}]}" \
    >/dev/null
  call ApplyClusterLayout '{"version":1}' >/dev/null
  echo "Garage: layout applied to node $NODE"
else
  echo "Garage: layout already applied"
fi

if call "GetKeyInfo?id=$KEY" >/dev/null 2>&1; then
  echo "Garage: key $KEY already there"
else
  call ImportKey "{\"accessKeyId\":\"$KEY\",\"secretAccessKey\":\"$SECRET\",\"name\":\"healdan-smoke\"}" >/dev/null
  echo "Garage: key $KEY imported"
fi

if BUCKET_INFO=$(call "GetBucketInfo?globalAlias=$BUCKET" 2>/dev/null); then
  echo "Garage: bucket $BUCKET already there"
else
  BUCKET_INFO=$(call CreateBucket "{\"globalAlias\":\"$BUCKET\"}")
  echo "Garage: bucket $BUCKET made"
fi
BUCKET_ID=$(first id "$BUCKET_INFO")
case $BUCKET_ID in
  "" | *[!0-9a-f]*) echo "Garage: no id in bucket $BUCKET's answer" >&2; exit 1 ;;
esac
call AllowBucketKey "{\"bucketId\":\"$BUCKET_ID\",\"accessKeyId\":\"$KEY\",\
\"permissions\":{\"read\":true,\"write\":true,\"owner\":true}}" >/dev/null
echo "Garage: key $KEY allowed on bucket $BUCKET"

wait_for "/health doesn't answer 200" wget -q -O /dev/null "$ADMIN/health"
echo "Garage started and set up in $(($(date +%s) - STARTED)) s"
