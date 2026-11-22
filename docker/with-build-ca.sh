#!/bin/sh
# SPDX-FileCopyrightText: 2026 The Healdan Project
# SPDX-License-Identifier: LGPL-3.0-only

# Runs the build stage's Maven build, the command it's given, trusting the CA certificates in the build's secret
# build_ca too, when the build has one: for a machine whose HTTPS a proxy re-signs with a CA of its own, such as a
# Claude Code cloud session (/root/.ccr/ca-bundle.crt). docker build --secret id=build_ca,src=<file> passes one, and the
# Docker test scripts pass HEALDAN_BUILD_CA's file (docker/tests/lib.sh). Without the secret it runs the command as it
# is, which is how CI builds.
# With it, Maven's JVM, which downloads the artifacts and frontend-maven-plugin's Node, gets a copy of the JDK's trust
# store with the certificates added, which goes when the command ends, and npm gets them through NODE_EXTRA_CA_CERTS. The
# JVMs Maven starts (the GWT compile, javadoc with the JDK's links offline) fetch nothing. Nothing of the secret stays in
# the layer, and a secret isn't in BuildKit's cache key, so a build with it and one without share the layer.
# The Dockerfile bind-mounts this file into the step, so a change to it runs Maven again, as a change to the sources does.
# Usage: with-build-ca.sh <command...>
set -eu

secret=/run/secrets/build_ca
[ -e "$secret" ] || exec "$@"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
cp "$JAVA_HOME/lib/security/cacerts" "$work/cacerts"
# One JVM adds them all, rather than keytool, which takes one certificate a file and a JVM's start for each: a cloud
# session's bundle holds 120, which took keytool 43 seconds. It leaves out those the JDK has already, and fails on a
# file with none.
cat >"$work/AddCertificates.java" <<'EOF'
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.util.Collection;

class AddCertificates {
    public static void main(String[] args) throws Exception {
        char[] password = "changeit".toCharArray();
        File storeFile = new File(args[0]);
        KeyStore store = KeyStore.getInstance(storeFile, password);
        Collection<? extends Certificate> certificates;
        try (InputStream in = new FileInputStream(args[1])) {
            certificates = CertificateFactory.getInstance("X.509").generateCertificates(in);
        } catch (CertificateException e) {
            System.err.println("The secret build_ca isn't a file of PEM certificates: " + e.getMessage());
            System.exit(1);
            return;
        }
        if (certificates.isEmpty()) {
            System.err.println("The secret build_ca holds no certificate");
            System.exit(1);
        }
        int added = 0;
        for (Certificate certificate : certificates) {
            if (store.getCertificateAlias(certificate) == null)
                store.setCertificateEntry("healdan-build-ca-" + ++added, certificate);
        }
        try (OutputStream out = new FileOutputStream(storeFile)) {
            store.store(out, password);
        }
        System.out.println("build_ca: trusting its " + certificates.size() + " certificates, " + added
                + " of them besides the JDK's own");
    }
}
EOF
java "$work/AddCertificates.java" "$work/cacerts" "$secret"

export MAVEN_OPTS="${MAVEN_OPTS:+$MAVEN_OPTS }-Djavax.net.ssl.trustStore=$work/cacerts -Djavax.net.ssl.trustStorePassword=changeit"
export NODE_EXTRA_CA_CERTS="$secret"
"$@"
