// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;

import com.google.crypto.tink.InsecureSecretKeyAccess;
import com.google.crypto.tink.StreamingAead;
import com.google.crypto.tink.streamingaead.AesGcmHkdfStreamingKey;
import com.google.crypto.tink.streamingaead.AesGcmHkdfStreamingParameters;
import com.google.crypto.tink.subtle.AesGcmHkdfStreaming;
import com.google.crypto.tink.util.SecretBytes;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Builds the S3 client and Tink's streaming AEAD as the storage backends do, on the WAR's own libraries. Maven's test
 * classpath has every library's dependencies, but WEB-INF/lib has only what the plugin zips ship, so a class missing
 * from every zip fails here and nowhere else before the server (Healdan-Project/healdan#202 was POI's SparseBitSet).
 * A request to a closed loopback port loads the signing, the request pipeline and the error path; a NoClassDefFoundError
 * or ClassNotFoundException is the failure this test exists to catch.
 * Usage: WarStorageTest
 */
public class WarStorageTest {

    public static void main(String[] args) throws Exception {
        s3RequestFailsToConnect();
        tinkRoundTrip();
        Check.done("WarStorageTest");
    }

    private static void s3RequestFailsToConnect() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }
        try (S3Client s3 = S3Client.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(1))
                        .socketTimeout(Duration.ofSeconds(1)))
                .endpointOverride(URI.create("http://127.0.0.1:" + port))
                .region(Region.of("us-east-1"))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("key", "secret")))
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .forcePathStyle(true)
                .overrideConfiguration(o -> o.retryStrategy(b -> b.maxAttempts(1)))
                .build()) {
            s3.putObject(b -> b.bucket("bucket").key("key").contentMD5("1B2M2Y8AsgTpgAmY7PhCfg=="),
                    RequestBody.fromBytes(new byte[] { 1, 2, 3 }));
            Check.fail("S3 putObject to a closed port succeeded");
        } catch (SdkClientException e) {
            boolean refused = false;
            for (Throwable t = e; t != null && !refused; t = t.getCause() == t ? null : t.getCause())
                refused = t instanceof ConnectException;
            Check.isTrue(refused, "S3 putObject to a closed port: no ConnectException in the causes of " + e);
        } catch (LinkageError | RuntimeException e) {
            Check.fail("S3 putObject to a closed port: " + e);
        }
    }

    private static void tinkRoundTrip() throws Exception {
        byte[] keyBytes = new byte[32];
        byte[] plain = new byte[3 << 20];
        SecureRandom random = new SecureRandom();
        random.nextBytes(keyBytes);
        random.nextBytes(plain);
        try {
            AesGcmHkdfStreamingParameters parameters = AesGcmHkdfStreamingParameters.builder().setKeySizeBytes(32)
                    .setDerivedAesGcmKeySizeBytes(32).setHkdfHashType(AesGcmHkdfStreamingParameters.HashType.SHA256)
                    .setCiphertextSegmentSizeBytes(1 << 20).build();
            AesGcmHkdfStreamingKey key = AesGcmHkdfStreamingKey.create(parameters,
                    SecretBytes.copyFrom(keyBytes, InsecureSecretKeyAccess.get()));
            StreamingAead aead = AesGcmHkdfStreaming.create(key);
            byte[] aad = new byte[0];
            ByteArrayOutputStream encrypted = new ByteArrayOutputStream();
            try (OutputStream out = aead.newEncryptingStream(encrypted, aad)) {
                out.write(plain);
            }
            byte[] decrypted;
            try (InputStream in = aead.newDecryptingStream(new ByteArrayInputStream(encrypted.toByteArray()), aad)) {
                decrypted = in.readAllBytes();
            }
            Check.isTrue(Arrays.equals(plain, decrypted), "Tink: 3 MiB did not round-trip");
        } catch (LinkageError | RuntimeException e) {
            Check.fail("Tink streaming AEAD: " + e);
        }
    }
}
