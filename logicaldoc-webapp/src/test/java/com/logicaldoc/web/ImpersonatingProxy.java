package com.logicaldoc.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.Date;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * An HTTP proxy that answers a CONNECT itself and impersonates the requested
 * server with a self-signed certificate, as an attacker on the network would.
 * A client that checks certificates aborts the handshake. One that doesn't
 * sends its request, which the proxy records and answers with a fixed page. It
 * serves a single connection.<br>
 * A trimmed copy of the core module's test helper, which the webapp's tests
 * can't reach.
 */
public class ImpersonatingProxy implements AutoCloseable {

    private final ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());

    private final AtomicReference<String> request = new AtomicReference<>();

    private final Thread thread;

    /**
     * @param host the host name the certificate is issued to
     * @param page the HTML the proxy answers
     */
    public ImpersonatingProxy(String host, String page) throws IOException {
        thread = new Thread(() -> serve(host, page));
        thread.start();
    }

    public String getHost() {
        return socket.getInetAddress().getHostAddress();
    }

    public int getPort() {
        return socket.getLocalPort();
    }

    /**
     * Waits for the client to finish
     *
     * @return the head of the request the client sent through the tunnel, or
     *         null if it aborted the handshake
     */
    public String getRequest() throws InterruptedException {
        thread.join(10000);
        return request.get();
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }

    private void serve(String host, String page) {
        try (Socket client = socket.accept()) {
            client.setSoTimeout(10000);
            readHead(client.getInputStream());
            client.getOutputStream().write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes());
            client.getOutputStream().flush();

            try (SSLSocket tls = (SSLSocket) selfSignedContext(host).getSocketFactory().createSocket(client, null,
                    true)) {
                tls.setUseClientMode(false);
                request.set(readHead(tls.getInputStream()));

                byte[] body = page.getBytes(StandardCharsets.UTF_8);
                OutputStream out = tls.getOutputStream();
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: %d\r\n"
                        + "Connection: close\r\n\r\n").formatted(body.length).getBytes());
                out.write(body);
                out.flush();
            }
        } catch (Exception e) {
            // A validating client aborts the handshake
        }
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        while (!head.toString().endsWith("\r\n\r\n")) {
            int b = in.read();
            if (b < 0)
                throw new IOException("Connection closed");
            head.write(b);
        }
        return head.toString();
    }

    private static SSLContext selfSignedContext(String host) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keys = generator.generateKeyPair();

        X500Name name = new X500Name("CN=" + host);
        Date now = new Date();
        Certificate certificate = new JcaX509CertificateConverter()
                .getCertificate(new JcaX509v3CertificateBuilder(name, BigInteger.ONE, now,
                        new Date(now.getTime() + 86400000L), name, keys.getPublic())
                        .build(new JcaContentSignerBuilder("SHA256withRSA").build(keys.getPrivate())));

        char[] password = "changeit".toCharArray();
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        store.setKeyEntry("server", keys.getPrivate(), password, new Certificate[] { certificate });

        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(store, password);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers.getKeyManagers(), null, null);
        return context;
    }
}
