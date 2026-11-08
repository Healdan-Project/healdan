// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.gui.frontend.client.healdan.beans;

import java.io.Serializable;
import java.util.Date;

/**
 * A storage backend, as the Storage tab shows and saves it (spec section 11).
 * It never holds the S3 secret: {@link #isHasSecret()} says only whether one is
 * stored and opens, and a save takes a new one beside it. Its recovery key is
 * here only in the answer of the add that made it.
 */
public class GUIStorageBackend implements Serializable {

	private static final long serialVersionUID = 1L;

	/** {@code local}, a folder */
	public static final String LOCAL = "local";

	/** {@code s3}, a bucket */
	public static final String S3 = "s3";

	/** Its id; 0 for one not added yet */
	private long id;

	/** {@link #LOCAL} or {@link #S3} */
	private String kind;

	private String name;

	/** A local folder's path in the container; Original's comes from the configuration */
	private String path;

	private String endpoint;

	private String region;

	private String bucket;

	private String prefix;

	private boolean pathStyle;

	private String accessKey;

	/** Whether an S3 secret is stored and opens */
	private boolean hasSecret;

	/**
	 * Whether its files are encrypted; null for the kind's default: on for S3,
	 * off for a local folder
	 */
	private Boolean encrypted;

	/** Whether the administrator typed back its recovery key's last group */
	private boolean keyConfirmed;

	/** Whether a Store may be given it: it isn't encrypted, or its recovery key is confirmed */
	private boolean offered;

	/** Whether it is Original, backend 1, which can be renamed only */
	private boolean original;

	/** Whether a file is recorded or held on it, so its bucket, prefix, path and encryption can't change */
	private boolean inUse;

	/** {@code unchecked}, {@code ok} or {@code failing} */
	private String status;

	/** The last error, while failing */
	private String error;

	/** Since when it has been in its status; null while unchecked */
	private Date since;

	/** Whether it is failing because it can't be reached */
	private boolean unreachable;

	/** How many files are on it */
	private long files;

	/** Their size in bytes */
	private long size;

	/** When its recovery key was last shown; null if never */
	private Date keyShown;

	/** Who it was last shown to */
	private String keyShownBy;

	/** Its recovery key, in the add's answer only */
	private String recoveryKey;

	/**
	 * True once the administrator confirmed making a new secret key for the S3
	 * secret they typed, as the key file is missing while values are sealed
	 * with it (M4 ruling R37)
	 */
	private boolean newKey;

	public long getId() {
		return id;
	}

	public void setId(long id) {
		this.id = id;
	}

	public String getKind() {
		return kind;
	}

	public void setKind(String kind) {
		this.kind = kind;
	}

	public boolean isS3() {
		return S3.equals(kind);
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getPath() {
		return path;
	}

	public void setPath(String path) {
		this.path = path;
	}

	public String getEndpoint() {
		return endpoint;
	}

	public void setEndpoint(String endpoint) {
		this.endpoint = endpoint;
	}

	public String getRegion() {
		return region;
	}

	public void setRegion(String region) {
		this.region = region;
	}

	public String getBucket() {
		return bucket;
	}

	public void setBucket(String bucket) {
		this.bucket = bucket;
	}

	public String getPrefix() {
		return prefix;
	}

	public void setPrefix(String prefix) {
		this.prefix = prefix;
	}

	public boolean isPathStyle() {
		return pathStyle;
	}

	public void setPathStyle(boolean pathStyle) {
		this.pathStyle = pathStyle;
	}

	public String getAccessKey() {
		return accessKey;
	}

	public void setAccessKey(String accessKey) {
		this.accessKey = accessKey;
	}

	public boolean isHasSecret() {
		return hasSecret;
	}

	public void setHasSecret(boolean hasSecret) {
		this.hasSecret = hasSecret;
	}

	public Boolean getEncrypted() {
		return encrypted;
	}

	public void setEncrypted(Boolean encrypted) {
		this.encrypted = encrypted;
	}

	public boolean isKeyConfirmed() {
		return keyConfirmed;
	}

	public void setKeyConfirmed(boolean keyConfirmed) {
		this.keyConfirmed = keyConfirmed;
	}

	public boolean isOffered() {
		return offered;
	}

	public void setOffered(boolean offered) {
		this.offered = offered;
	}

	public boolean isOriginal() {
		return original;
	}

	public void setOriginal(boolean original) {
		this.original = original;
	}

	public boolean isInUse() {
		return inUse;
	}

	public void setInUse(boolean inUse) {
		this.inUse = inUse;
	}

	public String getStatus() {
		return status;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	public String getError() {
		return error;
	}

	public void setError(String error) {
		this.error = error;
	}

	public Date getSince() {
		return since;
	}

	public void setSince(Date since) {
		this.since = since;
	}

	public boolean isUnreachable() {
		return unreachable;
	}

	public void setUnreachable(boolean unreachable) {
		this.unreachable = unreachable;
	}

	public long getFiles() {
		return files;
	}

	public void setFiles(long files) {
		this.files = files;
	}

	public long getSize() {
		return size;
	}

	public void setSize(long size) {
		this.size = size;
	}

	public Date getKeyShown() {
		return keyShown;
	}

	public void setKeyShown(Date keyShown) {
		this.keyShown = keyShown;
	}

	public String getKeyShownBy() {
		return keyShownBy;
	}

	public void setKeyShownBy(String keyShownBy) {
		this.keyShownBy = keyShownBy;
	}

	public String getRecoveryKey() {
		return recoveryKey;
	}

	public void setRecoveryKey(String recoveryKey) {
		this.recoveryKey = recoveryKey;
	}

	public boolean isNewKey() {
		return newKey;
	}

	public void setNewKey(boolean newKey) {
		this.newKey = newKey;
	}
}
