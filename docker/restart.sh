#!/bin/sh
# SPDX-FileCopyrightText: 2026 The Healdan Project
# SPDX-License-Identifier: LGPL-3.0-only

# Run by Admin > Restart (LDOCHOME/bin/restart.sh). PID 1 is tini, which forwards the signal to Tomcat; Tomcat
# stopping stops the container, and the restart policy (restart: unless-stopped) starts it again.
kill -TERM 1
