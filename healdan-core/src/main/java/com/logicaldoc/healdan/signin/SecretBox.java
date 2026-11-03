// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.signin;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
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
 * ({@link #sealWithNewKey}).
 * <p>
 * Once stored values are sealed, {@link #seal} never makes a key: a key file
 * that went missing, even for a moment, would otherwise be replaced by a new
 * key, and every value sealed with the old one lost for good, helpers' refresh
 * tokens revoked at their next check and every client secret unset, even
 * after the file came back. It fails instead, and logs what to do.
 * <p>
 * A key is made under a lock file beside the key file, so that two servers
 * sharing the configuration folder make one key, and is put in place whole.
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

	/**
	 * Held while a key is made, by every box, so that two first uses make one
	 * key even for the same file; the lock file does the same between processes
	 */
	private static final Object MAKING = new Object();

	private static final Logger log = LoggerFactory.getLogger(SecretBox.class);

	private final Path keyFile;

	private final LongSupplier sealedStored;

	/** Whether it warned that there is no key; cleared when a key reads, so a key lost again is warned of again */
	private final AtomicBoolean warnedNoKey = new AtomicBoolean();

	private final AtomicBoolean warnedUnopened = new AtomicBoolean();

	/**
	 * There is no key file, and stored values are sealed with the key that's
	 * missing, so a seal makes no key. The message never holds the secret.
	 */
	public static final class KeyMissingException extends IllegalStateException {

		private static final long serialVersionUID = 1L;

		private final long sealed;

		KeyMissingException(Path keyFile, long sealed) {
			super("Can't seal a secret: the key file " + keyFile
					+ " is missing or empty, and stored values are sealed with it");
			this.sealed = sealed;
		}

		/**
		 * @return how many stored values are sealed with the missing key
		 */
		public long sealed() {
			return sealed;
		}
	}

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
	 *        {@link #sealWithNewKey} makes one
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
	 * @return counts the values sealed in it: providers' client secrets, and
	 *         the refresh tokens of device tokens. Other settings aren't
	 *         sealed, whatever they start with.
	 */
	static LongSupplier sealedIn(DataSource dataSource) {
		JdbcTemplate jdbc = new JdbcTemplate(dataSource);
		return () -> count(jdbc, "select count(*) from hd_setting where name like ? and value like ?",
				SignInProviders.SECRET_SETTINGS, PREFIX + "%")
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
	 * @throws KeyMissingException if there is no key and stored values are
	 *         sealed, which it logs at error level with what to do
	 * @throws IllegalStateException if the key can't be read or made; the
	 *         message never holds the secret
	 */
	public String seal(String plain) {
		return seal(plain, false);
	}

	/**
	 * Seals a secret an administrator has just entered, as {@link #seal} does,
	 * but with no key file it makes one even when stored values are sealed,
	 * once the administrator has confirmed it: entering the secrets again is
	 * how a lost key is put right (spec section 13, ruling R37). Values sealed
	 * before then can't be opened, which it logs at warning level.
	 *
	 * @param plain the secret
	 * @return the sealed value; null if {@code plain} is null
	 * @throws IllegalStateException if the key can't be read or made; the message
	 *         never holds the secret
	 */
	public String sealWithNewKey(String plain) {
		return seal(plain, true);
	}

	private String seal(String plain, boolean newKey) {
		if (plain == null)
			return null;
		try {
			byte[] iv = new byte[IV_BYTES];
			RANDOM.nextBytes(iv);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyToSeal(newKey), "AES"),
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
	 *         value is damaged. It logs at warning level that there is no key,
	 *         once each time the key goes, and once that a value didn't open
	 *         with the key that's there.
	 */
	public String open(String sealed) {
		if (StringUtils.isEmpty(sealed))
			return null;
		byte[] key;
		try {
			key = read();
		} catch (IOException e) {
			key = null;
		}
		if (key == null)
			return noKey();
		try {
			if (!sealed.startsWith(PREFIX))
				return unopened();
			byte[] raw = Base64.getDecoder().decode(sealed.substring(PREFIX.length()));
			if (raw.length < IV_BYTES + TAG_BITS / 8)
				return unopened();
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
					new GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES));
			return new String(cipher.doFinal(raw, IV_BYTES, raw.length - IV_BYTES), StandardCharsets.UTF_8);
		} catch (GeneralSecurityException | IllegalArgumentException e) {
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

	private String noKey() {
		if (warnedNoKey.compareAndSet(false, true))
			log.warn("Sealed secrets can't be opened: the key file {} is missing, empty or can't be read. They read as "
					+ "unset until the key file is restored from a backup, or the secrets are entered again.", keyFile);
		return null;
	}

	private String unopened() {
		if (warnedUnopened.compareAndSet(false, true))
			log.warn("A sealed secret can't be opened with the key file {}: another key sealed it, or the value is "
					+ "damaged. The secret reads as unset until it is entered again.", keyFile);
		return null;
	}

	/**
	 * @param newKey whether an administrator confirmed making a key even when
	 *        stored values are sealed
	 * @return the key, made first if there is none
	 */
	private byte[] keyToSeal(boolean newKey) throws IOException {
		byte[] key = read();
		if (key != null)
			return key;
		synchronized (MAKING) {
			key = read();
			if (key != null)
				return key;
			return makeKey(newKey);
		}
	}

	private byte[] read() throws IOException {
		if (!Files.isRegularFile(keyFile) || Files.size(keyFile) != KEY_BYTES)
			return null;
		byte[] key = Files.readAllBytes(keyFile);
		if (key.length != KEY_BYTES)
			return null;
		// A key restored from a backup works with nothing restarted, so losing it again is warned of again
		warnedNoKey.set(false);
		return key;
	}

	private byte[] makeKey(boolean newKey) throws IOException {
		leftover();
		long sealed = sealedStored();
		if (sealed > 0 && !newKey) {
			log.error("Healdan's secret key file {} is missing or empty, but {} stored {} sealed, so Healdan won't make "
					+ "a new key, which would leave them unreadable for good. Restore the key file from a backup; "
					+ "nothing needs restarting. If it's lost for good, enter a sign-in provider's client secret again "
					+ "on the Sign-In tab of Healdan Settings and make a new key when it asks, then enter each other "
					+ "provider's client secret again; helpers that signed in through a provider sign in again.",
					keyFile, sealed, sealed == 1 ? "value is" : "values are");
			throw new KeyMissingException(keyFile, sealed);
		}

		Path folder = keyFile.toAbsolutePath().getParent();
		if (folder != null)
			Files.createDirectories(folder);
		byte[] key;
		Path lockFile = keyFile.resolveSibling(keyFile.getFileName() + ".lock");
		try (FileChannel channel = FileChannel.open(lockFile,
				EnumSet.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE), ownerOnly());
				FileLock lock = channel.lock()) {
			// Another server sharing the folder may have made a key while this one waited
			byte[] made = read();
			if (made != null)
				return made;
			boolean leftover = leftover();
			key = new byte[KEY_BYTES];
			RANDOM.nextBytes(key);
			write(key, leftover);
		}

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
	 * @return whether an empty key file is there, which a key replaces: what a
	 *         start that died left, before keys were put in place whole
	 * @throws IOException if a file of another size is there, which isn't
	 *         Healdan's to overwrite
	 */
	private boolean leftover() throws IOException {
		if (!Files.exists(keyFile))
			return false;
		if (Files.size(keyFile) > 0)
			throw new IOException("The key file " + keyFile + " is not " + KEY_BYTES + " bytes");
		return true;
	}

	/** @return the mode of a file only the app's user can read, where the file system has modes */
	private static FileAttribute<?>[] ownerOnly() {
		return FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
				? new FileAttribute<?>[] { PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")) }
				: new FileAttribute<?>[0];
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
	 * Puts a key in place whole, under the lock file. It's written to a file
	 * beside the key file, made with its mode so it's never readable by others,
	 * then moved into place, and the folder is synced so the move outlasts a
	 * crash. A start that dies part-way leaves at most that file, which the
	 * next one clears, and never an empty or partial key file.
	 *
	 * @param leftover whether an empty key file is there, which the key
	 *        replaces
	 */
	private void write(byte[] key, boolean leftover) throws IOException {
		Path fresh = keyFile.resolveSibling(keyFile.getFileName() + ".new");
		Files.deleteIfExists(fresh);
		try {
			try (FileChannel channel = FileChannel.open(fresh,
					EnumSet.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), ownerOnly())) {
				channel.write(ByteBuffer.wrap(key));
				channel.force(true);
			}
			if (leftover)
				Files.move(fresh, keyFile, StandardCopyOption.ATOMIC_MOVE);
			else
				// Fails if a process that takes no lock, an older Healdan, made a key meanwhile, rather than replacing it
				Files.move(fresh, keyFile);
		} finally {
			Files.deleteIfExists(fresh);
		}
		syncFolder();
	}

	/**
	 * Syncs the key file's folder, so that the key's move into place is on
	 * disk: lost in a crash, it would leave the values sealed with the key,
	 * which the database keeps, unreadable
	 */
	private void syncFolder() {
		Path folder = keyFile.toAbsolutePath().getParent();
		if (folder == null)
			return;
		try (FileChannel channel = FileChannel.open(folder, StandardOpenOption.READ)) {
			channel.force(true);
		} catch (IOException e) {
			// Some systems, such as Windows, can't open a folder to sync it
			log.warn("Couldn't sync the folder {} after putting the secret key file in place ({}). Back the key file "
					+ "up now: a crash before the system writes the folder out could lose it.", folder,
					e.getClass().getSimpleName());
		}
	}
}
