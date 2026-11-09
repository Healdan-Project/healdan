// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage.web;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.commons.lang3.StringUtils;

import com.logicaldoc.gui.frontend.client.healdan.beans.GUIStorageBackend;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.storage.Backends;
import com.logicaldoc.healdan.storage.ReadCache;
import com.logicaldoc.healdan.storage.Staging;
import com.logicaldoc.healdan.storage.StorageBackend;
import com.logicaldoc.healdan.storage.backend.Endpoints;
import com.logicaldoc.healdan.storage.backend.S3Backend;
import com.logicaldoc.healdan.storage.backend.S3Settings;
import com.logicaldoc.healdan.storage.crypto.BackendKeys;
import com.logicaldoc.healdan.util.BasicPlane;

/**
 * A storage backend's settings as the Storage tab sends them, read into a {@link Draft} and checked before anything is
 * written (spec sections 10 and 11): each field fits its column and the database's characters, an S3 endpoint keeps
 * the endpoint rule (Global Constraints), a name is the backend's own, and its place is shared with no other backend
 * nor with the server's own folders (call 23), a folder's links followed. Also what a draft changes of a backend's
 * row, and the row it makes.
 */
final class BackendDrafts {

	/**
	 * The longest S3 prefix (as stored, with its last {@code /}) and local path: a held location,
	 * {@code hd_storage_held.hd_location} ({@code varchar(700)}), then always fits a prefix and CE's folders for a doc
	 * id and a resource
	 */
	static final int MAX_PLACE = 255;

	/** The longest name, as {@code hd_name} holds */
	static final int MAX_NAME = 100;

	/** What {@link #changes} calls a new name: the only change that leaves the backend's built entry as it is */
	static final String NAME = "its name";

	static final int MAX_ENDPOINT = 1000;

	static final int MAX_REGION = 100;

	static final int MAX_BUCKET = 255;

	static final int MAX_ACCESS_KEY = 255;

	/** The longest S3 secret, in the bytes that are sealed: its sealed value fits {@code hd_secret} */
	static final int MAX_SECRET_BYTES = 1000;

	/**
	 * A backend's settings as the tab sent them, each one checked.
	 *
	 * @param root a local folder's path, absolute; null for S3
	 * @param s3 an S3 backend's settings; null for a local folder
	 * @param secret the S3 secret as typed, stripped; null to keep the stored one
	 */
	record Draft(String kind, String name, Path root, S3Settings s3, boolean encrypted, String secret) {

		boolean isS3() {
			return s3 != null;
		}

		/** @return where it is, for the log */
		String where() {
			if (!isS3())
				return root.toString();
			return s3.endpoint() + ", bucket " + s3.bucket() + (s3.prefix().isEmpty() ? "" : " under " + s3.prefix());
		}

		/** @return what it is and where, never the secret, which a record's own would print */
		@Override
		public String toString() {
			return "Draft[" + kind + " " + name + ", " + where() + (encrypted ? ", encrypted" : "")
					+ (secret != null ? ", with a new secret" : "") + "]";
		}
	}

	/**
	 * A backend's place as {@link #samePlace} compares it: its bucket and prefix, or its folder. A value, so that what
	 * a save read before the lock can be compared with the row under it.
	 */
	record Place(String bucket, String prefix, Path folder) {

		/** @return the row's place as it is now */
		static Place of(StorageBackend row) {
			if (StorageBackend.S3.equals(row.getKind()))
				return new Place(StringUtils.strip(row.getBucket()), S3Settings.prefix(row.getPrefix()), null);
			return new Place(null, null, BackendDrafts.folder(row));
		}
	}

	/** The folder of the backends' key files */
	private final Path keysFolder;

	/**
	 * @param keysFolder the folder of the backends' key files, which no backend's folder may share
	 */
	BackendDrafts(Path keysFolder) {
		this.keysFolder = keysFolder.toAbsolutePath().normalize();
	}

	/**
	 * @param existing the backend's row, for a save; null for an add
	 * @return the settings, each checked: S3's against the endpoint rule (Global Constraints)
	 * @throws HealdanRuleException saying what is refused
	 */
	static Draft draft(GUIStorageBackend backend, String secret, StorageBackend existing) throws HealdanRuleException {
		String kind = StringUtils.strip(backend.getKind());
		if (!StorageBackend.LOCAL.equals(kind) && !StorageBackend.S3.equals(kind))
			throw refuse("Choose the kind of backend: a local folder or S3.");
		if (existing != null && !kind.equals(existing.getKind()))
			throw refuse("A backend's kind can't change.");
		String name = name(backend.getName());
		boolean s3 = StorageBackend.S3.equals(kind);
		// On for S3 unless the administrator turns it off; off for a local folder unless turned on
		boolean encrypted = backend.getEncrypted() != null ? backend.getEncrypted() : s3;
		if (!s3)
			return new Draft(kind, name, root(backend.getPath()), null, encrypted, null);

		S3Settings place = s3Settings(backend);
		String typed = StringUtils.strip(secret);
		if (StringUtils.isEmpty(typed)) {
			if (existing == null || StringUtils.isBlank(existing.getSecret()))
				throw refuse("The secret can't be empty.");
			typed = null;
		} else if (typed.getBytes(StandardCharsets.UTF_8).length > MAX_SECRET_BYTES) {
			throw refuse(("The secret can't be longer than %d characters, or fewer in a script that takes more than one "
					+ "byte a character.").formatted(MAX_SECRET_BYTES));
		}
		return new Draft(kind, name, null, place, encrypted, typed);
	}

	/**
	 * @param typed a backend's name as typed
	 * @return it stripped
	 * @throws HealdanRuleException if it is empty, too long, or holds characters the database can't
	 */
	static String name(String typed) throws HealdanRuleException {
		return text(typed, "name", MAX_NAME);
	}

	/** @return the text, stripped, which the database can hold */
	private static String text(String value, String what, int max) throws HealdanRuleException {
		String text = StringUtils.strip(value);
		if (StringUtils.isEmpty(text))
			throw refuse("The %s can't be empty.".formatted(what));
		if (text.length() > max)
			throw refuse("The %s can't be longer than %d characters.".formatted(what, max));
		// MariaDB's utf8 tables hold no character beyond the Basic Multilingual Plane (R33)
		if (!BasicPlane.holds(text))
			throw refuse("The %s can't contain emoji or other characters the database can't store.".formatted(what));
		return text;
	}

	/** @return a local folder's path as typed, absolute and normalised, as it is stored */
	private static Path root(String typed) throws HealdanRuleException {
		String text = text(typed, "path", Integer.MAX_VALUE);
		Path path;
		try {
			path = Path.of(text);
		} catch (InvalidPathException e) {
			throw refuse("The path isn't a folder's path: " + e.getReason() + ".");
		}
		if (!path.isAbsolute())
			throw refuse("The path must be the folder's whole path in the container, from /, such as /mnt/archive.");
		path = path.normalize();
		if (path.toString().length() > MAX_PLACE)
			throw refuse("The path can't be longer than %d characters.".formatted(MAX_PLACE));
		return path;
	}

	/** @return an S3 backend's place, checked as {@link S3Backend} checks it when it is built */
	private static S3Settings s3Settings(GUIStorageBackend backend) throws HealdanRuleException {
		String endpoint = text(backend.getEndpoint(), "endpoint", MAX_ENDPOINT);
		String region = text(backend.getRegion(), "region", MAX_REGION);
		String bucket = text(backend.getBucket(), "bucket", MAX_BUCKET);
		String prefix = S3Settings.prefix(backend.getPrefix());
		if (prefix.length() > MAX_PLACE)
			throw refuse("The prefix can't be longer than %d characters, with the / it is kept with at its end."
					.formatted(MAX_PLACE));
		if (!BasicPlane.holds(prefix))
			throw refuse("The prefix can't contain emoji or other characters the database can't store.");
		String accessKey = text(backend.getAccessKey(), "access key", MAX_ACCESS_KEY);
		S3Settings settings;
		try {
			settings = new S3Settings(new URI(endpoint), region, bucket, prefix, backend.isPathStyle(), accessKey);
		} catch (URISyntaxException e) {
			throw refuse("The endpoint isn't an address, such as https://s3.example.com.");
		} catch (IllegalArgumentException e) {
			throw refuse(e.getMessage() + ".");
		}
		try {
			S3Backend.checkEndpoint(settings);
		} catch (Endpoints.HostNotFound e) {
			throw refuse(e.getMessage() + ", so its addresses can't be checked: http:// is only for an address on a "
					+ "private network.");
		} catch (IllegalArgumentException e) {
			throw refuse(e.getMessage() + ".");
		}
		return settings;
	}

	/**
	 * Refuses a name another backend has, whatever its case.
	 *
	 * @param id the backend's own id, which isn't compared with itself; 0 for one not added yet
	 * @param rows every backend's row
	 */
	static void checkName(String name, long id, List<StorageBackend> rows) throws HealdanRuleException {
		for (StorageBackend other : rows)
			if (other.getId() != id && name.equalsIgnoreCase(StringUtils.strip(other.getName())))
				throw refuse("Another backend is called " + other.getName() + ": give this one a name of its own.");
	}

	/**
	 * Backends can't share a place (call 23): an S3 bucket and prefix can't equal or hold another's at the same S3
	 * service (as {@code service} tells them apart; an empty prefix holds every other), and a folder can't be, hold or
	 * sit inside Original's, staging's, the cache's, the configuration folder, the key files' folder, or another
	 * backend's.
	 *
	 * @param id the backend's own id, which isn't compared with itself; 0 for one not added yet
	 * @param rows every backend's row
	 */
	void checkPlace(Draft draft, long id, List<StorageBackend> rows) throws HealdanRuleException {
		for (StorageBackend other : rows) {
			if (other.getId() == id)
				continue;
			if (draft.isS3() && StorageBackend.S3.equals(other.getKind()) && sharesBucket(draft.s3(), other))
				throw refuse("This bucket and prefix share their place with backend " + other.getName()
						+ ": two backends can't keep files in one place, so neither prefix may hold the other's. "
						+ "Choose another prefix, or another bucket.");
			if (!draft.isS3() && StorageBackend.LOCAL.equals(other.getKind()))
				apart(draft.root(), other.getId() == StorageBackend.ORIGINAL ? "Original's folder"
						: "the folder of backend " + other.getName(), folder(other));
		}
		if (!draft.isS3()) {
			apart(draft.root(), "staging's folder", Staging.folder());
			apart(draft.root(), "the read cache's folder", ReadCache.folder());
			apart(draft.root(), "the configuration folder", BackendKeys.configurationFolder());
			apart(draft.root(), "the storage key files' folder", keysFolder);
		}
	}

	/** @return whether the settings' place equals or holds the row's, or sits inside it, at the same service */
	private static boolean sharesBucket(S3Settings mine, StorageBackend other) {
		String theirs;
		try {
			theirs = service(URI.create(StringUtils.trimToEmpty(other.getEndpoint())));
		} catch (IllegalArgumentException e) {
			return false;
		}
		if (theirs == null || !theirs.equals(service(mine.endpoint()))
				|| !StringUtils.strip(StringUtils.defaultString(other.getBucket())).equalsIgnoreCase(mine.bucket()))
			return false;
		String prefix = S3Settings.prefix(other.getPrefix());
		return mine.prefix().startsWith(prefix) || prefix.startsWith(mine.prefix());
	}

	/**
	 * @param endpoint an S3 endpoint
	 * @return the S3 service it reaches, within which a bucket's name is one bucket: AWS's for every host of its, as its
	 *         bucket names are one namespace across its regions; otherwise the host and port, so two servers on one
	 *         host are two services; null if it has no host
	 */
	static String service(URI endpoint) {
		String host = endpoint.getHost();
		if (host == null)
			return null;
		host = host.toLowerCase(Locale.ROOT);
		if (host.equals("amazonaws.com") || host.endsWith(".amazonaws.com"))
			return "amazonaws.com";
		int port = endpoint.getPort();
		if (port < 0)
			port = "http".equalsIgnoreCase(endpoint.getScheme()) ? 80 : 443;
		return host + ":" + port;
	}

	/**
	 * Refuses a folder that is, holds or sits inside another, as typed or with its links followed: a link to Original's
	 * folder, say, or to a folder inside another backend's, is that folder.
	 */
	private static void apart(Path folder, String otherName, Path other) throws HealdanRuleException {
		if (other == null)
			return;
		Path theirs = other.toAbsolutePath().normalize();
		if (overlap(folder, theirs))
			throw refuse("The folder " + folder + " can't be, hold or sit inside " + otherName + " (" + theirs
					+ "): give the backend a folder of its own.");
		Path real = real(folder);
		Path theirsReal = real(theirs);
		if (overlap(real, theirsReal))
			throw refuse("The folder " + folder + " leads to " + real + ", which can't be, hold or sit inside "
					+ otherName + " (" + theirsReal + "): give the backend a folder of its own.");
	}

	private static boolean overlap(Path one, Path two) {
		return one.startsWith(two) || two.startsWith(one);
	}

	/**
	 * @param folder an absolute, normalised path
	 * @return it with its links followed: the real path of its nearest part that exists, then the rest of it; as it is
	 *         if no part of it can be read
	 */
	static Path real(Path folder) {
		Path existing = folder;
		Path rest = null;
		while (existing != null && !Files.exists(existing)) {
			Path name = existing.getFileName();
			if (name == null)
				return folder;
			rest = rest == null ? name : name.resolve(rest);
			existing = existing.getParent();
		}
		if (existing == null)
			return folder;
		try {
			Path real = existing.toRealPath();
			return rest == null ? real : real.resolve(rest).normalize();
		} catch (IOException e) {
			return folder;
		}
	}

	/**
	 * @param row a local backend's row
	 * @return its folder, as {@link Backends#folder} builds it; null for one without, or whose path is no path
	 */
	static Path folder(StorageBackend row) {
		try {
			return Backends.folder(row);
		} catch (InvalidPathException e) {
			return null;
		}
	}

	/** @return whether the draft keeps the row's bucket and prefix, or its folder */
	static boolean samePlace(StorageBackend row, Draft draft) {
		if (draft.isS3())
			return draft.s3().bucket().equals(StringUtils.strip(row.getBucket()))
					&& draft.s3().prefix().equals(S3Settings.prefix(row.getPrefix()));
		return draft.root().equals(folder(row));
	}

	/** @return what of the place a draft changes, for a refusal */
	static String moved(StorageBackend row, Draft draft) {
		if (!draft.isS3())
			return "The path";
		return draft.s3().bucket().equals(StringUtils.strip(row.getBucket())) ? "The prefix" : "The bucket";
	}

	/**
	 * @param secret whether a new secret is saved
	 * @return the settings a save changes, by name, for the log: never their values, the secret's above all
	 */
	static List<String> changes(StorageBackend row, Draft draft, boolean secret) {
		List<String> changes = new ArrayList<>();
		if (!draft.name().equals(row.getName()))
			changes.add(NAME);
		if (draft.isS3()) {
			S3Settings s3 = draft.s3();
			if (!s3.endpoint().toString().equals(row.getEndpoint()))
				changes.add("the endpoint");
			if (!s3.region().equals(row.getRegion()))
				changes.add("the region");
			if (!s3.bucket().equals(row.getBucket()))
				changes.add("the bucket");
			if (!s3.prefix().equals(row.getPrefix()))
				changes.add("the prefix");
			if (s3.pathStyle() != row.isPathStyleOn())
				changes.add("path style");
			if (!s3.accessKey().equals(row.getAccessKey()))
				changes.add("the access key");
			if (secret)
				changes.add("the secret");
		} else if (!draft.root().toString().equals(row.getPath())) {
			changes.add("the path");
		}
		return changes;
	}

	/** Writes a draft's settings onto a backend's row, all but its secret */
	static void apply(StorageBackend row, Draft draft) {
		row.setName(draft.name());
		row.setKind(draft.kind());
		row.setEncryptedOn(draft.encrypted());
		if (draft.isS3()) {
			S3Settings s3 = draft.s3();
			row.setPath(null);
			row.setEndpoint(s3.endpoint().toString());
			row.setRegion(s3.region());
			row.setBucket(s3.bucket());
			row.setPrefix(s3.prefix());
			row.setPathStyleOn(s3.pathStyle());
			row.setAccessKey(s3.accessKey());
		} else {
			row.setPath(draft.root().toString());
		}
	}

	private static HealdanRuleException refuse(String message) {
		return new HealdanRuleException(message);
	}
}
