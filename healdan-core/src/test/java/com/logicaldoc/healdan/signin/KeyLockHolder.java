// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.signin;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/**
 * Another server sharing the configuration folder, for {@code SecretBoxTest}:
 * run in a process of its own, it takes the key's lock file, says
 * {@link #LOCKED}, waits for a line, then puts its key in place as
 * {@link SecretBox} does and lets the lock go.
 * <p>
 * Arguments: the lock file, then the key file.
 */
public final class KeyLockHolder {

	static final String LOCKED = "locked";

	/** The key it puts in place */
	static final byte[] KEY = new byte[32];

	static {
		Arrays.fill(KEY, (byte) 7);
	}

	private KeyLockHolder() {
	}

	public static void main(String[] args) throws Exception {
		Path lockFile = Path.of(args[0]);
		Path keyFile = Path.of(args[1]);
		try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
				FileLock lock = channel.lock()) {
			System.out.println(LOCKED);
			System.out.flush();
			new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();

			Path fresh = keyFile.resolveSibling(keyFile.getFileName() + ".other");
			Files.write(fresh, KEY);
			Files.move(fresh, keyFile, StandardCopyOption.ATOMIC_MOVE);
		}
	}
}
