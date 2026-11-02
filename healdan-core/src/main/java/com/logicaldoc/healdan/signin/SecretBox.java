package com.logicaldoc.healdan.signin;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.logicaldoc.util.spring.Context;

/**
 * Seals the secrets Healdan keeps for provider sign-in, a provider's client
 * secret and the refresh tokens of device tokens (M4, spec section 13), with
 * AES-256-GCM under a key in a file of the configuration folder.
 * <p>
 * The key is 32 random bytes, in a file only the app's user can read. It is
 * made the first time something is sealed, never when one is opened. A
 * database dump on its own carries no secret, and a lost key makes every
 * sealed value read as unset ({@link #open} answers null), which the person
 * puts right by entering the secret again.
 * <p>
 * A sealed value is {@code v1:} and the base64 of the IV (12 bytes), the
 * ciphertext and the tag. The values and the key are never logged.
 */
@Component
public class SecretBox {

	/** The system property that names the key file, which {@code docker/setenv.sh} sets */
	public static final String KEY_PROPERTY = "healdan.secretKey";

	private static final String PREFIX = "v1:";

	private static final int KEY_BYTES = 32;

	private static final int IV_BYTES = 12;

	private static final int TAG_BITS = 128;

	private static final SecureRandom RANDOM = new SecureRandom();

	/** Held while a key is made, by every box, so that two first uses make one key even for the same file */
	private static final Object MAKING = new Object();

	private static final Logger log = LoggerFactory.getLogger(SecretBox.class);

	private final Path keyFile;

	private final AtomicBoolean warned = new AtomicBoolean();

	/**
	 * @return the box in the application context
	 */
	public static SecretBox get() {
		return Context.get(SecretBox.class);
	}

	/**
	 * A box whose key file is the one named by the system property
	 * {@code healdan.secretKey}, or else {@code logicaldoc/conf/healdan-secret.key}
	 * in the user's home folder.
	 */
	public SecretBox() {
		this(defaultKeyFile());
	}

	/**
	 * @param keyFile where the key is, or will be made
	 */
	public SecretBox(Path keyFile) {
		this.keyFile = keyFile;
	}

	private static Path defaultKeyFile() {
		String configured = System.getProperty(KEY_PROPERTY);
		if (StringUtils.isNotBlank(configured))
			return Paths.get(configured.trim());
		return Paths.get(System.getProperty("user.home"), "logicaldoc", "conf", "healdan-secret.key");
	}

	/**
	 * Seals a secret, making the key file first if there is none.
	 * 
	 * @param plain the secret
	 * @return the sealed value, which differs each time; null if {@code plain} is
	 *         null
	 * @throws IllegalStateException if the key can't be read or made; the message
	 *         never holds the secret
	 */
	public String seal(String plain) {
		if (plain == null)
			return null;
		try {
			byte[] iv = new byte[IV_BYTES];
			RANDOM.nextBytes(iv);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(true), "AES"), new GCMParameterSpec(TAG_BITS, iv));
			byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
			return PREFIX + Base64.getEncoder()
					.encodeToString(ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array());
		} catch (GeneralSecurityException | IOException e) {
			throw new IllegalStateException("Can't seal a secret", e); // never the value itself
		}
	}

	/**
	 * Opens a sealed value.
	 * 
	 * @param sealed a value {@link #seal} made
	 * @return the secret; null if {@code sealed} is null or empty, or there is no
	 *         key file, or the key is not the one that sealed the value, or the
	 *         value is damaged. It logs once, at warning level, that a value
	 *         couldn't be opened.
	 */
	public String open(String sealed) {
		if (StringUtils.isEmpty(sealed))
			return null;
		try {
			byte[] key = key(false);
			if (key == null || !sealed.startsWith(PREFIX))
				return unopened();
			byte[] raw = Base64.getDecoder().decode(sealed.substring(PREFIX.length()));
			if (raw.length < IV_BYTES + TAG_BITS / 8)
				return unopened();
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
					new GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES));
			return new String(cipher.doFinal(raw, IV_BYTES, raw.length - IV_BYTES), StandardCharsets.UTF_8);
		} catch (GeneralSecurityException | IOException | IllegalArgumentException e) {
			return unopened();
		}
	}

	/**
	 * @return true if the key file is there and holds a key
	 */
	public boolean hasKey() {
		try {
			return key(false) != null;
		} catch (IOException e) {
			return false;
		}
	}

	private String unopened() {
		if (warned.compareAndSet(false, true))
			log.warn("A sealed secret can't be opened: the key file {} is missing or doesn't fit, or the value is "
					+ "damaged. The secret reads as unset until it is entered again.", keyFile);
		return null;
	}

	/**
	 * @param make whether to make the key file when there is none
	 * @return the key, or null if there is no usable key file and {@code make} is
	 *         false
	 */
	private byte[] key(boolean make) throws IOException {
		byte[] key = read();
		if (key != null || !make)
			return key;
		synchronized (MAKING) {
			key = read();
			if (key != null)
				return key;
			return makeKey();
		}
	}

	private byte[] read() throws IOException {
		if (!Files.isRegularFile(keyFile) || Files.size(keyFile) != KEY_BYTES)
			return null;
		byte[] key = Files.readAllBytes(keyFile);
		return key.length == KEY_BYTES ? key : null;
	}

	private byte[] makeKey() throws IOException {
		boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
		boolean leftover = Files.exists(keyFile);
		// A file of another size isn't ours to overwrite; an empty one is what a start that died left
		if (leftover && Files.size(keyFile) > 0)
			throw new IOException("The key file " + keyFile + " is not " + KEY_BYTES + " bytes");

		byte[] key = new byte[KEY_BYTES];
		RANDOM.nextBytes(key);
		Path folder = keyFile.toAbsolutePath().getParent();
		if (folder != null)
			Files.createDirectories(folder);

		// Made and written through one open file, with the mode set at creation: it is never readable by others
		Set<StandardOpenOption> options = leftover ? EnumSet.of(StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
				: EnumSet.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
		FileAttribute<?>[] attributes = posix && !leftover
				? new FileAttribute<?>[] { PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")) }
				: new FileAttribute<?>[0];
		try (FileChannel channel = FileChannel.open(keyFile, options, attributes)) {
			channel.write(ByteBuffer.wrap(key));
			channel.force(true);
		}
		if (posix && leftover)
			Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-------"));
		return key;
	}
}
