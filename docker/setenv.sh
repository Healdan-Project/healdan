#!/bin/sh
# SPDX-FileCopyrightText: 2026 The Healdan Project
# SPDX-License-Identifier: LGPL-3.0-only

# Sourced by catalina.sh. CATALINA_OPTS from the environment (heap size and so on) is kept and extended.
# healdan.trustedProxies: the addresses server.xml's RemoteIpValve believes forwarded headers from, as DockerInit
# worked them out on this start. Without the file, none.
# healdan.secretKey: the file of the key that seals the secrets provider sign-in keeps (SecretBox), in the
# configuration volume. Healdan makes it the first time it seals one; it must be backed up with the database.
# healdan.storageStaging, healdan.storageCache and healdan.storageKeys: healdan-storage's folders. Staging, where a
# save waits for the mover to take it to its Store's backend, is in the repository volume and the backend key files in
# the configuration volume, so backups take both; the read cache is a volume of its own, which they leave out.
CATALINA_OPTS="${CATALINA_OPTS:-} -Dfile.encoding=UTF-8 -Dhealdan.trustedProxies=$(cat /opt/logicaldoc/run/trusted-proxies 2>/dev/null) -Dhealdan.secretKey=/opt/logicaldoc/conf/healdan-secret.key"
CATALINA_OPTS="$CATALINA_OPTS -Dhealdan.storageStaging=/opt/logicaldoc/repository/staging -Dhealdan.storageCache=/opt/logicaldoc/cache -Dhealdan.storageKeys=/opt/logicaldoc/conf/healdan-storage-keys"
# -XX:-UseBASE64Intrinsics: CVE-2026-114938 ("Java4Shell"), a heap overflow in the JVM's AVX-512 Base64 decoder on
# x86-64, which a request can reach before signing in. Off, Base64 is decoded by plain Java code. It comes last, so an
# option in the environment can't turn the decoder back on. Remove it once the image's Java is 25.0.5.1 or later
# (Docker spec §17, issue #273).
CATALINA_OPTS="$CATALINA_OPTS -XX:-UseBASE64Intrinsics"
