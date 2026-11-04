#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 The Healdan Project
# SPDX-License-Identifier: LGPL-3.0-only

# Staples a notarised file's ticket, or checks the one stapled to it, trying again while Apple's ticket server drops
# the connection. Both fetch the ticket from CloudKit, whose connection CI's runners lost on 0.4.0-rc3, 0.6.0 and
# 0.7.0, on the last two for longer than three tries 10 s apart: stapler then says "The network connection was lost"
# and exits with 68.
#
#   clients/helper/packaging/mac/stapler.sh staple|validate <file>
#
# It tries five times, waiting 15 s, 30 s, 60 s and then 120 s, but only while stapler exits with 68. Any other failure
# ends it at once, such as a ticket Apple doesn't have ("Record not found") or none stapled to the file, which both
# exit with 65. It exits with stapler's last exit code, so 68 means Apple's server never answered.
set -euo pipefail

say() { printf '%s\n' "$*"; }
die() { printf 'stapler.sh: %s\n' "$*" >&2; exit 1; }

if [ "$#" -ne 2 ] || { [ "$1" != staple ] && [ "$1" != validate ]; }; then
  die "usage: stapler.sh staple|validate <file>"
fi

lost=68 # EX_NOHOST, what stapler exits with when the connection fails (NSURLErrorDomain's -1005 on CI)
waits=(15 30 60 120)
tries=$((${#waits[@]} + 1))
for ((try = 1; ; try++)); do
  status=0
  xcrun stapler "$1" "$2" || status=$?
  [ "$status" -eq "$lost" ] || exit "$status"
  if [ "$try" -eq "$tries" ]; then
    say "stapler lost its connection to Apple $tries times; giving up"
    exit "$status"
  fi
  wait=${waits[try - 1]}
  say "stapler lost its connection to Apple (try $try of $tries); trying again in $wait s"
  sleep "$wait"
done
