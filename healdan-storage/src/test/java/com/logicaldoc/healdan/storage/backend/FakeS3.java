// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage.backend;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

/**
 * An S3 server for tests, on {@code 127.0.0.1} at a random port, over plain HTTP ({@link #start}) or HTTPS
 * ({@link #startTls}), holding its buckets in memory. It takes path-style requests only, as Healdan sends them, and
 * answers the calls {@link S3Backend} makes: PutObject, GetObject (with a Range), HeadObject, DeleteObject,
 * ListObjectsV2 by prefix, and multipart uploads (create, upload a part, complete, abort).
 * <p>
 * It refuses what S3 refuses: a {@code Content-MD5} that doesn't match the bytes it received ({@code 400 BadDigest})
 * or isn't an MD5 ({@code InvalidDigest}); a body whose length or SHA-256 isn't the one the request signed; another
 * access key ({@code 403 InvalidAccessKeyId}) or region ({@code 400 AuthorizationHeaderMalformed}); a missing bucket
 * ({@code 404 NoSuchBucket}, but a bare {@code 404} to {@code HEAD}, which has no body, as S3 answers it) or key
 * ({@code NoSuchKey}); an unknown upload ({@code NoSuchUpload}); a part list that doesn't match the parts
 * ({@code InvalidPart}, {@code InvalidPartOrder}) or with a part under 5 MiB but the last ({@code EntityTooSmall}). It
 * doesn't check signatures: a wrong secret is {@link #refuseSignatures}.
 * <p>
 * Over plain HTTP the SDK sends {@code aws-chunked} bodies in signed chunks
 * ({@code STREAMING-AWS4-HMAC-SHA256-PAYLOAD}, the plain length in {@code x-amz-decoded-content-length}), which it
 * decodes before any check; over HTTPS a plain body with {@code UNSIGNED-PAYLOAD}, as Wasabi gets. The client asks for
 * {@code Expect: 100-continue} before larger bodies; the JDK's server answers it itself, before a handler runs, so
 * the body follows at once.
 * <p>
 * It records every request ({@link #requests}), and misbehaves on demand ({@link #fail}, {@link #stall},
 * {@link #stallBody}, {@link #delay}, {@link #drop}, {@link #corrupt}, {@link #refuseSignatures},
 * {@link #echoSignatures}, {@link #denyAccess}) until {@link #behave}. Close it in an {@code @After} method.
 */
public final class FakeS3 implements AutoCloseable {

	/** The access key it expects */
	public static final String ACCESS_KEY = "GKfake0123456789abcdef01";

	/** The region it expects */
	public static final String REGION = "garage";

	/** The smallest part S3 takes, but for the last */
	static final int MIN_PART = 5 << 20;

	private static final String XMLNS = "http://s3.amazonaws.com/doc/2006-03-01/";

	private static final DateTimeFormatter HTTP_DATE = DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC);

	private static final Pattern CREDENTIAL = Pattern
			.compile("AWS4-HMAC-SHA256 Credential=([^/,]+)/(\\d{8})/([^/,]+)/s3/aws4_request,.*Signature=([0-9a-f]{64})");

	private static final Pattern HEX64 = Pattern.compile("[0-9a-f]{64}");

	private static final Pattern PART = Pattern.compile(
			"<Part>\\s*(?:<ETag>([^<]*)</ETag>\\s*<PartNumber>(\\d+)</PartNumber>|<PartNumber>(\\d+)</PartNumber>\\s*<ETag>([^<]*)</ETag>)\\s*</Part>");

	/** The test certificate, made once: every HTTPS fake serves it */
	private static Tls shared;

	/** What S3 is asked to do */
	public enum Op {
		PUT_OBJECT, GET_OBJECT, HEAD_OBJECT, DELETE_OBJECT, LIST_OBJECTS, CREATE_UPLOAD, UPLOAD_PART, COMPLETE_UPLOAD,
		ABORT_UPLOAD, OTHER
	}

	/**
	 * A request as it arrived.
	 *
	 * @param bytes the body's plain length, -1 if it wasn't read
	 * @param signatures the signatures it carried: its {@code Authorization} header's, and its chunks'
	 */
	public record Request(Op op, String method, String bucket, String key, Map<String, String> query,
			Map<String, List<String>> headers, int bytes, List<String> signatures) {

		/** @return the header's first value, null if it has none (names in any case) */
		public String header(String name) {
			List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
			return values == null || values.isEmpty() ? null : values.get(0);
		}

		/** @return the part's number, for an {@link Op#UPLOAD_PART} */
		public int partNumber() {
			String number = query.get("partNumber");
			return number == null ? 0 : Integer.parseInt(number);
		}
	}

	private enum Fault {
		ANSWER, STALL, STALL_BODY, DELAY, DROP, CORRUPT, SIGNATURE, ECHO
	}

	private record Rule(Predicate<Request> when, Fault fault, int status, String code, Duration delay,
			AtomicInteger times) {

		/** @return whether it applies, using up one of its times */
		boolean take(Request request) {
			if (!when.test(request))
				return false;
			if (times == null)
				return true;
			return times.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0;
		}
	}

	private record Stored(byte[] bytes, String etag, Instant modified) {
	}

	private record Part(byte[] bytes, byte[] md5) {
	}

	private record Upload(String bucket, String key, NavigableMap<Integer, Part> parts) {
	}

	/** A request S3 refuses before it does anything */
	private static final class Refusal extends Exception {

		private static final long serialVersionUID = 1L;

		final int status;

		final String code;

		Refusal(int status, String code, String message) {
			super(message, null, false, false);
			this.status = status;
			this.code = code;
		}
	}

	private final HttpServer server;

	private final ExecutorService executor;

	private final URI endpoint;

	private final Tls certificate;

	private final Map<String, NavigableMap<String, Stored>> buckets = new ConcurrentHashMap<>();

	private final Map<String, Upload> uploads = new ConcurrentHashMap<>();

	private final List<Request> requests = new CopyOnWriteArrayList<>();

	private final List<Rule> rules = new CopyOnWriteArrayList<>();

	/** Holds stalled requests until {@link #behave} or {@link #close} */
	private volatile CountDownLatch stalled = new CountDownLatch(1);

	private boolean trusting;

	private FakeS3(boolean https) throws IOException {
		InetSocketAddress address = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0);
		if (https) {
			certificate = tls();
			HttpsServer secure = HttpsServer.create(address, 0);
			secure.setHttpsConfigurator(new HttpsConfigurator(certificate.server()));
			server = secure;
		} else {
			certificate = null;
			server = HttpServer.create(address, 0);
		}
		executor = Executors.newCachedThreadPool(runnable -> {
			Thread thread = new Thread(runnable, "fake-s3");
			thread.setDaemon(true);
			return thread;
		});
		server.setExecutor(executor);
		server.createContext("/", this::handle);
		server.start();
		endpoint = URI.create((https ? "https" : "http") + "://127.0.0.1:" + server.getAddress().getPort());
	}

	/** @return a server over plain HTTP */
	public static FakeS3 start() {
		try {
			return new FakeS3(false);
		} catch (IOException e) {
			throw new IllegalStateException("The fake S3 server couldn't start", e);
		}
	}

	/**
	 * @return a server over HTTPS, with a certificate for {@code 127.0.0.1} that {@link S3Backend} doesn't trust until
	 *         {@link #trust}
	 */
	public static FakeS3 startTls() {
		try {
			return new FakeS3(true);
		} catch (IOException e) {
			throw new IllegalStateException("The fake S3 server couldn't start", e);
		}
	}

	/**
	 * Makes the S3 backends built from now on trust this server's certificate, and only it, until {@link #close}. The
	 * client still checks the address against the certificate, as for any server.
	 *
	 * @return this server
	 */
	public synchronized FakeS3 trust() {
		if (certificate == null)
			throw new IllegalStateException("The fake S3 server isn't on HTTPS");
		S3Backend.trustManagers = certificate::trusted;
		trusting = true;
		return this;
	}

	/** @return {@code http://127.0.0.1:<port>}, or {@code https} */
	public URI endpoint() {
		return endpoint;
	}

	/**
	 * @return settings for a backend in that bucket, under that prefix: this server, its region and access key, path
	 *         style, connection and read timeouts of a second, 20 seconds for an upload's attempt, and one attempt
	 */
	public S3Settings settings(String bucket, String prefix) {
		return new S3Settings(endpoint, REGION, bucket, prefix, true, ACCESS_KEY, Duration.ofSeconds(1),
				Duration.ofSeconds(1), Duration.ofSeconds(20), 1);
	}

	/** Makes a bucket, if it isn't there */
	public FakeS3 createBucket(String name) {
		buckets.computeIfAbsent(name, n -> new ConcurrentSkipListMap<>());
		return this;
	}

	/** @return the object's bytes, if there is one */
	public Optional<byte[]> object(String bucket, String key) {
		NavigableMap<String, Stored> objects = buckets.get(bucket);
		Stored stored = objects == null ? null : objects.get(key);
		return stored == null ? Optional.empty() : Optional.of(stored.bytes().clone());
	}

	/** Puts an object directly, as another client would have */
	public void putObject(String bucket, String key, byte[] bytes) {
		createBucket(bucket);
		buckets.get(bucket).put(key, new Stored(bytes.clone(), etag(md5(bytes)), Instant.now()));
	}

	/** @return the bucket's keys, in order */
	public List<String> keys(String bucket) {
		NavigableMap<String, Stored> objects = buckets.get(bucket);
		return objects == null ? List.of() : List.copyOf(objects.keySet());
	}

	/** @return how many multipart uploads are open: neither completed nor aborted */
	public int openUploads() {
		return uploads.size();
	}

	/** @return every request so far, in order */
	public List<Request> requests() {
		return List.copyOf(requests);
	}

	/** @return the requests so far that asked for that */
	public List<Request> requests(Op op) {
		return requests.stream().filter(r -> r.op() == op).toList();
	}

	/** Forgets the requests so far */
	public void clearRequests() {
		requests.clear();
	}

	/** Answers every request with this error until {@link #behave} */
	public FakeS3 fail(int status, String code) {
		return rule(r -> true, Fault.ANSWER, status, code, null, null);
	}

	/** Answers every request of that kind with this error until {@link #behave} */
	public FakeS3 fail(Op op, int status, String code) {
		return rule(r -> r.op() == op, Fault.ANSWER, status, code, null, null);
	}

	/** Answers that part's upload with this error until {@link #behave} */
	public FakeS3 failPart(int number, int status, String code) {
		return rule(part(number), Fault.ANSWER, status, code, null, null);
	}

	/**
	 * Reads the first MiB of every upload's body (a put or a part) and then stops reading, without answering, until
	 * {@link #behave} or {@link #close}. A client sending more than the sockets' buffers hold is then stuck in a
	 * write, which no read timeout ends.
	 */
	public FakeS3 stallBody() {
		return rule(r -> r.op() == Op.PUT_OBJECT || r.op() == Op.UPLOAD_PART, Fault.STALL_BODY, 0, null, null, null);
	}

	/** As {@link #stallBody}, for that part's upload only */
	public FakeS3 stallBody(int number) {
		return rule(part(number), Fault.STALL_BODY, 0, null, null, null);
	}

	/** Waits that long before answering every request of that kind (its body read), until {@link #behave} */
	public FakeS3 delay(Op op, Duration delay) {
		return rule(r -> r.op() == op, Fault.DELAY, 0, null, delay, null);
	}

	/**
	 * Takes every request and never answers or reads its body, until {@link #behave} or {@link #close} (then drops
	 * the connection)
	 */
	public FakeS3 stall() {
		return rule(r -> true, Fault.STALL, 0, null, null, null);
	}

	/** Reads every request's body, then closes the connection without an answer, until {@link #behave} */
	public FakeS3 drop() {
		return rule(r -> true, Fault.DROP, 0, null, null, null);
	}

	/** Drops the connection of that part's upload, until {@link #behave} */
	public FakeS3 dropPart(int number) {
		return rule(part(number), Fault.DROP, 0, null, null, null);
	}

	/** Flips a byte of the next upload's body (a put or a part) on its way in, before any check */
	public FakeS3 corrupt() {
		return rule(r -> r.op() == Op.PUT_OBJECT || r.op() == Op.UPLOAD_PART, Fault.CORRUPT, 0, null, null,
				new AtomicInteger(1));
	}

	/** Flips a byte of that part's body on its way in, until {@link #behave} */
	public FakeS3 corruptPart(int number) {
		return rule(part(number), Fault.CORRUPT, 0, null, null, null);
	}

	/**
	 * Answers every request as Wasabi answers a wrong secret, until {@link #behave}: {@code 403 SignatureDoesNotMatch},
	 * with the signature it was sent in {@code SignatureProvided}, as AWS's answer has it.
	 */
	public FakeS3 refuseSignatures() {
		return rule(r -> true, Fault.SIGNATURE, 403, "SignatureDoesNotMatch", null, null);
	}

	/**
	 * As {@link #refuseSignatures}, and repeats the signature in the answer's message too: the worst a server could
	 * do, which none of those tried does.
	 */
	public FakeS3 echoSignatures() {
		return rule(r -> true, Fault.ECHO, 403, "SignatureDoesNotMatch", null, null);
	}

	/** Answers every request as Garage answers a wrong secret, {@code 403 AccessDenied}, until {@link #behave} */
	public FakeS3 denyAccess() {
		return fail(403, "AccessDenied");
	}

	/** Stops misbehaving, and lets stalled requests go (unanswered) */
	public FakeS3 behave() {
		rules.clear();
		CountDownLatch release = stalled;
		stalled = new CountDownLatch(1);
		release.countDown();
		return this;
	}

	@Override
	public synchronized void close() {
		stalled.countDown();
		server.stop(0);
		executor.shutdownNow();
		if (trusting) {
			S3Backend.trustManagers = null;
			trusting = false;
		}
	}

	private static Predicate<Request> part(int number) {
		return r -> r.op() == Op.UPLOAD_PART && r.partNumber() == number;
	}

	private FakeS3 rule(Predicate<Request> when, Fault fault, int status, String code, Duration delay,
			AtomicInteger times) {
		rules.add(new Rule(when, fault, status, code, delay, times));
		return this;
	}

	private Rule rule(Request request) {
		for (Rule rule : rules)
			if (rule.take(request))
				return rule;
		return null;
	}

	private void handle(HttpExchange exchange) {
		String requestId = HexFormat.of().formatHex(md5(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8)))
				.substring(0, 16).toUpperCase(Locale.ROOT);
		exchange.getResponseHeaders().set("x-amz-request-id", requestId);
		try {
			Request request = parse(exchange);
			Rule rule = rule(request);
			if (rule != null && rule.fault() == Fault.STALL) {
				requests.add(request);
				stalled.await(30, TimeUnit.SECONDS);
				return;
			}
			if (rule != null && rule.fault() == Fault.STALL_BODY) {
				exchange.getRequestBody().readNBytes(1 << 20);
				requests.add(request);
				stalled.await(30, TimeUnit.SECONDS);
				return;
			}

			byte[] body;
			try {
				Decoded decoded = body(exchange, request);
				body = decoded.bytes;
				request = new Request(request.op(), request.method(), request.bucket(), request.key(), request.query(),
						request.headers(), body.length, withChunkSignatures(request.signatures(), decoded.signatures));
				if (rule != null && rule.fault() == Fault.CORRUPT && body.length > 0)
					body[body.length / 2] ^= 0x5a;
				checkDigests(request, body);
			} catch (Refusal refusal) {
				requests.add(request);
				error(exchange, request, refusal.status, refusal.code, refusal.getMessage(), Map.of());
				return;
			}
			requests.add(request);

			if (rule != null)
				switch (rule.fault()) {
					case DROP -> {
						return;
					}
					case ANSWER -> {
						error(exchange, request, rule.status(), rule.code(), "The fake was told to answer this",
								Map.of());
						return;
					}
					case SIGNATURE, ECHO -> {
						String signature = request.signatures().isEmpty() ? "" : request.signatures().get(0);
						Map<String, String> details = new LinkedHashMap<>();
						details.put("AWSAccessKeyId", ACCESS_KEY);
						details.put("StringToSign", "AWS4-HMAC-SHA256\n" + request.header("x-amz-date") + "\n…");
						details.put("SignatureProvided", signature);
						error(exchange, request, rule.status(), rule.code(),
								"The request signature we calculated does not match the signature you provided"
										+ (rule.fault() == Fault.ECHO ? " (" + signature + ")" : "")
										+ ". Check your key and signing method.",
								details);
						return;
					}
					case DELAY -> Thread.sleep(rule.delay().toMillis());
					default -> {
						// CORRUPT was applied to the body
					}
				}

			Refusal refused = authorise(request);
			if (refused != null) {
				error(exchange, request, refused.status, refused.code, refused.getMessage(), Map.of());
				return;
			}
			answer(exchange, request, body);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (IOException e) {
			// The client went away
		} catch (RuntimeException e) {
			// A fake that fails says so to the client, rather than leaving it to time out
			try {
				exchange.getResponseHeaders().set("Content-Type", "application/xml");
				byte[] text = ("<Error><Code>InternalError</Code><Message>" + xml("The fake failed: " + e)
						+ "</Message></Error>").getBytes(StandardCharsets.UTF_8);
				exchange.sendResponseHeaders(500, text.length);
				exchange.getResponseBody().write(text);
			} catch (IOException | RuntimeException ignored) {
				// The answer had begun; closing the exchange ends it
			}
		} finally {
			// Without an answer, this closes the connection
			exchange.close();
		}
	}

	private static List<String> withChunkSignatures(List<String> signatures, List<String> chunks) {
		if (chunks.isEmpty())
			return signatures;
		List<String> all = new ArrayList<>(signatures);
		all.addAll(chunks);
		return List.copyOf(all);
	}

	private Request parse(HttpExchange exchange) {
		String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
		String path = exchange.getRequestURI().getRawPath();
		String rest = path.startsWith("/") ? path.substring(1) : path;
		int slash = rest.indexOf('/');
		String bucket = decode(slash < 0 ? rest : rest.substring(0, slash));
		String key = slash < 0 ? "" : decode(rest.substring(slash + 1));

		Map<String, String> query = new LinkedHashMap<>();
		String raw = exchange.getRequestURI().getRawQuery();
		if (raw != null && !raw.isEmpty())
			for (String pair : raw.split("&")) {
				int equals = pair.indexOf('=');
				query.put(decode(equals < 0 ? pair : pair.substring(0, equals)),
						equals < 0 ? "" : decode(pair.substring(equals + 1)));
			}

		Map<String, List<String>> headers = new LinkedHashMap<>();
		exchange.getRequestHeaders()
				.forEach((name, values) -> headers.put(name.toLowerCase(Locale.ROOT), List.copyOf(values)));

		List<String> signatures = new ArrayList<>();
		List<String> authorization = headers.get("authorization");
		if (authorization != null && !authorization.isEmpty()) {
			Matcher matcher = CREDENTIAL.matcher(authorization.get(0));
			if (matcher.find())
				signatures.add(matcher.group(4));
		}
		return new Request(op(method, key, query, headers), method, bucket, key, Map.copyOf(query),
				Map.copyOf(headers), -1, List.copyOf(signatures));
	}

	private static Op op(String method, String key, Map<String, String> query, Map<String, List<String>> headers) {
		boolean copy = headers.containsKey("x-amz-copy-source");
		if (key.isEmpty())
			return "GET".equals(method) && "2".equals(query.get("list-type")) ? Op.LIST_OBJECTS : Op.OTHER;
		return switch (method) {
			case "PUT" -> copy ? Op.OTHER
					: query.containsKey("partNumber") && query.containsKey("uploadId") ? Op.UPLOAD_PART
							: query.isEmpty() ? Op.PUT_OBJECT : Op.OTHER;
			case "GET" -> query.isEmpty() ? Op.GET_OBJECT : Op.OTHER;
			case "HEAD" -> query.isEmpty() ? Op.HEAD_OBJECT : Op.OTHER;
			case "DELETE" -> query.containsKey("uploadId") ? Op.ABORT_UPLOAD
					: query.isEmpty() ? Op.DELETE_OBJECT : Op.OTHER;
			case "POST" -> query.containsKey("uploads") ? Op.CREATE_UPLOAD
					: query.containsKey("uploadId") ? Op.COMPLETE_UPLOAD : Op.OTHER;
			default -> Op.OTHER;
		};
	}

	private static String decode(String raw) {
		return URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8);
	}

	private record Decoded(byte[] bytes, List<String> signatures) {
	}

	/** Reads the body, decoding aws-chunked, and checks it against the length and hash the request signed */
	private static Decoded body(HttpExchange exchange, Request request) throws IOException, Refusal {
		String sha256 = request.header("x-amz-content-sha256");
		if (sha256 == null)
			throw new Refusal(400, "InvalidRequest", "Missing required header for this request: x-amz-content-sha256");
		InputStream in = exchange.getRequestBody();
		if (sha256.startsWith("STREAMING-")) {
			String decodedLength = request.header("x-amz-decoded-content-length");
			if (decodedLength == null)
				throw new Refusal(411, "MissingContentLength",
						"You must provide the x-amz-decoded-content-length header");
			long expected = Long.parseLong(decodedLength);
			Decoded decoded = chunks(in, sha256.startsWith("STREAMING-AWS4-HMAC-SHA256-PAYLOAD"),
					(int) Math.min(expected, Integer.MAX_VALUE - 8));
			if (decoded.bytes.length != expected)
				throw new Refusal(400, "IncompleteBody",
						"The request body is " + decoded.bytes.length + " bytes, not " + expected);
			return decoded;
		}
		byte[] body = in.readAllBytes();
		String length = request.header("content-length");
		if (length != null && Long.parseLong(length) != body.length)
			throw new Refusal(400, "IncompleteBody", "The request body is shorter than its Content-Length");
		if (HEX64.matcher(sha256).matches() && !sha256.equals(HexFormat.of().formatHex(sha256(body))))
			throw new Refusal(400, "XAmzContentSHA256Mismatch",
					"The provided 'x-amz-content-sha256' header does not match what was computed.");
		else if (!HEX64.matcher(sha256).matches() && !"UNSIGNED-PAYLOAD".equals(sha256))
			throw new Refusal(400, "InvalidArgument", "x-amz-content-sha256 must be UNSIGNED-PAYLOAD or a SHA-256");
		return new Decoded(body, List.of());
	}

	/**
	 * Decodes aws-chunked: {@code <hex size>;chunk-signature=<64 hex>\r\n<bytes>\r\n} repeated, then a chunk of size 0,
	 * any trailer lines and an empty line.
	 */
	private static Decoded chunks(InputStream in, boolean signed, int expected) throws IOException, Refusal {
		ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(expected, 32));
		List<String> signatures = new ArrayList<>();
		while (true) {
			String header = line(in);
			int semicolon = header.indexOf(';');
			String hex = semicolon < 0 ? header : header.substring(0, semicolon);
			int size;
			try {
				size = Integer.parseInt(hex, 16);
			} catch (NumberFormatException e) {
				throw new Refusal(400, "IncompleteBody", "Not a chunk header: " + header);
			}
			if (signed) {
				String extension = semicolon < 0 ? "" : header.substring(semicolon + 1);
				if (!extension.startsWith("chunk-signature=")
						|| !HEX64.matcher(extension.substring("chunk-signature=".length())).matches())
					throw new Refusal(400, "IncompleteBody", "A signed chunk without its signature: " + header);
				signatures.add(extension.substring("chunk-signature=".length()));
			}
			if (size < 0)
				throw new Refusal(400, "IncompleteBody", "Not a chunk header: " + header);
			if (size == 0) {
				while (!line(in).isEmpty()) {
					// a trailer line
				}
				break;
			}
			byte[] chunk = in.readNBytes(size);
			if (chunk.length != size)
				throw new Refusal(400, "IncompleteBody", "A chunk ended early");
			out.write(chunk);
			if (!line(in).isEmpty())
				throw new Refusal(400, "IncompleteBody", "A chunk's bytes don't end with CRLF");
		}
		if (in.read() >= 0)
			throw new Refusal(400, "IncompleteBody", "Bytes after the last chunk");
		return new Decoded(out.toByteArray(), signatures);
	}

	/** @return a line, without its CRLF */
	private static String line(InputStream in) throws IOException, Refusal {
		ByteArrayOutputStream line = new ByteArrayOutputStream();
		int previous = -1;
		while (true) {
			int b = in.read();
			if (b < 0)
				throw new Refusal(400, "IncompleteBody", "The body ended inside a chunk header");
			if (previous == '\r' && b == '\n') {
				byte[] bytes = line.toByteArray();
				return new String(bytes, 0, bytes.length - 1, StandardCharsets.US_ASCII);
			}
			line.write(b);
			if (line.size() > 4096)
				throw new Refusal(400, "IncompleteBody", "A chunk header too long");
			previous = b;
		}
	}

	/** Content-MD5, when sent, against the bytes received */
	private static void checkDigests(Request request, byte[] body) throws Refusal {
		String contentMd5 = request.header("content-md5");
		if (contentMd5 == null)
			return;
		byte[] sent;
		try {
			sent = Base64.getDecoder().decode(contentMd5);
		} catch (IllegalArgumentException e) {
			throw new Refusal(400, "InvalidDigest", "The Content-MD5 you specified was invalid.");
		}
		if (sent.length != 16)
			throw new Refusal(400, "InvalidDigest", "The Content-MD5 you specified was invalid.");
		if (!MessageDigest.isEqual(sent, md5(body)))
			throw new Refusal(400, "BadDigest", "The Content-MD5 you specified did not match what we received.");
	}

	private static Refusal authorise(Request request) {
		String authorization = request.header("authorization");
		Matcher matcher = authorization == null ? null : CREDENTIAL.matcher(authorization);
		if (matcher == null || !matcher.find())
			return new Refusal(403, "AccessDenied", "Access Denied");
		if (!ACCESS_KEY.equals(matcher.group(1)))
			return new Refusal(403, "InvalidAccessKeyId",
					"The AWS Access Key Id you provided does not exist in our records.");
		if (!REGION.equals(matcher.group(3)))
			return new Refusal(400, "AuthorizationHeaderMalformed",
					"The authorization header is malformed; the region '" + matcher.group(3) + "' is wrong; expecting '"
							+ REGION + "'");
		return null;
	}

	private void answer(HttpExchange exchange, Request request, byte[] body) throws IOException {
		NavigableMap<String, Stored> objects = buckets.get(request.bucket());
		if (request.op() == Op.OTHER) {
			error(exchange, request, 501, "NotImplemented", "The fake doesn't do " + request.method(), Map.of());
			return;
		}
		if (objects == null) {
			error(exchange, request, 404, "NoSuchBucket", "The specified bucket does not exist",
					Map.of("BucketName", request.bucket()));
			return;
		}
		switch (request.op()) {
			case PUT_OBJECT -> {
				Stored stored = new Stored(body, etag(md5(body)), Instant.now());
				objects.put(request.key(), stored);
				exchange.getResponseHeaders().set("ETag", stored.etag());
				send(exchange, request, 200, null, new byte[0]);
			}
			case GET_OBJECT, HEAD_OBJECT -> get(exchange, request, objects.get(request.key()));
			case DELETE_OBJECT -> {
				objects.remove(request.key());
				send(exchange, request, 204, null, new byte[0]);
			}
			case LIST_OBJECTS -> list(exchange, request, objects);
			case CREATE_UPLOAD -> {
				String id = UUID.randomUUID().toString().replace("-", "");
				uploads.put(id, new Upload(request.bucket(), request.key(), new ConcurrentSkipListMap<>()));
				send(exchange, request, 200, "application/xml",
						xmlDocument("InitiateMultipartUploadResult", "<Bucket>" + xml(request.bucket()) + "</Bucket><Key>"
								+ xml(request.key()) + "</Key><UploadId>" + id + "</UploadId>"));
			}
			case UPLOAD_PART -> {
				Upload upload = upload(request);
				if (upload == null) {
					noSuchUpload(exchange, request);
					return;
				}
				int number = request.partNumber();
				if (number < 1 || number > 10000) {
					error(exchange, request, 400, "InvalidArgument", "Part number must be between 1 and 10000",
							Map.of());
					return;
				}
				byte[] md5 = md5(body);
				upload.parts().put(number, new Part(body, md5));
				exchange.getResponseHeaders().set("ETag", etag(md5));
				send(exchange, request, 200, null, new byte[0]);
			}
			case COMPLETE_UPLOAD -> complete(exchange, request, body, objects);
			case ABORT_UPLOAD -> {
				if (upload(request) == null) {
					noSuchUpload(exchange, request);
					return;
				}
				uploads.remove(request.query().get("uploadId"));
				send(exchange, request, 204, null, new byte[0]);
			}
			default -> error(exchange, request, 501, "NotImplemented", "Not implemented", Map.of());
		}
	}

	private Upload upload(Request request) {
		Upload upload = uploads.get(request.query().get("uploadId"));
		return upload != null && upload.bucket().equals(request.bucket()) && upload.key().equals(request.key()) ? upload
				: null;
	}

	private static void noSuchUpload(HttpExchange exchange, Request request) throws IOException {
		error(exchange, request, 404, "NoSuchUpload",
				"The specified upload does not exist. The upload ID may be invalid, or the upload may have been aborted or completed.",
				Map.of("UploadId", String.valueOf(request.query().get("uploadId"))));
	}

	private static void get(HttpExchange exchange, Request request, Stored stored) throws IOException {
		if (stored == null) {
			error(exchange, request, 404, "NoSuchKey", "The specified key does not exist.",
					Map.of("Key", request.key()));
			return;
		}
		byte[] bytes = stored.bytes();
		exchange.getResponseHeaders().set("ETag", stored.etag());
		exchange.getResponseHeaders().set("Last-Modified", HTTP_DATE.format(stored.modified()));
		exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
		exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
		if (request.op() == Op.HEAD_OBJECT) {
			exchange.getResponseHeaders().set("Content-Length", Long.toString(bytes.length));
			exchange.sendResponseHeaders(200, -1);
			return;
		}
		long[] range = range(request.header("range"), bytes.length);
		if (range == null) {
			send(exchange, request, 200, null, bytes);
			return;
		}
		if (range.length == 0) {
			exchange.getResponseHeaders().set("Content-Range", "bytes */" + bytes.length);
			error(exchange, request, 416, "InvalidRange", "The requested range is not satisfiable",
					Map.of("RangeRequested", request.header("range"), "ActualObjectSize",
							Integer.toString(bytes.length)));
			return;
		}
		int first = (int) range[0];
		int last = (int) range[1];
		exchange.getResponseHeaders().set("Content-Range", "bytes " + first + "-" + last + "/" + bytes.length);
		exchange.sendResponseHeaders(206, last - first + 1L);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes, first, last - first + 1);
		}
	}

	/**
	 * @return null for no range or one S3 ignores, an empty array for one it can't satisfy, or the first and last
	 *         byte
	 */
	private static long[] range(String header, long size) {
		if (header == null || !header.startsWith("bytes=") || header.indexOf(',') >= 0)
			return null;
		String spec = header.substring("bytes=".length());
		int dash = spec.indexOf('-');
		if (dash < 0)
			return null;
		try {
			if (dash == 0) {
				long suffix = Long.parseLong(spec.substring(1));
				if (suffix <= 0 || size == 0)
					return new long[0];
				return new long[] { Math.max(0, size - suffix), size - 1 };
			}
			long first = Long.parseLong(spec.substring(0, dash));
			long last = dash == spec.length() - 1 ? size - 1 : Long.parseLong(spec.substring(dash + 1));
			if (last < first)
				return null;
			if (first >= size)
				return new long[0];
			return new long[] { first, Math.min(last, size - 1) };
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static void list(HttpExchange exchange, Request request, NavigableMap<String, Stored> objects)
			throws IOException {
		if (request.query().containsKey("delimiter")) {
			error(exchange, request, 501, "NotImplemented", "The fake lists without a delimiter only", Map.of());
			return;
		}
		String prefix = request.query().getOrDefault("prefix", "");
		boolean encode = "url".equals(request.query().get("encoding-type"));
		int max = Integer.parseInt(request.query().getOrDefault("max-keys", "1000"));
		String token = request.query().get("continuation-token");
		String after = token == null ? request.query().get("start-after")
				: new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);

		NavigableMap<String, Stored> from = after == null ? objects : objects.tailMap(after, false);
		StringBuilder contents = new StringBuilder();
		int count = 0;
		String last = null;
		boolean truncated = false;
		for (Map.Entry<String, Stored> entry : from.entrySet()) {
			if (!entry.getKey().startsWith(prefix))
				continue;
			if (count == max) {
				truncated = true;
				break;
			}
			Stored stored = entry.getValue();
			contents.append("<Contents><Key>").append(xml(encode ? urlEncode(entry.getKey()) : entry.getKey()))
					.append("</Key><LastModified>")
					.append(stored.modified().truncatedTo(ChronoUnit.MILLIS).toString())
					.append("</LastModified><ETag>").append(xml(stored.etag())).append("</ETag><Size>")
					.append(stored.bytes().length).append("</Size><StorageClass>STANDARD</StorageClass></Contents>");
			count++;
			last = entry.getKey();
		}
		StringBuilder result = new StringBuilder();
		result.append("<Name>").append(xml(request.bucket())).append("</Name><Prefix>")
				.append(xml(encode ? urlEncode(prefix) : prefix)).append("</Prefix><KeyCount>").append(count)
				.append("</KeyCount><MaxKeys>").append(max).append("</MaxKeys>");
		if (encode)
			result.append("<EncodingType>url</EncodingType>");
		result.append("<IsTruncated>").append(truncated).append("</IsTruncated>");
		if (token != null)
			result.append("<ContinuationToken>").append(xml(token)).append("</ContinuationToken>");
		if (truncated)
			result.append("<NextContinuationToken>")
					.append(Base64.getUrlEncoder().encodeToString(last.getBytes(StandardCharsets.UTF_8)))
					.append("</NextContinuationToken>");
		result.append(contents);
		send(exchange, request, 200, "application/xml", xmlDocument("ListBucketResult", result.toString()));
	}

	private void complete(HttpExchange exchange, Request request, byte[] body, NavigableMap<String, Stored> objects)
			throws IOException {
		Upload upload = upload(request);
		if (upload == null) {
			noSuchUpload(exchange, request);
			return;
		}
		Matcher matcher = PART.matcher(new String(body, StandardCharsets.UTF_8));
		List<Part> parts = new ArrayList<>();
		int previous = 0;
		while (matcher.find()) {
			int number = Integer.parseInt(matcher.group(2) != null ? matcher.group(2) : matcher.group(3));
			String etag = (matcher.group(1) != null ? matcher.group(1) : matcher.group(4)).replace("&quot;", "\"")
					.replace("&#34;", "\"");
			if (number <= previous) {
				error(exchange, request, 400, "InvalidPartOrder", "The list of parts was not in ascending order.",
						Map.of());
				return;
			}
			Part part = upload.parts().get(number);
			if (part == null || !etag(part.md5()).equals(etag)) {
				error(exchange, request, 400, "InvalidPart",
						"One or more of the specified parts could not be found, or its entity tag didn't match.",
						Map.of("PartNumber", Integer.toString(number)));
				return;
			}
			parts.add(part);
			previous = number;
		}
		if (parts.isEmpty()) {
			error(exchange, request, 400, "MalformedXML", "The XML you provided was not well-formed.", Map.of());
			return;
		}
		for (int i = 0; i < parts.size() - 1; i++)
			if (parts.get(i).bytes().length < MIN_PART) {
				error(exchange, request, 400, "EntityTooSmall",
						"Your proposed upload is smaller than the minimum allowed object size.", Map.of());
				return;
			}
		ByteArrayOutputStream whole = new ByteArrayOutputStream(
				parts.stream().mapToInt(p -> p.bytes().length).sum());
		MessageDigest digests = digest("MD5");
		for (Part part : parts) {
			whole.write(part.bytes());
			digests.update(part.md5());
		}
		String etag = "\"" + HexFormat.of().formatHex(digests.digest()) + "-" + parts.size() + "\"";
		uploads.remove(request.query().get("uploadId"));
		objects.put(request.key(), new Stored(whole.toByteArray(), etag, Instant.now()));
		send(exchange, request, 200, "application/xml",
				xmlDocument("CompleteMultipartUploadResult",
						"<Location>" + xml(endpoint + "/" + request.bucket() + "/" + request.key()) + "</Location><Bucket>"
								+ xml(request.bucket()) + "</Bucket><Key>" + xml(request.key()) + "</Key><ETag>"
								+ xml(etag) + "</ETag>"));
	}

	private static void error(HttpExchange exchange, Request request, int status, String code, String message,
			Map<String, String> details) throws IOException {
		StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<Error><Code>")
				.append(xml(code)).append("</Code><Message>").append(xml(message)).append("</Message>");
		details.forEach((name, value) -> xml.append('<').append(name).append('>').append(xml(value)).append("</")
				.append(name).append('>'));
		xml.append("<RequestId>").append(exchange.getResponseHeaders().getFirst("x-amz-request-id"))
				.append("</RequestId></Error>");
		send(exchange, request, status, "application/xml", xml.toString().getBytes(StandardCharsets.UTF_8));
	}

	/** Sends an answer; a HEAD's has no body, as HTTP says */
	private static void send(HttpExchange exchange, Request request, int status, String contentType, byte[] body)
			throws IOException {
		if (contentType != null)
			exchange.getResponseHeaders().set("Content-Type", contentType);
		if (request.op() == Op.HEAD_OBJECT || "HEAD".equals(request.method()) || body.length == 0) {
			exchange.sendResponseHeaders(status, -1);
			return;
		}
		exchange.sendResponseHeaders(status, body.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(body);
		}
	}

	private static byte[] xmlDocument(String root, String content) {
		return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<" + root + " xmlns=\"" + XMLNS + "\">" + content + "</"
				+ root + ">").getBytes(StandardCharsets.UTF_8);
	}

	private static String xml(String text) {
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
				.replace("'", "&apos;");
	}

	private static String urlEncode(String text) {
		return URLEncoder.encode(text, StandardCharsets.UTF_8);
	}

	private static String etag(byte[] md5) {
		return "\"" + HexFormat.of().formatHex(md5) + "\"";
	}

	static byte[] md5(byte[] bytes) {
		return digest("MD5").digest(bytes);
	}

	private static byte[] sha256(byte[] bytes) {
		return digest("SHA-256").digest(bytes);
	}

	private static MessageDigest digest(String algorithm) {
		try {
			return MessageDigest.getInstance(algorithm);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/** A self-signed certificate for {@code 127.0.0.1}, and what trusts it alone */
	private record Tls(SSLContext server, TrustManager[] trusted) {
	}

	private static synchronized Tls tls() {
		if (shared == null)
			try {
				KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
				generator.initialize(256);
				KeyPair pair = generator.generateKeyPair();
				X500Name name = new X500Name("CN=127.0.0.1");
				Instant now = Instant.now();
				JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(name,
						BigInteger.valueOf(now.toEpochMilli()), Date.from(now.minus(Duration.ofDays(1))),
						Date.from(now.plus(Duration.ofDays(1))), name, pair.getPublic());
				builder.addExtension(Extension.subjectAlternativeName, false,
						new GeneralNames(new GeneralName(GeneralName.iPAddress, "127.0.0.1")));
				X509Certificate certificate = new JcaX509CertificateConverter().getCertificate(
						builder.build(new JcaContentSignerBuilder("SHA256withECDSA").build(pair.getPrivate())));

				char[] password = "fake-s3".toCharArray();
				KeyStore keys = KeyStore.getInstance("PKCS12");
				keys.load(null, null);
				keys.setKeyEntry("fake", pair.getPrivate(), password, new Certificate[] { certificate });
				KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
				keyManagers.init(keys, password);
				SSLContext server = SSLContext.getInstance("TLS");
				server.init(keyManagers.getKeyManagers(), null, null);

				KeyStore trusted = KeyStore.getInstance("PKCS12");
				trusted.load(null, null);
				trusted.setCertificateEntry("fake", certificate);
				TrustManagerFactory trustManagers = TrustManagerFactory
						.getInstance(TrustManagerFactory.getDefaultAlgorithm());
				trustManagers.init(trusted);
				shared = new Tls(server, trustManagers.getTrustManagers());
			} catch (Exception e) {
				throw new IllegalStateException("The fake S3 server's certificate couldn't be made", e);
			}
		return shared;
	}
}
