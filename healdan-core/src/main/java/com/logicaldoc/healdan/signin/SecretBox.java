package com.logicaldoc.healdan.signin;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.logicaldoc.util.spring.Context;

/**
 * Seals the secrets Healdan keeps for provider sign-in, a provider's client
 * secret and the refresh tokens of device tokens (M4, spec section 13), with
 * AES-256-GCM under a key in a file of the configuration folder.
 * <p>
 * The key is 32 random bytes, in a file only the app's user can read. It is
 * made the first time something is sealed on a fresh install, never when one
 * is opened. A database dump on its own carries no secret, and a lost key
 * makes every sealed value read as unset ({@link #open} answers null), which
 * an administrator puts right by entering the secrets again
 * ({@link #sealEntered}).
 * <p>
 * Once stored values are sealed, {@link #seal} never makes a key: a key file
 * that went missing, even for a moment, would otherwise be replaced by a new
 * key, and every value sealed with the old one lost for good, helpers' refresh
 * tokens revoked at their next check and every client secret unset, even
 * after the file came back. It fails instead, and logs what to do.
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

	private final LongSupplier sealedStored;

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
	 * in the user's home folder, for the values sealed in this database
	 *
	 * @param dataSource the database
	 */
	@Autowired
	public SecretBox(DataSource dataSource) {
		this(defaultKeyFile(), sealedIn(dataSource));
	}

	/**
	 * @param keyFile where the key is, or will be made
	 * @param sealedStored counts the values stored sealed, which it asks only
	 *        when there is no key; while it answers more than none, only
	 *        {@link #sealEntered} makes one
	 */
	public SecretBox(Path keyFile, LongSupplier sealedStored) {
		this.keyFile = keyFile;
		this.sealedStored = sealedStored;
	}

	private static Path defaultKeyFile() {
		String configured = System.getProperty(KEY_PROPERTY);
		if (StringUtils.isNotBlank(configured))
			return Paths.get(configured.trim());
		return Paths.get(System.getProperty("user.home"), "logicaldoc", "conf", "healdan-secret.key");
	}

	/**
	 * @param dataSource the database
	 * @return counts the values sealed in it: settings, such as client secrets,
	 *         and the refresh tokens of device tokens
	 */
	static LongSupplier sealedIn(DataSource dataSource) {
		JdbcTemplate jdbc = new JdbcTemplate(dataSource);
		return () -> count(jdbc, "select count(*) from hd_setting where value like ?", PREFIX + "%")
				+ count(jdbc, "select count(*) from hd_api_token where hd_refresh is not null");
	}

	private static long count(JdbcTemplate jdbc, String sql, Object... arguments) {
		Long count = jdbc.queryForObject(sql, Long.class, arguments);
		return count == null ? 0L : count;
	}

	/**
	 * Seals a secret, making the key file first if there is none and nothing
	 * stored is sealed, as on a fresh install.
	 * 
	 * @param plain the secret
	 * @return the sealed value, which differs each time; null if {@code plain} is
	 *         null
	 * @throws IllegalStateException if the key can't be read or made, or there
	 *         is no key and stored values are sealed, which it logs at error
	 *         level with what to do; the message never holds the secret
	 */
	public String seal(String plain) {
		return seal(plain, false);
	}

	/**
	 * Seals a secret an administrator has just entered, as {@link #seal} does,
	 * but with no key file it makes one even when stored values are sealed:
	 * entering the secrets again is how an administrator puts a lost key right
	 * (spec section 13). Values sealed before then can't be opened, which it
	 * logs at warning level.
	 * 
	 * @param plain the secret
	 * @return the sealed value; null if {@code plain} is null
	 * @throws IllegalStateException if the key can't be read or made; the message
	 *         never holds the secret
	 */
	public String sealEntered(String plain) {
		return seal(plain, true);
	}

	private String seal(String plain, boolean entered) {
		if (plain == null)
			return null;
		try {
			byte[] iv = new byte[IV_BYTES];
			RANDOM.nextBytes(iv);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyToSeal(entered), "AES"),
					new GCMParameterSpec(TAG_BITS, iv));
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
			byte[] key = read();
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
			return read() != null;
		} catch (IOException e) {
			return false;
		}
	}

	private String unopened() {
		if (warned.compareAndSet(false, true))
			log.warn("A sealed secret can't be opened: the key file {} is missing or doesn't fit, or the value is "
					+ "damaged. The secret reads as unset until the key file is restored from a backup, or the secret "
					+ "is entered again.", keyFile);
		return null;
	}

	/**
	 * @param entered whether an administrator entered the secret, who may make
	 *        a key even when stored values are sealed
	 * @return the key, made first if there is none
	 */
	private byte[] keyToSeal(boolean entered) throws IOException {
		byte[] key = read();
		if (key != null)
			return key;
		synchronized (MAKING) {
			key = read();
			if (key != null)
				return key;
			return makeKey(entered);
		}
	}

	private byte[] read() throws IOException {
		if (!Files.isRegularFile(keyFile) || Files.size(keyFile) != KEY_BYTES)
			return null;
		byte[] key = Files.readAllBytes(keyFile);
		return key.length == KEY_BYTES ? key : null;
	}

	private byte[] makeKey(boolean entered) throws IOException {
		boolean leftover = Files.exists(keyFile);
		// A file of another size isn't ours to overwrite; an empty one is what a start that died left, before keys were
		// put in place whole
		if (leftover && Files.size(keyFile) > 0)
			throw new IOException("The key file " + keyFile + " is not " + KEY_BYTES + " bytes");

		long sealed = sealedStored();
		if (sealed > 0 && !entered) {
			log.error("Healdan's secret key file {} is missing or empty, but {} stored {} sealed, so Healdan won't make "
					+ "a new key, which would leave them unreadable for good. Restore the key file from a backup; "
					+ "nothing needs restarting. If it's lost for good, enter each sign-in provider's client secret "
					+ "again on the Sign-In tab of Healdan Settings, which makes a new key, and helpers that signed in "
					+ "through a provider sign in again.", keyFile, sealed, sealed == 1 ? "value is" : "values are");
			throw new IllegalStateException("Can't seal a secret: the key file " + keyFile
					+ " is missing or empty, and stored values are sealed with it");
		}

		byte[] key = new byte[KEY_BYTES];
		RANDOM.nextBytes(key);
		Path folder = keyFile.toAbsolutePath().getParent();
		if (folder != null)
			Files.createDirectories(folder);
		write(key, leftover);

		if (sealed > 0)
			log.warn("Made a new secret key file {}, for a client secret an administrator entered, while {} stored {} "
					+ "sealed with a key that's missing. Those can't be opened: enter each other sign-in provider's "
					+ "client secret again, and helpers that signed in through a provider sign in again at their next "
					+ "check.", keyFile, sealed, sealed == 1 ? "value was" : "values were");
		else
			log.info("Made the secret key file {}. Back it up with the database.", keyFile);
		return key;
	}

	/**
	 * @return how many values are stored sealed
	 * @throws IOException if that can't be told, as a key made then might leave
	 *         them unreadable
	 */
	private long sealedStored() throws IOException {
		try {
			return sealedStored.getAsLong();
		} catch (RuntimeException e) {
			throw new IOException("Can't tell whether stored values are sealed, so no key is made", e);
		}
	}

	/**
	 * Puts a key in place whole. It's written to a file beside the key file,
	 * made with its mode so it's never readable by others, then moved into
	 * place. A start that dies part-way leaves at most that file, which the
	 * next one clears, and never an empty or partial key file.
	 *
	 * @param leftover whether an empty key file is there, which the key
	 *        replaces
	 */
	private void write(byte[] key, boolean leftover) throws IOException {
		Path fresh = keyFile.resolveSibling(keyFile.getFileName() + ".new");
		Files.deleteIfExists(fresh);
		FileAttribute<?>[] attributes = FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
				? new FileAttribute<?>[] { PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")) }
				: new FileAttribute<?>[0];
		try {
			try (FileChannel channel = FileChannel.open(fresh,
					EnumSet.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), attributes)) {
				channel.write(ByteBuffer.wrap(key));
				channel.force(true);
			}
			if (leftover)
				Files.move(fresh, keyFile, StandardCopyOption.ATOMIC_MOVE);
			else
				// Fails if another process made a key meanwhile, rather than replacing it
				Files.move(fresh, keyFile);
		} finally {
			Files.deleteIfExists(fresh);
		}
	}
}
