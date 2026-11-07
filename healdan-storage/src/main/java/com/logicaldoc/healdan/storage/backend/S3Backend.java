// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage.backend;

import java.io.Closeable;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.exception.SdkServiceException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.ContentStreamProvider;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.TlsTrustManagersProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * A backend in an S3 bucket (spec section 10.1), under the backend's prefix, in CE's layout:
 * {@code healdan/123/45/doc/1.2-thumb.png}. The bucket and prefix hold {@code <prefix>.healdan-backend}, the marker
 * naming this install and the backend, which {@link #check()} reads with a {@code GET} and only the constructor
 * writes (call 23).
 *
 * <p>
 * Every upload carries its {@code Content-MD5}, so S3 refuses bytes that changed on the way, and the SDK's own
 * checksums are off for any endpoint but AWS's, as not every provider takes them (section 10.1). A file up to 64 MiB
 * goes up in one request; a larger one in 16 MiB parts, one at a time, each with its own {@code Content-MD5}, and the
 * upload is aborted on any failure. Each attempt of an upload's request has its own timeout
 * ({@link S3Settings#uploadTimeout()}), as the read timeout doesn't end a write that stalls; reads have only the read
 * timeout, so a long download isn't cut.
 * </p>
 *
 * <p>
 * Failures are mapped here: a network failure, or a {@code 5xx} answer once the client's attempts are spent, is a
 * {@link BackendUnreachableException}; a {@code 401} or {@code 403} is a {@link BackendFailingException}, "access
 * denied", and so is a missing bucket, "bucket not found"; a missing key on a read is a
 * {@link FileNotFoundException}; any other refusal is the file's, a plain {@link IOException} naming the error code,
 * which the mover counts as one failed try. None carries the SDK's exception for a refusal, nor the provider's
 * message: an answer to a wrong secret can hold the request's signature, and neither the secret nor a signature may
 * reach a log, an answer or the screen.
 * </p>
 */
public final class S3Backend implements Backend, Closeable {

	private static final Logger log = LoggerFactory.getLogger(S3Backend.class);

	/** A file up to this size goes up in one request */
	public static final long SINGLE_PUT_LIMIT = 64L << 20;

	/** A larger one in parts of this size, the last smaller */
	public static final int PART_SIZE = 16 << 20;

	/** The most parts S3 takes in an upload: 10,000 of 16 MiB is about 156 GiB */
	public static final int MAX_PARTS = 10_000;

	/** A marker is a line; anything longer isn't one */
	private static final int MARKER_LIMIT = 1024;

	private static final String OCTET_STREAM = "application/octet-stream";

	/** What an S3 error code looks like; anything else isn't repeated */
	private static final Pattern ERROR_CODE = Pattern.compile("[A-Za-z0-9.]{1,64}");

	/**
	 * The certificates calls trust, or null for the JVM's own, which production uses. {@code FakeS3.trust()} sets it
	 * to its test certificate; it is read when a backend is built.
	 */
	static volatile TlsTrustManagersProvider trustManagers;

	/** Looks endpoints' hosts up when a backend is built: the JDK's in production, a map in tests */
	static volatile Function<String, InetAddress[]> resolver = Endpoints.DNS;

	private final long id;

	private final String installId;

	private final S3Settings settings;

	private final SdkHttpClient http;

	private final S3Client client;

	/**
	 * @param id the backend's id
	 * @param installId the id of this install, which the marker names
	 * @param settings where it is
	 * @param secret the access key's secret, kept by the client alone
	 * @param create writes the marker when the bucket and prefix have none; otherwise the marker must already be there
	 *        and name this install and this backend, or {@link #check()} fails
	 * @throws BackendFailingException if the endpoint breaks the endpoint rule ({@link Endpoints}); if its host can't
	 *         be looked up ({@link BackendUnreachableException}); or if creating, and the bucket and prefix hold the
	 *         marker of another backend or install, or can't be reached, found or written to
	 */
	public S3Backend(long id, String installId, S3Settings settings, String secret, boolean create)
			throws BackendFailingException {
		if (installId == null || installId.isBlank())
			throw new IllegalArgumentException("No install id");
		if (settings == null)
			throw new IllegalArgumentException("No settings");
		if (secret == null || secret.isBlank())
			throw new IllegalArgumentException("Backend " + id + " has no secret");
		this.id = id;
		this.installId = installId;
		this.settings = settings;
		try {
			Endpoints.check(settings, resolver);
		} catch (Endpoints.HostNotFound e) {
			throw new BackendUnreachableException("Backend " + id + " can't be reached: " + e.getMessage());
		} catch (IllegalArgumentException e) {
			throw new BackendFailingException("Backend " + id + "'s endpoint is refused: " + e.getMessage());
		}

		UrlConnectionHttpClient.Builder httpBuilder = UrlConnectionHttpClient.builder()
				.connectionTimeout(settings.connectTimeout()).socketTimeout(settings.readTimeout());
		TlsTrustManagersProvider trust = trustManagers;
		if (trust != null)
			httpBuilder.tlsTrustManagersProvider(trust);
		http = httpBuilder.build();

		S3ClientBuilder builder = S3Client.builder().httpClient(http).endpointOverride(settings.endpoint())
				.region(Region.of(settings.region())).forcePathStyle(settings.pathStyle())
				.credentialsProvider(
						StaticCredentialsProvider.create(AwsBasicCredentials.create(settings.accessKey(), secret)))
				.overrideConfiguration(o -> o.retryStrategy(r -> r.maxAttempts(settings.attempts())));
		if (!aws(settings))
			builder.requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
					.responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED);
		try {
			client = builder.build();
		} catch (RuntimeException e) {
			http.close();
			throw e;
		}

		if (create)
			try {
				createMarker();
			} catch (BackendFailingException | RuntimeException e) {
				close();
				throw e;
			}
	}

	/** @return whether the endpoint is AWS's own, which takes the SDK's checksums */
	private static boolean aws(S3Settings settings) {
		String host = settings.endpoint().getHost().toLowerCase(Locale.ROOT);
		return host.equals("amazonaws.com") || host.endsWith(".amazonaws.com");
	}

	@Override
	public long id() {
		return id;
	}

	/** @return where it is */
	public S3Settings settings() {
		return settings;
	}

	private String bucket() {
		return settings.bucket();
	}

	/**
	 * @return the key's object, under the prefix: CE's layout, or the check object or one of Test's; never the marker
	 * @throws IllegalArgumentException if it isn't a storage key ({@link LocalBackend#checkKey})
	 */
	String objectKey(String key) {
		LocalBackend.checkKey(key);
		return settings.prefix() + key;
	}

	/**
	 * @param bytes what the request sends
	 * @return what gives an upload's request its attempt timeout ({@link S3Settings#uploadTimeoutFor})
	 */
	private Consumer<AwsRequestOverrideConfiguration.Builder> uploadAttempt(long bytes) {
		Duration timeout = settings.uploadTimeoutFor(bytes);
		return override -> override.apiCallAttemptTimeout(timeout);
	}

	private String markerKey() {
		return settings.prefix() + LocalBackend.MARKER;
	}

	private String marker() {
		return installId + " " + id + "\n";
	}

	/** @return the bucket and prefix, as messages name them */
	private String place() {
		return "bucket " + bucket() + (settings.prefix().isEmpty() ? "" : " under " + settings.prefix());
	}

	/**
	 * @return the marker's text, or empty if there is none. It is read as any object is ({@link Download}), so one cut
	 *         off on its way is an outage, not another backend's marker, and its connection isn't used again.
	 */
	private Optional<String> readMarker() throws BackendFailingException {
		ResponseInputStream<GetObjectResponse> response;
		try {
			response = client.getObject(b -> b.bucket(bucket()).key(markerKey()));
		} catch (NoSuchKeyException e) {
			return Optional.empty();
		} catch (SdkException e) {
			throw checkFailure(failure("read its marker", e));
		}
		Long length = response.response().contentLength();
		try (InputStream in = new Download(response, markerKey(), length == null ? -1 : length)) {
			// A longer object isn't a marker; closing it unread aborts its connection
			return Optional.of(new String(in.readNBytes(MARKER_LIMIT + 1), StandardCharsets.UTF_8));
		} catch (BackendFailingException e) {
			throw e;
		} catch (IOException e) {
			throw new BackendUnreachableException(
					"Backend " + id + " can't be reached: its marker's read stopped: " + e.getMessage(), e);
		}
	}

	private void createMarker() throws BackendFailingException {
		Optional<String> found = readMarker();
		if (found.isPresent()) {
			if (found.get().equals(marker()))
				return;
			throw new BackendFailingException("The " + place() + " holds the marker of another backend or install");
		}
		byte[] bytes = marker().getBytes(StandardCharsets.UTF_8);
		try {
			client.putObject(b -> b.bucket(bucket()).key(markerKey()).contentLength((long) bytes.length)
					.contentMD5(base64(md5(bytes))).contentType("text/plain; charset=UTF-8")
					.overrideConfiguration(uploadAttempt(bytes.length)), RequestBody.fromBytes(bytes));
		} catch (SdkException e) {
			throw checkFailure(uploadFailure("write its marker", e));
		}
		log.info("Backend {} wrote its marker {} in bucket {}", id, markerKey(), bucket());
	}

	@Override
	public void check() throws BackendFailingException {
		Optional<String> found = readMarker();
		if (found.isEmpty())
			throw new BackendFailingException("The " + place() + " of backend " + id + " has no marker "
					+ LocalBackend.MARKER + ": it isn't this backend's, or it was emptied");
		if (!found.get().equals(marker()))
			throw new BackendFailingException(
					"The " + place() + " of backend " + id + " belongs to another backend or install");
	}

	@Override
	public void put(String key, Path file, byte[] md5) throws IOException {
		String objectKey = objectKey(key);
		if (md5 != null && md5.length != 16)
			throw new IllegalArgumentException("Not an MD5: " + md5.length + " bytes");
		long size = Files.size(file);
		if (size > SINGLE_PUT_LIMIT)
			multipart(key, objectKey, file, size, md5);
		else
			single(key, objectKey, file, size, md5);
	}

	/** S3: the object is put at its key, already in place; {@code discard} deletes it (call 20) */
	@Override
	public Staged stage(String key, Path file, byte[] md5) throws IOException {
		put(key, file, md5);
		return new Staged() {
			@Override
			public void commit() {
				// Already in place
			}

			@Override
			public void discard() throws IOException {
				delete(key);
			}
		};
	}

	private void single(String key, String objectKey, Path file, long size, byte[] md5) throws IOException {
		byte[] digest = md5 != null ? md5 : md5(file, 0, size, null);
		try (Source source = new Source(file, 0, size)) {
			try {
				client.putObject(
						b -> b.bucket(bucket()).key(objectKey).contentLength(size).contentMD5(base64(digest))
								.overrideConfiguration(uploadAttempt(size)),
						RequestBody.fromContentProvider(source, size, OCTET_STREAM));
			} catch (SdkException e) {
				throw source.failureOr(uploadFailure("upload " + key, e));
			}
		}
	}

	/**
	 * Uploads in parts. Each part's MD5 is taken first, with the whole file's, which must match the one given; then
	 * each part goes up with its own, so the object is the bytes hashed, and the bytes the caller meant.
	 */
	private void multipart(String key, String objectKey, Path file, long size, byte[] md5) throws IOException {
		long parts64 = (size + PART_SIZE - 1) / PART_SIZE;
		if (parts64 > MAX_PARTS)
			throw new IOException("The file for " + key + " is " + size + " bytes: more than S3's 10,000 parts of "
					+ (PART_SIZE >> 20) + " MiB");
		int count = (int) parts64;
		byte[][] parts = new byte[count][];
		MessageDigest whole = digest();
		for (int i = 0; i < count; i++) {
			long offset = (long) i * PART_SIZE;
			parts[i] = md5(file, offset, Math.min(PART_SIZE, size - offset), whole);
		}
		if (md5 != null && !MessageDigest.isEqual(md5, whole.digest()))
			throw new IOException("The file for " + key + " doesn't match its checksum");

		String uploadId;
		try {
			uploadId = client.createMultipartUpload(b -> b.bucket(bucket()).key(objectKey).contentType(OCTET_STREAM)
					.overrideConfiguration(uploadAttempt(0))).uploadId();
		} catch (SdkException e) {
			throw uploadFailure("start the upload of " + key, e);
		}
		try {
			List<CompletedPart> completed = new ArrayList<>(count);
			for (int i = 0; i < count; i++) {
				int number = i + 1;
				long offset = (long) i * PART_SIZE;
				long length = Math.min(PART_SIZE, size - offset);
				String partMd5 = base64(parts[i]);
				try (Source source = new Source(file, offset, length)) {
					try {
						String etag = client.uploadPart(
								b -> b.bucket(bucket()).key(objectKey).uploadId(uploadId).partNumber(number)
										.contentLength(length).contentMD5(partMd5)
										.overrideConfiguration(uploadAttempt(length)),
								RequestBody.fromContentProvider(source, length, OCTET_STREAM)).eTag();
						completed.add(CompletedPart.builder().partNumber(number).eTag(etag).build());
					} catch (SdkException e) {
						throw source.failureOr(uploadFailure("upload part " + number + " of " + key, e));
					}
				}
			}
			try {
				client.completeMultipartUpload(b -> b.bucket(bucket()).key(objectKey).uploadId(uploadId)
						.multipartUpload(m -> m.parts(completed)).overrideConfiguration(uploadAttempt(0)));
			} catch (SdkException e) {
				throw uploadFailure("finish the upload of " + key, e);
			}
		} catch (IOException | RuntimeException | Error e) {
			abort(key, objectKey, uploadId, e);
			throw e;
		}
	}

	/** Aborts an upload, and if that fails too, however it fails, keeps the first failure and logs the second */
	private void abort(String key, String objectKey, String uploadId, Throwable first) {
		try {
			client.abortMultipartUpload(b -> b.bucket(bucket()).key(objectKey).uploadId(uploadId)
					.overrideConfiguration(uploadAttempt(0)));
		} catch (RuntimeException e) {
			Exception aborting = e instanceof SdkException sdk ? failure("abort the upload of " + key, sdk) : e;
			first.addSuppressed(aborting);
			log.warn("Backend {}: the unfinished upload of {} couldn't be aborted ({}); "
					+ "the bucket's rule for incomplete uploads clears it", id, key, aborting.getMessage());
		}
	}

	@Override
	public InputStream get(String key) throws IOException {
		String objectKey = objectKey(key);
		ResponseInputStream<GetObjectResponse> in;
		try {
			in = client.getObject(b -> b.bucket(bucket()).key(objectKey));
		} catch (NoSuchKeyException e) {
			throw missing(key);
		} catch (SdkException e) {
			throw failure("read " + key, e);
		}
		Long length = in.response().contentLength();
		return new Download(in, key, length == null ? -1 : length);
	}

	/** The size comes from {@code HEAD}; each read is a ranged {@code GET} ({@link S3Channel}) */
	@Override
	public SeekableByteChannel open(String key) throws IOException {
		String objectKey = objectKey(key);
		long size = head(key, objectKey);
		if (size < 0)
			throw missing(key);
		return new S3Channel(size, (first, last) -> range(key, objectKey, first, last, size));
	}

	/** @return the bytes from first to last, checking they are that range of an object of that size */
	private InputStream range(String key, String objectKey, long first, long last, long size) throws IOException {
		String asked = "bytes=" + first + "-" + last;
		ResponseInputStream<GetObjectResponse> in;
		try {
			in = client.getObject(b -> b.bucket(bucket()).key(objectKey).range(asked));
		} catch (NoSuchKeyException e) {
			throw missing(key);
		} catch (S3Exception e) {
			if (e.statusCode() == 416)
				throw changed(key);
			throw failure("read " + key, e);
		} catch (SdkException e) {
			throw failure("read " + key, e);
		}
		GetObjectResponse response = in.response();
		String range = response.contentRange();
		boolean whole = range == null && first == 0 && last == size - 1 && response.contentLength() != null
				&& response.contentLength() == size;
		if (!whole && !("bytes " + first + "-" + last + "/" + size).equals(range)) {
			in.abort();
			throw changed(key);
		}
		return new Download(in, key, last - first + 1);
	}

	private IOException changed(String key) {
		return new IOException(key + " on backend " + id + " changed while it was read");
	}

	@Override
	public long size(String key) throws IOException {
		return head(key, objectKey(key));
	}

	/** @return the object's size, -1 when there is none (HEAD can't tell a missing bucket from a missing key) */
	private long head(String key, String objectKey) throws IOException {
		Long length;
		try {
			length = client.headObject(b -> b.bucket(bucket()).key(objectKey)).contentLength();
		} catch (NoSuchKeyException e) {
			return -1;
		} catch (S3Exception e) {
			if (e.statusCode() == 404)
				return -1;
			throw failure("look up " + key, e);
		} catch (SdkException e) {
			throw failure("look up " + key, e);
		}
		if (length == null)
			throw new IOException("Backend " + id + " answered the look-up of " + key + " without its length");
		return length;
	}

	@Override
	public void delete(String key) throws IOException {
		String objectKey = objectKey(key);
		try {
			client.deleteObject(b -> b.bucket(bucket()).key(objectKey));
		} catch (NoSuchKeyException e) {
			// Missing is fine
		} catch (SdkException e) {
			throw failure("delete " + key, e);
		}
	}

	@Override
	public List<String> list(String prefix, int max) throws IOException {
		List<String> keys = new ArrayList<>();
		if (max <= 0)
			return keys;
		String base = settings.prefix();
		String full = base + (prefix == null ? "" : prefix);
		String token = null;
		do {
			String next = token;
			ListObjectsV2Response page;
			try {
				page = client.listObjectsV2(
						b -> b.bucket(bucket()).prefix(full).maxKeys(Math.min(1000, max)).continuationToken(next));
			} catch (SdkException e) {
				throw failure("list " + (full.isEmpty() ? "the bucket" : full), e);
			}
			for (S3Object object : page.contents()) {
				// An object named as the prefix itself, as some tools make for a folder, is no key
				if (!object.key().startsWith(base) || object.key().length() == base.length())
					continue;
				keys.add(object.key().substring(base.length()));
				if (keys.size() >= max)
					return keys;
			}
			token = Boolean.TRUE.equals(page.isTruncated()) ? page.nextContinuationToken() : null;
		} while (token != null);
		return keys;
	}

	@Override
	public Optional<String> held(String key) {
		return Optional.empty();
	}

	/**
	 * S3: leaves the object where it is and sends nothing (spec section 8), so a delete never waits on an outage while
	 * it holds the file's lock. An object that's gone is held all the same: the purge's delete of it does nothing.
	 *
	 * @throws IllegalArgumentException if the key isn't a storage key
	 */
	@Override
	public String hold(String key) {
		objectKey(key);
		return key;
	}

	@Override
	public void purgeHeld(String location) throws IOException {
		try {
			objectKey(location);
		} catch (IllegalArgumentException e) {
			throw new IOException("Not a held location: " + location, e);
		}
		delete(location);
	}

	@Override
	public void close() {
		client.close();
		http.close();
	}

	@Override
	public String toString() {
		return "S3Backend[" + id + ", " + settings.endpoint() + ", " + place() + "]";
	}

	private static FileNotFoundException missing(String key) {
		return new FileNotFoundException(key);
	}

	/**
	 * Maps an SDK failure: unreachable for the network or a {@code 5xx}; the backend failing for a {@code 401} or
	 * {@code 403} ("access denied") or a missing bucket ("bucket not found"); the file's, a plain
	 * {@link IOException}, for any other refusal. A refusal keeps only its status and error code: the provider's
	 * message and the SDK's exception, which holds the provider's whole answer, are left out, as an answer to a wrong
	 * secret can hold the request's signature.
	 *
	 * @param what what was being done, as "upload 1/2/doc/1.0"
	 */
	private IOException failure(String what, SdkException e) {
		if (e instanceof SdkServiceException service) {
			int status = service.statusCode();
			String code = errorCode(e);
			String answer = status + (code != null ? " " + code : "");
			if (status >= 500 || status == 429 || "SlowDown".equals(code)
					|| (status == 400 && "RequestTimeout".equals(code)))
				return new BackendUnreachableException(
						"Backend " + id + " can't be reached: the server answered " + answer + " to " + what);
			if (status == 401 || status == 403)
				return new BackendFailingException("Backend " + id + ": access denied (" + answer + ") to " + what
						+ ": check its access key and secret");
			if ("NoSuchBucket".equals(code))
				return new BackendFailingException("Backend " + id + ": bucket not found: " + bucket());
			return new IOException("Backend " + id + " was refused to " + what + ": " + answer);
		}
		if (e instanceof SdkClientException)
			return new BackendUnreachableException(
					"Backend " + id + " can't be reached to " + what + ": " + e.getMessage(), networkCause(e));
		return new IOException("Backend " + id + " couldn't " + what + ": " + e.getClass().getSimpleName());
	}

	/** @return the answer's S3 error code, if it has one that looks like one */
	private static String errorCode(SdkException e) {
		String code = e instanceof AwsServiceException aws && aws.awsErrorDetails() != null
				? aws.awsErrorDetails().errorCode()
				: null;
		return code != null && ERROR_CODE.matcher(code).matches() ? code : null;
	}

	/**
	 * Maps an upload's failure as {@link #failure} does, and tells a missing bucket from a missing upload when the
	 * answer is a {@code 404} with no code. A server that refuses an upload before {@code 100 Continue}, as Garage
	 * and Wasabi may, reaches the SDK's URL-connection client as a status with no body; a {@code GET} sends no body,
	 * so a list says whether the bucket is there.
	 */
	private IOException uploadFailure(String what, SdkException e) {
		IOException mapped = failure(what, e);
		if (e instanceof SdkServiceException service && service.statusCode() == 404 && errorCode(e) == null
				&& !(mapped instanceof BackendFailingException) && bucketMissing())
			return new BackendFailingException("Backend " + id + ": bucket not found: " + bucket());
		return mapped;
	}

	/** @return whether a list of the bucket says it doesn't exist; any other answer, or none, says no */
	private boolean bucketMissing() {
		try {
			client.listObjectsV2(b -> b.bucket(bucket()).prefix(settings.prefix()).maxKeys(1));
			return false;
		} catch (SdkException e) {
			return "NoSuchBucket".equals(errorCode(e));
		}
	}

	/**
	 * Maps a failure of the backend's own check, or of writing its marker: any refusal there is the backend's, as no
	 * file is involved.
	 */
	private BackendFailingException checkFailure(IOException mapped) {
		return mapped instanceof BackendFailingException failing ? failing
				: new BackendFailingException(mapped.getMessage());
	}

	/**
	 * @return the first cause that isn't the SDK's, as the {@code java.net} exception of a network failure, or null.
	 *         The SDK's exception isn't kept: it carries the earlier attempts' failures as suppressed exceptions,
	 *         which can be a provider's answers.
	 */
	private static Throwable networkCause(SdkException e) {
		Throwable cause = e.getCause();
		while (cause instanceof SdkException && cause.getCause() != cause)
			cause = cause.getCause();
		return cause instanceof SdkException ? null : cause;
	}

	private static String base64(byte[] md5) {
		return Base64.getEncoder().encodeToString(md5);
	}

	private static MessageDigest digest() {
		try {
			return MessageDigest.getInstance("MD5");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static byte[] md5(byte[] bytes) {
		return digest().digest(bytes);
	}

	/**
	 * @param whole also hashes the bytes into this, if not null
	 * @return the MD5 of the file's bytes from offset, that many
	 */
	private static byte[] md5(Path file, long offset, long length, MessageDigest whole) throws IOException {
		MessageDigest part = digest();
		ByteBuffer buffer = ByteBuffer.allocate(1 << 16);
		try (FileChannel in = FileChannel.open(file, StandardOpenOption.READ)) {
			long position = offset;
			long end = offset + length;
			while (position < end) {
				buffer.clear().limit((int) Math.min(buffer.capacity(), end - position));
				int n = in.read(buffer, position);
				if (n < 0)
					throw new IOException("The file " + file.getFileName() + " got shorter while it was read");
				buffer.flip();
				if (whole != null)
					whole.update(buffer.duplicate());
				part.update(buffer);
				position += n;
			}
		}
		return part.digest();
	}

	/**
	 * A range of the file to upload, which the SDK opens once per attempt. A failure to read it is the file's, not
	 * the backend's, so it is kept and thrown as it is, rather than as the SDK's failure to send. It reads through a
	 * {@link FileInputStream}, not a {@link FileChannel}: the SDK interrupts the thread when an attempt times out,
	 * which would close a channel and pass for the file's failure.
	 */
	private static final class Source implements ContentStreamProvider, Closeable {

		private final Path file;

		private final long offset;

		private final long length;

		private final List<InputStream> opened = new ArrayList<>();

		private volatile IOException failure;

		Source(Path file, long offset, long length) {
			this.file = file;
			this.offset = offset;
			this.length = length;
		}

		@Override
		public InputStream newStream() {
			FileInputStream in;
			try {
				in = new FileInputStream(file.toFile());
			} catch (IOException e) {
				throw new UncheckedIOException(record(e));
			}
			try {
				in.skipNBytes(offset);
			} catch (IOException e) {
				try {
					in.close();
				} catch (IOException suppressed) {
					e.addSuppressed(suppressed);
				}
				throw new UncheckedIOException(record(e));
			}
			InputStream stream = new Slice(in);
			synchronized (opened) {
				opened.add(stream);
			}
			return stream;
		}

		private IOException record(IOException e) {
			IOException local = new IOException(
					"The file " + file.getFileName() + " couldn't be read to upload it: " + e.getMessage(), e);
			if (failure == null)
				failure = local;
			return local;
		}

		/** @return the failure to read the file if there was one, else the backend's */
		IOException failureOr(IOException backend) {
			IOException local = failure;
			if (local == null)
				return backend;
			local.addSuppressed(backend);
			return local;
		}

		@Override
		public void close() {
			synchronized (opened) {
				for (InputStream stream : opened)
					try {
						stream.close();
					} catch (IOException ignored) {
						// It was read
					}
				opened.clear();
			}
		}

		/** The range's bytes, and an exception (recorded) if the file ends before them */
		private final class Slice extends InputStream {

			private final InputStream in;

			private long left = length;

			private volatile boolean closed;

			Slice(InputStream in) {
				this.in = in;
			}

			@Override
			public int read() throws IOException {
				byte[] one = new byte[1];
				int n = read(one, 0, 1);
				return n < 0 ? -1 : one[0] & 0xff;
			}

			@Override
			public int read(byte[] b, int off, int len) throws IOException {
				if (left == 0)
					return -1;
				if (len == 0)
					return 0;
				int n;
				try {
					n = in.read(b, off, (int) Math.min(len, left));
				} catch (IOException e) {
					if (closed)
						throw e;
					throw record(e);
				}
				if (n < 0) {
					if (closed)
						return -1;
					throw record(new IOException("it ended " + left + " bytes early"));
				}
				left -= n;
				return n;
			}

			@Override
			public void close() throws IOException {
				closed = true;
				in.close();
			}
		}
	}

	/**
	 * An object's bytes as they arrive. Reads that fail, and a stream that ends before the object's length, are the
	 * backend's being unreachable, not an object that is shorter: a dropped connection never reads as a whole file.
	 */
	private final class Download extends InputStream {

		private final ResponseInputStream<GetObjectResponse> in;

		private final String key;

		/** The object's length, -1 if not known */
		private final long expected;

		private long read;

		Download(ResponseInputStream<GetObjectResponse> in, String key, long expected) {
			this.in = in;
			this.key = key;
			this.expected = expected;
		}

		@Override
		public int read() throws IOException {
			byte[] one = new byte[1];
			int n = read(one, 0, 1);
			return n < 0 ? -1 : one[0] & 0xff;
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			if (len == 0)
				return 0;
			int n;
			try {
				n = in.read(b, off, len);
			} catch (IOException e) {
				throw new BackendUnreachableException("Backend " + id + " can't be reached: the read of " + key
						+ " stopped after " + read + " bytes: " + e.getMessage(), e);
			} catch (SdkException e) {
				throw failure("read " + key, e);
			}
			if (n < 0) {
				if (expected >= 0 && read < expected)
					throw new BackendUnreachableException("Backend " + id + " can't be reached: the read of " + key
							+ " ended after " + read + " of " + expected + " bytes");
				return -1;
			}
			read += n;
			return n;
		}

		@Override
		public void close() throws IOException {
			if (expected < 0 || read < expected)
				in.abort();
			in.close();
		}
	}
}
